package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DEHREN;
import static dev.floor42.domain.Fixture.GEOFFREY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(GoldenFixtureExtension.class)
class TicketWorkflowTest {

    final Fixture f = new Fixture();

    @Test
    void workStartsOnlyOnceThePlanIsApproved() {
        MissionId m = f.mission();
        TicketId t = f.ticket(m, "Analyse");
        f.record(f.company.tickets().assign(f.director(), t, CHRIS));

        assertThatThrownBy(() -> f.company.tickets().start(f.agent(CHRIS), t))
                .extracting("code")
                .isEqualTo("MISSION_NOT_ACTIVE");
    }

    @Test
    void onlyTheAssigneeStartsATicket() {
        MissionId m = f.runningMission();
        TicketId t = f.ticket(m, "Second task");
        f.record(f.company.tickets().assign(f.director(), t, CHRIS));

        assertThatThrownBy(() -> f.company.tickets().start(f.agent(GEOFFREY), t))
                .extracting("code")
                .isEqualTo("NOT_ASSIGNEE");
    }

    @Test
    void aTicketWaitsForItsDependencies() {
        MissionId m = f.mission();
        TicketId first = f.ticket(m, "Analyse");
        TicketId second = f.ticket(m, "Implement", first);
        f.record(f.company.tickets().assign(f.director(), second, CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(GEOFFREY), m));
        f.record(f.company.missions().approvePlan(f.director(), m));

        assertThatThrownBy(() -> f.company.tickets().start(f.agent(CHRIS), second))
                .extracting("code")
                .isEqualTo("DEPENDENCY_OPEN");
    }

    @Test
    void dependenciesMustBelongToTheSameMission() {
        MissionId m1 = f.mission();
        TicketId other = f.ticket(m1, "Elsewhere");
        MissionId m2 = f.mission();

        assertThatThrownBy(() -> f.company
                        .tickets()
                        .create(f.agent(GEOFFREY), m2, "x", "", Priority.LOW, List.of(other), List.of()))
                .extracting("code")
                .isEqualTo("UNKNOWN_DEPENDENCY");
    }

    @Test
    void theProducerCannotReviewItsOwnWork() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "patch"));

        assertThatThrownBy(() -> f.company.tickets().review(f.agent(CHRIS), t, ReviewVerdict.VALIDATED, "looks good"))
                .extracting("code")
                .isEqualTo("SELF_REVIEW");
    }

    @Test
    void theDirectorIsNotAReviewer() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "patch"));

        assertThatThrownBy(() -> f.company.tickets().review(f.director(), t, ReviewVerdict.VALIDATED, "ok"))
                .extracting("code")
                .isEqualTo("REVIEWER_NOT_AGENT");
    }

    @Test
    void aRejectedReviewSendsTheTicketBackToWork() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "patch"));

        f.record(f.company
                .tickets()
                .review(f.agent(DEHREN), t, ReviewVerdict.REJECTED, "audience not checked on refresh"));

        Ticket ticket = f.company.tickets().find(t).orElseThrow();
        assertThat(ticket.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.reviews())
                .singleElement()
                .extracting(Ticket.Review::verdict)
                .isEqualTo(ReviewVerdict.REJECTED);
    }

    @Test
    void anUnverifiableReviewIsEscalatedAndTheDirectorDecides() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "patch"));

        f.record(f.company.tickets().review(f.agent(DEHREN), t, ReviewVerdict.UNVERIFIABLE, "no test environment"));

        Question q = f.company.desk().open().getFirst();
        assertThat(q.kind()).isEqualTo(QuestionKind.REVIEW_ESCALATION);
        assertThat(q.blocking()).isTrue();
        assertThat(q.askedBy()).isNull();
        assertThat(f.company.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.IN_REVIEW);

        f.record(f.company.desk().answer(f.director(), q.id(), "send back to work"));

        assertThat(f.company.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    void aBlockingQuestionHoldsTheTicketItConcerns() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company
                .desk()
                .ask(
                        f.agent(CHRIS),
                        m,
                        "Validate audience at the gateway or in each service?",
                        "Both are possible",
                        List.of("gateway", "services"),
                        "gateway",
                        Criticality.HIGH,
                        true,
                        t));

        assertThatThrownBy(() -> f.company.tickets().submitForReview(f.agent(CHRIS), t, "patch"))
                .extracting("code")
                .isEqualTo("BLOCKED_BY_QUESTION");

        f.record(f.company
                .desk()
                .answer(f.director(), f.company.desk().open().getFirst().id(), "Gateway"));
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "patch"));
        assertThat(f.company.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.IN_REVIEW);
    }

    @Test
    void anAnswerMustBeOneOfTheOfferedOptions() {
        MissionId m = f.runningMission();
        f.record(f.company
                .desk()
                .ask(
                        f.agent(CHRIS),
                        m,
                        "Which gateway?",
                        "",
                        List.of("Kong", "APISIX"),
                        null,
                        Criticality.LOW,
                        false,
                        null));

        assertThatThrownBy(() -> f.company
                        .desk()
                        .answer(f.director(), f.company.desk().open().getFirst().id(), "Envoy"))
                .extracting("code")
                .isEqualTo("INVALID_ANSWER");
    }

    @Test
    void suspendedAgentsTakeNoNewAssignments() {
        MissionId m = f.mission();
        TicketId t = f.ticket(m, "Analyse");
        f.record(f.company.staff().suspendAssignments(f.director(), CHRIS, "overloaded"));

        assertThatThrownBy(() -> f.company.tickets().assign(f.director(), t, CHRIS))
                .extracting("code")
                .isEqualTo("AGENT_SUSPENDED");
    }
}
