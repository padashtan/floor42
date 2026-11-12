package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.CommitteeEvent.CommitteeAdvanced;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarificationWritten;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarified;
import dev.floor42.domain.event.CommitteeEvent.CommitteeSentBack;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.DeskEvent.QuestionWithdrawn;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.TicketCancelled;
import dev.floor42.domain.event.TicketEvent.TicketCompleted;
import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import java.util.stream.Collectors;

/** The questions a committee puts to the director (its analyst's clarifications, the ruling) and what the director rules. */
final class Arbitration {

    static final String ADOPT = "Adopt";
    static final String REJECT_DECISION = "Reject";
    static final String DESPITE = " despite the critical objection";

    private final CompanyView view;

    Arbitration(CompanyView view) {
        this.view = view;
    }

    /** The analyst wrote these options: none is one of Floor42's, whatever its words. */
    QuestionAsked clarification(Committee c, Committee.Clarification q) {
        List<String> options = q.options() == null
                ? List.of()
                : q.options().stream()
                        .filter(o -> o != null && !o.isBlank())
                        .map(String::strip)
                        .distinct()
                        .toList();
        List<String> offered = options.size() >= 2 ? options : List.of();
        return new QuestionAsked(
                view.nextQuestionId(),
                c.mission(),
                QuestionKind.CLARIFICATION,
                c.analyst,
                q.question().strip(),
                q.context(),
                offered,
                recommendation(q, options),
                Criticality.MEDIUM,
                true,
                c.ticket(),
                null,
                null,
                OptionCode.none(offered));
    }

    private static String recommendation(Committee.Clarification q, List<String> options) {
        if (q.recommendation() == null) return null;
        String wanted = q.recommendation().strip();
        if (options.isEmpty()) return wanted;
        return options.stream()
                .filter(o -> o.equalsIgnoreCase(wanted))
                .findFirst()
                .orElse(null);
    }

    /** The ruling's labels carry a suffix and are read by their prefix: no code of their own yet. */
    void ask(Recorder office, Committee c) {
        boolean standing = !c.standingCriticalObjections().isEmpty();
        String suffix = standing ? DESPITE : "";
        // Implementing is offered only when the mission's workflow produces after its committees (ADR-0050).
        boolean produces = view.workflowOf(c.mission())
                .runnable()
                .map(Workflow::producesAfterCommittees)
                .orElse(false);
        List<String> options = produces
                ? List.of(ADOPT + suffix, implementOption(c.mission()) + suffix, REJECT_DECISION)
                : List.of(ADOPT + suffix, REJECT_DECISION);
        office.emit(new QuestionAsked(
                view.nextQuestionId(),
                c.mission(),
                QuestionKind.COMMITTEE_ARBITRATION,
                null,
                "Committee decision: " + c.topic(),
                context(c, standing),
                options,
                null,
                standing ? Criticality.HIGH : Criticality.MEDIUM,
                true,
                c.ticket(),
                null,
                Phrase.of("committee", "topic", c.topic()),
                OptionCode.none(options)));
    }

    /** The implementing option, saying what Write and Edit will be on the implementation ticket (ImplementOption). */
    private String implementOption(MissionId id) {
        return ImplementOption.label(
                ImplementOption.writesNow(view.mission(id).orElseThrow(), view::defaultPermission));
    }

    private static String context(Committee c, boolean standing) {
        Committee.Contribution record = c.synthesis();
        String objections = c.standingCriticalObjections().stream()
                .map(o -> "- " + o.member() + " (" + c.roleOf(o.member()) + "): " + o.criticalObjection())
                .collect(Collectors.joining("\n"));
        String agreement = c.format().moderated()
                ? c.agreement()
                        .map(a -> "\nThe members agree on " + a + "'s proposal (from their closing lines).")
                        .orElse("\nThe members did not all agree after " + c.debateRoundsBehindRecord()
                                + " debate round(s): read the disagreements in the record.")
                : "";
        return "Recommendation: " + (record.proposal() == null ? "(not stated: read the record)" : record.proposal())
                + agreement
                + (standing
                        ? "\nCritical objections still standing (a majority cannot remove them):\n" + objections
                        : "");
    }

