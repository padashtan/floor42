package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.GEOFFREY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The planner proposes, the director validates ticket by ticket, and only approved tickets can start (ADR-0017). */
@ExtendWith(GoldenFixtureExtension.class)
class TicketValidationTest {

    final Fixture f = new Fixture();
    final MissionId m = f.mission();
    final TicketId planning = f.ticket(m, "Plan: secure the APIs");

    {
        f.record(f.company.tickets().assign(f.director(), planning, GEOFFREY));
        f.record(f.company.missions().enableTicketValidation(f.director(), m));
        f.record(f.company.missions().submitPlan(f.agent(GEOFFREY), m));
        f.record(f.company.missions().approvePlan(f.director(), m));
        f.record(f.company.tickets().start(f.agent(GEOFFREY), planning));
    }

    List<Ticket> proposed() {
        return f.company.tickets().of(m).stream()
                .filter(t -> t.status() == TicketStatus.PROPOSED)
                .toList();
    }

    void plan() {
        f.record(f.company
                .tickets()
                .propose(
                        f.agent(GEOFFREY),
                        planning,
                        CHRIS,
                        List.of(
                                new ProposedTicket("Pin the compiler plugin", "why", List.of("compiles"), List.of()),
                                new ProposedTicket("Add the wrapper", "why", List.of("mvnw works"), List.of(0)),
                                new ProposedTicket("Enforcer rule", "why", List.of(), List.of()))));
    }

    @Test
    void thePlannersTicketsArriveProposedAndThePlanningTicketIsDone() {
        plan();

        assertThat(proposed())
                .extracting(Ticket::title)
                .containsExactly("Pin the compiler plugin", "Add the wrapper", "Enforcer rule");
        assertThat(proposed()).allSatisfy(t -> assertThat(t.assignee()).isEqualTo(CHRIS));
        assertThat(proposed().get(1).dependsOn())
                .containsExactly(proposed().getFirst().id());
        assertThat(f.company.tickets().find(planning).orElseThrow().status()).isEqualTo(TicketStatus.DONE);
        assertThatThrownBy(() -> f.company
                        .tickets()
                        .start(f.agent(CHRIS), proposed().getFirst().id()))
                .as("nothing proposed runs")
                .isInstanceOf(DomainException.class);
    }

    @Test
    void theDirectorApprovesASelectionEditsAndDrops() {
        plan();
        List<Ticket> p = proposed();

        f.record(f.company
                .tickets()
                .edit(
                        f.director(),
                        p.get(1).id(),
                        "Add the Maven Wrapper 3.9",
                        "checksum pinned",
                        List.of(" mvnw -v ", "")));
        f.record(f.company
                .tickets()
                .approve(f.director(), m, List.of(p.get(0).id(), p.get(1).id())));
        f.record(f.company.tickets().cancel(f.director(), p.get(2).id(), "not now"));

        assertThat(f.company.tickets().find(p.get(0).id()).orElseThrow().status())
                .isEqualTo(TicketStatus.TODO);
        Ticket edited = f.company.tickets().find(p.get(1).id()).orElseThrow();
        assertThat(edited.title()).isEqualTo("Add the Maven Wrapper 3.9");
        assertThat(edited.acceptanceCriteria()).containsExactly("mvnw -v");
        assertThat(f.company.tickets().find(p.get(2).id()).orElseThrow().status())
                .isEqualTo(TicketStatus.CANCELLED);
        f.record(f.company.tickets().start(f.agent(CHRIS), p.get(0).id()));
        assertThatThrownBy(() -> f.company
                        .tickets()
                        .approve(f.director(), m, List.of(p.get(0).id())))
                .hasMessageContaining("not proposed");
        assertThatThrownBy(() -> f.company.tickets().edit(f.director(), p.get(0).id(), "late", "", List.of()))
                .hasMessageContaining("before work starts");
    }

