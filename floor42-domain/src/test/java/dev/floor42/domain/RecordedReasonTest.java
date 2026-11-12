package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The reasons Floor42 writes itself are read back as their code and values; a typed reason has none. */
class RecordedReasonTest {

    @Test
    void theSentencesFloor42WritesAreReadBack() {
        assertThat(RecordedReason.read(RecordedReason.unauthorizedReason(new ActionId("u2"))))
                .contains(Phrase.of("unauthorizedExecution", "action", "u2"));
        assertThat(RecordedReason.read(RecordedReason.ranWithout(new AgentId("chris"), "Bash", "rm -rf build")))
                .contains(Phrase.of(
                        "ranWithoutAuthorization", "agent", "chris", "tool", "Bash", "summary", "rm -rf build"));
        assertThat(RecordedReason.read(RecordedReason.retroClosedEarly())).contains(Phrase.of("retroClosedEarly"));
        assertThat(RecordedReason.read(RecordedReason.sentBack())).contains(Phrase.of("sentBack"));
        assertThat(RecordedReason.read(RecordedReason.missionCancelled())).contains(Phrase.of("missionCancelled"));
    }

    @Test
    void aTypedReasonHasNoCode() {
        assertThat(RecordedReason.read(null)).isEmpty();
        assertThat(RecordedReason.read("Bought a product instead")).isEmpty();
        assertThat(RecordedReason.read("unauthorized execution of action ")).isEmpty();
    }
}
