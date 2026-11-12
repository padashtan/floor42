package dev.floor42.server.app;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.ActionStage;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.Decision;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.ReplayRuntime;
import dev.floor42.server.runtime.SessionReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

/**
 * A complete simulated mission, end to end, driven through the real domain and journal: planning, permissions,
 * approval, a replayed Claude Code session under governance, a director decision, an independent review and a question
 * left on the director's desk. Every event it records is {@link Origin#SIMULATED}.
 *
 * <p>Two ways to play it. {@link #run()} plays it at once, with a scripted director, for tests and the CLI. {@link
 * #startLive} plays it at a watchable pace in the background and waits for the real director to answer the approval
 * request: the blocked ticket stays blocked until then, while nothing else is stopped.
 */
@ApplicationScoped
public class DemoScenario {

    public record Result(MissionId mission, SessionReport session) {}

    private static final Logger LOG = Logger.getLogger(DemoScenario.class);
    static final String TRANSCRIPT = "/transcripts/inventory-with-notes.jsonl";
    static final AgentId GALEN = SimulatedCrew.GALEN;
    static final AgentId CHRIS = SimulatedCrew.CHRIS;
    static final AgentId DARIUS = SimulatedCrew.DARIUS;
    static final Duration DIRECTOR_PATIENCE = Duration.ofMinutes(30);

    @Inject
    MissionScheduler scheduler;

    @Inject
    OfficeService office;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper json;

    @Inject
    SimulatedCrew crew;

    @Inject
    dev.floor42.server.iam.SessionIdentity identity;

    public Result run() {
        MissionId m = open();
        try {
            return new Result(m, play(m, Duration.ZERO, false));
        } catch (InterruptedException | TimeoutException e) {
            // Without pace and without waiting for the director, neither can happen; keep the interrupt visible.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo interrupted", e);
        }
    }

