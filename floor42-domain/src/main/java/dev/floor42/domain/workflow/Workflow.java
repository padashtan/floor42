package dev.floor42.domain.workflow;

import dev.floor42.domain.WorkRole;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The steps a mission goes through, in order (ADR-0046). {@link #BUILT_IN} is the one Floor42 always followed, written
 * down; the director writes others, and a mission pins the one it follows as a document ({@link WorkflowDocument},
 * ADR-0050).
 *
 * @param name how the director calls it
 * @param steps in the order a mission meets them
 * @param mode how the team works (ADR-0062): the loop, or the task graph (schema version 4)
 */
public record Workflow(String name, List<Step> steps, Mode mode) {

    /** How the team works on a mission: chosen at its creation, pinned with the workflow, never changed after. */
    public enum Mode {
        LOOP,
        GRAPH
    }

    /** A workflow of the loop, which every document before schema version 4 describes. */
    public Workflow(String name, List<Step> steps) {
        this(name, steps, Mode.LOOP);
    }

    /** One step of a workflow, with its settings (ADR-0050). */
    public sealed interface Step {

        /**
         * The mission's plan is drafted: one ticket, the planner's tickets, or a committee's decision ticket.
         *
         * @param maxTickets the most tickets the planner may propose, 1 to {@link #MAX_TICKETS}
         */
        record Plan(int maxTickets) implements Step {
            /** Most tickets a planner may propose: more is a plan the director cannot weigh ticket by ticket. */
            public static final int MAX_TICKETS = 8;

            public Plan {
                if (maxTickets < 1 || maxTickets > MAX_TICKETS) {
                    throw new IllegalArgumentException(
                            "A plan holds 1 to " + MAX_TICKETS + " tickets, not " + maxTickets);
                }
            }

            public Plan() {
                this(MAX_TICKETS);
            }
        }

        /** The director approves the plan, deciding its cap: nothing runs before. */
        record Approval() implements Step {}

        /** A committee analyses, debates and records; the director rules (ADR-0015). Only when convened. */
        record Committee() implements Step {}

        /** A developer produces each ticket in its own worktree. */
        record Produce() implements Step {}

        /** An independent reviewer reads each produced ticket and gives a verdict (ADR-0017). */
        record Review() implements Step {}

        /**
         * A check the director wrote (ADR-0052, schema version 2): run on each produced ticket after its review
         * validates, by a reviewer, with a reviewer's permissions on the mission. Its texts are data for the session's
         * brief, never a permission; the step holds no key that maps to a tool, a rule or a scope.
         *
         * @param key a slug unique in the workflow, never shown, never changed once saved
         * @param title 1 to 60 characters
         * @param does what the check does, 1 to 2,000 characters
         * @param why why it is there, 1 to 500 characters
         * @param handsOver what it must hand over, 1 to 500 characters: its session's acceptance criterion
         * @param blocks true: a verdict that does not validate reopens the ticket, as the review does; false:
         *     advisory, the verdict is recorded and the next step runs
         */
        record Check(String key, String title, String does, String why, String handsOver, boolean blocks)
                implements Step {
            /** Most checks a workflow holds: each one is one more paid session per ticket. */
            public static final int MAX = 4;

            public Check {
                requireKey(key);
                StepText.require(StepText.Field.TITLE, title);
                StepText.require(StepText.Field.DOES, does);
                StepText.require(StepText.Field.WHY, why);
                StepText.require(StepText.Field.HANDS_OVER, handsOver);
            }

            /** The role that runs it: a reviewer, the only runner a check is offered (the director's answer 2). */
            public WorkRole runBy() {
                return WorkRole.REVIEWER;
            }
        }

        /**
         * A task the director wrote (ADR-0061, schema version 3): done once per mission, as a ticket of its own that the
         * plan's approval opens, by a developer, a reviewer or a researcher. Its texts are data for the session's brief,
         * never a permission: the session's tools are its runner's role filtered by the mission's table.
         *
         * @param slot where it sits in Floor42's order: before production, or before the acceptance
         * @param runBy the role that runs it: developer, reviewer or researcher
         * @param reviewed whether a reviewer reads the ticket as any other; always true for a developer, who writes
         */
        record Task(
                String key,
                String title,
                String does,
                String why,
                String handsOver,
                Slot slot,
                WorkRole runBy,
                boolean reviewed)
                implements Step {
            /** Most tasks a workflow holds: each one is at least one more paid session. */
            public static final int MAX = 4;

            /** The two places of Floor42's order where the director's tasks go. */
            public enum Slot {
                BEFORE_PRODUCTION,
                BEFORE_ACCEPTANCE
            }

            public Task {
                requireKey(key);
                StepText.require(StepText.Field.TITLE, title);
                StepText.require(StepText.Field.DOES, does);
                StepText.require(StepText.Field.WHY, why);
                StepText.require(StepText.Field.HANDS_OVER, handsOver);
                Objects.requireNonNull(slot, "slot");
                if (runBy != WorkRole.DEVELOPER && runBy != WorkRole.REVIEWER && runBy != WorkRole.RESEARCHER) {
                    throw new StepRefused(
                            "STEP_RUNNER_NOT_ALLOWED", "A task is run by a developer, a reviewer or a researcher");
                }
                if (runBy == WorkRole.DEVELOPER && !reviewed) {
                    throw new StepRefused("WRITING_STEP_UNREVIEWED", "A developer's task writes: it is reviewed");
                }
            }
        }

        /** The director accepts the produced work in the workshop, and closes the mission. */
        record Accept() implements Step {}

        /** Those who worked on the ended mission hold its retrospective (ADR-0020). */
        record Retro() implements Step {}
    }

    /**
     * What Floor42 does without a written workflow: plan, approval, a committee when one is convened, then each ticket
     * produced and reviewed, the director's acceptance, the retrospective.
     */
    public static final Workflow BUILT_IN = new Workflow(
            "built-in",
            List.of(
                    new Step.Plan(),
                    new Step.Approval(),
                    new Step.Committee(),
                    new Step.Produce(),
                    new Step.Review(),
                    new Step.Accept(),
                    new Step.Retro()));

    /**
     * What a workflow must hold until the director can write one (step 3, ADR-0046):
     *
     * <ul>
     *   <li>the steps in Floor42's order (plan, approval, committee, produce, review, accept, retro), each once;
     *   <li>the director's approval and the director's acceptance: no workflow runs unapproved or closes on its own;
     *   <li>produced code is reviewed (the director's rule, 2026-11-01). The director's acceptance standing in for the
     *       review is decided but not built: until the interpreter drives it, a workflow that produces without a
     *       review is refused, rather than accepted and left with tickets nobody takes;
     *   <li>a review reads produced work: no review without production;
     *   <li>a workflow that produces nothing is a decision workflow: it holds a committee (ADR-0050);
     *   <li>the director's checks come after the review, in the director's order, read produced work, at most 4, each
     *       key once (ADR-0052, schema version 2).
     * </ul>
     *
     * Any change of what this constructor accepts comes with a new schema version of {@link WorkflowDocument}.
     */
    public Workflow {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mode, "mode");
        require(!name.isBlank(), "A workflow has a name");
        steps = List.copyOf(steps);
        for (int i = 1; i < steps.size(); i++) {
            boolean checks = (steps.get(i) instanceof Step.Check && steps.get(i - 1) instanceof Step.Check)
                    || (steps.get(i) instanceof Step.Task && steps.get(i - 1) instanceof Step.Task);
            int now = rank(steps.get(i));
            int before = rank(steps.get(i - 1));
            if (now < before || (now == before && !checks)) {
                throw new IllegalArgumentException("Steps come once each, in Floor42's order: " + kind(steps.get(i - 1))
                        + " cannot come before " + kind(steps.get(i)));
            }
        }
        require(has(steps, Step.Approval.class), "Every workflow has the director's approval");
        require(has(steps, Step.Accept.class), "Every workflow has the director's acceptance");
        require(
                !has(steps, Step.Produce.class) || has(steps, Step.Review.class),
                "Produced work is reviewed (the director's acceptance in its place is not built yet)");
        require(!has(steps, Step.Review.class) || has(steps, Step.Produce.class), "A review reads produced work");
        require(
                has(steps, Step.Produce.class) || has(steps, Step.Committee.class) || has(steps, Step.Task.class),
                "A workflow that produces nothing holds a committee");
        checks(steps);
        tasks(steps);
        require(mode == Mode.LOOP || has(steps, Step.Plan.class), "The graph mode needs the plan step");
    }

    /** The review rejections after which the director is asked: 2 (in graph mode, counted with the checks'). */
    public int reviewRejectionCap() {
        return 2;
    }

    /** The rejections of one blocking check after which the director is asked: 3 in the loop, 2 in graph mode. */
    public int checkRejectionCap() {
        return graph() ? 2 : 3;
    }

    /**
     * Graph mode (ADR-0062): a node gets one rework in all, so the review's and every check's rejections count
     * together; the second rejection of any kind asks the director. The loop counts each apart.
     */
    public boolean reworksTogether() {
        return graph();
    }

    /** Whether the planner draws the mission's task graph (ADR-0062). */
    public boolean graph() {
        return mode == Mode.GRAPH;
    }

    /**
     * The workflow as a node of the graph meets it (ADR-0062): the review alone on a node that is not high risk, none
     * of the director's checks. The loop and a high-risk node meet it whole.
     */
    public Workflow forNode(boolean highRisk) {
        if (!graph() || highRisk) return this;
        return new Workflow(
                name, steps.stream().filter(s -> !(s instanceof Step.Check)).toList(), mode);
    }

    /** The same workflow in graph mode. */
    public Workflow asGraph() {
        return new Workflow(name, steps, Mode.GRAPH);
    }

    /** The director's checks (ADR-0052): after the review, each key once, at most {@link Step.Check#MAX}. */
    private static void checks(List<Step> steps) {
        List<Step.Check> checks = checksOf(steps);
        if (checks.isEmpty()) return;
        if (!has(steps, Step.Produce.class)) {
            throw new StepRefused(
                    "STEP_NEEDS_PRODUCTION", "A check reads produced work: the workflow produces nothing");
        }
        if (checks.size() > Step.Check.MAX) {
            throw new StepRefused("TOO_MANY_STEPS", "At most " + Step.Check.MAX + " checks in a workflow");
        }
    }

    /** The director's tasks (ADR-0061): at most 4, a task before production needs production, keys unique overall. */
    private static void tasks(List<Step> steps) {
        List<Step.Task> tasks = steps.stream()
                .filter(Step.Task.class::isInstance)
                .map(Step.Task.class::cast)
                .toList();
        if (tasks.stream().anyMatch(t -> t.slot() == Step.Task.Slot.BEFORE_PRODUCTION)
                && !has(steps, Step.Produce.class)) {
            throw new StepRefused("STEP_NEEDS_PRODUCTION", "A task before production needs production");
        }
        if (tasks.size() > Step.Task.MAX) {
            throw new StepRefused("TOO_MANY_STEPS", "At most " + Step.Task.MAX + " tasks in a workflow");
        }
        List<String> keys =
                steps.stream().map(Workflow::keyOf).filter(Objects::nonNull).toList();
        if (keys.stream().distinct().count() < keys.size()) {
            throw new StepRefused("STEP_KEY_TAKEN", "Two steps of the workflow have the same key");
        }
    }

    private static String keyOf(Step s) {
        return switch (s) {
            case Step.Check c -> c.key();
            case Step.Task t -> t.key();
            default -> null;
        };
    }

    private static List<Step.Check> checksOf(List<Step> steps) {
        return steps.stream()
                .filter(Step.Check.class::isInstance)
                .map(Step.Check.class::cast)
                .toList();
    }

    /** Whether a mission of this workflow waits for the approval with no ticket: tasks, and no plan or production. */
    public boolean opensOnStepApproval() {
        return has(Step.Task.class) && !has(Step.Produce.class) && !has(Step.Committee.class);
    }

    /** The director's tasks, in their run order; empty for a workflow without any. */
    public List<Step.Task> tasks() {
        return steps.stream()
                .filter(Step.Task.class::isInstance)
                .map(Step.Task.class::cast)
                .toList();
    }

    /** The task of this key, if the workflow holds it. */
    public java.util.Optional<Step.Task> task(String key) {
        return tasks().stream().filter(t -> t.key().equals(key)).findFirst();
    }

    /** The director's checks, in their run order; empty for a workflow of Floor42's steps only. */
    public List<Step.Check> checks() {
        return checksOf(steps);
    }

    /** The same steps under another name. */
    public Workflow renamed(String other) {
        return new Workflow(other, steps, mode);
    }

    /** Whether a committee's decision can go to production: only then may the director adopt it for implementation. */
    public boolean producesAfterCommittees() {
        return follows(Step.Produce.class, Step.Committee.class);
    }

    /** The plan step's limit, or the default when the workflow has no plan step. */
    public int maxTickets() {
        for (Step s : steps) if (s instanceof Step.Plan p) return p.maxTickets();
        return Step.Plan.MAX_TICKETS;
    }

    /** Where a step stands in Floor42's order. */
    private static int rank(Step step) {
        return switch (step) {
            case Step.Plan _ -> 1;
            case Step.Approval _ -> 2;
            case Step.Committee _ -> 3;
            case Step.Task t when t.slot() == Step.Task.Slot.BEFORE_PRODUCTION -> 4;
            case Step.Produce _ -> 5;
            case Step.Review _ -> 6;
            case Step.Check _ -> 7;
            case Step.Task _ -> 8;
            case Step.Accept _ -> 9;
            case Step.Retro _ -> 10;
        };
    }

    private static String kind(Step step) {
        return step.getClass().getSimpleName();
    }

    private static final Pattern KEY =
            Pattern.compile("(?!.*--)[a-z0-9][a-z0-9-]*(?<!-)"); // a slug: no leading, trailing or doubled hyphen

    private static void requireKey(String key) {
        if (key == null || key.length() > 40 || !KEY.matcher(key).matches()) {
            throw new StepRefused("STEP_KEY_INVALID", "A step's key is a slug of 1 to 40 characters");
        }
    }

    private static void require(boolean holds, String why) {
        if (!holds) throw new IllegalArgumentException(why);
    }

    /** Whether the workflow holds a step of this kind. */
    public boolean has(Class<? extends Step> kind) {
        return has(steps, kind);
    }

    /** Whether {@code later} comes after {@code earlier}, both being in the workflow. */
    public boolean follows(Class<? extends Step> later, Class<? extends Step> earlier) {
        int a = indexOf(earlier);
        int b = indexOf(later);
        return a >= 0 && b > a;
    }

    private int indexOf(Class<? extends Step> kind) {
        for (int i = 0; i < steps.size(); i++) if (kind.isInstance(steps.get(i))) return i;
        return -1;
    }

    private static boolean has(List<Step> steps, Class<? extends Step> kind) {
        return steps.stream().anyMatch(kind::isInstance);
    }
}
