package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A delivery's proof in the journal (ADR-0058): who records what, the limits, and a replay that keeps it all. */
@ExtendWith(GoldenFixtureExtension.class)
class ProofsTest {

    final Fixture f = new Fixture(Origin.REAL);
    final MissionId m = ((dev.floor42.domain.event.MissionEvent.MissionCreated) f.record(f.company
                            .missions()
                            .create(
                                    f.director(),
                                    "Ship the service",
                                    "A compose file that starts",
                                    "C:/work/repo",
                                    Workflow.BUILT_IN))
                    .getFirst()
                    .event())
            .mission();
    final ProofTarget target = new ProofTarget(CHRIS, "0123456789abcdef", "abc1234", "fedcba9876543210");

    static ProofStep passed(int n) {
        return new ProofStep(n, "Step " + n, "echo ok", "FLOOR42", null, "PASSED", null, 0, false, 12, "ok\n");
    }

    static ProofStep directors(int n) {
        return new ProofStep(
                n,
                "Up",
                "docker compose up -d",
                "DIRECTOR",
                "NEEDS_DOCKER",
                "NOT_RUN",
                "DIRECTOR_STEP",
                null,
                false,
                0,
                "");
    }

    ProofsFacade proofs() {
        return f.company.deliveries().proofs();
    }

    @Test
    void floor42RecordsItsRunAndTheDirectorTheRest() {
        f.record(proofs().recordRun(f.system(), m, target, List.of(passed(1), directors(2))));
        f.record(proofs().witness(f.director(), m, target, 2, false, "  curl answers 503 "));
        f.record(proofs().verdict(f.director(), m, "NOT_PROVEN", List.of(target), "the service does not start"));

        assertThat(proofs().runs(m))
                .singleElement()
                .satisfies(r -> assertThat(r.failed()).isFalse());
        assertThat(proofs().witnesses(m)).singleElement().satisfies(w -> {
            assertThat(w.works()).isFalse();
            assertThat(w.seen()).isEqualTo("curl answers 503");
        });
        assertThat(proofs().verdict(m).orElseThrow().proven()).isFalse();
    }

    @Test
    void nobodyElseRecordsAndNothingMalformedIsRecorded() {
        assertThatThrownBy(() -> proofs().recordRun(f.director(), m, target, List.of(passed(1))))
                .hasMessageContaining("Only Floor42");
        assertThatThrownBy(() -> proofs().witness(f.agent(CHRIS), m, target, 1, true, ""))
                .hasMessageContaining("Only the director");
        assertThatThrownBy(() -> proofs().verdict(f.agent(CHRIS), m, "PROVEN", List.of(target), ""))
                .hasMessageContaining("Only the director");
        assertThatThrownBy(() -> proofs().acceptUnproven(f.agent(CHRIS), m, "NO_RECIPE", "0123456789abcdef"))
                .hasMessageContaining("Only the director");
        assertThatThrownBy(() -> proofs().recordRun(f.system(), m, target, List.of()))
                .hasMessageContaining("1 to 20 steps");
        assertThatThrownBy(() -> proofs().recordRun(
                                f.system(),
                                m,
                                new ProofTarget(CHRIS, "x", "abc1234", "fedcba9876543210"),
                                List.of(passed(1))))
                .hasMessageContaining("malformed");
        assertThatThrownBy(() -> proofs().witness(f.director(), m, target, 1, false, " "))
                .hasMessageContaining("Say what you see");
        assertThatThrownBy(() -> proofs().verdict(f.director(), m, "MAYBE", List.of(target), ""))
                .hasMessageContaining("PROVEN or NOT_PROVEN");
        assertThatThrownBy(() -> proofs().acceptUnproven(f.director(), m, "FINE", "0123456789abcdef"))
                .hasMessageContaining("Unknown proof state");
        assertThat(proofs().runs(m)).isEmpty();
    }

    @Test
    void aClosedMissionTakesNoProof() {
        f.record(f.company.missions().cancel(f.director(), m, "not needed"));
        assertThatThrownBy(() -> proofs().recordRun(f.system(), m, target, List.of(passed(1))))
                .hasMessageContaining("is over");
    }

    @Test
    void theProofSurvivesAReplay() {
        f.record(proofs().recordRun(f.system(), m, target, List.of(passed(1), directors(2))));
        f.record(proofs().witness(f.director(), m, target, 2, true, ""));
        f.record(proofs().verdict(f.director(), m, "PROVEN", List.of(target), ""));
        f.record(proofs().acceptUnproven(f.director(), m, "NO_VERDICT", "0123456789abcdef"));

        ProofsFacade replayed = Company.replay(f.journal).deliveries().proofs();

        assertThat(replayed.runs(m)).isEqualTo(proofs().runs(m));
        assertThat(replayed.witnesses(m)).isEqualTo(proofs().witnesses(m));
        assertThat(replayed.verdict(m)).isEqualTo(proofs().verdict(m));
        assertThat(replayed.unprovenAccepted(m)).contains("NO_VERDICT");
    }
}
