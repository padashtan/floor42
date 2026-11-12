package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.app.ProofViews.ProofStepResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Who runs each step, and how Floor42 judges what it measured (ADR-0058). */
class ProofStepsTest {

    static ProofRecipe.Step step(int i, String command, Integer exit, List<String> output, boolean director) {
        return new ProofRecipe.Step(i, "step " + i, command, exit, output, director, List.of());
    }

    @Test
    void dockerADesktopOrAMarkSendTheStepAndEveryLaterOneToTheDirector() {
        List<ProofPlan.Assigned> plan = ProofPlan.assign(
                List.of(
                        step(1, "npm test", 0, List.of(), false),
                        step(2, "cd app && sudo docker compose up -d", 0, List.of(), false),
                        step(3, "echo after", 0, List.of(), false)),
                true);
        assertThat(plan).extracting(ProofPlan.Assigned::runner).containsExactly("FLOOR42", "DIRECTOR", "DIRECTOR");
        assertThat(plan)
                .extracting(ProofPlan.Assigned::reason)
                .containsExactly(null, "NEEDS_DOCKER", "AFTER_DIRECTOR_STEP");
        assertThat(ProofPlan.needs("xdg-open index.html")).isEqualTo("NEEDS_DESKTOP");
        for (String hidden : List.of(
                "env docker ps",
                "/usr/bin/docker ps",
                "FOO=1 sudo -E /usr/local/bin/podman ps",
                "env -i A=b kubectl get"))
            assertThat(ProofPlan.needs(hidden)).as(hidden).isEqualTo("NEEDS_DOCKER");
        assertThat(ProofPlan.needs("env A=b npm test")).isNull();
        assertThat(ProofPlan.assign(List.of(step(1, "ls", 0, List.of(), true)), true)
                        .getFirst()
                        .reason())
                .isEqualTo("MARKED_FOR_DIRECTOR");
    }

    @Test
    void withoutAWorkingSandboxEveryStepIsTheDirectorsAndNothingRuns() {
        List<ProofPlan.Assigned> plan = ProofPlan.assign(List.of(step(1, "ls", 0, List.of(), false)), false);
        assertThat(plan.getFirst().reason()).isEqualTo("SANDBOX_UNAVAILABLE");
        List<String> ran = new ArrayList<>();
        List<ProofStepResult> results = ProofSteps.run(plan, c -> {
            ran.add(c);
            return new BoundedProcess.Ran(0, false, 1, "");
        });
        assertThat(ran).isEmpty();
        assertThat(results.getFirst().failure()).isEqualTo("DIRECTOR_STEP");
    }

    @Test
    void aStepPassesOnlyWithTheExitCodeAndEveryExpectedText() {
        ProofRecipe.Step s = step(1, "curl", 0, List.of("UP", "ok"), false);
        assertThat(ProofSteps.judge(s, new BoundedProcess.Ran(0, false, 5, "status UP ok"))
                        .outcome())
                .isEqualTo("PASSED");
        assertThat(ProofSteps.judge(s, new BoundedProcess.Ran(7, false, 5, "UP ok"))
                        .failure())
                .isEqualTo("EXIT_CODE");
        assertThat(ProofSteps.judge(s, new BoundedProcess.Ran(0, false, 5, "UP"))
                        .failure())
                .isEqualTo("OUTPUT_MISSING");
        assertThat(ProofSteps.judge(s, new BoundedProcess.Ran(null, true, 5, ""))
                        .failure())
                .isEqualTo("TIMED_OUT");
        assertThat(ProofSteps.judge(s, new BoundedProcess.Ran(null, false, 0, ""))
                        .failure())
                .isEqualTo("DID_NOT_START");
    }

    @Test
    void theRunStopsAtTheFirstFailure() {
        List<ProofPlan.Assigned> plan = ProofPlan.assign(
                List.of(
                        step(1, "first", 0, List.of(), false),
                        step(2, "second", 0, List.of(), false),
                        step(3, "third", 0, List.of(), false)),
                true);
        List<String> ran = new ArrayList<>();
        List<ProofStepResult> results = ProofSteps.run(plan, c -> {
            ran.add(c);
            return new BoundedProcess.Ran(c.equals("second") ? 1 : 0, false, 1, "");
        });
        assertThat(ran).containsExactly("first", "second");
        assertThat(results).extracting(ProofStepResult::outcome).containsExactly("PASSED", "FAILED", "NOT_RUN");
        assertThat(results.get(2).failure()).isEqualTo("EARLIER_STEP_FAILED");
    }
}
