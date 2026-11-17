package dev.floor42.server.iam.fga;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.AuthorizationTuple;
import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.DelegationSink;
import dev.floor42.server.iam.OfficeFacts;
import dev.floor42.server.iam.OfficeFacts.AuthorizationChange;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Grants, mission delegations and MCP ticks as OpenFGA tuples (ADR-0080), and the checks the brokers make against them.
 * The screen is the only writer: each write is one OpenFGA call followed by one {@code AuthorizationChanged} row (at
 * most 100 tuples per call and per row). A journal row that cannot be written leaves a tuple the start reconciler
 * journals with {@code reconciled: true}, never silently. Every method either answers or throws {@link FgaException}:
 * an answer that is not a clear yes is a refusal.
 *
 * <p>{@code runner} (an agent on a mission) is never stored: it is a contextual tuple of the one check that carries the
 * session's agent and mission.
 */
@ApplicationScoped
public class GrantTuples implements DelegationSink {

    private static final Logger LOG = Logger.getLogger(GrantTuples.class);

    /** The relations Floor42 owns on resources and MCP tools: the ones it writes and the reconciler compares. */
    private static final Set<String> OWNED = Set.of("granted", "delegated", "server", "enabled");

    /** What a tool is pinned as: its name and the SHA-256 of its definition, which is part of the object id. */
    public record Pin(String tool, String sha256) {}

    private final FgaClient fga;
    private final Supplier<Optional<String>> model;
    private final OfficeFacts office;
    private final java.util.Queue<AuthorizationChange> unjournaled = new java.util.concurrent.ConcurrentLinkedQueue<>();

    @Inject
    GrantTuples(FgaClient fga, FgaStore store, OfficeFacts office) {
        this(fga, store::modelId, office);
    }

    public GrantTuples(FgaClient fga, Supplier<Optional<String>> model, OfficeFacts office) {
        this.fga = fga;
        this.model = model;
        this.office = office;
    }

    /** The recorded model id, or empty when OpenFGA is not the one in use (tests, IAM off). */
    public Optional<String> modelId() {
        return model.get();
    }

    // ---- the checks ------------------------------------------------------------------------------------------------

    /** The agent's grant AND the mission's delegation, decided by OpenFGA; the session's runner tuple is contextual. */
    public boolean allowed(AgentId agent, MissionId mission, String resource) {
        return fga.check(agentUser(agent), "can_call", resourceObject(resource), runner(agent, mission));
    }

    /** A tool ticked at exactly this definition (the hash is in the object id) of a server the caller may use. */
    public boolean toolAllowed(AgentId agent, MissionId mission, String server, String tool, String sha256) {
        return fga.check(agentUser(agent), "can_call", toolObject(server, tool, sha256), runner(agent, mission));
    }

    // ---- grants ----------------------------------------------------------------------------------------------------

