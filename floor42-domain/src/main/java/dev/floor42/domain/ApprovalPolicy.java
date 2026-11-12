package dev.floor42.domain;

import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.DeskEvent.ApprovalForgotten;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * When a tool request goes to the director, and what the answer settles. The director is asked once per approval
 * scope: a remembered answer decides at once, and a request arriving while the same question is open waits for it.
 */
final class ApprovalPolicy {

    private static final List<String> APPROVAL_OPTIONS = List.of("ALLOW", "DENY");

    private final CompanyView view;

    ApprovalPolicy(CompanyView view) {
        this.view = view;
    }

    List<EventEnvelope> askOnce(Recorder office, Mission m, Action a) {
        Optional<Question> remembered = view.questions(m.id()).stream()
                .filter(q -> view.remembered(q.id()))
                .filter(q -> sameScope(view.action(q.action()).orElse(null), a))
                .reduce((first, last) -> last);
        if (remembered.isPresent()) return decided(office, remembered.get(), a);
        boolean alreadyAsked = view.questions(m.id()).stream()
                .filter(q -> q.kind() == QuestionKind.ACTION_APPROVAL && q.status() == QuestionStatus.OPEN)
                .filter(q -> !view.forgotten(q.id()))
                .anyMatch(q -> sameScope(view.action(q.action()).orElse(null), a));
        if (alreadyAsked) return office.events();
        ask(office, m, a, ApprovalScope.forTheMission(a));
        return office.events();
    }

    private static List<EventEnvelope> decided(Recorder office, Question q, Action a) {
        boolean allow = q.answer().equals("ALLOW");
        String basis = DecisionBasis.alreadyAnswered(q.id(), a, allow);
        return allow
                ? office.authorize(a.id(), basis, "question:" + q.id())
                : office.emit(new ActionDenied(a.id(), DenialReason.DIRECTOR, basis))
                        .events();
    }

    /** A question for this call only: its answer is forgotten at once and decides nothing else. */
    List<EventEnvelope> askForThisCall(Recorder office, Mission m, Action a, String coverage) {
        QuestionId id = ask(office, m, a, coverage);
        office.emit(new ApprovalForgotten(id, "asked for this call only"));
        return office.events();
    }

    private QuestionId ask(Recorder office, Mission m, Action a, String coverage) {
        QuestionId id = view.nextQuestionId();
        office.emit(new QuestionAsked(
                id,
                m.id(),
                QuestionKind.ACTION_APPROVAL,
                a.agent(),
                "Allow " + a.agent() + " to use " + a.tool() + "?",
                ApprovalScope.context(a.summary(), coverage),
                APPROVAL_OPTIONS,
                null,
                Criticality.MEDIUM,
                true,
                a.ticket(),
                a.id(),
                Phrase.of("approval", "agent", a.agent().value(), "tool", a.tool()),
                OptionCode.none(APPROVAL_OPTIONS)));
        return id;
    }

    /**
     * The answer settles the asking action and every request in the same scope that waited for it, but never a request
     * that has its own question (setting: ask every time).
     */
    void answered(Recorder r, Question q, String chosen) {
        Action asked = view.action(q.action()).orElse(null);
        QuestionId id = q.id();
        List<Action> waiting = view.actions(q.mission()).stream()
                .filter(a -> a.stage == ActionStage.REQUESTED && sameScope(asked, a))
                .filter(a -> a == asked || !hasOwnOpenQuestion(a))
                .filter(a -> a == asked || !view.forgotten(id))
                .toList();
        Mission m = view.mission(q.mission()).orElseThrow();
        for (Action a : waiting) {
            PermissionPolicy.Evaluation now =
                    m.policy().evaluate(a.agent(), a.ticket(), a.category(), a.tool(), a.summary());
            // The world moved since the question was asked: an answer never authorises what the request would be
            // refused now.
            if (chosen.equals("ALLOW") && m.status != MissionStatus.IN_PROGRESS) {
                r.emit(new ActionDenied(
                        a.id(), DenialReason.MISSION_NOT_ACTIVE, DecisionBasis.missionNotActive(m.status)));
            } else if (chosen.equals("ALLOW") && now.decision() == Decision.DENY) {
                r.emit(new ActionDenied(a.id(), DenialReason.POLICY, now.basis()));
            } else if (chosen.equals("ALLOW")) r.authorize(a.id(), DecisionBasis.directorAllowed(id), "question:" + id);
            else r.emit(new ActionDenied(a.id(), DenialReason.DIRECTOR, DecisionBasis.directorDenied(id)));
        }
    }

    /** Why a lifted refusal no longer decides: the start of {@link ApprovalForgotten#reason}, for the screen. */
    static final String ASKED_AGAIN = "asked again";

    /**
     * The remembered refusals the director may lift while answering this question: an agent's own question, still
     * open, and that agent's refused calls asked before it, whose answer still decides. Empty for any other question.
     */
    List<Question> liftable(Question q) {
        if (q.kind() != QuestionKind.OPEN || q.askedBy() == null || q.status() != QuestionStatus.OPEN) return List.of();
        List<Question> denials = new java.util.ArrayList<>();
        for (Question x : view.questions(q.mission())) {
            if (x.id().equals(q.id())) break;
            if (!view.remembered(x.id()) || !"DENY".equals(x.answer())) continue;
            if (view.action(x.action())
                    .filter(a -> a.agent().equals(q.askedBy()))
                    .isPresent()) denials.add(x);
        }
        return denials;
    }

    /** Refused unless {@code denial} is one of {@link #liftable}: checked before anything is recorded. */
    void checkLift(Question q, QuestionId denial) {
        DomainException.require(
                liftable(q).stream().anyMatch(x -> x.id().equals(denial)),
                Refusal.NOT_REMEMBERED,
                denial + " is not a remembered refusal of " + q.askedBy() + " before " + q.id());
    }

    /**
     * The director chose, with the answer, to be asked again for this one refused call: its refusal no longer decides,
     * the next request in its scope asks the director again. A lift never authorizes anything; free text lifts nothing.
     */
    void lift(Recorder r, Question q, QuestionId denial) {
        r.emit(new ApprovalForgotten(denial, ASKED_AGAIN + " by the director, answering " + q.id()));
    }

    private boolean hasOwnOpenQuestion(Action a) {
        return view.questions().stream().anyMatch(q -> q.status() == QuestionStatus.OPEN && a.id().equals(q.action()));
    }

    /** The same agent and tool; for shell commands the exact command, so approving one never approves every one. */
    private static boolean sameScope(Action answered, Action asked) {
        if (answered == asked) return true;
        return answered.agent().equals(asked.agent())
                && answered.tool().equals(asked.tool())
                && (asked.category() != ActionCategory.EXECUTE
                        // A summary cut at its length cap ("…") hides its end: it is never the same command.
                        || (answered.summary().equals(asked.summary())
                                && !asked.summary().endsWith("…")));
    }
}
