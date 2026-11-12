package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionArchived;
import dev.floor42.domain.event.MissionEvent.MissionCancelled;
import dev.floor42.domain.event.MissionEvent.MissionCompleted;
import dev.floor42.server.iam.Delegations;
import dev.floor42.server.iam.IamOnProfile;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.MissionDelegation;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A delegation ends with its mission, however the mission ends; and the dispatcher starts nothing while it is blocked. */
@QuarkusTest
@TestProfile(IamOnProfile.class)
class DelegationReactionsTest {

    @Inject
    EventReactions reactions;

    @Inject
    Delegations delegations;

    @Inject
    Dispatcher dispatcher;

    private MissionDelegation grantedTo(MissionId m) {
        return delegations.grant(m, new Tokens("a", "offline-" + m, null, Instant.now()), List.of());
    }

    private void ends(MissionId m, DomainEvent event) {
        grantedTo(m);
        assertThat(delegations.blocks(m, false)).isNotEqualTo(java.util.Optional.of("delegation revoked"));
        reactions.react(List.of(
                new EventEnvelope("e-" + m, Instant.now(), Origin.REAL, Actor.director(), m, null, null, event)));
        assertThat(delegations.find(m)).get().extracting("revoked").isEqualTo(true);
        assertThat(delegations.blocks(m, false)).contains("delegation revoked");
    }

    @Test
    void cancelledArchivedAndCompletedRevokeTheDelegation() {
        ends(new MissionId("M-9001"), new MissionCancelled(new MissionId("M-9001"), "no"));
        ends(new MissionId("M-9002"), new MissionArchived(new MissionId("M-9002")));
        ends(new MissionId("M-9003"), new MissionCompleted(new MissionId("M-9003"), "done"));
    }

    @Test
    void dispatcherStartsNothingWhileBlocked() {
        MissionId m = new MissionId("M-9004");
        grantedTo(m);
        delegations.revokeLocally(m);
        assertThat(dispatcher.blocked(m, false)).isTrue();
        dispatcher.produce(m);
    }

    @Test
    void dispatcherStartsNothingForAnApprovedMissionWithoutDelegation() {
        MissionId m = new MissionId("M-9005");
        assertThat(dispatcher.blocked(m, true)).isTrue();
        assertThat(delegations.blocks(m, true))
                .contains("no delegation: approve the plan again with a Keycloak sign-in");
        dispatcher.produce(m);
    }
}
