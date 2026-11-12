package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.SessionIdentity.Minted;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A revoke that lands after the start checks and before the registration must still end the session. */
class RevokeBetweenCheckAndRegisterTest {

    @Test
    void aRevokeBetweenTheCheckAndTheRegistrationStopsTheSession() {
        Instant checked = Instant.now().minusSeconds(5);
        Instant revokedAt = checked.plusSeconds(2); // after the check, before register
        List<SessionClaims> stopped = new ArrayList<>();
        var sessions =
                new IamSessions(null, null, Set::of, (m, since) -> revokedAt.isAfter(since), () -> 0, stopped::add);
        var claims = new SessionClaims(
                "11111111-1111-1111-1111-111111111111", new AgentId("ada"), new MissionId("M-1"), new TicketId("T-1"));

        sessions.register(
                null, claims, new Minted("t", "j", "iss", Instant.now().plusSeconds(60), checked));

        assertThat(sessions.stopRevoked()).isEqualTo(1);
        assertThat(stopped).containsExactly(claims);
    }
}
