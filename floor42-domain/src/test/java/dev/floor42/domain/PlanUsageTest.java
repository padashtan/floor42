package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The plan's usage windows, as reported by Claude Code, and the hold before a session they would likely cut. */
@ExtendWith(GoldenFixtureExtension.class)
class PlanUsageTest {

    final Fixture f = new Fixture();
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);
    static final long RESET = Instant.parse("2099-01-01T10:00:00Z").getEpochSecond();
    static final Instant NOW = Instant.parse("2099-01-01T08:00:00Z");
    int sessions;

    String session(String model, double... fiveHour) {
        String id = "S-" + ++sessions;
        f.record(f.company.sessions().start(f.system(), id, t, CHRIS, model, "2.1"));
        for (double u : fiveHour) {
            f.record(f.company.verification().observePlanUsage(f.system(), id, "five_hour", u, RESET, "allowed"));
        }
        f.record(f.company.sessions().end(f.system(), id, false, "completed", 1, 10L, 10L, 0.01));
        return id;
    }

    @Test
    void whatOneSessionAddsIsLearnedPerModel() {
        session("sonnet", 0.40, 0.42);
        session("sonnet", 0.44, 0.50);
        session("haiku", 0.51);
        session("sonnet", 0.52, 0.58);

        // A reading shows the usage before its session: sonnet used 0.44 - 0.40 and 0.51 - 0.44 (median 0.055).
        assertThat(f.company
                        .verification()
                        .planUsage()
                        .expectedIncrease("five_hour", "sonnet", f.company.sessions()::modelOf))
                .hasValueSatisfying(v -> assertThat(v).isCloseTo(0.055, org.assertj.core.data.Offset.offset(1e-9)));
        assertThat(f.company
                        .verification()
                        .planUsage()
                        .expectedIncrease("five_hour", "opus", f.company.sessions()::modelOf))
                .isEmpty();
    }

    @Test
    void aSessionThatWouldReachTheThresholdIsAtRisk() {
        session("sonnet", 0.50, 0.60);
        session("sonnet", 0.70, 0.84);

        assertThat(f.company.verification().planLimitRisk("sonnet", 0.95, NOW)).hasValueSatisfying(r -> {
            assertThat(r.window()).isEqualTo("five_hour");
            assertThat(r.utilization()).isEqualTo(0.84);
            assertThat(r.expectedIncrease()).isCloseTo(0.20, org.assertj.core.data.Offset.offset(1e-9));
        });
        assertThat(f.company.verification().planLimitRisk("sonnet", 0.95, NOW.plusSeconds(3 * 3600)))
                .as("after the reset the reading tells nothing")
                .isEmpty();
    }

    @Test
    void withoutHistoryOnlyTheReadingCounts() {
        session("sonnet", 0.30);

        assertThat(f.company.verification().planLimitRisk("sonnet", 0.95, NOW)).isEmpty();
        assertThat(f.company.verification().planLimitRisk("sonnet", 0.30, NOW)).isPresent();
    }

    @Test
    void theDirectorIsAskedOnceWithTheFacts() {
        session("sonnet", 0.97);
        PlanUsage.Risk risk =
                f.company.verification().planLimitRisk("sonnet", 0.95, NOW).orElseThrow();

        f.record(f.company.verification().reportPlanLimit(f.system(), m, "sonnet", risk));
        assertThat(f.company.verification().reportPlanLimit(f.system(), m, "sonnet", risk))
                .isEmpty();

        Question q = f.company.desk().open().getFirst();
        assertThat(q.kind()).isEqualTo(QuestionKind.PLAN_LIMIT);
        assertThat(q.options()).containsExactly("Wait for the reset", "Run anyway", "Stop here");
        assertThat(q.context())
                .contains("5-hour window at 97% (measured by Claude Code)")
                .contains("not known yet");
        assertThat(Company.replay(f.journal)
                        .verification()
                        .planUsage()
                        .latest()
                        .get("five_hour")
                        .utilization())
                .isEqualTo(0.97);
    }

    @Test
    void onlyTheRuntimeReportsUsage() {
        String s = session("sonnet", 0.1);

        assertThatThrownBy(() ->
                        f.company.verification().observePlanUsage(f.director(), s, "five_hour", 0.2, RESET, "allowed"))
                .isInstanceOf(DomainException.class);
    }
}