    /** The director sends the decision back with a constraint: one more debate round, then a new synthesis. */
    List<EventEnvelope> sendBack(CommandContext ctx, CommitteeRef ref, String constraint) {
        requireDirector(ctx);
        MissionId id = ref.mission();
        view.missionFor(ctx, id);
        Committee c = existing(ref);
        require(
                c.stage == Committee.Stage.AWAITING_DIRECTOR,
                Refusal.INVALID_TRANSITION,
                "The committee is not waiting for you");
        requireText(constraint, "constraint");
        Recorder r = view.record(ctx, id);
        for (Question q : view.questions(id)) {
            if (q.status() == QuestionStatus.OPEN && q.kind() == QuestionKind.COMMITTEE_ARBITRATION) {
                r.emit(new QuestionWithdrawn(q.id(), RecordedReason.sentBack()));
            }
        }
        return r.emit(new CommitteeSentBack(id, constraint.strip(), ref.number()))
                .events();
    }

    private Committee existing(CommitteeRef ref) {
        Committee c = view.committee(ref).orElse(null);
        require(c != null, Refusal.NO_COMMITTEE, ref.mission() + " has no committee");
        return c;
    }

    /** The director's ruling, while the committee waits for it: adopted (with or without implementing) or rejected. */
    void arbitrated(Recorder r, Committee c, String chosen) {
        if (c.stage != Committee.Stage.AWAITING_DIRECTOR) return;
        boolean adopted = !chosen.equals(REJECT_DECISION);
        boolean implement = ImplementOption.implementing(chosen);
        boolean over = adopted && !c.standingCriticalObjections().isEmpty();
        r.emit(new CommitteeArbitrated(c.mission(), adopted, implement, over, c.number()));
        r.emit(
                adopted
                        ? new TicketCompleted(c.ticket())
                        : new TicketCancelled(c.ticket(), "director rejected the committee's decision"));
    }

    /** Each question blocks: the committee waits for every answer, then debates once more with them. */
    List<EventEnvelope> clarify(
            CommandContext ctx, CommitteeRef ref, String text, List<Committee.Clarification> asked) {
        MissionId id = ref.mission();
        Mission m = view.missionFor(ctx, id);
        Committee c = existing(ref);
        require(m.status == MissionStatus.IN_PROGRESS, Refusal.MISSION_NOT_ACTIVE, id + " is " + m.status);
        require(c.stage == Committee.Stage.CLARIFICATION, Refusal.INVALID_TRANSITION, "No clarification is under way");
        require(
                ctx.actor().is(c.analyst),
                Refusal.FORBIDDEN,
                "The committee's business analyst writes the clarification");
        requireText(text, "text");
        List<Committee.Clarification> questions = asked(asked);
        Recorder r = view.record(ctx, id)
                .emit(new CommitteeClarificationWritten(id, c.analyst, text, questions.size(), c.number()));
        questions.forEach(q -> r.emit(clarification(c, q)));
        Recorder office = r.as(Actor.system());
        if (questions.isEmpty()) {
            office.emit(new CommitteeClarified(id, List.of(), c.number()));
            ask(office, c);
        } else {
            office.emit(new CommitteeAdvanced(id, Committee.Stage.AWAITING_ANSWERS, c.round, c.number()));
        }
        return r.events();
    }

    /** The analyst's questions worth asking, at most five. */
    private static List<Committee.Clarification> asked(List<Committee.Clarification> asked) {
        return asked == null
                ? List.of()
                : asked.stream()
                        .filter(q -> q != null
                                && q.question() != null
                                && !q.question().isBlank())
                        .limit(5)
                        .toList();
    }
}
