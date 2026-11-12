package dev.floor42.server.app;

import static dev.floor42.server.app.SimulatedCrew.CHRIS;
import static dev.floor42.server.app.SimulatedCrew.DEHREN;
import static dev.floor42.server.app.SimulatedCrew.GEOFFREY;
import static dev.floor42.server.app.SimulatedCrew.pause;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.Decision;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.AskOutcome;
import dev.floor42.server.runtime.ReplayRuntime;
import dev.floor42.server.runtime.SessionReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

/**
 * The guided first mission (ADR-0045): a rehearsal on Floor42's sample repository, every event {@link
 * Origin#SIMULATED}, no model called. The director's objective opens it; then it waits for the director at each step a
 * real mission would: the plan's approval with its cap, an agent's question (ask_director), the recorded session's
 * writes. The writes the director allows land in the rehearsal's worktree of the sample, for the workshop to show.
 */
@ApplicationScoped
public class PracticeScenario {

    private static final Logger LOG = Logger.getLogger(PracticeScenario.class);
    static final Duration PATIENCE = Duration.ofMinutes(30);
    static final String WORKDIR = "/workspace";
    static final String QUESTION = "Should my findings quote the README of the sample repository?";
    static final List<String> OPTIONS = List.of("Yes", "No");

    /** Where a rehearsal stands: its script runs, it went to its end, or it stopped (restart, refusal, patience). */
    public enum State {
        PLAYING,
        DONE,
        STOPPED
    }

    /** The rehearsals whose script runs in this daemon: none after a restart. */
    private final Set<MissionId> playing = ConcurrentHashMap.newKeySet();

    /** The plan's two tickets. */
    record Plan(TicketId inspect, TicketId summarize) {}

    @Inject
    MissionScheduler scheduler;

    @Inject
    OfficeService office;

    @Inject
    SimulatedCrew crew;

    @Inject
    SampleRepository sample;

    @Inject
    Workspaces workspaces;

    @Inject
    ObjectMapper json;

    @Inject
    dev.floor42.server.iam.SessionIdentity identity;

    /** Opens the rehearsal and its plan now (awaiting approval on return), and plays the rest in the background. */
    public MissionId start(String title, String objective, Duration pace) {
        crew.hireIfMissing();
        String repository = sample.prepare().toString();
        MissionId m = ((MissionCreated)
                        office.execute(c -> c.missions().createRehearsal(crew.director(), title, objective, repository))
                                .getFirst()
                                .event())
                .mission();
        Plan plan = plan(m);
        playing.add(m);
        scheduler.fork("practice-" + m, () -> playUntilStopped(m, plan, pace));
        return m;
    }

