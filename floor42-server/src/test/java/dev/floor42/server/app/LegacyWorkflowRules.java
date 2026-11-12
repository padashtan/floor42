package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Committee;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketKind;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.CommitteeEvent.CommitteeAdvanced;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarificationOpened;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarified;
import dev.floor42.domain.event.CommitteeEvent.CommitteeConvened;
import dev.floor42.domain.event.CommitteeEvent.CommitteeSentBack;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCompleted;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The workflow decisions as Floor42 took them at c62612d, before the workflow interpreter (ADR-0046), frozen for the
 * equivalence bench. Each method is copied from {@code git show c62612d:<file>}, named in its comment, with only the
 * changes needed to compile today, also named. They run against a frozen copy of what they read that changed since:
 * the committee store of c62612d ({@link FrozenCommittees}: one committee per mission, replaced by each convening,
 * its stage moved by the handlers of the old {@code CommitteeHall}), and the work roles of {@code Staff} at c62612d.
 * Nothing here calls the new committee API ({@code latest}, {@code of}, {@code find(ref)}).
 */
final class LegacyWorkflowRules {

    private LegacyWorkflowRules() {}

    // ---- Staff.java at c62612d, lines 20-32: roleOf and FIRST_STAFF (StaffProvisioner's ids written out) ----------

    private static final Map<AgentId, WorkRole> FIRST_STAFF = Map.of(
            new AgentId("chris"), WorkRole.DEVELOPER,
            new AgentId("dehren"), WorkRole.REVIEWER,
            new AgentId("geoffrey"), WorkRole.PLANNER,
            new AgentId("noe"), WorkRole.RESEARCHER,
            new AgentId("linnea"), WorkRole.ANALYST);

    static WorkRole roleOf(Agent a) {
        return a.workRole() != null ? a.workRole() : FIRST_STAFF.get(a.id());
    }

    // ---- MissionHolds.java at c62612d: waiting and holdsMission (unchanged since, copied to stay frozen) -----------

    static boolean waiting(Company c, MissionId m) {
        return c.desk().of(m).stream().anyMatch(LegacyWorkflowRules::holdsMission);
    }

    static boolean holdsMission(Question q) {
        return q.status() == QuestionStatus.OPEN
                && (q.kind() == QuestionKind.BUDGET_REACHED
                        || q.kind() == QuestionKind.PLAN_LIMIT
                        || q.kind() == QuestionKind.REVIEW_ESCALATION
                        || q.offers(OptionCode.RETRY));
    }

    /** What TaskSelection.next decided first at c62612d: nothing, the committee's session, or the ticket queue. */
    sealed interface Phase {
        record Nothing() implements Phase {}

        record CommitteeSession(TicketId ticket) implements Phase {}

        /** @param leftOut the committee's ticket, never worked as a ticket, or null */
        record Tickets(TicketId leftOut) implements Phase {}
    }

    /**
     * TaskSelection.next at c62612d, lines 48-61, up to the choice between the committee and the ticket queue. Changed:
     * {@code c.committees().find(m)} reads the frozen store; the resting time is not in the journal (null on both
     * sides); the task built for each branch is reduced to the branch.
     */
    static Phase next(Company c, FrozenCommittees committees, MissionId m) {
        if (c.missions().find(m).orElseThrow().status() != MissionStatus.IN_PROGRESS || waiting(c, m))
            return new Phase.Nothing();
        Optional<FrozenCommittee> committee = committees.find(m);
        if (committee.map(FrozenCommittee::deliberating).orElse(false))
            return new Phase.CommitteeSession(committee.get().ticket);
        TicketId committeeTicket = committee.map(k -> k.ticket).orElse(null);
        return new Phase.Tickets(committeeTicket);
    }

    /** TaskSelection.isPlanning at c62612d, lines 228-238; {@code find(t.mission())} reads the frozen store. */
    static boolean isPlanning(Company c, FrozenCommittees committees, Ticket t) {
        return t.assignee() != null
                && c.staff()
                        .find(t.assignee())
                        .map(a -> roleOf(a) == WorkRole.PLANNER)
                        .orElse(false)
                && t.kind() == TicketKind.PLANNING
                && c.missions().find(t.mission()).orElseThrow().ticketValidation()
                && committees.find(t.mission()).isEmpty();
    }

    /**
     * What a ticket of the queue was given at c62612d (TaskSelection reviewTask, resumedTask, readyTask): a review when
     * in review; the planner's or a developer's session when to do or in progress; nothing otherwise.
     */
    static String work(Company c, FrozenCommittees committees, Ticket t) {
        return switch (t.status()) {
            case IN_REVIEW -> "REVIEW";
            case TODO, IN_PROGRESS -> isPlanning(c, committees, t) ? "PLAN" : "PRODUCE";
            case PROPOSED, DONE, CANCELLED -> "NONE";
        };
    }

