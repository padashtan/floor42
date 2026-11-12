package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.MissionEvent.MissionPlanApproved;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.WorkflowEvent.WorkflowPinned;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A mission whose workflow leaves work untaken is named, never left waiting in silence (ADR-0050): a ticket no step
 * of a decision workflow works on, and a pin this build cannot read. The dispatcher is off: each test reads what it
 * built.
 */
@QuarkusTest
@TestProfile(StallTest.DispatcherOff.class)
class WorkflowStallTest {

    static final AgentId CHRIS = new AgentId("chris");

    @Inject
    OfficeService office;

    @Inject
    LiveMissions live;

    @Inject
    dev.floor42.domain.workflow.MissionInterpreter interpreter;

    @Inject
    MissionRecovery recovery;

    @Inject
    Retrospectives retrospectives;

    CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    private MissionId created(Workflow w) throws Exception {
        if (office.query(c -> c.staff().find(CHRIS).isEmpty())) {
            office.execute(c -> c.staff()
                    .hire(
                            director(),
                            CHRIS,
                            "Chris",
                            "Developer",
                            "anthropic",
                            "claude-sonnet-5-5",
                            "medium",
                            List.of()));
        }
        String repo = StallTest.gitRepository().toString();
        if (w != null)
            return ((MissionCreated) office.execute(c -> c.missions().create(director(), "Stall", "Watch", repo, w))
                            .getFirst()
                            .event())
                    .mission();
        // No command of this build creates an unpinned REAL mission: the row is one an older Floor42 wrote.
        MissionId m = new MissionId("M-" + (office.query(c -> c.missions().all().size()) + 1));
        journaled(m, new MissionCreated(m, "Stall", "Watch", repo));
        return m;
    }

    private void withTicketSubmitted(MissionId m) {
        TicketId t = ((TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        director(),
                                        m,
                                        "Write notes",
                                        "",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of("report")))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.tickets().assign(director(), t, CHRIS));
        office.execute(c -> c.missions().submitPlan(director(), m));
        office.execute(c -> c.verification().setBudget(director(), m, null));
    }

    /** A row as a later Floor42 wrote it: journaled and applied as it is, no command of this build. */
    private void journaled(MissionId m, DomainEvent event) {
        EventEnvelope e = new EventEnvelope(
                UUID.randomUUID().toString(), Instant.now(), Origin.REAL, Actor.director(), m, null, null, event);
        office.execute(c -> {
            c.apply(e);
            return List.of(e);
        });
    }

    @Test
    void aTicketNoStepOfTheWorkflowTakesIsNamed() throws Exception {
        MissionId m =
                created(new Workflow("Decide", List.of(new Step.Approval(), new Step.Committee(), new Step.Accept())));
        withTicketSubmitted(m);
        office.execute(c -> c.missions().approvePlan(director(), m));

        Stall s = live.stall(m, Instant.now());

        assertThat(s.state()).isEqualTo("WAITING");
        assertThat(s.reason()).isEqualTo("NO_STEP_TAKES_IT");
        assertThat(s.ticket()).isNotNull();
    }

    /**
     * A ticket already in progress on a decision workflow (started under 1.8.0, where the ruling still offered to
     * implement) is never handed to a developer again: no task, and the stall names it.
     */
    @Test
    void aTicketInProgressNoStepTakesIsNotResumed() throws Exception {
        MissionId m =
                created(new Workflow("Decide", List.of(new Step.Approval(), new Step.Committee(), new Step.Accept())));
        withTicketSubmitted(m);
        office.execute(c -> c.missions().approvePlan(director(), m));
        TicketId t = office.query(c -> c.tickets().of(m).getFirst().id());
        office.execute(c -> c.tickets().start(office.context(Actor.agent(CHRIS), Origin.REAL), t));

        Stall s = live.stall(m, Instant.now());

        assertThat(s.state()).isEqualTo("WAITING");
        assertThat(s.reason()).isEqualTo("NO_STEP_TAKES_IT");
        assertThat(s.ticket()).isEqualTo(t.value());
    }

    /** Approved under a later Floor42, read by this one: nothing starts, and the reason is the workflow's. */
    @Test
    void aMissionWhoseWorkflowThisBuildCannotReadSaysSo() throws Exception {
        MissionId m = created(null);
        journaled(m, new WorkflowPinned(m, "{\"of\":\"later\"}", 2));
        withTicketSubmitted(m);
        journaled(m, new MissionPlanApproved(m));

        Stall s = live.stall(m, Instant.now());

        assertThat(s.state()).isEqualTo("WAITING");
        assertThat(s.reason()).isEqualTo("WORKFLOW_UNREADABLE");
        // The dispatcher's own rule, the one a restart's recovery asks too, starts nothing.
        Task next = office.query(c -> new TaskSelection(c, m, null, interpreter).next(Set.of(), Set.of(), Set.of()));
        assertThat(next).isNull();
        recovery.recover();
        assertThat(office.<List<dev.floor42.domain.AgentSession>>query(
                        c -> c.sessions().of(m)))
                .isEmpty();
    }

    /**
     * A retrospective left open by a restart, on a mission whose pin this build cannot read, is not run on: the path
     * the restart takes ({@code retro-recover}) asks the workflow too.
     */
    @Test
    void anOpenRetrospectiveOfAnUnrunnableMissionIsNotRunAfterARestart() throws Exception {
        MissionId m = created(null);
        journaled(m, new WorkflowPinned(m, "{\"of\":\"later\"}", 2));
        withTicketSubmitted(m);
        journaled(m, new MissionPlanApproved(m));
        // Chris worked on it before the restart: one ended session, so he takes part.
        TicketId t = office.query(c -> c.tickets().of(m).getFirst().id());
        CommandContext chris = office.context(Actor.agent(CHRIS), Origin.REAL);
        office.execute(c -> c.tickets().start(chris, t));
        String session = "s-" + UUID.randomUUID();
        office.execute(
                c -> c.sessions().start(office.context(Actor.system(), Origin.REAL), session, t, CHRIS, "m", "1"));
        office.execute(c ->
                c.sessions().end(office.context(Actor.system(), Origin.REAL), session, false, "done", 1, 1L, 1L, 0.0));
        int sessions = office.query(c -> c.sessions().of(m).size());
        office.execute(c -> c.tickets().cancel(director(), t, "not needed"));
        office.execute(c -> c.missions().complete(director(), m, "stopped"));
        office.execute(
                c -> c.retros().open(office.context(Actor.system(), Origin.REAL), m, CHRIS, List.of(CHRIS), "facts"));

        retrospectives.run(m);

        var retro = office.query(c -> c.retros().find(m).orElseThrow());
        assertThat(retro.contributions()).as("nobody was asked to speak").isEmpty();
        assertThat(office.<Integer>query(c -> c.sessions().of(m).size()))
                .as("no retrospective session started")
                .isEqualTo(sessions);
    }
}