    private void playUntilStopped(MissionId m, Plan plan, Duration pace) {
        try {
            play(m, plan, pace);
        } catch (DomainException e) {
            // Handled: The director may reject the plan, cancel or suspend the mission at any time: the rehearsal stops
            // there.
            LOG.infof("Practice mission %s stopped: %s", m, e.getMessage());
        } catch (InterruptedException | TimeoutException e) {
            // Handled: logged; the rehearsal stops there
            LOG.infof("Practice mission %s stopped while waiting: %s", m, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // Handled: logged; the rehearsal stops there, the daemon goes on
            LOG.warnf(e, "Practice mission %s stopped", m);
        } finally {
            playing.remove(m);
        }
    }

    /**
     * Where the rehearsal stands; empty for a mission that is not one. Nothing resumes a stopped script: a restart of
     * the daemon or the director's patience running out leaves it as it was, and the guide offers a new one.
     */
    public Optional<State> state(MissionId m) {
        if (playing.contains(m)) return Optional.of(State.PLAYING);
        return office.query(c -> c.missions()
                .find(m)
                .filter(PracticeScenario::isRehearsal)
                .map(x -> {
                    var tickets = c.tickets().of(m);
                    // No ticket is no plan (it failed after the mission was created): stopped, never done.
                    boolean done = x.status() == MissionStatus.COMPLETED
                            || (!tickets.isEmpty() && tickets.stream().allMatch(t -> t.status() == TicketStatus.DONE));
                    return done ? State.DONE : State.STOPPED;
                }));
    }

    private static boolean isRehearsal(Mission m) {
        return m.origin() == Origin.SIMULATED && m.workspace() != null;
    }

    /** Geoffrey's plan: two tickets, then the permission table a read-and-note mission needs. */
    Plan plan(MissionId m) {
        TicketId inspect = crew.ticket(
                m,
                "Inspect the sample repository",
                List.of(),
                List.of(
                        "Working tree status reported",
                        "README read",
                        "Notes written only where the director allowed"));
        TicketId summarize = crew.ticket(
                m, "Summarize findings for the director", List.of(inspect), List.of("Says what was checked"));
        office.execute(c -> c.tickets().assign(crew.director(), inspect, CHRIS));
        office.execute(c -> c.tickets().assign(crew.director(), summarize, GEOFFREY));
        crew.allow(m, new PermissionRule(null, ActionCategory.READ, null, Decision.ALLOW));
        crew.allow(m, new PermissionRule(null, ActionCategory.EXECUTE, "git status", Decision.ALLOW));
        crew.allow(m, new PermissionRule(null, ActionCategory.WRITE, null, Decision.ASK));
        office.execute(c -> c.missions().submitPlan(crew.agent(GEOFFREY), m));
        return new Plan(inspect, summarize);
    }

    void play(MissionId m, Plan plan, Duration pace) throws InterruptedException, TimeoutException {
        crew.awaitDirector(
                m, c -> c.missions().find(m).orElseThrow().status() != MissionStatus.AWAITING_APPROVAL, PATIENCE);
        pause(pace);
        office.execute(c -> c.tickets().start(crew.agent(CHRIS), plan.inspect()));
        SessionSpec spec = new SessionSpec(m, plan.inspect(), CHRIS, Origin.SIMULATED, WORKDIR);
        SampleWrites governor = new SampleWrites(new MissionGovernor(office, spec), worktree(m), WORKDIR);
        // The agents' ask_director path: a blocking question on the desk, the ticket waiting for the answer.
        var quote = governor.askDirector(QUESTION, OPTIONS, "Yes", null, PATIENCE);
        crew.requireInProgress(m);
        pause(pace);
        SessionReport report = new ReplayRuntime(DemoScenario.replayLines(), pace, PATIENCE, json)
                .identifying(identity)
                .run(spec, governor);
        crew.requireInProgress(m);
        deliver(plan, findings(report, quote instanceof AskOutcome.Answer a && "Yes".equals(a.text())), pace);
    }

    /** Chris's worktree of the sample, branched for this mission and recorded so the workshop shows it. */
    private Path worktree(MissionId m) {
        String repository = office.query(c -> c.missions().find(m).orElseThrow().workspace());
        Path worktree = workspaces.worktree(repository, m, CHRIS);
        String branch = workspaces.branch(worktree);
        String base = workspaces.head(worktree);
        office.execute(c -> c.workshop()
                .prepareWorkspace(
                        office.context(Actor.system(), Origin.SIMULATED), m, CHRIS, worktree.toString(), branch, base));
        return worktree;
    }

    private void deliver(Plan plan, String findings, Duration pace) throws InterruptedException {
        office.execute(c -> c.tickets().submitForReview(crew.agent(CHRIS), plan.inspect(), findings));
        pause(pace);
        office.execute(c -> c.tickets()
                .review(
                        crew.agent(DEHREN),
                        plan.inspect(),
                        ReviewVerdict.VALIDATED,
                        "Checked the recorded tool results against the sample: README content matches."));
        office.execute(c -> c.tickets().start(crew.agent(GEOFFREY), plan.summarize()));
        pause(pace);
        office.execute(c -> c.tickets()
                .submitForReview(
                        crew.agent(GEOFFREY),
                        plan.summarize(),
                        "Simulated mission: the sample was inspected, notes were written only where the director allowed,"
                                + " and nothing was paid (no model was called)."));
        office.execute(c -> c.tickets()
                .review(
                        crew.agent(DEHREN),
                        plan.summarize(),
                        ReviewVerdict.VALIDATED,
                        "The summary matches the record."));
    }

    static String findings(SessionReport report, boolean quoteReadme) {
        String text = report.finalText() == null ? "(no final message)" : report.finalText();
        String readme = quoteReadme
                ? "\nREADME.md, as read: " + SampleRepository.README.strip().replace("\n", " / ")
                : "";
        return text + readme
                + "\n[Replayed session: the text was written during the recording; see the actions for what this"
                + " mission allowed.]";
    }
}
