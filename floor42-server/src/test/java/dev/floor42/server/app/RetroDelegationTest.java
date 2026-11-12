package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.api.LiveTestSupport;
import dev.floor42.server.iam.Delegations;
import dev.floor42.server.iam.IamSessionProfile;
import dev.floor42.server.iam.IamSessionTestBase;
import dev.floor42.server.iam.IdentityUnavailableException;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.SessionIdentity;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A completed mission's delegation lives for its retrospective only, and ends with it (or at a restart). */
@QuarkusTest
// IamSessionProfile: no mission orchestrator racing the test to start its ticket (IN_PROGRESS -> IN_PROGRESS).
@TestProfile(IamSessionProfile.class)
class RetroDelegationTest extends IamSessionTestBase {

    @Inject
    Delegations delegations;

    @Inject
    SessionIdentity identity;

    @Inject
    RetroDelegations retroDelegations;

    /** A mission with no ticket, approved, with a delegation, completed first (its reaction revokes nothing yet), the delegation after. */
    private MissionId completedWithDelegation() throws Exception {
        return completed(true);
    }

    private MissionId completed(boolean grant) throws Exception {
        realMissionInProgress(); // hires the agent
        MissionId m = ((MissionCreated) office.execute(
                                c -> c.missions().create(director(), "Done", "Nothing to do", ".", Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        var t = ((dev.floor42.domain.event.TicketEvent.TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        director(),
                                        m,
                                        "T",
                                        "",
                                        dev.floor42.domain.Priority.NORMAL,
                                        List.of(),
                                        List.of()))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.missions().submitPlan(director(), m));
        office.execute(c -> c.verification().setBudget(director(), m, null));
        office.execute(c -> c.missions().approvePlan(director(), m));
        office.execute(c -> c.tickets().assign(director(), t, CHRIS));
        startUnlessStarted(t);
        office.execute(c -> c.sessions()
                .start(
                        office.context(dev.floor42.domain.Actor.system(), dev.floor42.domain.Origin.REAL),
                        "s-" + m,
                        t,
                        CHRIS,
                        "claude-sonnet-5-5",
                        "1"));
        office.execute(c -> c.tickets().cancel(director(), t, "not needed"));
        office.execute(c -> c.missions().complete(director(), m, "done"));
        if (grant) delegations.grant(m, new Tokens("a", "offline-" + m, null, Instant.now()), List.of());

        return m;
    }

    /** The real dispatcher may start the ticket first (IN_PROGRESS to IN_PROGRESS is refused): then it is already so. */
    private void startUnlessStarted(dev.floor42.domain.TicketId t) {
        if (inProgress(t)) return;
        try {
            office.execute(c -> c.tickets().start(agentCtx(), t));
        } catch (RuntimeException e) {
            // Handled: lost the race to the dispatcher; fine as long as the ticket is running.
            if (!inProgress(t)) throw e;
        }
    }

    private boolean inProgress(dev.floor42.domain.TicketId t) {
        return office.query(c -> c.tickets().find(t).orElseThrow().status())
                == dev.floor42.domain.TicketStatus.IN_PROGRESS;
    }

    private dev.floor42.domain.CommandContext agentCtx() {
        return office.context(dev.floor42.domain.Actor.agent(CHRIS), dev.floor42.domain.Origin.REAL);
    }

    private SessionSpec specOf(MissionId m, dev.floor42.domain.TicketId ticket) {
        return new SessionSpec(m, ticket, CHRIS, dev.floor42.domain.Origin.REAL, ".", "b", "m", "low", List.of());
    }

    @Test
    void aCompletedMissionsDelegationServesOnlyItsRetrospective() throws Exception {
        MissionId m = completedWithDelegation();
        assertThat(delegations.isRevoked(m)).isFalse();
        assertThatThrownBy(() -> identity.mint(specOf(m, null), "l1"))
                .isInstanceOf(IdentityUnavailableException.class)
                .hasMessageContaining("only its retrospective");
        office.execute(c -> c.retros()
                .open(
                        office.context(dev.floor42.domain.Actor.system(), dev.floor42.domain.Origin.REAL),
                        m,
                        CHRIS,
                        List.of(CHRIS),
                        "facts"));
        var ticket = office.query(c -> c.retros().find(m).orElseThrow().ticket());
        // The retrospective's own ticket passes that gate (it then meets the fake offline token, not that refusal).
        try {
            identity.mint(specOf(m, ticket), "l2");
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).doesNotContain("only its retrospective").doesNotContain("revoked");
        }
    }

    @Test
    void releaseRevokesAndStartupSettlesAFinishedMissionWithNoOpenRetro() throws Exception {
        MissionId m = completedWithDelegation();
        assertThat(delegations.liveMissions()).contains(m);
        retroDelegations.settle(null);
        LiveTestSupport.await(
                "the delegation is revoked", java.time.Duration.ofSeconds(15), () -> delegations.isRevoked(m));
        assertThat(delegations.isRevoked(m)).isTrue();
        MissionId n = completedWithDelegation();
        retroDelegations.release(n);
        assertThat(delegations.blocks(n, false)).contains("delegation revoked");
    }

    @Test
    void aRetrospectiveOfAMissionWithoutDelegationRidesTheSignInAsBefore() throws Exception {
        MissionId m = completed(false);
        office.execute(c -> c.retros()
                .open(
                        office.context(dev.floor42.domain.Actor.system(), dev.floor42.domain.Origin.REAL),
                        m,
                        CHRIS,
                        List.of(CHRIS),
                        "facts"));
        var ticket = office.query(c -> c.retros().find(m).orElseThrow().ticket());
        assertThatThrownBy(() -> identity.mint(specOf(m, ticket), "l3"))
                .hasMessageContaining("director's sign-in")
                .hasMessageNotContaining("no delegation");
    }

    @Test
    void aRunCutOutsideAShutdownReleasesTheDelegation() throws Exception {
        MissionId m = completedWithDelegation();
        retroDelegations.ended(m, true);
        assertThat(delegations.isRevoked(m)).isTrue();
    }
}
