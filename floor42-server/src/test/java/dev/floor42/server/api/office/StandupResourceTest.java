package dev.floor42.server.api.office;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.ws.rs.BadRequestException;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class StandupResourceTest {

    @Test
    void aKnownZoneIsUsedAndNoZoneMeansTheDaemons() {
        assertThat(StandupResource.zoneOf("Europe/Paris")).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(StandupResource.zoneOf(null)).isEqualTo(ZoneId.systemDefault());
        assertThat(StandupResource.zoneOf(" ")).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    void anUnknownZoneIsABadRequest() {
        assertThatThrownBy(() -> StandupResource.zoneOf("Mars/Olympus")).isInstanceOf(BadRequestException.class);
    }
}
