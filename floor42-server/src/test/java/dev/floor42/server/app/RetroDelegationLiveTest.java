package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.Retro;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.iam.BrokerTestBase;
import dev.floor42.server.iam.IamSessionProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * IAM mode with retrospectives on and the fake Claude Code: the completed mission keeps its delegation, the
 * retrospective's sessions get their identity from it, and it is revoked once the retrospective has ended.
 */
@QuarkusTest
@TestProfile(RetroDelegationLiveTest.Profile.class)
class RetroDelegationLiveTest extends BrokerTestBase {

    public static class Profile extends IamSessionProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            var all = new java.util.HashMap<>(super.getConfigOverrides());
            all.put("floor42.live-missions.enabled", "true");
            all.put("floor42.retro.enabled", "true");
            return all;
        }
    }

    @Inject
    RetroDelegations retroDelegations;

    @Inject
    AgentReservations reservations;

    private AgentReservations.Reservation held;

    private static void await(String what, BooleanSupplier done) throws Exception {
        for (int i = 0; i < 600 && !done.getAsBoolean(); i++) Thread.sleep(100);
        assertThat(done.getAsBoolean()).as(what).isTrue();
    }

    /** A mission CHRIS worked on, finished, with its delegation granted before it completes. */
    private MissionId missionToComplete() throws Exception {
        realMissionInProgress(); // hires the agent
        held = reservations.await(CHRIS, java.time.Duration.ofSeconds(20)); // the live dispatcher gives CHRIS no work
        keycloak.ensureAgentClient(CHRIS, 28800);
        var dir = Files.createTempDirectory("retro-live");
        MissionId m = ((MissionCreated) office.execute(c -> c.missions()
                                .create(director(), "Done", "Describe it", dir.toString(), Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((TicketCreated) office.execute(
                                c -> c.tickets().create(director(), m, "T", "", Priority.NORMAL, List.of(), List.of()))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.missions().submitPlan(director(), m));
        office.execute(c -> c.verification().setBudget(director(), m, null));
        office.execute(c -> c.missions().approvePlan(director(), m));
        delegations.grant(m, offlineTokens(), List.of());
        office.execute(c -> c.tickets().assign(director(), t, CHRIS));
        office.execute(c -> c.tickets().start(office.context(Actor.agent(CHRIS), Origin.REAL), t));
        var system = office.context(Actor.system(), Origin.REAL);
        office.execute(c -> c.sessions().start(system, "s-" + m, t, CHRIS, "claude-sonnet-5-5", "1"));
        office.execute(c -> c.tickets().cancel(director(), t, "not needed"));
        return m;
    }

    private List<String> events(MissionId m) {
        return journal.readAll().stream()
                .filter(e -> m.equals(e.mission()))
                .map(e -> e.event().getClass().getSimpleName())
                .toList();
    }

    @Test
    void theRetrospectiveRunsOnTheDelegationThenItIsRevoked() throws Exception {
        MissionId m = missionToComplete();
        // CHRIS is held since missionToComplete: the retro waits for its speaker, the window is open
        office.execute(c -> c.missions().complete(director(), m, "done"));
        await(
                "the retrospective is open",
                () -> office.query(c -> c.retros().find(m).isPresent()));
        assertThat(delegations.isRevoked(m))
                .as("kept while the retrospective is due")
                .isFalse();
        // A restart now: an open retrospective needs it, so it is kept.
        retroDelegations.settle(null);
        Thread.sleep(300);
        assertThat(delegations.isRevoked(m)).isFalse();
        reservations.release(held);

        await(
                "the retrospective ended",
                () -> office.query(c -> c.retros().find(m).map(Retro::concluded).orElse(false)));
        await("the delegation revoked", () -> delegations.isRevoked(m));
        List<String> seq = events(m);
        System.out.println("RETRO SEQUENCE " + seq);
        assertThat(seq)
                .contains("MissionCompleted", "RetroOpened", "RetroConcluded")
                .doesNotContain("RetroSkipped");
        // Positive proof the identity came from the delegation: every retrospective session got a token.
        assertThat(journal.readAll().stream()
                        .filter(e -> m.equals(e.mission()))
                        .map(e -> e.event().toString())
                        .filter(t -> t.contains("no identity token")))
                .isEmpty();
        assertThat(java.util.Collections.frequency(seq, "AgentTokenIssued")).isEqualTo(2); // speaker and chair
        assertThat(seq.indexOf("MissionCompleted")).isLessThan(seq.indexOf("RetroConcluded"));
    }
}
