package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.StepAt;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.domain.workflow.WorkflowInterpreter.TicketStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The director's checks on a ticket (ADR-0052, increment 3): the review, then each check in order, the completion after
 * the last advancing verdict only, a stale verdict refused, the rejection cap, and the director's escalation answers at
 * each position. No new event: the position is derived from the submissions and the verdicts.
 */
@ExtendWith(GoldenFixtureExtension.class)
class WorkflowChecksTest {

    static final Step.Check SECURITY =
            new Step.Check("security-check", "Security check", "Look for secrets", "Contract", "Findings", true);
    static final Step.Check WORDING =
            new Step.Check("wording-pass", "Wording pass", "Read the texts", "Clear texts", "Passages", false);
    static final Workflow CHECKED = new Workflow(
            "Checked",
            List.of(new Step.Approval(), new Step.Produce(), new Step.Review(), SECURITY, WORDING, new Step.Accept()));

    final Fixture f = new Fixture(Origin.REAL);
    final MissionInterpreter interpreter = new MissionInterpreter();
    TicketId t;

    /** A REAL mission pinned to {@code w}, its one ticket started by Chris and submitted. */
    void submittedUnder(Workflow w) {
        List<EventEnvelope> created =
                f.record(f.company.missions().create(f.director(), "Checks", "Check it", "C:/repo", w));
        MissionId m = ((MissionCreated) created.getFirst().event()).mission();
        t = f.ticket(m, "Work");
        f.record(f.company.tickets().assign(f.director(), t, Fixture.CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(Fixture.GEOFFREY), m));
        f.record(f.company.verification().setBudget(f.director(), m, null));
        f.record(f.company.missions().approvePlan(f.director(), m));
        f.record(f.company.tickets().start(f.agent(Fixture.CHRIS), t));
        submit();
    }

    void submit() {
        f.record(f.company.tickets().submitForReview(f.agent(Fixture.CHRIS), t, "patch"));
    }

    Ticket ticket() {
        return f.company.tickets().find(t).orElseThrow();
    }

    StepAt at() {
        return interpreter.progress(f.company, ticket()).orElseThrow().at();
    }

    TicketStep step() {
        return interpreter.step(f.company, ticket(), WorkRole.DEVELOPER);
    }

    List<String> verdict(ReviewVerdict v) {
        return verdict(v, at());
    }

    List<String> verdict(ReviewVerdict v, StepAt at) {
        return f.record(f.company.tickets().review(f.agent(Fixture.DEHREN), t, v, "findings", List.of(), at)).stream()
                .map(e -> e.event().getClass().getSimpleName())
                // The review's sorted items follow every verdict given with claims (ADR-0053): not what is tested.
                .filter(name -> !name.equals("ReviewItemsRecorded"))
                .toList();
    }

    Question openQuestion() {
        return f.company.desk().open().stream()
                .filter(q -> t.equals(q.ticket()))
                .findFirst()
                .orElseThrow();
    }

    void answer(OptionCode code) {
        f.record(f.company.desk().answer(f.director(), openQuestion().id(), code.label()));
    }

    @Test
    void theReviewThenEachCheckRunsAndOnlyTheLastAdvancingVerdictCompletesTheTicket() {
        submittedUnder(CHECKED);
        assertThat(step()).isEqualTo(TicketStep.REVIEW);
        assertThat(at()).isEqualTo(new StepAt(1, 0));

        assertThat(verdict(ReviewVerdict.VALIDATED)).containsExactly("TicketReviewed");
        assertThat(ticket().status()).isEqualTo(TicketStatus.IN_REVIEW);
        assertThat(step()).isEqualTo(new TicketStep.Check(SECURITY, new StepAt(1, 1)));
        assertThat(((TicketStep.Check) step()).key()).isEqualTo("security-check");

        assertThat(verdict(ReviewVerdict.VALIDATED_WITH_RESERVATIONS)).containsExactly("TicketReviewed");
        assertThat(step()).isEqualTo(new TicketStep.Check(WORDING, new StepAt(1, 2)));

        // The last step is advisory: a rejection is recorded, shown at acceptance, and the ticket is done.
        assertThat(verdict(ReviewVerdict.REJECTED)).containsExactly("TicketReviewed", "TicketCompleted");
        assertThat(ticket().status()).isEqualTo(TicketStatus.DONE);
        assertThat(step()).isEqualTo(TicketStep.NONE);
    }

