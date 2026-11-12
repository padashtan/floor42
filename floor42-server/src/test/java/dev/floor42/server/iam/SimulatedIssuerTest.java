package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SimulatedIssuerTest {

    @Test
    void mintedTokenCarriesTheSimulatedIssuer() {
        var issuer = new SimulatedIssuer();
        String jwt = issuer.mint(
                new SessionClaims("L-1", new AgentId("ada"), new MissionId("M-1"), new TicketId("T-2")),
                Duration.ofHours(1));
        var parsed = Jwts.parse(jwt);
        assertThat(parsed.claims().path("iss").asText()).isEqualTo("urn:floor42:simulated");
        assertThat(parsed.header().path("kid").asText()).isEqualTo("simulated-1");
        assertThat(parsed.claims().path("floor42").path("launch").asText()).isEqualTo("L-1");
        assertThat(Jwts.verify(parsed, issuer.publicKey())).isTrue();
        assertThat(Jwts.shortSignature(jwt)).hasSize(11).endsWith("...");
    }

    @Test
    void parseRefusesAnythingButThreeParts() {
        assertThatThrownBy(() -> Jwts.parse("a.b")).isInstanceOf(IllegalArgumentException.class);
    }
}
