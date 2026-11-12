package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The director defines committee seats of their own; the journal keeps them. */
@ExtendWith(GoldenFixtureExtension.class)
class CommitteeSeatTest {

    final Fixture f = new Fixture();

    void define(String key, List<String> panels) {
        f.record(f.company
                .committees()
                .defineSeat(
                        f.director(),
                        key,
                        "Rosa",
                        "GDPR expert",
                        "claude-sonnet-5-5",
                        "medium",
                        "Personal data, consent, retention and the rights of the people concerned.",
                        panels));
    }

    @Test
    void aSeatIsDefinedChangedAndRebuiltFromTheJournal() {
        define("rosa", List.of("idea"));
        define("rosa", List.of("Idea", "architecture", "idea"));

        assertThat(f.company.committees().seats()).singleElement().satisfies(s -> {
            assertThat(s.agent()).isEqualTo(new AgentId("rosa"));
            assertThat(s.panels()).containsExactly("idea", "architecture");
        });
        assertThat(Company.replay(f.journal).committees().seats())
                .isEqualTo(f.company.committees().seats());
    }

    @Test
    void onlyTheDirectorDefinesASeatAndItNamesNoOtherAgent() {
        assertThatThrownBy(() -> f.company
                        .committees()
                        .defineSeat(f.agent(CHRIS), "rosa", "Rosa", "t", "m", "e", "lens", List.of("idea")))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
        assertThatThrownBy(() -> define(CHRIS.value(), List.of("idea")))
                .extracting("code")
                .isEqualTo("AGENT_EXISTS");
        assertThatThrownBy(() -> define("rosa", List.of("board")))
                .extracting("code")
                .isEqualTo("INVALID_ARGUMENT");
        assertThatThrownBy(() -> define("Rosa Lee", List.of("idea")))
                .extracting("code")
                .isEqualTo("INVALID_ARGUMENT");
    }
}