    @Test
    void aBlockingCheckThatRejectsReopensTheTicketAndTheNextSubmissionStartsAtTheReview() {
        submittedUnder(CHECKED);
        verdict(ReviewVerdict.VALIDATED);
        assertThat(verdict(ReviewVerdict.REJECTED)).containsExactly("TicketReviewed", "TicketReopened");
        assertThat(ticket().status()).isEqualTo(TicketStatus.IN_PROGRESS);
        submit();
        assertThat(at()).isEqualTo(new StepAt(2, 0));
        assertThat(step()).isEqualTo(TicketStep.REVIEW);
    }

    @Test
    void aStaleVerdictIsRefusedAndNothingReachesTheJournal() {
        submittedUnder(CHECKED);
        int before = f.journal.size();
        assertThat(code(() -> verdict(ReviewVerdict.VALIDATED, new StepAt(1, 1))))
                .isEqualTo("STEP_MOVED_ON");
        assertThat(code(() -> verdict(ReviewVerdict.VALIDATED, null))).isEqualTo("STEP_MOVED_ON");
        assertThat(f.journal).hasSize(before);

        // Two sessions sent to the same position: the first is recorded, the second has moved on.
        StepAt review = at();
        verdict(ReviewVerdict.VALIDATED, review);
        assertThat(code(() -> verdict(ReviewVerdict.REJECTED, review))).isEqualTo("STEP_MOVED_ON");

        // Reopened and submitted again while a check ran: its verdict belongs to a submission that is gone.
        StepAt check = at();
        f.record(f.company
                .tickets()
                .review(f.agent(Fixture.GEOFFREY), t, ReviewVerdict.REJECTED, "no", List.of(), check));
        submit();
        assertThat(code(() -> verdict(ReviewVerdict.VALIDATED, check))).isEqualTo("STEP_MOVED_ON");
        assertThat(at()).isEqualTo(new StepAt(2, 0));
    }

    @Test
    void aBlockingCheckThatRejectsThreeTimesAsksTheDirectorInsteadOfReopeningAgain() {
        submittedUnder(CHECKED);
        for (int i = 1; i <= 2; i++) {
            verdict(ReviewVerdict.VALIDATED);
            assertThat(verdict(ReviewVerdict.REJECTED)).containsExactly("TicketReviewed", "TicketReopened");
            submit();
        }
        verdict(ReviewVerdict.VALIDATED);
        assertThat(verdict(ReviewVerdict.REJECTED)).containsExactly("TicketReviewed", "QuestionAsked");
        Question q = openQuestion();
        assertThat(q.blocking()).isTrue();
        assertThat(q.kind()).isEqualTo(QuestionKind.REVIEW_ESCALATION);
        assertThat(q.phrase().code()).isEqualTo("checkRejectedTooOften");
        assertThat(q.phrase().params()).containsEntry("times", "3").containsEntry("check", "Security check");
        assertThat(ticket().status()).isEqualTo(TicketStatus.IN_REVIEW);

        // Sent back: reopened, and the next rejection by the same check asks again.
        answer(OptionCode.SEND_BACK);
        assertThat(ticket().status()).isEqualTo(TicketStatus.IN_PROGRESS);
        submit();
        verdict(ReviewVerdict.VALIDATED);
        assertThat(verdict(ReviewVerdict.REJECTED)).containsExactly("TicketReviewed", "QuestionAsked");
        assertThat(openQuestion().phrase().params()).containsEntry("times", "4");

        // Reviewed again: the same check runs again, at the same position.
        StepAt held = at();
        answer(OptionCode.REVIEW_AGAIN);
        assertThat(step()).isEqualTo(new TicketStep.Check(SECURITY, held));

        // Accepted as is: done, the advisory check after it included.
        verdict(ReviewVerdict.REJECTED);
        answer(OptionCode.ACCEPT_AS_IS);
        assertThat(ticket().status()).isEqualTo(TicketStatus.DONE);
    }

