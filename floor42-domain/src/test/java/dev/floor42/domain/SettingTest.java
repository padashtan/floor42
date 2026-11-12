package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Company-wide settings: only the director changes them, only to an acceptable value, and every change is journaled. */
@ExtendWith(GoldenFixtureExtension.class)
class SettingTest {

    final Fixture f = new Fixture();

    @Test
    void theDirectorChangesASettingAndCanGoBackToTheDefault() {
        assertThat(f.company.preferences().setting(Setting.AGENT_LANGUAGE)).isEmpty();
        f.record(f.company.preferences().changeSetting(f.director(), Setting.AGENT_LANGUAGE, " FR "));
        f.record(f.company.preferences().changeSetting(f.director(), Setting.PLAN_LIMIT_THRESHOLD, "0.95"));
        assertThat(f.company.preferences().changeSetting(f.director(), Setting.AGENT_LANGUAGE, "fr"))
                .as("the value in force again records nothing")
                .isEmpty();

        Company replayed = Company.replay(f.journal);
        assertThat(replayed.preferences().setting(Setting.AGENT_LANGUAGE)).contains("fr");
        assertThat(replayed.preferences().setting(Setting.PLAN_LIMIT_THRESHOLD)).contains("0.95");

        f.record(f.company.preferences().changeSetting(f.director(), Setting.AGENT_LANGUAGE, null));
        assertThat(Company.replay(f.journal).preferences().setting(Setting.AGENT_LANGUAGE))
                .isEmpty();
    }

    @Test
    void onlyAcceptableValuesAndOnlyTheDirector() {
        assertThatThrownBy(() -> f.company.preferences().changeSetting(f.agent(CHRIS), Setting.PARALLEL_SESSIONS, "4"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> f.company.preferences().changeSetting(f.director(), Setting.PARALLEL_SESSIONS, "9"))
                .hasMessageContaining("one of");
        assertThatThrownBy(
                        () -> f.company.preferences().changeSetting(f.director(), Setting.PLAN_LIMIT_THRESHOLD, "0.2"))
                .hasMessageContaining("between");
        assertThatThrownBy(
                        () -> f.company.preferences().changeSetting(f.director(), Setting.PLAN_LIMIT_THRESHOLD, "most"))
                .hasMessageContaining("fraction");
        assertThatThrownBy(() -> f.company.preferences().changeSetting(f.director(), Setting.RETRO_MODEL, "gpt"))
                .hasMessageContaining("one of");
    }

    @Test
    void aSessionCapIsAnAmountInRange() {
        f.record(f.company.preferences().changeSetting(f.director(), Setting.SESSION_BUDGET_USD, " 2.5 "));
        assertThat(f.company.preferences().setting(Setting.SESSION_BUDGET_USD)).contains("2.5");
        for (String bad : java.util.List.of("0.01", "101", "two")) {
            assertThatThrownBy(
                            () -> f.company.preferences().changeSetting(f.director(), Setting.SESSION_BUDGET_USD, bad))
                    .isInstanceOf(DomainException.class);
        }
    }

    @Test
    void aMissionsDefaultCapIsAnAmountInRangeOrNone() {
        f.record(f.company.preferences().changeSetting(f.director(), Setting.MISSION_BUDGET_USD, " 5 "));
        assertThat(Company.replay(f.journal).preferences().setting(Setting.MISSION_BUDGET_USD))
                .contains("5.0");
        f.record(f.company.preferences().changeSetting(f.director(), Setting.MISSION_BUDGET_USD, "NONE"));
        assertThat(f.company.preferences().setting(Setting.MISSION_BUDGET_USD)).contains(Setting.NO_DEFAULT);
        for (String bad : java.util.List.of("0.01", "1001", "five", "NaN", "-3")) {
            assertThatThrownBy(
                            () -> f.company.preferences().changeSetting(f.director(), Setting.MISSION_BUDGET_USD, bad))
                    .isInstanceOf(DomainException.class);
        }
    }

    @Test
    void theThemeIsOneOfFourDecades() {
        f.record(f.company.preferences().changeSetting(f.director(), Setting.OFFICE_THEME, "2020s"));
        assertThat(f.company.preferences().setting(Setting.OFFICE_THEME)).contains("2020s");
        assertThatThrownBy(() -> f.company.preferences().changeSetting(f.director(), Setting.OFFICE_THEME, "1970s"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void theDefaultPermissionTableKeepsTheDirectorsLinesAndGoesBack() {
        f.record(f.company
                .preferences()
                .setDefaultPermission(f.director(), "Bash", ActionCategory.EXECUTE, Decision.DENY));
        assertThat(Company.replay(f.journal).preferences().defaultPermission("Bash"))
                .contains(Decision.DENY);
        assertThat(f.company
                        .preferences()
                        .setDefaultPermission(f.director(), "Bash", ActionCategory.EXECUTE, Decision.DENY))
                .as("the line in force again records nothing")
                .isEmpty();
        f.record(f.company.preferences().setDefaultPermission(f.director(), "Bash", ActionCategory.EXECUTE, null));
        assertThat(Company.replay(f.journal).preferences().defaultPermission("Bash"))
                .isEmpty();
        assertThatThrownBy(() -> f.company
                        .preferences()
                        .setDefaultPermission(f.agent(CHRIS), "Bash", ActionCategory.EXECUTE, Decision.ALLOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void onlyOfficeRecordsAMerge() {
        assertThatThrownBy(() -> f.company
                        .workshop()
                        .recordMerge(f.director(), new TicketId("T-1"), CHRIS, java.util.List.of("b"), true, null))
                .isInstanceOf(DomainException.class);
    }
}
