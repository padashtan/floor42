package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The release's claim (ADR-0082): a trust level is a cap applied after the table, and it never widens a line. */
class TrustCapTest {

    @Test
    void aLevelNeverWidensALine() {
        int lines = 0;
        for (TrustLevel level : TrustLevel.values()) {
            for (ActionCategory category : ActionCategory.values()) {
                for (Decision table : Decision.values()) {
                    Decision capped = TrustCap.cap(level, category, table);
                    assertThat(capped.compareTo(table))
                            .as("%s %s %s gave %s", level, category, table, capped)
                            .isGreaterThanOrEqualTo(0);
                    lines++;
                }
            }
        }
        assertThat(lines).isEqualTo(3 * 6 * 4);
    }

    @Test
    void standardIsTheTable() {
        for (ActionCategory category : ActionCategory.values()) {
            for (Decision table : Decision.values()) {
                assertThat(TrustCap.cap(TrustLevel.STANDARD, category, table)).isEqualTo(table);
            }
        }
    }

    @Test
    void probationAsksOnWritesAndAlwaysOnRunsAndNetwork() {
        assertThat(TrustCap.cap(TrustLevel.PROBATION, ActionCategory.WRITE, Decision.ALLOW))
                .isEqualTo(Decision.ASK);
        assertThat(TrustCap.cap(TrustLevel.PROBATION, ActionCategory.EXECUTE, Decision.ALLOW))
                .isEqualTo(Decision.ASK_ALWAYS);
        assertThat(TrustCap.cap(TrustLevel.PROBATION, ActionCategory.EXECUTE, Decision.ASK))
                .isEqualTo(Decision.ASK_ALWAYS);
        assertThat(TrustCap.cap(TrustLevel.PROBATION, ActionCategory.NETWORK, Decision.ALLOW))
                .isEqualTo(Decision.ASK_ALWAYS);
        assertThat(TrustCap.cap(TrustLevel.PROBATION, ActionCategory.READ, Decision.ALLOW))
                .isEqualTo(Decision.ALLOW);
    }

    @Test
    void shadowDeniesWritesRunsAndNetworkAndLeavesTheRestToTheTable() {
        for (ActionCategory c :
                new ActionCategory[] {ActionCategory.WRITE, ActionCategory.EXECUTE, ActionCategory.NETWORK}) {
            assertThat(TrustCap.cap(TrustLevel.SHADOW, c, Decision.ALLOW)).isEqualTo(Decision.DENY);
            assertThat(TrustCap.proposes(TrustLevel.SHADOW, c, Decision.ALLOW)).isTrue();
            assertThat(TrustCap.proposes(TrustLevel.SHADOW, c, Decision.DENY)).isFalse();
        }
        for (ActionCategory c :
                new ActionCategory[] {ActionCategory.READ, ActionCategory.FLOOR42, ActionCategory.OTHER}) {
            assertThat(TrustCap.cap(TrustLevel.SHADOW, c, Decision.ALLOW)).isEqualTo(Decision.ALLOW);
            assertThat(TrustCap.proposes(TrustLevel.SHADOW, c, Decision.ALLOW)).isFalse();
        }
    }
}