    /** StallDiagnosis.waitingForOthers at c62612d: the committee waits for the director's ruling. */
    static boolean awaitingRuling(FrozenCommittees committees, MissionId m) {
        Optional<FrozenCommittee> committee = committees.find(m);
        return committee.map(k -> k.stage == Committee.Stage.AWAITING_DIRECTOR).orElse(false);
    }

    /**
     * EventReactions at c62612d: react skips any event not REAL, then on CommitteeArbitrated:
     * {@code if (ruling.adopted() && ruling.implement()) answers.adoptedForImplementation(ruling.mission())}.
     */
    static boolean implementsAfter(EventEnvelope e) {
        if (e.origin() != Origin.REAL) return false;
        return e.event() instanceof CommitteeArbitrated ruling && ruling.adopted() && ruling.implement();
    }

    /**
     * Retrospectives.react at c62612d: a REAL MissionCompleted or MissionCancelled opened the retrospective. Changed: a
     * cancelled mission opens none now (it closes what it holds), so only MissionCompleted does.
     */
    static boolean opensRetro(EventEnvelope e) {
        return e.origin() == Origin.REAL && e.event() instanceof MissionCompleted;
    }

    /**
     * MissionFactory.create at c62612d, lines 62-75; changed: each branch returns its name instead of creating
     * ("decision:<panel>", "planned", "delivery"), CommitteeRoles' constants written out.
     */
    static String create(String repository, List<String> committee, boolean plan, String panel) {
        String kind = panel == null || panel.isBlank() ? "architecture" : panel;
        if (kind.equals("idea") && (committee == null || committee.isEmpty())) {
            throw new DomainException("INVALID_ARGUMENT", "An idea committee needs its members");
        }
        if (committee != null && !committee.isEmpty()) {
            return "decision:" + kind;
        }
        if (repository == null || repository.isBlank()) {
            throw new DomainException("INVALID_ARGUMENT", "A mission works on a repository");
        }
        return plan ? "planned" : "delivery";
    }

    /** The part of Committee at c62612d these rules read: its ticket and its stage, as its handlers moved them. */
    static final class FrozenCommittee {
        final TicketId ticket;
        Committee.Stage stage = Committee.Stage.ANALYSIS;
        int round = 1;
        int clarifiedUpTo;
        int clarifications;
        final List<String> constraints = new ArrayList<>();

        FrozenCommittee(TicketId ticket) {
            this.ticket = ticket;
        }

        /** Committee.deliberating at c62612d. */
        boolean deliberating() {
            return stage == Committee.Stage.ANALYSIS
                    || stage == Committee.Stage.DEBATE
                    || stage == Committee.Stage.SYNTHESIS
                    || stage == Committee.Stage.CLARIFICATION;
        }
    }

    /**
     * CommitteeHall's store at c62612d: {@code Map<MissionId, Committee>}, with the stage handlers of its {@code on}
     * methods (lines 284-369), the committee's number ignored as it did not exist. Contributions, notes and questions
     * move no stage and are left out.
     */
    static final class FrozenCommittees {
        private final Map<MissionId, FrozenCommittee> committees = new LinkedHashMap<>();

        Optional<FrozenCommittee> find(MissionId mission) {
            return Optional.ofNullable(committees.get(mission));
        }

        void apply(EventEnvelope envelope) {
            switch (envelope.event()) {
                case CommitteeConvened e -> committees.put(e.mission(), new FrozenCommittee(e.ticket()));
                case CommitteeAdvanced e -> {
                    committees.get(e.mission()).stage = e.stage();
                    committees.get(e.mission()).round = e.round();
                }
                case CommitteeClarificationOpened e ->
                    committees.get(e.mission()).stage = Committee.Stage.CLARIFICATION;
                case CommitteeClarified e -> clarified(committees.get(e.mission()), e);
                case CommitteeSentBack e -> {
                    FrozenCommittee c = committees.get(e.mission());
                    c.constraints.add(e.constraint());
                    c.stage = Committee.Stage.DEBATE;
                    c.round = c.round + 1;
                }
                case CommitteeArbitrated e ->
                    committees.get(e.mission()).stage =
                            e.adopted() ? Committee.Stage.ADOPTED : Committee.Stage.REJECTED;
                default -> {}
            }
        }

        private static void clarified(FrozenCommittee c, CommitteeClarified e) {
            c.clarifiedUpTo = c.round;
            c.clarifications++;
            if (e.answers().isEmpty()) {
                c.stage = Committee.Stage.AWAITING_DIRECTOR;
            } else {
                c.constraints.addAll(e.answers());
                c.stage = Committee.Stage.DEBATE;
                c.round = c.round + 1;
            }
        }
    }
}
