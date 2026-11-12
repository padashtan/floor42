package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What a mission's sessions receive is the director's choice (ADR-0040), and so are plugin installs: never an agent's,
 * never Floor42's own, never simulated.
 */
@ExtendWith(GoldenFixtureExtension.class)
class MissionAssetsTest {

    static final String REVIEWER = "floor42:pr-review-toolkit@claude-plugins-official/agent/code-reviewer";
    static final String PDF = "claude:document-skills@anthropic-agent-skills/skill/pdf";

    final Fixture f = new Fixture(Origin.REAL);

    MissionId mission() {
        return ((dev.floor42.domain.event.MissionEvent.MissionCreated) f.record(f.company
                                .missions()
                                .create(f.director(), "Notes", "Write notes", "C:/work/notes", Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
    }

    @Test
    void theDirectorsChoiceReplacesTheLastAndSurvivesAReplay() {
        MissionId m = mission();
        assertThat(f.company.deliveries().missionAssets(m)).isEmpty();
        f.record(f.company.deliveries().selectMissionAssets(f.director(), m, List.of(REVIEWER, PDF, PDF)));
        assertThat(f.company.deliveries().missionAssets(m)).containsExactly(REVIEWER, PDF);
        f.record(f.company.deliveries().selectMissionAssets(f.director(), m, List.of(PDF)));

        Company replayed = Company.replay(f.journal);
        assertThat(replayed.deliveries().missionAssets(m)).containsExactly(PDF);
        f.record(f.company.deliveries().selectMissionAssets(f.director(), m, List.of()));
        assertThat(Company.replay(f.journal).deliveries().missionAssets(m)).isEmpty();
    }

    @Test
    void anAgentOrFloor42ItselfIsRefused() {
        MissionId m = mission();
        assertThatThrownBy(() -> f.company.deliveries().selectMissionAssets(f.agent(CHRIS), m, List.of(PDF)))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> f.company.deliveries().selectMissionAssets(f.system(), m, List.of(PDF)))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> f.company
                        .deliveries()
                        .recordPluginCommand(
                                f.agent(CHRIS),
                                "FLOOR42",
                                "INSTALL",
                                "x",
                                "y",
                                List.of("claude plugin install x@y"),
                                0,
                                true,
                                "1.0.0"))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void aMalformedItemAnUnknownTargetAndAClosedMissionAreRefused() {
        MissionId m = mission();
        for (String bad : List.of(
                "pdf",
                "floor42:x@y/hook/h",
                "other:x@y/skill/pdf",
                "floor42:x@y/skill/../../etc",
                "floor42:x y@y/skill/pdf")) {
            assertThatThrownBy(() -> f.company.deliveries().selectMissionAssets(f.director(), m, List.of(bad)))
                    .extracting("code")
                    .isEqualTo("INVALID_ARGUMENT");
        }
        assertThatThrownBy(() -> f.company
                        .deliveries()
                        .recordPluginCommand(f.director(), "HOME", "INSTALL", "x", "y", List.of("c"), 0, true, null))
                .extracting("code")
                .isEqualTo("INVALID_ARGUMENT");
        f.record(f.company.missions().cancel(f.director(), m, "no longer needed"));
        assertThatThrownBy(() -> f.company.deliveries().selectMissionAssets(f.director(), m, List.of(PDF)))
                .extracting("code")
                .isEqualTo("MISSION_CLOSED");
    }

    @Test
    void aSimulatedMissionReceivesNothing() {
        Fixture sim = new Fixture(Origin.SIMULATED);
        MissionId m = sim.mission();
        assertThatThrownBy(() -> sim.company.deliveries().selectMissionAssets(sim.director(), m, List.of(PDF)))
                .extracting("code")
                .isEqualTo("SIMULATED_MISSION");
    }

    @Test
    void aPluginCommandIsRecordedAsTheDirectorsRealAct() {
        var events = f.record(f.company
                .deliveries()
                .recordPluginCommand(
                        f.director(),
                        "CLAUDE",
                        "UNINSTALL",
                        "skill-creator",
                        "claude-plugins-official",
                        List.of("claude plugin uninstall skill-creator@claude-plugins-official --json"),
                        1,
                        true,
                        "1.0.0"));
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.origin()).isEqualTo(Origin.REAL);
            assertThat(e.actor().isDirector()).isTrue();
        });
    }
}
