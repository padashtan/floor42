package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.golden.GoldenFile;
import dev.floor42.domain.golden.WorkflowDecisions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Missions as the daemon runs them, command by command in its own order (MissionFactory, the runners, the director's
 * desk): what the workflow interpreter decides at each event is the former rules' decision (ADR-0046) and is kept as a
 * golden master. The scripted day covers committees; these cover the planner and the review loop.
 */
class WorkflowScenariosTest {

    private static final AgentId PLANNER = new AgentId("galen");
    private static final AgentId DEVELOPER = new AgentId("chris");
    private static final AgentId REVIEWER = new AgentId("darius");

    private final Company company = new Company();
    private final List<EventEnvelope> journal = new ArrayList<>();
    private Instant now = Instant.parse("2026-11-01T09:00:00Z");

    /** The planner plans; the director approves two tickets; one is rejected once, then both validated; retro. */
    @Test
    void aPlannedMissionGoesFromThePlannerToReviewedTickets() {
        hireTheStaff();
        MissionId m = mission();
        TicketId plan = ticket(m, "Plan: Inventory", TicketKind.PLANNING);
        run(() -> company.tickets().assign(system(), plan, PLANNER));
        run(() -> company.missions().enableTicketValidation(director(), m));
        run(() -> company.missions().submitPlan(system(), m));
        run(() -> company.missions().approvePlan(director(), m));
        run(() -> company.tickets().start(agent(PLANNER), plan));
        run(() -> company.tickets()
                .propose(
                        agent(PLANNER),
                        plan,
                        DEVELOPER,
                        List.of(
                                new ProposedTicket("List the services", "", List.of("a list"), List.of()),
                                new ProposedTicket("Count them", "", List.of("a count"), List.of(0)))));
        List<TicketId> work = company.tickets().of(m).stream()
                .filter(t -> t.status() == TicketStatus.PROPOSED)
                .map(Ticket::id)
                .toList();
        run(() -> company.tickets().approve(director(), m, work));
        produceAndReview(work.getFirst(), ReviewVerdict.REJECTED);
        produceAndReview(work.getFirst(), ReviewVerdict.VALIDATED);
        produceAndReview(work.getLast(), ReviewVerdict.VALIDATED_WITH_RESERVATIONS);
        run(() -> company.missions().complete(director(), m, "done"));

        String decisions = WorkflowDecisions.of(journal);

        assertThat(GoldenFile.check("workflow/planned-mission.txt", decisions)).isNull();
        assertThat(decisions).contains(plan + ":PLAN", ":REVIEW", "HoldRetro");
    }

    private void produceAndReview(TicketId t, ReviewVerdict verdict) {
        if (company.tickets().find(t).orElseThrow().status() == TicketStatus.TODO) {
            run(() -> company.tickets().start(agent(DEVELOPER), t));
        }
        run(() -> company.tickets().submitForReview(agent(DEVELOPER), t, "report"));
        run(() -> company.tickets().review(agent(REVIEWER), t, verdict, "findings"));
    }

    private void hireTheStaff() {
        hire(PLANNER, WorkRole.PLANNER);
        hire(DEVELOPER, WorkRole.DEVELOPER);
        hire(REVIEWER, WorkRole.REVIEWER);
    }

    private void hire(AgentId id, WorkRole role) {
        run(() -> company.staff().hire(director(), id, id.value(), role.name(), role, "claude-sonnet-5-5", "medium"));
    }

    private MissionId mission() {
        return ((MissionCreated) run(() -> company.missions().create(director(), "Inventory", "List and count"))
                        .getFirst()
                        .event())
                .mission();
    }

    private TicketId ticket(MissionId m, String title, TicketKind kind) {
        return ((TicketCreated) run(() -> company.tickets()
                                .create(system(), m, title, "", Priority.NORMAL, List.of(), List.of("done"), kind))
                        .getFirst()
                        .event())
                .ticket();
    }

    private List<EventEnvelope> run(Supplier<List<EventEnvelope>> command) {
        List<EventEnvelope> events = command.get();
        journal.addAll(events);
        return events;
    }

    private CommandContext director() {
        return ctx(Actor.director());
    }

    private CommandContext system() {
        return ctx(Actor.system());
    }

    private CommandContext agent(AgentId id) {
        return ctx(Actor.agent(id));
    }

    private CommandContext ctx(Actor actor) {
        now = now.plusSeconds(60);
        return new CommandContext(actor, Origin.SIMULATED, now, null);
    }
}