    /** Creates the mission now and plays the rest in the background. Returns the new mission id. */
    public MissionId startLive(Duration pace) {
        MissionId m = open();
        scheduler.fork("demo-" + m, () -> {
            try {
                play(m, pace, true);
            } catch (DomainException e) {
                // Handled: The director may cancel or suspend the mission at any time: the demo then simply stops.
                LOG.infof("Demo %s stopped: %s", m, e.getMessage());
            } catch (InterruptedException | TimeoutException e) {
                // Handled: logged; the demo stops there
                LOG.infof("Demo %s stopped while waiting: %s", m, e);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        });
        return m;
    }

    private MissionId open() {
        crew.hireIfMissing();
        return ((MissionCreated) office.execute(c -> c.missions()
                                .create(
                                        director(),
                                        "Inventory the sandbox repository (demo)",
                                        "Replay a recorded read-only Claude Code session under Floor42 governance"))
                        .getFirst()
                        .event())
                .mission();
    }

    private SessionReport play(MissionId m, Duration pace, boolean waitForDirector)
            throws InterruptedException, TimeoutException {
        TicketId inspect = ticket(
                m, "Inspect the repository state", List.of(), List.of("Working tree status reported", "README read"));
        TicketId summarize = ticket(
                m,
                "Summarize findings for the director",
                List.of(inspect),
                List.of("Findings list what was checked and what was refused"));
        office.execute(c -> c.tickets().assign(director(), inspect, CHRIS));
        office.execute(c -> c.tickets().assign(director(), summarize, GALEN));
        pause(pace);
        approveReadOnlyPlan(m, pace);
        office.execute(c -> c.tickets().start(agent(CHRIS), inspect));
        pause(pace);

        SessionSpec spec = new SessionSpec(m, inspect, CHRIS, Origin.SIMULATED, "/workspace");
        Duration patience = waitForDirector ? DIRECTOR_PATIENCE : Duration.ZERO;
        SessionReport report = new ReplayRuntime(replayLines(), pace, patience, json)
                .identifying(identity)
                .run(spec, new MissionGovernor(office, spec));
        settleApprovals(m, waitForDirector);
        pause(pace);
        review(m, inspect, report, pace);
        askAboutTheWrites(m, summarize);
        return report;
    }

    /** Read-only mission: reading is allowed, two git commands are allowed, any write needs the director. */
    private void approveReadOnlyPlan(MissionId m, Duration pace) throws InterruptedException {
        allow(m, new PermissionRule(null, ActionCategory.READ, null, Decision.ALLOW));
        allow(m, new PermissionRule(null, ActionCategory.EXECUTE, "git status", Decision.ALLOW));
        allow(m, new PermissionRule(null, ActionCategory.EXECUTE, "git log", Decision.ALLOW));
        allow(m, new PermissionRule(null, ActionCategory.WRITE, null, Decision.ASK));
        office.execute(c -> c.missions().submitPlan(agent(GALEN), m));
        pause(pace);
        office.execute(c -> c.missions().approvePlan(director(), m));
        pause(pace);
    }

    /**
     * The recorded session tried to write a file: the policy turned it into a question for the director. The director
     * may already have answered while the session was replaying: take every approval request.
     */
    private void settleApprovals(MissionId m, boolean waitForDirector) throws InterruptedException, TimeoutException {
        List<QuestionId> approvals = office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.kind() == QuestionKind.ACTION_APPROVAL)
                .map(Question::id)
                .toList());
        for (QuestionId q : approvals) {
            if (waitForDirector) awaitAnswer(m, q);
            else if (office.query(c -> c.desk().find(q).orElseThrow().status() == QuestionStatus.OPEN)) {
                office.execute(c -> c.desk().answer(director(), q, "DENY"));
            }
        }
    }

    /** The deliverable text is the recorded agent's; the decisions behind it are this mission's. */
    private void review(MissionId m, TicketId inspect, SessionReport report, Duration pace)
            throws InterruptedException {
        office.execute(c -> c.tickets()
                .submitForReview(
                        agent(CHRIS),
                        inspect,
                        (report.finalText() == null ? "(no final message)" : report.finalText())
                                + "\n[Replayed session: the text was written during the recording; see the actions for what"
                                + " this mission allowed.]"));
        pause(pace.multipliedBy(2));
        office.execute(c -> c.tickets()
                .review(
                        agent(DARIUS),
                        inspect,
                        ReviewVerdict.VALIDATED,
                        "Checked the recorded tool results: git status clean, README content matches; the write was decided by"
                                + " the director."));
        office.query(c -> c.actions().of(m).stream()
                        .filter(a ->
                                a.stage() == ActionStage.EXECUTED && a.tool().equals("Bash"))
                        .map(a -> a.id())
                        .toList())
                .forEach(a -> office.execute(c -> c.actions()
                        .confirm(
                                agent(DARIUS),
                                a,
                                "Independent re-read of the recorded output (simulated confirmation)")));
        pause(pace);
    }

    private void askAboutTheWrites(MissionId m, TicketId summarize) {
        office.execute(c -> c.desk()
                .ask(
                        agent(GALEN),
                        m,
                        "Should the summary mention the file writes?",
                        "Chris asked to create notes.txt and summary.txt; the director decided once for both.",
                        List.of("Yes", "No"),
                        "Yes",
                        Criticality.LOW,
                        false,
                        summarize));
    }

    /** Blocks this scenario (and only it) until the director answers, the question is withdrawn or the mission closes. */
    private void awaitAnswer(MissionId m, QuestionId q) throws InterruptedException, TimeoutException {
        crew.awaitDirector(m, c -> c.desk().find(q).orElseThrow().status() != QuestionStatus.OPEN, DIRECTOR_PATIENCE);
    }

    private static void pause(Duration pace) throws InterruptedException {
        if (!pace.isZero()) Thread.sleep(pace);
    }

    private TicketId ticket(MissionId m, String title, List<TicketId> deps, List<String> criteria) {
        return crew.ticket(m, title, deps, criteria);
    }

    private void allow(MissionId m, PermissionRule rule) {
        crew.allow(m, rule);
    }

    private CommandContext director() {
        return crew.director();
    }

    private CommandContext agent(AgentId id) {
        return crew.agent(id);
    }

    static List<String> replayLines() {
        return replayLines(TRANSCRIPT);
    }

    static List<String> replayLines(String resource) {
        try (InputStream in = DemoScenario.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }
}
