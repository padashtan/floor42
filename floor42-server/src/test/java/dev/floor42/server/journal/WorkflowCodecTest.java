package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.WorkflowEvent.WorkflowPinned;
import org.junit.jupiter.api.Test;

/**
 * The workflow is opaque to the journal (ADR-0050): any document and any schema version decode, the codec never maps
 * steps to classes; a row without its key is corrupt, left out, never a crash of the replay.
 */
class WorkflowCodecTest {

    private final EventCodec codec = new EventCodec();

    @Test
    void aDocumentOfALaterFloor42DecodesAsItIs() {
        String later = "{\"name\":\"x\",\"steps\":[{\"step\":\"deploy\",\"canary\":{\"percent\":5}}]}";
        WorkflowPinned pin = new WorkflowPinned(new MissionId("M-1"), later, 7);

        String row = codec.encode(pin);

        assertThat(row).isEqualTo("{\"mission\":\"M-1\",\"document\":" + quoted(later) + ",\"schemaVersion\":7}");
        assertThat(codec.decode("WorkflowPinned", row)).isEqualTo(pin);
    }

    @Test
    void aRowWithoutItsNameIsCorruptNotACrash() {
        assertThatThrownBy(() -> codec.decode("CompanyWorkflowSaved", "{\"document\":\"{}\",\"schemaVersion\":1}"))
                .isInstanceOf(UnreadableEventException.class)
                .satisfies(
                        e -> assertThat(((UnreadableEventException) e).reason()).isEqualTo(Unreadable.Reason.CORRUPT));
        assertThatThrownBy(() -> codec.decode("CompanyWorkflowRemoved", "{}"))
                .satisfies(
                        e -> assertThat(((UnreadableEventException) e).reason()).isEqualTo(Unreadable.Reason.CORRUPT));
        assertThatThrownBy(() -> codec.decode("WorkflowPinned", "{\"document\":\"{}\",\"schemaVersion\":1}"))
                .satisfies(
                        e -> assertThat(((UnreadableEventException) e).reason()).isEqualTo(Unreadable.Reason.CORRUPT));
    }

    private static String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
