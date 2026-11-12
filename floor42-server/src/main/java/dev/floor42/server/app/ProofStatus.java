package dev.floor42.server.app;

import dev.floor42.server.app.ProofViews.ProofVerdictView;
import dev.floor42.server.app.ProofViews.ProofView;
import dev.floor42.server.app.ProofViews.ProofWorktree;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Where a delivery's proof stands, worst first (ADR-0058): no usable recipe, a step that failed, Floor42's steps not
 * run on the recipe and code as they are now, or every step Floor42 can run passed. A run, a report or a verdict about
 * another recipe or other code counts for nothing.
 */
final class ProofStatus {

    private ProofStatus() {}

    static String of(List<ProofWorktree> worktrees) {
        if (worktrees.stream().anyMatch(ProofStatus::unusable)) return ProofViews.NO_RECIPE;
        if (worktrees.stream().anyMatch(ProofStatus::failed)) return ProofViews.FAILED;
        if (worktrees.stream().anyMatch(ProofStatus::notRun)) return ProofViews.NOT_RUN;
        return ProofViews.PASSED;
    }

    /**
     * What an acceptance must be told: null when the delivery is proven (a current PROVEN verdict, every Floor42 step
     * passed) or there is no producer worktree to prove; else the first reason it is not.
     */
    static String unproven(ProofView v) {
        if (v.worktrees().isEmpty()) return null;
        if (!v.status().equals(ProofViews.PASSED)) return v.status();
        ProofVerdictView verdict = v.verdict();
        if (verdict == null || !verdict.current()) return "NO_VERDICT";
        return verdict.verdict().equals("PROVEN") ? null : "NOT_PROVEN";
    }

    /** Names this exact state: what is unproven, of which recipes and code, and which verdict. */
    static String token(String state, List<String> targets, ProofVerdictView verdict) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(String.valueOf(state).getBytes(StandardCharsets.UTF_8));
            for (String t : targets) sha.update(("\u0000" + t).getBytes(StandardCharsets.UTF_8));
            sha.update(("\u0000" + (verdict == null ? "" : verdict.verdict() + verdict.at()))
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /** Names one recipe and code (16 hex digits). */
    static String target(dev.floor42.domain.ProofTarget t) {
        return token("target", List.of(String.valueOf(t)), null);
    }

    /** Names every worktree's recipe and code, in a fixed order; an unknown one is named as such. */
    static String targets(List<dev.floor42.domain.ProofTarget> all) {
        return token("targets", all.stream().map(String::valueOf).sorted().toList(), null);
    }

    private static boolean unusable(ProofWorktree w) {
        return !w.state().equals(ProofRecipe.FOUND)
                || w.head() == null
                || !w.problems().isEmpty()
                || w.steps().stream().anyMatch(s -> !s.problems().isEmpty());
    }

    private static boolean failed(ProofWorktree w) {
        return w.lastRun() != null
                && !w.lastRun().stale()
                && w.lastRun().steps().stream().anyMatch(s -> s.outcome().equals("FAILED"));
    }

    private static boolean notRun(ProofWorktree w) {
        boolean floor42Steps = w.steps().stream().anyMatch(s -> s.runner().equals(ProofPlan.FLOOR42));
        return floor42Steps && (w.lastRun() == null || w.lastRun().stale());
    }
}