    /** The resources each agent holds, among {@code known}, in catalogue order. */
    public Map<String, List<String>> grantsOf(Collection<String> known) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String resource : known)
            for (FgaTuple t : fga.read(resourceObject(resource)))
                if (t.relation().equals("granted") && t.user().startsWith("agent:"))
                    out.computeIfAbsent(t.user().substring("agent:".length()), k -> new ArrayList<>())
                            .add(resource);
        return out;
    }

    /** The agent holds exactly {@code names} among {@code known}: what is missing is written, what is extra deleted. */
    public synchronized void grant(AgentId agent, List<String> names, Collection<String> known) {
        List<FgaTuple> writes = new ArrayList<>();
        List<FgaTuple> deletes = new ArrayList<>();
        for (String resource : known) {
            FgaTuple t = new FgaTuple(agentUser(agent), "granted", resourceObject(resource));
            boolean held = fga.read(t.object()).contains(t);
            if (names.contains(resource) && !held) writes.add(t);
            if (!names.contains(resource) && held) deletes.add(t);
        }
        apply(writes, deletes, "GRANT", false, false);
    }

    /** A resource leaves the catalogue: its grants and delegations go. */
    public synchronized void forgetResource(String name) {
        apply(
                List.of(),
                fga.read(resourceObject(name)).stream()
                        .filter(GrantTuples::owned)
                        .toList(),
                "GRANT",
                false,
                false);
    }

    // ---- delegations (told by Delegations) -------------------------------------------------------------------------

    @Override
    public void granted(MissionId mission, List<String> resources) {
        try {
            apply(delegated(mission, resources), List.of(), "DELEGATION", false, false);
        } catch (FgaException e) {
            // Handled: the delegation stands in Floor42; the start reconciler writes what OpenFGA missed
            LOG.warn("The delegation of " + mission + " is not in OpenFGA yet: " + e.code());
        }
    }

    @Override
    public void ended(MissionId mission, List<String> resources, String reason) {
        try {
            apply(List.of(), delegated(mission, resources), reason, false, false);
        } catch (FgaException e) {
            // Handled: the local revocation already refuses every call; the start reconciler deletes the tuples
            LOG.warn("The end of the delegation of " + mission + " is not in OpenFGA yet: " + e.code());
        }
    }

    /** Writes the delegation tuple of one resource when it is missing; true when it wrote it (a check may now differ). */
    public synchronized boolean ensureDelegated(MissionId mission, String resource) {
        FgaTuple t = new FgaTuple(missionUser(mission), "delegated", resourceObject(resource));
        if (fga.read(t.object()).contains(t)) return false;
        apply(List.of(t), List.of(), "DELEGATION", false, false);
        return true;
    }

    private static List<FgaTuple> delegated(MissionId mission, List<String> resources) {
        return resources.stream()
                .map(r -> new FgaTuple(missionUser(mission), "delegated", resourceObject(r)))
                .toList();
    }

    // ---- MCP ticks -------------------------------------------------------------------------------------------------

    /** Whether the tool is ticked at this definition. */
    public boolean ticked(String server, String tool, String sha256) {
        return fga.read(toolObject(server, tool, sha256)).contains(enabled(server, tool, sha256));
    }

    /** The ticked set of {@code server}: each pinned tool in {@code ticked} gets its two tuples, each other loses them. */
    public synchronized void setTicks(String server, Collection<Pin> pinned, Set<String> ticked) {
        List<FgaTuple> writes = new ArrayList<>();
        List<FgaTuple> deletes = new ArrayList<>();
        for (Pin p : pinned) {
            boolean on = ticked.contains(p.tool());
            boolean was = ticked(server, p.tool(), p.sha256());
            if (on && !was) writes.addAll(tickTuples(server, p));
            if (!on && was) deletes.addAll(tickTuples(server, p));
        }
        apply(writes, deletes, "MCP_TICK", false, false);
    }

    /** Unticks the tools: both tuples of each go. */
    public synchronized void untick(String server, Collection<Pin> pins) {
        setTicks(server, pins, Set.of());
    }

    private static List<FgaTuple> tickTuples(String server, Pin p) {
        return List.of(
                new FgaTuple(resourceUser(server), "server", toolObject(server, p.tool(), p.sha256())),
                enabled(server, p.tool(), p.sha256()));
    }

    private static FgaTuple enabled(String server, String tool, String sha256) {
        return new FgaTuple("agent:*", "enabled", toolObject(server, tool, sha256));
    }

    // ---- the one-shot import of the 1.2.0 files --------------------------------------------------------------------

    /**
     * Writes the tuples of the old files once (reason IMPORT, {@code imported: true}); what OpenFGA already holds is not
     * written again, so a run that stopped before the files were renamed repeats nothing in the journal.
     */
    public synchronized void importTuples(List<FgaTuple> wanted) {
        Set<String> objects = new LinkedHashSet<>();
        wanted.forEach(t -> objects.add(t.object()));
        Set<FgaTuple> present = new LinkedHashSet<>();
        for (String object : objects) present.addAll(fga.read(object));
        apply(wanted.stream().filter(t -> !present.contains(t)).toList(), List.of(), "IMPORT", true, false);
    }

    // ---- the reconciler --------------------------------------------------------------------------------------------

    /**
     * What start does about a crash between an OpenFGA write and its journal row, and about writes behind Floor42's
     * back: the delegations of missions that no longer run are deleted, those that run and are missing are written
     * (both journaled with {@code reconciled: true}); then every difference between what the journal's rows leave
     * standing and what OpenFGA holds is journaled, again {@code reconciled: true}. It sees additions and deletions of
     * the relations Floor42 owns only. Idempotent.
     *
     * @param objects the resources and MCP tools to look at, in addition to every object the journal names
     * @param live the delegations that run now, mission to its resources
     */
    public synchronized void reconcile(Collection<String> objects, Map<MissionId, List<String>> live) {
        Set<String> look = new LinkedHashSet<>(objects);
        office.journaledAuthorization().forEach(t -> look.add(t.object()));
        Set<FgaTuple> expected = new LinkedHashSet<>();
        live.forEach((m, resources) -> expected.addAll(delegated(m, resources)));
        Set<FgaTuple> held = readOwned(look);
        List<FgaTuple> stale = held.stream()
                .filter(t -> t.relation().equals("delegated") && !expected.contains(t))
                .toList();
        List<FgaTuple> missing =
                expected.stream().filter(t -> !held.contains(t)).toList();
        apply(missing, stale, "RECONCILE", false, true);
        Set<FgaTuple> now = readOwned(look);
        Set<AuthorizationTuple> rows = office.journaledAuthorization();
        Set<AuthorizationTuple> heldDomain = new LinkedHashSet<>();
        now.forEach(t -> heldDomain.add(domain(t)));
        journal(
                heldDomain.stream().filter(t -> !rows.contains(t)).toList(),
                rows.stream().filter(t -> !heldDomain.contains(t)).toList(),
                "RECONCILE",
                false,
                true);
    }

    private Set<FgaTuple> readOwned(Collection<String> objects) {
        Set<FgaTuple> out = new LinkedHashSet<>();
        for (String object : objects) for (FgaTuple t : fga.read(object)) if (owned(t)) out.add(t);
        return out;
    }

    private static boolean owned(FgaTuple t) {
        return OWNED.contains(t.relation())
                && (t.object().startsWith("resource:") || t.object().startsWith("mcp_tool:"));
    }

    // ---- writing ---------------------------------------------------------------------------------------------------

    /** OpenFGA first, then the journal row; at most 100 tuples of each kind per call. */
    private void apply(
            List<FgaTuple> writes, List<FgaTuple> deletes, String reason, boolean imported, boolean reconciled) {
        int w = 0;
        int d = 0;
        while (w < writes.size() || d < deletes.size()) {
            List<FgaTuple> wc = writes.subList(w, Math.min(writes.size(), w + FgaClient.MAX_WRITE));
            List<FgaTuple> dc = deletes.subList(d, Math.min(deletes.size(), d + FgaClient.MAX_WRITE));
            fga.write(wc, dc);
            journal(
                    wc.stream().map(GrantTuples::domain).toList(),
                    dc.stream().map(GrantTuples::domain).toList(),
                    reason,
                    imported,
                    reconciled);
            w += wc.size();
            d += dc.size();
        }
    }

    private void journal(
            List<AuthorizationTuple> writes,
            List<AuthorizationTuple> deletes,
            String reason,
            boolean imported,
            boolean reconciled) {
        for (int w = 0, d = 0;
                w < writes.size() || d < deletes.size();
                w += FgaClient.MAX_WRITE, d += FgaClient.MAX_WRITE) {
            List<AuthorizationTuple> wc =
                    writes.subList(Math.min(w, writes.size()), Math.min(writes.size(), w + FgaClient.MAX_WRITE));
            List<AuthorizationTuple> dc =
                    deletes.subList(Math.min(d, deletes.size()), Math.min(deletes.size(), d + FgaClient.MAX_WRITE));
            AuthorizationChange change = new AuthorizationChange(
                    List.copyOf(wc), List.copyOf(dc), model.get().orElse("unrecorded"), reason, imported, reconciled);
            try {
                office.authorizationChanged(change);
            } catch (RuntimeException e) {
                // Handled: OpenFGA already holds the change; it is kept, told by the IAM status and retried every
                // minute, and the next start journals what is still missing with reconciled: true
                unjournaled.add(change);
                LOG.warn("An authorization change is not journaled yet ("
                        + e.getClass().getSimpleName() + "): " + wc.size() + " written, " + dc.size() + " deleted");
            }
        }
    }

    /** How many changes OpenFGA holds that the journal does not yet: the IAM status says so while it is above zero. */
    public int unjournaled() {
        return unjournaled.size();
    }

    /** Journals again what could not be journaled; what still fails stays queued. Run by the identity watch. */
    public synchronized void retryJournal() {
        for (int n = unjournaled.size(); n > 0; n--) {
            AuthorizationChange change = unjournaled.poll();
            if (change == null) return;
            try {
                office.authorizationChanged(change);
            } catch (RuntimeException e) {
                // Handled: still queued, the status still says so
                unjournaled.add(change);
            }
        }
    }

    /** Whether the journal already holds the one-shot import: it never runs twice. */
    public boolean alreadyImported() {
        return office.authorizationImported();
    }

    private static AuthorizationTuple domain(FgaTuple t) {
        return new AuthorizationTuple(t.user(), t.relation(), t.object());
    }

    // ---- ids -------------------------------------------------------------------------------------------------------

    private static List<FgaTuple> runner(AgentId agent, MissionId mission) {
        return List.of(new FgaTuple(agentUser(agent), "runner", "mission:" + mission));
    }

    private static String agentUser(AgentId a) {
        return "agent:" + a.value();
    }

    private static String missionUser(MissionId m) {
        return "mission:" + m;
    }

    private static String resourceUser(String name) {
        return "resource:" + name;
    }

    /** {@code resource:<name>}, the object of grants and delegations. */
    public static String resourceObject(String name) {
        return "resource:" + name;
    }

    /** {@code mcp_tool:<server>/<tool>/<sha256>}: a changed definition is another object, with no tuples. */
    public static String toolObject(String server, String tool, String sha256) {
        return "mcp_tool:" + server + "/" + tool + "/" + sha256;
    }
}
