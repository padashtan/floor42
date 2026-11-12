package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.GALEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.workflow.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Checks measured by Floor42, and a budget enforced between sessions (spec sections 12, 15, 16). */
@ExtendWith(GoldenFixtureExtension.class)
class VerificationAndBudgetTest {

    final Fixture f = new Fixture(Origin.REAL);
    final MissionId m = realMission();
    final TicketId t = f.firstTicket(m);

    MissionId realMission() {
        MissionId id = ((MissionCreated) f.record(f.company
                                .missions()
                                .create(
                                        f.director(),
                                        "Fix the build",
                                        "Make mvn test pass",
                                        "C:/work/repo",
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId ticket = f.ticket(id, "Deliver: Fix the build");
        f.record(f.company.tickets().assign(f.director(), ticket, CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(GALEN), id));
        f.record(f.company.verification().setBudget(f.director(), id, null));
        f.record(f.company.missions().approvePlan(f.director(), id));
        f.record(f.company.tickets().start(f.agent(CHRIS), ticket));
        f.record(f.company
                .workshop()
                .prepareWorkspace(f.system(), id, CHRIS, "C:/ws/M-1-chris", "floor42/m-1-chris", "abc"));
        return id;
    }

    void session(String id, double cost) {
        f.record(f.company.sessions().start(f.system(), id, t, CHRIS, "claude-sonnet-5-5", "2.1.304"));
        f.record(f.company.sessions().end(f.system(), id, false, "completed", 3, 1000L, 100L, cost));
    }

    @Test
    void checksAreConfiguredByTheDirectorAndMeasuredByOffice() {
        assertThatThrownBy(() -> f.company.verification().checkChecksCanRun(f.director(), m, CHRIS))
                .hasMessageContaining("No checks configured");

        f.record(f.company.verification().configureChecks(f.director(), m, "  mvn -q test ", true));
        f.company.verification().checkChecksCanRun(f.director(), m, CHRIS);
        f.record(f.company
                .verification()
                .recordChecks(
                        f.system(),
                        m,
                        CHRIS,
                        "mvn -q test",
                        1,
                        false,
                        4_200,
                        "BUILD FAILURE",
                        "abc",
                        "after delivery"));

        Mission mission = f.company.missions().find(m).orElseThrow();
        assertThat(mission.checkCommand()).isEqualTo("mvn -q test");
        assertThat(mission.checksAutomatic()).isTrue();
        CheckRun run = f.company.verification().checkRuns(m).getFirst();
        assertThat(run.passed()).isFalse();
        assertThat(run.durationMs()).isEqualTo(4_200);
        assertThatThrownBy(() -> f.company
                        .verification()
                        .recordChecks(f.director(), m, CHRIS, "mvn test", 0, false, 1, "", "abc", "director"))
                .hasMessageContaining("Only Floor42");
        assertThatThrownBy(() -> f.company.verification().configureChecks(f.agent(CHRIS), m, "true", false))
                .hasMessageContaining("Only the director");
        assertThatThrownBy(() -> f.company.verification().configureChecks(f.director(), m, " ", true))
                .hasMessageContaining("need a command");
    }

    @Test
    void noChecksAreRecordedOnceTheMissionIsCancelled() {
        f.record(f.company.verification().configureChecks(f.director(), m, "mvn test", true));
        f.record(f.company.missions().cancel(f.director(), m, "wrong mission"));

        assertThatThrownBy(() -> f.company
                        .verification()
                        .recordChecks(f.system(), m, CHRIS, "mvn test", 1, false, 1, "", "abc", "after delivery"))
                .hasMessageContaining("is closed");
        assertThat(f.company.verification().checkRuns(m)).isEmpty();
    }

    @Test
    void checksNeverRunUnderARunningAgent() {
        f.record(f.company.verification().configureChecks(f.director(), m, "mvn test", false));
        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", "2.1.304"));

        assertThatThrownBy(() -> f.company.verification().checkChecksCanRun(f.director(), m, CHRIS))
                .hasMessageContaining("session is running");
    }

    @Test
    void theBudgetIsComparedWithEstimatedSpending() {
        assertThat(f.company.verification().budgetReached(m)).isFalse();
        f.record(f.company.verification().setBudget(f.director(), m, 0.10));
        session("s1", 0.06);
        assertThat(f.company.verification().budgetReached(m)).isFalse();
        assertThatThrownBy(() -> f.company.verification().reportBudgetReached(f.system(), m))
                .hasMessageContaining("budget left");

        session("s2", 0.05);

        assertThat(f.company.verification().spentUsdEstimated(m))
                .isEqualTo(0.11, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(f.company.verification().budgetReached(m)).isTrue();
    }

    @Test
    void aSessionThatEndedWithoutACostCountsForTheCapItRanUnder() {
        f.record(f.company.verification().setBudget(f.director(), m, 0.50));
        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", "2.1.304"));
        f.record(f.company.sessions().end(f.system(), "s1", true, "interrupted", null, null, null, null, 0.30));

        AgentSession killed = f.company.sessions().find("s1").orElseThrow();
        assertThat(killed.costUsdEstimated())
                .as("nothing was reported, nothing is claimed")
                .isNull();
        assertThat(killed.costUsdBound()).isEqualTo(0.30);
        assertThat(f.company.verification().spentUsdEstimated(m))
                .as("a bound is never added to the estimates")
                .isZero();
        assertThat(f.company.verification().spentUsdBound(m)).isEqualTo(0.30);
        assertThat(f.company.verification().sessionsBounded(m)).isEqualTo(1);
        assertThat(f.company.verification().spentUsdCounted(m)).isEqualTo(0.30);
        assertThat(f.company.verification().budgetReached(m)).isFalse();

        f.record(f.company.sessions().start(f.system(), "s2", t, CHRIS, "claude-sonnet-5-5", "2.1.304"));
        f.record(f.company.sessions().end(f.system(), "s2", true, "failed", null, null, null, null, 0.30));

        assertThat(f.company.verification().budgetReached(m))
                .as("two killed sessions spend the cap")
                .isTrue();
    }

    @Test
    void theCapASessionRunsUnderIsJournaledAtItsStart() {
        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", "2.1.304", 0.25));
        f.record(f.company.sessions().start(f.system(), "s2", t, CHRIS, "claude-sonnet-5-5", "2.1.304"));

        assertThat(f.company.sessions().find("s1").orElseThrow().capUsd()).isEqualTo(0.25);
        assertThat(f.company.sessions().find("s2").orElseThrow().capUsd())
                .as("none given (a replay, an older journal): none is claimed")
                .isNull();
    }

    /** The mission was cancelled while the process ran: no session may start, yet the launch is recorded and counts. */
    @Test
    void aLaunchThatNeverNamedItsSessionCountsForItsCapEvenOnACancelledMission() {
        f.record(f.company.missions().cancel(f.director(), m, "enough"));

        f.record(f.company.sessions().recordUnnamedLaunch(f.system(), "M-1:launch-1", t, CHRIS, null, 0.20));

        AgentSession launch = f.company.sessions().find("M-1:launch-1").orElseThrow();
        assertThat(launch.isOpen()).isFalse();
        assertThat(launch.failed()).isTrue();
        assertThat(launch.model()).isEqualTo("not reported");
        assertThat(launch.costUsdEstimated()).isNull();
        assertThat(f.company.verification().spentUsdBound(m)).isEqualTo(0.20);
        assertThatThrownBy(() ->
                        f.company.sessions().recordUnnamedLaunch(f.director(), "M-1:launch-2", t, CHRIS, null, 0.2))
                .as("the runtime's record only")
                .isInstanceOf(DomainException.class);
    }

    @Test
    void theBudgetQuestionSaysTheEstimatesAndTheBoundsApart() {
        f.record(f.company.verification().setBudget(f.director(), m, 0.50));
        session("s1", 0.04);
        f.record(f.company.sessions().start(f.system(), "s2", t, CHRIS, "claude-sonnet-5-5", "2.1.304"));
        f.record(f.company.sessions().end(f.system(), "s2", true, "interrupted", null, null, null, null, 0.46));

        f.record(f.company.verification().reportBudgetReached(f.system(), m));

        Question q = f.company.desk().of(m).stream()
                .filter(x -> x.kind() == QuestionKind.BUDGET_REACHED)
                .findFirst()
                .orElseThrow();
        assertThat(q.context())
                .startsWith("Spending counted 0.5000 USD of a 0.5000 USD budget: estimated 0.0400 USD by the runtime"
                        + " + up to 0.4600 USD for 1 session(s) with no reported cost")
                .doesNotContain("Estimated spending");
        assertThat(q.phrase().code()).isEqualTo("budgetBounded");
        assertThat(q.phrase().params())
                .containsEntry("estimated", "0.0400")
                .containsEntry("bound", "0.4600")
                .containsEntry("bounded", "1");
    }

    @Test
    void aReportedCostWinsOverTheBound() {
        f.record(f.company.verification().setBudget(f.director(), m, 1.00));
        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", "2.1.304"));
        f.record(f.company.sessions().end(f.system(), "s1", false, "completed", 3, 10L, 10L, 0.04, 0.30));

        assertThat(f.company.verification().spentUsdEstimated(m)).isEqualTo(0.04);
        assertThat(f.company.verification().spentUsdBound(m)).isZero();
        assertThat(f.company.verification().sessionsBounded(m)).isZero();
    }

    @Test
    void tenSessionsOfTenCentsReachADollar() {
        f.record(f.company.verification().setBudget(f.director(), m, 1.00));
        for (int i = 1; i <= 9; i++) session("s" + i, 0.10);
        assertThat(f.company.verification().budgetReached(m)).isFalse();

        session("s10", 0.10);

        // A plain loop of doubles gives 0.9999999999999999 (the stream's compensated sum happens to land on 1.0).
        assertThat(f.company.verification().budgetReached(m)).isTrue();
        assertThat(f.company.verification().budgetLeftUsd(m)).contains(0.0);
        assertThat(f.company.verification().spentUsdEstimated(m)).isEqualTo(1.0);
    }

    @Test
    void seventyAndTenCentsReachEightyCents() {
        f.record(f.company.verification().setBudget(f.director(), m, 0.80));
        session("s1", 0.70);

        session("s2", 0.10);

        // In double, 0.7 + 0.1 is 0.7999999999999999, even summed exactly: under the 0.80 cap, a third session would
        // have started over it.
        assertThat(f.company.verification().budgetReached(m)).isTrue();
        assertThat(f.company.verification().budgetLeftUsd(m)).contains(0.0);
    }

    @Test
    void theDirectorRaisesTheBudgetOrStops() {
        f.record(f.company.verification().setBudget(f.director(), m, 0.10));
        session("s1", 0.12);

        f.record(f.company.verification().reportBudgetReached(f.system(), m));
        assertThat(f.company.verification().reportBudgetReached(f.system(), m))
                .as("asked once")
                .isEmpty();
        Question q = f.company.desk().open().getFirst();
        assertThat(q.kind()).isEqualTo(QuestionKind.BUDGET_REACHED);
        assertThat(q.context()).contains("0.1200 USD of a 0.1000 USD budget");

        f.record(f.company.desk().answer(f.director(), q.id(), OptionCode.RAISE_BUDGET.label()));

        // Raised from what was really spent, so one answer is enough to start again.
        assertThat(f.company.missions().find(m).orElseThrow().budgetUsd()).isEqualTo(0.18);
        assertThat(f.company.verification().budgetReached(m)).isFalse();
    }

    @Test
    void stoppingKeepsTheBudgetAndRemovingItLiftsTheLimit() {
        f.record(f.company.verification().setBudget(f.director(), m, 0.10));
        session("s1", 0.12);
        f.record(f.company.verification().reportBudgetReached(f.system(), m));
        f.record(f.company
                .desk()
                .answer(f.director(), f.company.desk().open().getFirst().id(), "Stop here"));
        assertThat(f.company.verification().budgetReached(m)).isTrue();

        f.record(f.company.verification().setBudget(f.director(), m, null));

        assertThat(f.company.verification().budgetReached(m)).isFalse();
        assertThatThrownBy(() -> f.company.verification().setBudget(f.director(), m, -1.0))
                .hasMessageContaining("positive");
    }

    MissionId plannedRealMission() {
        MissionId id = ((MissionCreated) f.record(f.company
                                .missions()
                                .create(f.director(), "Docs", "Write the docs", "C:/work/docs", Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        f.record(f.company.tickets().assign(f.director(), f.ticket(id, "Write"), CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(GALEN), id));
        return id;
    }

    @Test
    void aRealPlanIsNotApprovedBeforeItsCapIsDecided() {
        MissionId id = plannedRealMission();
        assertThat(f.company.missions().find(id).orElseThrow().capDecided()).isFalse();

        assertThatThrownBy(() -> f.company.missions().approvePlan(f.director(), id))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("no budget")
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo("CAP_NEEDED");

        f.record(f.company.verification().setBudget(f.director(), id, 2.5));
        f.record(f.company.missions().approvePlan(f.director(), id));
        assertThat(f.company.missions().find(id).orElseThrow().status()).isEqualTo(MissionStatus.IN_PROGRESS);
    }

    @Test
    void noCapIsAnExplicitRecordedChoice() {
        MissionId id = plannedRealMission();
        f.record(f.company.verification().setBudget(f.director(), id, null));

        f.record(f.company.missions().approvePlan(f.director(), id));

        Mission replayed = Company.replay(f.journal).missions().find(id).orElseThrow();
        assertThat(replayed.capDecided()).isTrue();
        assertThat(replayed.budgetUsd()).isNull();
        assertThat(f.journal)
                .extracting(e -> e.event())
                .contains(new dev.floor42.domain.event.MissionEvent.BudgetSet(id, null));
    }

    @Test
    void aSimulatedPlanCostsNothingAndNeedsNoCap() {
        Fixture sim = new Fixture();
        MissionId id = sim.mission();
        sim.record(sim.company.tickets().assign(sim.director(), sim.ticket(id, "Play"), CHRIS));
        sim.record(sim.company.missions().submitPlan(sim.agent(GALEN), id));

        sim.record(sim.company.missions().approvePlan(sim.director(), id));

        assertThat(sim.company.missions().find(id).orElseThrow().status()).isEqualTo(MissionStatus.IN_PROGRESS);
    }

    @Test
    void verificationAndBudgetSurviveAReplay() {
        f.record(f.company.verification().configureChecks(f.director(), m, "mvn test", false));
        f.record(f.company
                .verification()
                .recordChecks(f.system(), m, CHRIS, "mvn test", 0, false, 10, "ok", "abc", "director"));
        f.record(f.company.verification().setBudget(f.director(), m, 1.0));

        Company replayed = Company.replay(f.journal);

        assertThat(replayed.verification().checkRuns(m))
                .singleElement()
                .satisfies(r -> assertThat(r.passed()).isTrue());
        assertThat(replayed.missions().find(m).orElseThrow().budgetUsd()).isEqualTo(1.0);
        assertThat(replayed.missions().find(m).orElseThrow().checkCommand()).isEqualTo("mvn test");
    }
}