    @Test
    void anUnverifiableVerdictAnsweredReviewAgainLeavesTheSameStepToRunAgainAtEachPosition() {
        submittedUnder(CHECKED);
        assertThat(verdict(ReviewVerdict.UNVERIFIABLE)).containsExactly("TicketReviewed", "QuestionAsked");
        answer(OptionCode.REVIEW_AGAIN);
        assertThat(step()).isEqualTo(TicketStep.REVIEW);
        assertThat(at()).isEqualTo(new StepAt(1, 0));

        verdict(ReviewVerdict.VALIDATED);
        assertThat(verdict(ReviewVerdict.UNVERIFIABLE)).containsExactly("TicketReviewed", "QuestionAsked");
        answer(OptionCode.REVIEW_AGAIN);
        assertThat(step()).isEqualTo(new TicketStep.Check(SECURITY, new StepAt(1, 1)));

        // An advisory check never escalates: its unverifiable verdict is recorded and the ticket goes on.
        verdict(ReviewVerdict.VALIDATED);
        assertThat(verdict(ReviewVerdict.UNVERIFIABLE)).containsExactly("TicketReviewed", "TicketCompleted");
    }

    /**
     * Each answer to an escalated verdict, at the review and at a blocking check's position (ADR-0052): "review again"
     * runs the same step again at the same position, "send back" reopens the ticket and the next submission starts at
     * the review, "accept as is" completes the ticket, the later checks included.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "0, REVIEW_AGAIN",
        "0, SEND_BACK",
        "0, ACCEPT_AS_IS",
        "1, REVIEW_AGAIN",
        "1, SEND_BACK",
        "1, ACCEPT_AS_IS"
    })
    void eachEscalationAnswerAtEachPosition(int position, OptionCode answer) {
        submittedUnder(CHECKED);
        for (int i = 0; i < position; i++) verdict(ReviewVerdict.VALIDATED);
        StepAt held = at();
        TicketStep before = step();
        assertThat(held).isEqualTo(new StepAt(1, position));
        assertThat(verdict(ReviewVerdict.UNVERIFIABLE)).containsExactly("TicketReviewed", "QuestionAsked");
        // While the director is asked, the position holds and no other verdict is recorded there.
        assertThat(code(() -> verdict(ReviewVerdict.VALIDATED, held))).isEqualTo("STEP_MOVED_ON");
        answer(answer);
        switch (answer) {
            case REVIEW_AGAIN -> {
                assertThat(ticket().status()).isEqualTo(TicketStatus.IN_REVIEW);
                assertThat(at()).isEqualTo(held);
                assertThat(step()).isEqualTo(before);
            }
            case SEND_BACK -> {
                assertThat(ticket().status()).isEqualTo(TicketStatus.IN_PROGRESS);
                submit();
                assertThat(at()).isEqualTo(new StepAt(2, 0));
                assertThat(step()).isEqualTo(TicketStep.REVIEW);
            }
            default -> {
                assertThat(ticket().status()).isEqualTo(TicketStatus.DONE);
                assertThat(step()).isEqualTo(TicketStep.NONE);
            }
        }
    }

    /**
     * A check's verdict is calibrated against what the check hands over, its one criterion C1 (ADR-0052): a reservation
     * citing C1 is kept with that text, one citing the ticket's own criteria is a note. The review keeps the ticket's.
     */
    @Test
    void aChecksReservationsAreSortedAgainstWhatItHandsOver() {
        submittedUnder(CHECKED);
        List<ReviewClaim> claims = List.of(
                ReviewClaim.reservation("C1", "the findings name no file", "name each file"),
                ReviewClaim.reservation("C2", "the code is slow", "cache it"));
        f.record(f.company.tickets().review(f.agent(Fixture.DEHREN), t, ReviewVerdict.VALIDATED, "ok", claims, at()));
        dev.floor42.domain.event.TicketEvent.ReviewItemsRecorded items = itemsOf(f.record(f.company
                .tickets()
                .review(
                        f.agent(Fixture.DEHREN),
                        t,
                        ReviewVerdict.VALIDATED_WITH_RESERVATIONS,
                        "findings",
                        claims,
                        at())));
        assertThat(items.reservations())
                .extracting(ReviewReservation::criterion, ReviewReservation::criterionText)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, SECURITY.handsOver()));
        assertThat(items.notes()).hasSize(1);
    }

    private static dev.floor42.domain.event.TicketEvent.ReviewItemsRecorded itemsOf(List<EventEnvelope> events) {
        return events.stream()
                .map(EventEnvelope::event)
                .filter(e -> e instanceof dev.floor42.domain.event.TicketEvent.ReviewItemsRecorded)
                .map(e -> (dev.floor42.domain.event.TicketEvent.ReviewItemsRecorded) e)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aMissionWithoutChecksKeepsTodaysReviewWithoutAPosition() {
        submittedUnder(Workflow.BUILT_IN);
        assertThat(interpreter.progress(f.company, ticket())).isEmpty();
        assertThat(f.record(f.company.tickets().review(f.agent(Fixture.DEHREN), t, ReviewVerdict.VALIDATED, "ok")))
                .extracting(e -> e.event().getClass().getSimpleName())
                .containsExactly("TicketReviewed", "TicketCompleted");
    }

    /** The position survives a replay: it is derived from the journal, never written. */
    @Test
    void aReplayRebuildsTheSubmissionAndThePosition() {
        submittedUnder(CHECKED);
        verdict(ReviewVerdict.VALIDATED);
        verdict(ReviewVerdict.REJECTED);
        submit();
        verdict(ReviewVerdict.VALIDATED);
        Company again = Company.replay(new ArrayList<>(f.journal));
        Ticket replayed = again.tickets().find(t).orElseThrow();
        assertThat(interpreter.progress(again, replayed).orElseThrow().at()).isEqualTo(new StepAt(2, 1));
        assertThat(again.tickets().reviewRounds(t)).isEqualTo(new ReviewRounds(2, List.of(1, 1, 2)));
    }

    /**
     * Random sequences of verdicts, reopenings and escalation answers against a reference walk written from the ADR's
     * rules: after every command, the ticket's status and position are the reference's.
     */
    @Test
    void randomSequencesFollowTheReferenceWalk() {
        Random random = new Random(52);
        for (int run = 0; run < 25; run++) {
            submittedUnder(CHECKED);
            Reference ref = new Reference(CHECKED);
            ref.submit();
            for (int i = 0; i < 30 && ticket().status() != TicketStatus.DONE; i++) {
                if (ticket().status() == TicketStatus.IN_PROGRESS) {
                    submit();
                    ref.submit();
                } else if (ref.asked) {
                    OptionCode a = List.of(OptionCode.ACCEPT_AS_IS, OptionCode.SEND_BACK, OptionCode.REVIEW_AGAIN)
                            .get(random.nextInt(3));
                    answer(a);
                    ref.answer(a);
                } else {
                    ReviewVerdict v = ReviewVerdict.values()[random.nextInt(ReviewVerdict.values().length)];
                    verdict(v);
                    ref.verdict(v);
                }
                assertThat(ticket().status()).as("run %d step %d", run, i).isEqualTo(ref.status);
                if (ref.status == TicketStatus.IN_REVIEW) {
                    assertThat(at()).as("run %d step %d", run, i).isEqualTo(new StepAt(ref.submission, ref.position));
                }
            }
        }
    }

    /** The ADR's rules, walked by hand. */
    static final class Reference {
        final Workflow w;
        TicketStatus status = TicketStatus.IN_PROGRESS;
        int submission;
        int position;
        boolean asked;
        final Map<Integer, Integer> rejections = new HashMap<>();

        Reference(Workflow w) {
            this.w = w;
        }

        void submit() {
            submission++;
            position = 0;
            status = TicketStatus.IN_REVIEW;
        }

        void verdict(ReviewVerdict v) {
            boolean validates = v == ReviewVerdict.VALIDATED || v == ReviewVerdict.VALIDATED_WITH_RESERVATIONS;
            boolean advisory = position > 0 && !w.checks().get(position - 1).blocks();
            if (validates || advisory) {
                position++;
                if (position > w.checks().size()) status = TicketStatus.DONE;
            } else if (v == ReviewVerdict.REJECTED) {
                if (position > 0 && rejections.merge(position, 1, Integer::sum) >= 3) asked = true;
                else status = TicketStatus.IN_PROGRESS;
            } else {
                asked = true;
            }
        }

        void answer(OptionCode a) {
            asked = false;
            if (a == OptionCode.ACCEPT_AS_IS) status = TicketStatus.DONE;
            if (a == OptionCode.SEND_BACK) status = TicketStatus.IN_PROGRESS;
        }
    }

    private static String code(Runnable r) {
        try {
            r.run();
            return "recorded";
        } catch (DomainException e) {
            // Handled: the refusal's code is what the test reads.
            return e.code();
        }
    }
}
