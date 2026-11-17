package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.event.TerminalEvent.ApiTokenIssued;
import org.junit.jupiter.api.Test;

/** ADR-0083: a bound script token's row names its subject and binding; every other row stays as it was. */
class ApiTokenIssuedTest {

    private final EventCodec codec = new EventCodec();

    @Test
    void anOldRowReadsWithNulls() {
        String v12 = "{\"token\":\"S-1\",\"sha256\":\"" + "ab".repeat(32)
                + "\",\"scope\":\"read\",\"expiresAt\":\"2026-11-30T00:00:00Z\",\"label\":\"ci\"}";
        ApiTokenIssued old = (ApiTokenIssued) codec.decode("ApiTokenIssued", v12);
        assertThat(old.subject()).isNull();
        assertThat(old.binding()).isNull();
        // Written back unchanged, so a rollback binary still reads it (ADR-0042).
        assertThat(codec.encode(old)).isEqualTo(v12);
    }

    @Test
    void aBoundRowRoundTrips() {
        var bound = new ApiTokenIssued(
                "S-2",
                "cd".repeat(32),
                "missions:write",
                "2026-11-30T00:00:00Z",
                null,
                "7f3c9a10-1111-2222-3333-444455556666",
                "KEYCLOAK_OFFLINE_SESSION");
        String json = codec.encode(bound);
        assertThat(json)
                .contains("\"subject\":\"7f3c9a10-1111-2222-3333-444455556666\"")
                .contains("\"binding\":\"KEYCLOAK_OFFLINE_SESSION\"");
        assertThat(codec.decode("ApiTokenIssued", json)).isEqualTo(bound);
    }
}
