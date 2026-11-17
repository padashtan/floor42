package dev.floor42.server.iam.fga;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.Delegations;
import dev.floor42.server.iam.IamSettings;
import dev.floor42.server.iam.IamStatus;
import dev.floor42.server.iam.McpServers;
import dev.floor42.server.iam.McpServers.LegacyTick;
import dev.floor42.server.iam.ResourceCatalogue;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.runtime.ClaudeCodeRuntime;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * What the first start of 1.3.0 in IAM mode does once OpenFGA is ready (ADR-0080): the grants of {@code grants.json} and
 * the ticks of {@code mcp-servers.json} become tuples (one {@code AuthorizationChanged} row per 100, reason IMPORT,
 * {@code imported: true}), {@code grants.json} is renamed {@code grants.json.imported-1.3.0} and the ticks are dropped
 * from the file, which keeps the pinned definitions only. Then the reconciler runs, as at every start.
 *
 * <p>Idempotent: a start that stopped between the OpenFGA write and the rename writes nothing twice and journals
 * nothing twice. A downgrade to 1.2.0 finds no {@code grants.json} and refuses every resource call
 * ({@code RESOURCE_NOT_GRANTED}): it fails closed; renaming the file back restores the 1.2.0 grants.
 */
@ApplicationScoped
public class GrantImport {

    private static final Logger LOG = Logger.getLogger(GrantImport.class);
    static final String IMPORTED = "grants.json.imported-1.3.0";

    private final Path dir;
    private final ObjectMapper json;
    private final GrantTuples tuples;
    private final McpServers servers;
    private final ResourceCatalogue catalogue;
    private final Delegations delegations;
    private final IamSettings settings;
    private final IamStatus status;

    @Inject
    GrantImport(
            @ConfigProperty(name = "floor42.data-dir") Path dataDir,
            ObjectMapper json,
            GrantTuples tuples,
            McpServers servers,
            ResourceCatalogue catalogue,
            Delegations delegations,
            IamSettings settings,
            IamStatus status) {
        this.dir = dataDir.resolve("iam");
        this.json = json;
        this.tuples = tuples;
        this.servers = servers;
        this.catalogue = catalogue;
        this.delegations = delegations;
        this.settings = settings;
        this.status = status;
    }

    /** For the tests: no start-up observer is involved, {@link #run} is called by hand. */
    public GrantImport(
            Path iamDir,
            ObjectMapper json,
            GrantTuples tuples,
            McpServers servers,
            ResourceCatalogue catalogue,
            Delegations delegations) {
        this.dir = iamDir;
        this.json = json;
        this.tuples = tuples;
        this.servers = servers;
        this.catalogue = catalogue;
        this.delegations = delegations;
        this.settings = null;
        this.status = null;
    }

    /** After IAM mode's own start (which readies OpenFGA) and the delegations' (which are read back there). */
    void onStart(@Observes @Priority(ClaudeCodeRuntime.STARTUP_PRIORITY + 30) StartupEvent ignored) {
        if (!settings.enabled() || status.blocksSessions().isPresent()) return;
        try {
            run();
        } catch (FgaException e) {
            // Handled: told in the log; the files stay as they are and the next start tries again
            LOG.warn("The OpenFGA import and reconcile did not run: " + e.code());
        }
    }

    /** The import when there is something to import, then the reconcile. */
    public void run() {
        importFiles();
        reconcile();
    }

    /** True when something was imported. */
    public boolean importFiles() {
        Path grants = dir.resolve("grants.json");
        if (tuples.alreadyImported()) {
            putAwayImported(grants);
            return false;
        }
        Map<String, List<String>> held = Map.of();
        boolean file = Files.isRegularFile(grants);
        if (file) {
            try {
                held = json.readValue(grants.toFile(), new TypeReference<Map<String, List<String>>>() {});
            } catch (IOException | RuntimeException e) {
                // Handled: nothing is imported from a file that cannot be read, and the file is left for the director
                LOG.warn("grants.json cannot be read, nothing is imported from it: "
                        + e.getClass().getSimpleName());
                return false;
            }
        }
        List<LegacyTick> ticks = servers.legacyTicks();
        if (!file && ticks.isEmpty()) return false;
        tuples.importTuples(wanted(held, ticks));
        putAwayImported(file ? grants : null);
        return true;
    }

    private List<FgaTuple> wanted(Map<String, List<String>> held, List<LegacyTick> ticks) {
        List<FgaTuple> wanted = new ArrayList<>();
        held.forEach((agent, resources) -> resources.forEach(
                r -> wanted.add(new FgaTuple("agent:" + agent, "granted", GrantTuples.resourceObject(r)))));
        for (LegacyTick t : ticks) {
            String object = GrantTuples.toolObject(t.server(), t.tool(), t.sha256());
            wanted.add(new FgaTuple("resource:" + t.server(), "server", object));
            wanted.add(new FgaTuple("agent:*", "enabled", object));
        }
        liveDelegations()
                .forEach((m, resources) -> resources.forEach(
                        r -> wanted.add(new FgaTuple("mission:" + m, "delegated", GrantTuples.resourceObject(r)))));
        return wanted;
    }

    /**
     * The imported files are put away: the grants file renamed, the ticks dropped from the definitions. When the journal
     * already says the import was made, a file left behind (a crash before the rename) must never bring back a grant
     * the director took back since, so it is only put away.
     */
    private void putAwayImported(Path grantsFile) {
        try {
            if (grantsFile != null && Files.isRegularFile(grantsFile))
                Files.move(grantsFile, dir.resolve(IMPORTED), StandardCopyOption.REPLACE_EXISTING);
            servers.dropStoredTicks();
        } catch (IOException e) {
            throw new FgaException(
                    FgaException.INVALID, "The imported files could not be renamed: " + e.getMessage(), e);
        }
    }

    public void reconcile() {
        List<String> objects = new ArrayList<>();
        catalogue.all().stream().map(Resource::name).forEach(n -> objects.add(GrantTuples.resourceObject(n)));
        servers.stored()
                .forEach(s -> s.tools()
                        .forEach(t -> objects.add(GrantTuples.toolObject(s.resource(), t.name(), t.sha256()))));
        tuples.reconcile(objects, liveDelegations());
    }

    private Map<MissionId, List<String>> liveDelegations() {
        Map<MissionId, List<String>> live = new LinkedHashMap<>();
        for (MissionId m : delegations.liveMissions())
            if (delegations.activeForWork(m)) delegations.find(m).ifPresent(d -> live.put(m, d.resources()));
        return live;
    }
}
