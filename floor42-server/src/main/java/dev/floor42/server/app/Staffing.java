package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.Setting;
import dev.floor42.domain.StaffingReview;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.DeskEvent.QuestionAnswered;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Reinforcing the team of a mission (asked by the director from the board). The planner reads the facts Floor42
 * measured, on a light model and with no tool, and advises whether more developers would finish sooner at a cost
 * worth paying, and whom to hire. Floor42 then asks the director yes or no; only a yes hires, and raises the parallel
 * sessions if the planner advised it. Floor42's own figures go with the question, labelled as estimates.
 */
@ApplicationScoped
public class Staffing {

    private static final Logger LOG = Logger.getLogger(Staffing.class);

    private final MissionScheduler scheduler;
    private final OfficeService office;
    /** Every live session goes through the gate's origin rule (ADR-0045); a retrospective's holds are its own. */
    private final SessionGate gate;

    private final Workspaces workspaces;
    private final LiveMissions live;
    private final StaffProvisioner staffProvisioner;
    private final OfficeSettings settings;
    /** Off only in tests that drive the runtime by hand. */
    private final boolean enabled;

    @Inject
    Staffing(
            MissionScheduler scheduler,
            OfficeService office,
            SessionGate gate,
            Workspaces workspaces,
            LiveMissions live,
            StaffProvisioner staffProvisioner,
            OfficeSettings settings,
            @ConfigProperty(name = "floor42.live-missions.enabled", defaultValue = "true") boolean enabled) {
        this.scheduler = scheduler;
        this.office = office;
        this.gate = gate;
        this.workspaces = workspaces;
        this.live = live;
        this.staffProvisioner = staffProvisioner;
        this.settings = settings;
        this.enabled = enabled;
    }

    void listen(@Observes StartupEvent ignored) {
        if (enabled) office.subscribe(this::react);
    }

    /** The director's request: the planner's session runs on its own thread; the advice comes as a question. */
    public void request(MissionId m) {
        staffProvisioner.ensurePlanner();
        office.execute(c -> c.staff()
                .requestStaffingReview(office.context(Actor.director(), Origin.REAL), m, StaffProvisioner.PLANNER));
        scheduler.fork("staffing-" + m, () -> review(m));
    }

    void review(MissionId m) {
        StaffingReview r = office.query(c -> c.staff().staffingReview(m).orElseThrow());
        AgentReservations.Reservation reservation = reserve(m, r.planner());
        if (reservation == null) return;
        try {
            Facts facts = office.query(c -> facts(c, m, settings.parallelSessions()));
            SessionSpec spec = office.query(c -> specOf(c, r, facts));
            settle(m, r.planner(), facts, gate.runUnheld(spec));
        } catch (DomainException e) {
            // Handled: the review is given up, and the director told why
            giveUp(m, "the planner's advice was refused: " + e.getMessage());
        } catch (RuntimeException e) {
            // Handled: logged; the review is given up, and the director told why
            LOG.errorf(e, "Mission %s: the staffing review failed", m);
            giveUp(m, "Floor42 could not run the review: " + e.getMessage());
        } finally {
            live.release(reservation);
        }
    }

    /** The planner held for the review; null when the daemon stops, or when it stayed busy and the review is closed. */
    private AgentReservations.Reservation reserve(MissionId m, AgentId planner) {
        try {
            return live.await(planner, live.reservationPatience());
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the review is not run
            Thread.currentThread().interrupt();
            return null;
        } catch (AgentReservations.ReservationTimeout e) {
            // Handled: the review is given up, and the director told why
            giveUp(m, "Floor42 could not run the review: " + e.getMessage());
            return null;
        }
    }

    /** No tool at all: the facts are in the brief, and nothing can change anything. */
    private SessionSpec specOf(Company c, StaffingReview r, Facts facts) {
        Mission mission = c.missions().find(r.mission()).orElseThrow();
        Agent a = c.staff().find(r.planner()).orElseThrow();
        return new SessionSpec(
                r.mission(),
                r.ticket(),
                r.planner(),
                Origin.REAL,
                workspaces.research(r.mission()).toString(),
                Briefs.inLanguage(c, Briefs.staffing(mission, a, facts.text())),
                settings.retroModel(),
                "low",
                List.of());
    }

