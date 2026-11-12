package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Company;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.SandboxTools;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.server.runtime.DockerAccess;
import dev.floor42.server.runtime.SandboxProbe;
import dev.floor42.server.runtime.SandboxToolkit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Says at plan time what the sandbox does with Docker, when the project needs it (a Dockerfile or a compose file at its
 * root, or a ticket that NEEDS docker). The daemon is measured then, from inside the sandbox, and the question says only
 * what that measure says: out of reach (installing would change nothing, ADR-0028), no docker command, or not measured.
 * The options are the honest ones: the director runs the Docker steps (named tickets only), or the mission goes on
 * without Docker. Nothing here mounts the socket or widens a permission (ADR-0056 only proposes it).
 */
@ApplicationScoped
public class DockerQuestion {

    /** The phrase codes, by what the measure said; {@code EnvironmentBlocker.directorStep} reads "tools", "tickets". */
    static final String UNREACHABLE = "dockerUnreachable";

    static final String MISSING = "dockerMissing";

    static final String UNMEASURED = "dockerUnmeasured";

    static final List<String> OPTIONS = List.of(OptionCode.DO_IT_MYSELF.label(), OptionCode.WITHOUT_DOCKER.label());

    private static final List<String> COMPOSE =
            List.of("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml");

    /** What makes a mission plausibly use Docker: its objective, checks command or tickets say one of these. */
    private static final java.util.regex.Pattern MENTIONS =
            java.util.regex.Pattern.compile("docker|compose|container|image", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final Logger LOG = Logger.getLogger(DockerQuestion.class);

    private final OfficeService office;
    private final SandboxProbe probe;
    private final MissingTools missingTools;

    @Inject
    DockerQuestion(OfficeService office, SandboxProbe probe, MissingTools missingTools) {
        this.office = office;
        this.probe = probe;
        this.missingTools = missingTools;
    }

    /**
     * What says the project needs Docker: the files at its root (as {@link ProjectToolchain} reads them, plus compose
     * files) when {@code missionText} (objective, checks command, ticket texts) mentions Docker, and "plan" when a ticket NEEDS docker. Empty when nothing does. Reads files only.
     */
    static List<String> because(Path root, String checkCommand, List<List<String>> toolNeeds, String missionText) {
        LinkedHashSet<String> because = new LinkedHashSet<>();
        // A file of the repository alone does not make the mission use Docker: it counts when the mission says so
        if (MENTIONS.matcher(missionText == null ? "" : missionText).find()) {
            for (ProjectToolchain.Need n : ProjectToolchain.read(root, checkCommand)) {
                if (n.tool().equals("docker")) because.addAll(n.because());
            }
            if (root != null) {
                for (String f : COMPOSE) if (Files.isRegularFile(root.resolve(f))) because.add(f);
            }
        }
        if (toolNeeds.stream().anyMatch(DockerQuestion::namesDocker)) because.add("plan");
        return List.copyOf(because);
    }

    private static boolean namesDocker(List<String> tools) {
        return tools.stream().map(SandboxToolkit::normalize).anyMatch("docker"::equals);
    }

    /**
     * Before the plan is recorded, outside the office lock: when the project needs Docker and the sandbox is on, measures
     * the daemon unless a probe already did since {@code since}. The tools' part is journaled as any measure; the
     * daemon's state stays in memory. Never throws.
     */
    void prepare(MissionId m, List<String> because, Instant since) {
        if (because.isEmpty() || !office.query(DockerQuestion::sandboxed)) return;
        Instant at = probe.docker().at();
        if (at != null && !at.isBefore(since)) return;
        try {
            SandboxTools last = office.query(c -> c.preferences().sandboxTools().orElse(null));
            SandboxProbe.Probe p = probe.probe(MissingTools.asked(List.of(), last));
            office.execute(c -> missingTools.record(c, p));
        } catch (RuntimeException e) {
            // Handled: logged; the question then says Docker was not measured
            LOG.warnf("Mission %s: the sandbox could not be measured for Docker: %s", m, e.getMessage());
        }
    }

    /**
     * The question's events, inside the plan's command: none when nothing needs Docker, the sandbox is off, the daemon
     * answered from inside it, the mission is closed, or such a question is open. Never throws: a question that cannot
     * be asked is logged, the plan stands.
     */
    List<EventEnvelope> askWithin(
            Company c, MissionId m, List<String> because, List<EventEnvelope> planned, List<List<String>> toolNeeds) {
        try {
            DockerAccess.Measure docker = probe.docker();
            if (because.isEmpty() || !wanted(c, m, docker.state())) return List.of();
            List<TicketId> tickets = tickets(planned, toolNeeds);
            Phrase phrase = phrase(tickets, because, docker);
            return c.desk()
                    .ask(
                            office.context(Actor.system(), Origin.REAL),
                            m,
                            subject(phrase.code()),
                            context(phrase.code(), phrase.params()),
                            OPTIONS,
                            null,
                            Criticality.HIGH,
                            true,
                            tickets.isEmpty() ? null : tickets.getFirst(),
                            phrase);
        } catch (DomainException e) {
            // Handled: checked before emitting (Desk.ask), so nothing was applied; the plan stands without the question
            LOG.warnf("Mission %s: the Docker question could not be asked: %s", m, e.getMessage());
            return List.of();
        }
    }

    private static boolean sandboxed(Company c) {
        return OfficeSettings.sandboxed(c);
    }

    /** Sandbox on, daemon not answering from inside it, mission still open, no such question open already. */
    private static boolean wanted(Company c, MissionId m, DockerAccess.InSandbox state) {
        if (!sandboxed(c) || state == DockerAccess.InSandbox.DAEMON_REACHABLE) return false;
        if (c.missions().find(m).map(x -> x.status().isTerminal()).orElse(true)) return false;
        return c.desk().of(m).stream()
                .noneMatch(q -> q.status() == QuestionStatus.OPEN
                        && q.phrase() != null
                        && List.of(UNREACHABLE, MISSING, UNMEASURED)
                                .contains(q.phrase().code()));
    }

    /** What the screen writes in its language: the code says what was measured, the values are language-neutral. */
    private static Phrase phrase(List<TicketId> tickets, List<String> because, DockerAccess.Measure docker) {
        // Never asked when the daemon answered (wanted): what is left is out of reach, no client, or not measured.
        DockerAccess.InSandbox state = docker.state();
        String code = state == DockerAccess.InSandbox.DAEMON_UNREACHABLE_IN_SANDBOX
                ? UNREACHABLE
                : state == DockerAccess.InSandbox.CLIENT_MISSING ? MISSING : UNMEASURED;
        return Phrase.of(
                code,
                "tools",
                "docker",
                "tickets",
                String.join(", ", tickets.stream().map(TicketId::value).toList()),
                "because",
                String.join(", ", because),
                "measured",
                docker.at() == null ? "" : docker.at().toString(),
                "reason",
                docker.reason() == null ? "" : docker.reason());
    }

    /**
     * The tickets that NEED docker, and only those: a review that does not validate escalates to the director for
     * them alone. Empty when none names it: the director's step is then the mission's, no ticket's.
     */
    static List<TicketId> tickets(List<EventEnvelope> planned, List<List<String>> toolNeeds) {
        List<TicketId> created = planned.stream()
                .map(EventEnvelope::event)
                .filter(TicketCreated.class::isInstance)
                .map(e -> ((TicketCreated) e).ticket())
                .toList();
        List<TicketId> naming = new ArrayList<>();
        for (int i = 0; i < created.size() && i < toolNeeds.size(); i++) {
            if (namesDocker(toolNeeds.get(i))) naming.add(created.get(i));
        }
        return List.copyOf(naming);
    }

    private static String subject(String code) {
        return switch (code) {
            case UNREACHABLE -> "The project needs Docker, and the sandbox cannot reach its daemon";
            case MISSING -> "The project needs Docker, and the sandbox has no docker command";
            default -> "The project needs Docker, and Floor42 could not measure whether the sandbox reaches it";
        };
    }

    /** The journal's English sentence, from the phrase's values. */
    private static String context(String code, Map<String, String> p) {
        String measured = switch (code) {
            case UNREACHABLE ->
                "Measured at " + p.get("measured") + ": the shell sandbox cannot reach the Docker daemon (its socket is"
                        + " hidden there and the docker group dropped). Installing Docker will not change it.";
            case MISSING ->
                "Measured at " + p.get("measured") + ": the sandbox has no docker command, and installing one would"
                        + " not let it reach the daemon (its socket is hidden there).";
            default -> "Floor42 could not measure whether the sandbox reaches Docker (" + p.get("reason") + ").";
        };
        return "Needed by " + p.get("because") + ". " + measured + " Floor42 does not open the sandbox to Docker. Do"
                + " the Docker steps yourself (the developer does the rest and leaves those steps to you), or go on"
                + " without Docker: the mission does without it, and a step that needs it is reported as blocked.";
    }
}
