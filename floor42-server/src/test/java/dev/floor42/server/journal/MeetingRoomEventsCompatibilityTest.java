package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.MeetingEvent.MeetingCalled;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a daemon of an older version makes of the meeting's rows (ADR-0049): a call keeps the four fields 1.8.0 reads,
 * and an event type a daemon does not know is set aside as written by a newer version, never a reason not to start.
 */
class MeetingRoomEventsCompatibilityTest {

    final EventCodec codec = new EventCodec();

    @Test
    void aMeetingsCallKeepsTheFieldsOneEightZeroReads() {
        String row = codec.encode(new MeetingCalled("MT-1", new MissionId("M-1"), List.of(new AgentId("chris")), null));
        assertThat(row)
                .isEqualTo("{\"meeting\":\"MT-1\",\"mission\":\"M-1\",\"attendees\":[\"chris\"],\"agenda\":null}");
    }

    @Test
    void anEventTypeThisVersionDoesNotKnowIsSetAsideAsNewer() {
        assertThatThrownBy(() -> codec.decode("MeetingFromALaterVersion", "{\"meeting\":\"MT-1\"}"))
                .isInstanceOf(UnreadableEventException.class)
                .extracting(e -> ((UnreadableEventException) e).reason())
                .isEqualTo(Unreadable.Reason.NEWER_VERSION);
    }
}