    /** The advice is recorded as given; when there is none, the record says why, truly. */
    void settle(MissionId m, AgentId planner, Facts facts, Unheld result) {
        Unheld.Answer answer = Unheld.answerOf(result);
        if (!answer.heard()) {
            giveUp(m, "Floor42 could not get the planner's advice: " + answer.why());
            return;
        }
        Briefs.StaffingAdvice advice = Briefs.staffingAdvice(answer.text());
        office.execute(c -> c.staff()
                .adviseStaffing(
                        office.context(Actor.agent(planner), Origin.REAL),
                        m,
                        answer.text(),
                        advice.worthIt(),
                        advice.hires(),
                        advice.parallel(),
                        facts.estimate()));
    }

    /**
     * The daemon stopped before the planner advised (start-up recovery): the review is closed as interrupted, which
     * frees the planner and the director's next request. The director asks again if he still wants it.
     */
    void closeInterrupted(MissionId m) {
        boolean unadvised = office.query(
                c -> c.staff().staffingReview(m).map(r -> !r.advised()).orElse(false));
        if (unadvised)
            giveUp(m, "interrupted: the daemon stopped before the planner advised. Ask for the review again.");
    }

    private void giveUp(MissionId m, String why) {
        try {
            office.execute(c -> c.staff()
                    .adviseStaffing(office.context(Actor.system(), Origin.REAL), m, why, false, List.of(), null, null));
        } catch (DomainException e) {
            // Handled: logged; the review stays open
            LOG.infof("Mission %s: staffing review not closed: %s", m, e.getMessage());
        }
    }

    private void react(List<EventEnvelope> events) {
        for (EventEnvelope e : events) {
            if (e.origin() != Origin.REAL || !(e.event() instanceof QuestionAnswered answered)) continue;
            Question q = office.query(c -> c.desk().find(answered.question()).orElse(null));
            if (q == null || q.kind() != QuestionKind.STAFFING) continue;
            dev.floor42.domain.OptionCode code = q.codeOf(answered.answer());
            if (code == dev.floor42.domain.OptionCode.HIRE || code == dev.floor42.domain.OptionCode.HIRE_ANYWAY) {
                scheduler.fork("hire-" + q.mission(), () -> hire(q.mission()));
            }
        }
    }

    /** The director said yes: Floor42 hires the developers the planner named, and raises the parallel sessions. */
    void hire(MissionId m) {
        // Values read under the service lock, never the live review.
        record Advice(List<StaffingReview.Hire> hires, Integer parallel) {}
        Advice r = office.query(c -> {
            StaffingReview x = c.staff().staffingReview(m).orElseThrow();
            return new Advice(List.copyOf(x.hires()), x.parallel());
        });
        CommandContext director = office.context(Actor.director(), Origin.REAL);
        // New developers work like the first one: same model and effort.
        Agent model = office.query(
                c -> Staff.active(c, WorkRole.DEVELOPER).stream().findFirst().orElse(null));
        for (StaffingReview.Hire h : r.hires()) hireOne(m, director, model, h);
        if (r.parallel() != null && r.parallel() > settings.parallelSessions()) {
            office.execute(c ->
                    c.preferences().changeSetting(director, Setting.PARALLEL_SESSIONS, String.valueOf(r.parallel())));
        }
        live.staffChanged();
    }

    private void hireOne(MissionId m, CommandContext director, Agent model, StaffingReview.Hire h) {
        try {
            office.execute(c -> c.staff()
                    .hire(
                            director,
                            Staff.newId(c, h.name()),
                            h.name(),
                            h.title() == null || h.title().isBlank() ? "Developer" : h.title(),
                            WorkRole.DEVELOPER,
                            model == null ? "claude-sonnet-5-5" : model.model(),
                            model == null ? "medium" : model.effort()));
        } catch (DomainException e) {
            // Handled: logged; the others are hired
            LOG.infof("Mission %s: %s not hired: %s", m, h.name(), e.getMessage());
        }
    }

    /** What the planner is given, and Floor42's own estimate for the director, both from the journal. */
    record Facts(String text, String estimate) {}

    static Facts facts(Company c, MissionId m, int parallel) {
        return StaffingFacts.facts(c, m, parallel);
    }

    /** The planner of the company, for the API to check before asking. */
    static AgentId planner() {
        return StaffProvisioner.PLANNER;
    }
}
