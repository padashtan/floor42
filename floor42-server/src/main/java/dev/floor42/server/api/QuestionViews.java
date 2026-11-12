package dev.floor42.server.api;

import dev.floor42.domain.Action;
import dev.floor42.domain.ApprovalScope;
import dev.floor42.domain.Company;
import dev.floor42.domain.Decision;
import dev.floor42.domain.DecisionBasis;
import dev.floor42.domain.ImplementOption;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.PermissionPolicy;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read models of the director's desk: the questions agents and Floor42 ask. */
public final class QuestionViews {

    private QuestionViews() {}

    /** A refused call of the asking agent whose remembered refusal the director may lift while answering. */
    public record AskAgainView(String question, String action, String tool, String summary) {}

    /**
     * @param rounds how many times the director asked back on this question's thread
     * @param possible whether the director can ask it back now (the session may be gone: the route says so)
     * @param text the director's words when this question was asked back; null otherwise
     */
    public record AskBackView(int rounds, boolean possible, String text) {}

    public record QuestionView(
            String id,
            String mission,
            String kind,
            String askedBy,
            String subject,
            String context,
            List<String> options,
            String recommendation,
            String criticality,
            boolean blocking,
            String ticket,
            String action,
            String status,
            String answer,
            String askedAt,
            String phraseCode,
            Map<String, String> phraseParams,
            /**
             * For an agent's open question: its earlier refused calls whose refusal still decides. The director may send
             * one's {@code question} as {@code askAgain} with the answer, to be asked again for that call; empty
             * otherwise.
             */
            List<AskAgainView> askAgain,
            String contextCode,
            Map<String, String> contextParams,
            /**
             * For an open approval question: the rule that makes the call ask, as a phrase ({@code basis.<code>}), read
             * from the mission's table now; null otherwise, and for a question asked because the director edited the
             * file.
             */
            String causeCode,
            Map<String, String> causeParams,
            /** Who recorded that rule ({@link PermissionViews#by}); null without a cause. */
            String causeBy,
            /**
             * For an open ruling that offers to implement: what its implementing option says writing will be (ALLOW,
             * ASK, DENY, MIXED), fixed when it was asked; null otherwise.
             */
            String writesAsked,
            /** What the same tables give now; the screen says so when it differs from {@code writesAsked}. */
            String writesNow,
            /** Asking back, as the screen needs it (ADR-0059). */
            AskBackView askBack) {
        public QuestionView {
            askAgain = askAgain == null ? null : Collections.unmodifiableList(new ArrayList<>(askAgain));
            causeParams = causeParams == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(causeParams));
            contextParams =
                    contextParams == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(contextParams));
            options = options == null ? null : Collections.unmodifiableList(new ArrayList<>(options));
            phraseParams = phraseParams == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(phraseParams));
        }

        static List<QuestionView> all(Company c, MissionId m) {
            return c.desk().of(m).stream().map(q -> of(c, q)).toList();
        }

        /**
         * The context in the screen's language goes by {@code contextCode} and its values: the English sentence Floor42
         * wrote stays in {@code context}, as the journal holds it. Null for any other context (an agent's own words).
         */
        static QuestionView of(Company c, Question q) {
            Read read = Read.of(c, q);
            return new QuestionView(
                    q.id().value(),
                    q.mission().value(),
                    q.kind().name(),
                    q.askedBy() == null ? "floor42" : q.askedBy().value(),
                    q.subject(),
                    q.context(),
                    q.options(),
                    q.recommendation(),
                    q.criticality().name(),
                    q.blocking(),
                    q.ticket() == null ? null : q.ticket().value(),
                    q.action() == null ? null : q.action().value(),
                    q.status().name(),
                    q.answer(),
                    q.askedAt().toString(),
                    q.phrase() == null ? null : q.phrase().code(),
                    q.phrase() == null ? null : q.phrase().params(),
                    askAgain(c, q),
                    read.scope() == null ? null : read.scope().code(),
                    read.scope() == null ? null : read.scope().params(),
                    read.cause() == null ? null : read.cause().phrase().code(),
                    read.cause() == null ? null : read.cause().phrase().params(),
                    read.cause() == null ? null : read.cause().by(),
                    read.writes() == null ? null : read.writes().asked().name(),
                    read.writes() == null ? null : read.writes().now().name(),
                    askBackView(c.desk().askBack(q)));
        }

        /**
         * The rule that decides the asking call now, if it says to ask: while the question is open, that is what keeps
         * it asked. Null once answered (the table may have changed since), or when no rule says to ask.
         */
        private static PermissionRule rule(Company c, Question q) {
            if (q.kind() != QuestionKind.ACTION_APPROVAL || q.status() != QuestionStatus.OPEN || q.action() == null) {
                return null;
            }
            Action a = c.actions().find(q.action()).orElse(null);
            Mission m = c.missions().find(q.mission()).orElse(null);
            if (a == null || m == null) return null;
            PermissionPolicy.Evaluation eval =
                    m.policy().evaluate(a.agent(), a.ticket(), a.category(), a.tool(), a.summary());
            boolean asks = eval.decision() == Decision.ASK || eval.decision() == Decision.ASK_ALWAYS;
            return asks ? eval.rule() : null;
        }

        private static List<AskAgainView> askAgain(Company c, Question q) {
            return c.desk().liftable(q.id()).stream()
                    .flatMap(x -> c.actions().find(x.action()).stream()
                            .map(a -> new AskAgainView(x.id().value(), a.id().value(), a.tool(), a.summary())))
                    .toList();
        }
    }

    /** Why an approval question asks: the rule, as a phrase, and who recorded it. */
    private record Cause(Phrase phrase, String by) {
        /** None for a question asked because the director edited the file: its context says so already. */
        static Cause of(Company c, Question q, Phrase scope) {
            if (scope != null && scope.code().equals(ApprovalScope.CODE_EDITED)) return null;
            PermissionRule rule = QuestionView.rule(c, q);
            if (rule == null) return null;
            Phrase phrase = DecisionBasis.read(DecisionBasis.RULE_PREFIX + rule).orElse(null);
            if (phrase == null) return null;
            return new Cause(
                    phrase, PermissionViews.by(c, c.missions().find(q.mission()).orElseThrow(), rule));
        }
    }

    /** What an open ruling's implementing option said writing would be, and what it would be now. */
    private record Writes(ImplementOption.Writes asked, ImplementOption.Writes now) {
        static Writes of(Company c, Question q) {
            if (q.kind() != QuestionKind.COMMITTEE_ARBITRATION || q.status() != QuestionStatus.OPEN) return null;
            ImplementOption.Writes asked = q.options().stream()
                    .map(ImplementOption::said)
                    .flatMap(java.util.Optional::stream)
                    .findFirst()
                    .orElse(null);
            if (asked == null) return null;
            Mission m = c.missions().find(q.mission()).orElseThrow();
            return new Writes(asked, ImplementOption.writesNow(m, c.preferences()::defaultPermission));
        }
    }

    private static AskBackView askBackView(dev.floor42.domain.DeskFacade.AskBack a) {
        return new AskBackView(a.rounds(), a.possible(), a.text());
    }

    /** What the view reads beyond the question itself: the approval's scope, why it asks, what a ruling says of writes. */
    private record Read(Phrase scope, Cause cause, Writes writes) {
        static Read of(Company c, Question q) {
            Phrase scope = q.kind() == QuestionKind.ACTION_APPROVAL
                    ? ApprovalScope.read(q.context()).orElse(null)
                    : null;
            return new Read(scope, Cause.of(c, q, scope), Writes.of(c, q));
        }
    }
}
