package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The director's answer 3 of ADR-0052: the findings of an advisory check reach the producer, in the brief of its later
 * tickets of the mission, as data. A blocking check's verdict is not quoted there (it reopened the ticket, whose own
 * brief carries the review), and a mission without an advisory check has no such section.
 */
class AdvisoryFindingsTest {

    static final AgentId GALEN = new AgentId("galen");
    static final AgentId CHRIS = new AgentId("chris");
    static final AgentId DARIUS = new AgentId("darius");
    static final Step.Check WORDING =
            new Step.Check("wording", "Wording pass", "Read the texts", "Clear texts", "Passages", false);
    static final Step.Check SECURITY =
            new Step.Check("security", "Security check", "Look", "Contract", "Findings", true);

    final OfficeServiceTest.MemoryJournal journal = new OfficeServiceTest.MemoryJournal();
    final OfficeService office =
            new OfficeService(journal, Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC));

    CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    CommandContext agent(AgentId a) {
        return office.context(Actor.agent(a), Origin.REAL);
    }

    TicketId ticket(MissionId m, String title) {
        return ((TicketCreated) office.execute(c -> c.tickets()
                                .create(agent(GALEN), m, title, "", Priority.NORMAL, List.of(), List.of("done")))
                        .getFirst()
                        .event())
                .ticket();
    }

    String briefOfSecondTicket(Step.Check first, Step.Check second) {
        for (AgentId a : List.of(GALEN, CHRIS, DARIUS)) {
            office.execute(c -> c.staff()
                    .hire(director(), a, a.value(), "role", "anthropic", "claude-sonnet-5-5", "medium", List.of()));
        }
        Workflow w = new Workflow(
                "Advisory",
                List.of(new Step.Approval(), new Step.Produce(), new Step.Review(), first, second, new Step.Accept()));
        MissionId m = ((MissionCreated) office.execute(c -> c.missions().create(director(), "Brief", "o", "C:/repo", w))
                        .getFirst()
                        .event())
                .mission();
        TicketId one = ticket(m, "First");
        TicketId two = ticket(m, "Second");
        office.execute(c -> c.tickets().assign(director(), one, CHRIS));
        office.execute(c -> c.tickets().assign(director(), two, CHRIS));
        office.execute(c -> c.missions().submitPlan(agent(GALEN), m));
        office.execute(c -> c.verification().setBudget(director(), m, null));
        office.execute(c -> c.missions().approvePlan(director(), m));
        office.execute(c -> c.tickets().start(agent(CHRIS), one));
        office.execute(c -> c.tickets().submitForReview(agent(CHRIS), one, "patch"));
        // The review, then each check in turn; the advisory one reserves, the blocking one validates.
        var progress = new dev.floor42.domain.workflow.MissionInterpreter();
        for (int i = 0; i < 3; i++) {
            var at =
                    office.query(c -> progress.progress(c, c.tickets().find(one).orElseThrow())
                            .orElseThrow()
                            .at());
            ReviewVerdict v = i == 1 ? ReviewVerdict.VALIDATED_WITH_RESERVATIONS : ReviewVerdict.VALIDATED;
            String findings = i == 1 ? "The title of section 2 is vague" : "ok";
            office.execute(c -> c.tickets().review(agent(DARIUS), one, v, findings, List.of(), at));
        }
        return office.query(c -> ProducerBrief.of(
                c,
                c.missions().find(m).orElseThrow(),
                c.tickets().find(two).orElseThrow(),
                c.staff().find(CHRIS).orElseThrow()));
    }

    @Test
    void anAdvisoryCheckFindingsReachTheProducersLaterTicketAsData() {
        // Review (position 0), the advisory wording pass (1, reserves), the blocking security check (2, validates).
        String brief = briefOfSecondTicket(WORDING, SECURITY);
        assertThat(brief)
                .contains("Findings of the director's advisory checks on your earlier tickets")
                .contains("Wording pass by darius (VALIDATED_WITH_RESERVATIONS): The title of section 2 is vague");
        assertThat(brief).doesNotContain("Security check by darius");
    }

    @Test
    void aMissionWithoutAnAdvisoryCheckHasNoSuchSection() {
        // Two blocking checks: the second verdict (position 1) is a blocking check's, never quoted.
        String brief =
                briefOfSecondTicket(SECURITY, new Step.Check("legal", "Legal", "Look", "Contract", "Notes", true));
        assertThat(brief).doesNotContain("advisory checks");
    }
}
