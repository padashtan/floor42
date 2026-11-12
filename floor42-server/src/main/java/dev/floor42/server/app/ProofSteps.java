package dev.floor42.server.app;

import dev.floor42.server.app.ProofViews.ProofStepResult;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Runs Floor42's steps of one recipe in order and judges each against what the producer said it must show (ADR-0058):
 * the exit code named, every expected text in the output. Stops at the first failure. Director steps never run here.
 */
final class ProofSteps {

    private ProofSteps() {}

    static List<ProofStepResult> run(List<ProofPlan.Assigned> plan, Function<String, BoundedProcess.Ran> shell) {
        List<ProofStepResult> out = new ArrayList<>();
        boolean failed = false;
        for (ProofPlan.Assigned a : plan) {
            ProofRecipe.Step s = a.step();
            if (a.runner().equals(ProofPlan.DIRECTOR)) {
                out.add(notRun(s, "DIRECTOR_STEP"));
            } else if (!s.runnable()) {
                out.add(notRun(s, "NOT_RUNNABLE"));
            } else if (failed) {
                out.add(notRun(s, "EARLIER_STEP_FAILED"));
            } else {
                ProofStepResult r = judge(s, shell.apply(s.command()));
                failed = r.outcome().equals("FAILED");
                out.add(r);
            }
        }
        return List.copyOf(out);
    }

    /** Whether the measure shows what the step expects; the first reason it does not. */
    static ProofStepResult judge(ProofRecipe.Step s, BoundedProcess.Ran r) {
        String failure = null;
        if (r.timedOut()) failure = "TIMED_OUT";
        else if (r.exitCode() == null) failure = "DID_NOT_START";
        else if (s.expectExit() != null && !s.expectExit().equals(r.exitCode())) failure = "EXIT_CODE";
        else if (!s.expectOutput().stream().allMatch(r.output()::contains)) failure = "OUTPUT_MISSING";
        return new ProofStepResult(
                s.index(),
                s.command(),
                failure == null ? "PASSED" : "FAILED",
                failure,
                r.exitCode(),
                r.timedOut(),
                r.durationMs(),
                r.output());
    }

    private static ProofStepResult notRun(ProofRecipe.Step s, String why) {
        return new ProofStepResult(s.index(), s.command(), "NOT_RUN", why, null, false, 0, "");
    }
}