    @Test
    void ticketsCreatedLaterByOfficeAreProposedButTheDirectorsAreApproved() {
        TicketId byOffice = ((dev.floor42.domain.event.TicketEvent.TicketCreated) f.record(f.company
                                .tickets()
                                .create(
                                        f.system(),
                                        m,
                                        "Implement the decision",
                                        "",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of()))
                        .getFirst()
                        .event())
                .ticket();
        TicketId byDirector = ((dev.floor42.domain.event.TicketEvent.TicketCreated) f.record(f.company
                                .tickets()
                                .create(f.director(), m, "My own ticket", "", Priority.NORMAL, List.of(), List.of()))
                        .getFirst()
                        .event())
                .ticket();

        assertThat(f.company.tickets().find(byOffice).orElseThrow().status()).isEqualTo(TicketStatus.PROPOSED);
        assertThat(f.company.tickets().find(byDirector).orElseThrow().status()).isEqualTo(TicketStatus.TODO);
        assertThat(Company.replay(f.journal)
                        .tickets()
                        .find(byOffice)
                        .orElseThrow()
                        .status())
                .isEqualTo(TicketStatus.PROPOSED);
    }

    @Test
    void onlyThePlannerHandsInAPlanAndOnlyTheDirectorApproves() {
        assertThatThrownBy(() -> f.company
                        .tickets()
                        .propose(
                                f.agent(CHRIS),
                                planning,
                                CHRIS,
                                List.of(new ProposedTicket("x", "", List.of(), List.of()))))
                .hasMessageContaining("Only the planner");
        assertThatThrownBy(() -> f.company
                        .tickets()
                        .propose(
                                f.agent(GEOFFREY),
                                planning,
                                CHRIS,
                                List.of(new ProposedTicket("x", "", List.of(), List.of(0)))))
                .hasMessageContaining("depends on a later one");
        plan();
        assertThatThrownBy(() -> f.company
                        .tickets()
                        .approve(
                                f.agent(GEOFFREY),
                                m,
                                List.of(proposed().getFirst().id())))
                .isInstanceOf(DomainException.class);
    }

    /** What the company shows after a refused command must equal a replay of what was journaled. */
    void assertSameAsReplay() {
        Company replayed = Company.replay(f.journal);
        assertThat(f.company.version()).isEqualTo(replayed.version());
        assertThat(f.company.tickets().of(m).stream()
                        .map(t -> t.id() + ":" + t.status())
                        .toList())
                .isEqualTo(replayed.tickets().of(m).stream()
                        .map(t -> t.id() + ":" + t.status())
                        .toList());
    }

    @Test
    void aRefusedApprovalBatchChangesNothing() {
        plan();
        List<Ticket> p = proposed();
        f.record(f.company.tickets().cancel(f.director(), p.get(2).id(), "not now"));

        assertThatThrownBy(() -> f.company
                        .tickets()
                        .approve(
                                f.director(),
                                m,
                                List.of(p.get(0).id(), p.get(1).id(), p.get(2).id())))
                .hasMessageContaining("not proposed");

        assertThat(f.company.tickets().find(p.get(0).id()).orElseThrow().status())
                .isEqualTo(TicketStatus.PROPOSED);
        assertSameAsReplay();
    }

    @Test
    void aRefusedPlanChangesNothing() {
        assertThatThrownBy(() -> f.company
                        .tickets()
                        .propose(
                                f.agent(GEOFFREY),
                                planning,
                                CHRIS,
                                List.of(
                                        new ProposedTicket("Fine", "", List.of(), List.of()),
                                        new ProposedTicket(" ", "", List.of(), List.of()))))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> f.company
                        .tickets()
                        .propose(
                                f.agent(GEOFFREY),
                                planning,
                                CHRIS,
                                List.of(
                                        new ProposedTicket("Fine", "", List.of(), List.of()),
                                        new ProposedTicket("Later", "", List.of(), List.of(1)))))
                .hasMessageContaining("depends on a later one");

        assertThat(proposed()).isEmpty();
        assertSameAsReplay();
    }

    @Test
    void aTicketSelectedTwiceIsApprovedOnce() {
        plan();
        TicketId first = proposed().getFirst().id();

        assertThat(f.record(f.company.tickets().approve(f.director(), m, List.of(first, first))))
                .hasSize(1);
    }

    @Test
    void missionsWithoutValidationAreUnchanged() {
        MissionId other = f.mission();
        TicketId t = f.ticket(other, "Deliver");

        assertThat(f.company.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.TODO);
    }
}
