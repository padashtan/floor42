package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Committee;
import dev.floor42.domain.CommitteeRef;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Runs the committee's pending sessions until it needs the director: every member's analysis (each alone), then every
 * member's rebuttal (each reading the others), then the chair's decision record. The business analyst's session comes
 * in between when members raised questions.
 */
@ApplicationScoped
public class CommitteeRunner implements TaskRunner {

    private static final Logger LOG = Logger.getLogger(CommitteeRunner.class);

    private final OfficeService office;
    private final AgentWorktrees worktrees;
    private final SessionGate gate;
    private final RetryDesk retry;

    @Inject
    CommitteeRunner(OfficeService office, AgentWorktrees worktrees, SessionGate gate, RetryDesk retry) {
        this.office = office;
        this.worktrees = worktrees;
        this.gate = gate;
        this.retry = retry;
    }

    /**
     * What the dispatcher needs of a committee, read under the service lock: the committee itself is mutated there (a
     * director's contribution), and iterating its contributions outside would race with it.
     */
    record CommitteeStep(
            AgentId chair, TicketId ticket, boolean deliberating, Committee.Stage stage, List<AgentId> pending) {
        static CommitteeStep of(Committee k) {
            return new CommitteeStep(k.chair(), k.ticket(), k.deliberating(), k.stage(), List.copyOf(k.pending()));
        }
    }

    @Override
    public Work work() {
        return Work.COMMITTEE;
    }

    @Override
    public void run(MissionId m, Task task) {
        // The committee the workflow interpreter found deliberating, carried by the task (ADR-0046).
        CommitteeRef ref = task.committee();
        try {
            Path worktree = open(ref);
            while (true) {
                CommitteeStep now = step(ref);
                if (!now.deliberating() || now.pending().isEmpty()) return;
                AgentId member = now.pending().getFirst();
                boolean goOn = now.stage() == Committee.Stage.CLARIFICATION
                        ? clarify(ref, member, worktree, now.ticket())
                        : sit(ref, member, worktree, kindOf(now.stage()));
                if (!goOn) return;
            }
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            // Handled: logged; the director is asked to retry, the mission waits
            LOG.errorf(e, "Committee of %s failed", m);
            CommitteeStep k = step(ref);
            // With "Retry" open the mission waits: the dispatcher does not start the committee again on its own.
            retry.ask(m, k.ticket(), k.chair(), "Floor42 could not run the committee: " + e.getMessage());
        }
    }

    /** Entities are live objects mutated under the service lock: only values read inside query leave it. */
    private CommitteeStep step(CommitteeRef ref) {
        return office.query(c -> CommitteeStep.of(c.committees().find(ref).orElseThrow()));
    }

    /** The committee's ticket starts with its first session; it meets in the chair's worktree. */
    private Path open(CommitteeRef ref) {
        MissionId m = ref.mission();
        CommitteeStep committee = step(ref);
        AgentId chair = committee.chair();
        if (office.query(c -> c.tickets().find(committee.ticket()).orElseThrow().status()) == TicketStatus.TODO) {
            office.execute(c -> c.tickets().start(office.context(Actor.agent(chair), Origin.REAL), committee.ticket()));
        }
        return worktrees.ready(m, chair);
    }

    private static Committee.Kind kindOf(Committee.Stage stage) {
        return switch (stage) {
            case ANALYSIS -> Committee.Kind.ANALYSIS;
            case DEBATE -> Committee.Kind.REBUTTAL;
            default -> Committee.Kind.SYNTHESIS;
        };
    }

    /** One member's session. False when the committee stops here (held, or the director is asked to retry). */
    private boolean sit(CommitteeRef ref, AgentId member, Path worktree, Committee.Kind kind) {
        MissionId m = ref.mission();
        SessionSpec spec = office.query(c -> {
            Agent a = c.staff().find(member).orElseThrow();
            Mission mission = c.missions().find(m).orElseThrow();
            Committee k = c.committees().find(ref).orElseThrow();
            String brief = kind == Committee.Kind.SYNTHESIS
                    ? Briefs.committeeChair(c, mission, k, a)
                    : Briefs.committeeMember(c, mission, k, a);
            return SessionGate.spec(c, m, k.ticket(), a, worktree, brief, Briefs.offeredTools(mission, k.ticket()));
        });
        Optional<String> report = gate.report(spec);
        if (report.isEmpty()) return false;
        Briefs.Summary s = Briefs.summary(report.get());
        if (seatTaken(ref, member)) {
            LOG.infof("Mission %s: the director spoke in the seat of %s, whose session is set aside", m, member);
            return true;
        }
        contribute(ref, member, kind, report.get(), s);
        return true;
    }

    /** The director may have spoken in person in this seat while its double's session ran: theirs stands. */
    private boolean seatTaken(CommitteeRef ref, AgentId member) {
        return office.query(c -> {
            Committee k = c.committees().find(ref).orElseThrow();
            return member.equals(k.format().directorSeat()) && !k.pending().contains(member);
        });
    }

    private void contribute(CommitteeRef ref, AgentId member, Committee.Kind kind, String text, Briefs.Summary s) {
        boolean synthesis = kind == Committee.Kind.SYNTHESIS;
        office.execute(c -> c.committees()
                .contribute(
                        office.context(Actor.agent(member), Origin.REAL),
                        ref,
                        member,
                        kind,
                        text,
                        synthesis ? s.recommendation() : s.proposal(),
                        s.position(),
                        synthesis ? null : s.criticalObjection(),
                        synthesis ? List.of() : s.questions()));
    }

    /**
     * The business analyst's session: it reads the members' questions and the record, settles what the files answer,
     * and asks the director the rest. False when the session failed (the director is asked whether to retry).
     */
    private boolean clarify(CommitteeRef ref, AgentId analyst, Path worktree, TicketId ticket) {
        MissionId m = ref.mission();
        SessionSpec spec = office.query(c -> {
            Agent a = c.staff().find(analyst).orElseThrow();
            Mission mission = c.missions().find(m).orElseThrow();
            Committee k = c.committees().find(ref).orElseThrow();
            return SessionGate.spec(
                    c, m, ticket, a, worktree, Briefs.analyst(c, mission, k, a), Briefs.offeredTools(mission, ticket));
        });
        Optional<String> report = gate.report(spec);
        report.ifPresent(text -> office.execute(c -> c.committees()
                .clarify(office.context(Actor.agent(analyst), Origin.REAL), ref, text, Briefs.clarifications(text))));
        return report.isPresent();
    }
}
