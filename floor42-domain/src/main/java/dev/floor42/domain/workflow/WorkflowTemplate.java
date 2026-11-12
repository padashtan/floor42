package dev.floor42.domain.workflow;

import dev.floor42.domain.workflow.Workflow.Step;
import java.util.List;

/**
 * Floor42's own starting points (ADR-0052): constants of the code, never journaled, like {@link Workflow#BUILT_IN}. A
 * later build may revise one (its {@code revision} goes up); the copies the company saved and the pins never change.
 * A template that needs schema version 3 (a task, ADR-0061) is offered only where that version can be written.
 *
 * @param key stable, what the screen translates its title by
 * @param revision starts at 1
 */
public record WorkflowTemplate(String key, int revision, Workflow workflow) {

    /** Code change: what Floor42 always did. */
    public static final WorkflowTemplate CODE_CHANGE = new WorkflowTemplate(
            "code-change",
            1,
            new Workflow(
                    "Code change",
                    List.of(
                            new Step.Plan(),
                            new Step.Approval(),
                            new Step.Committee(),
                            new Step.Produce(),
                            new Step.Review(),
                            new Step.Accept(),
                            new Step.Retro())));

    /** Light change: a small plan, no committee, no retrospective. */
    public static final WorkflowTemplate LIGHT_CHANGE = new WorkflowTemplate(
            "light-change",
            1,
            new Workflow(
                    "Light change",
                    List.of(
                            new Step.Plan(3),
                            new Step.Approval(),
                            new Step.Produce(),
                            new Step.Review(),
                            new Step.Accept())));

    /** Decision: approval, a committee, acceptance. */
    public static final WorkflowTemplate DECISION = new WorkflowTemplate(
            "decision",
            1,
            new Workflow("Decision", List.of(new Step.Approval(), new Step.Committee(), new Step.Accept())));

    /**
     * Document production (schema version 2, ADR-0052): plan, approval, production, review, then an advisory
     * proofreading check whose findings reach the producer and the director's acceptance without reopening anything.
     */
    public static final WorkflowTemplate DOCUMENT_PRODUCTION = new WorkflowTemplate(
            "document-production",
            1,
            new Workflow(
                    "Document production",
                    List.of(
                            new Step.Plan(),
                            new Step.Approval(),
                            new Step.Produce(),
                            new Step.Review(),
                            new Step.Check(
                                    "proofreading",
                                    "Proofreading",
                                    "Read every document produced for spelling, grammar, clarity and consistent terms."
                                            + " List each problem with its file and the passage concerned.",
                                    "A document goes out under the company's name: its errors cost the reader's trust.",
                                    "The problems found, file by file, each with a suggested correction, or a line"
                                            + " saying none were found.",
                                    false),
                            new Step.Accept())));

    /**
     * Research (schema version 3, ADR-0061): approval, then one researcher's task, not reviewed, then the acceptance. The
     * researcher has the web tools the mission's table allows and no repository.
     */
    public static final WorkflowTemplate RESEARCH = new WorkflowTemplate(
            "research",
            1,
            new Workflow(
                    "Research",
                    List.of(
                            new Step.Approval(),
                            new Step.Task(
                                    "research",
                                    "Research",
                                    "Research the objective on the web. Cite the source next to every claim, say where"
                                            + " sources disagree, and mark what could not be confirmed.",
                                    "The director needs sourced facts before deciding.",
                                    "A report of the findings with a source for each claim.",
                                    Step.Task.Slot.BEFORE_ACCEPTANCE,
                                    dev.floor42.domain.WorkRole.RESEARCHER,
                                    false),
                            new Step.Accept())));

    /**
     * Review only (schema version 3, ADR-0061): approval, then one reviewer's task, read by another reviewer, then the
     * acceptance. The reviewer reads and changes nothing.
     */
    public static final WorkflowTemplate REVIEW_ONLY = new WorkflowTemplate(
            "review-only",
            1,
            new Workflow(
                    "Review only",
                    List.of(
                            new Step.Approval(),
                            new Step.Task(
                                    "review",
                                    "Review",
                                    "Read the repository against the objective. List each finding with its file and"
                                            + " the passage concerned.",
                                    "The director wants an independent reading before anything changes.",
                                    "The findings, file by file, or a line saying none were found.",
                                    Step.Task.Slot.BEFORE_ACCEPTANCE,
                                    dev.floor42.domain.WorkRole.REVIEWER,
                                    true),
                            new Step.Accept())));

    /** The templates this build knows, in the order the library lists them; the screen shows those it can save. */
    public static final List<WorkflowTemplate> ALL =
            List.of(CODE_CHANGE, LIGHT_CHANGE, DECISION, DOCUMENT_PRODUCTION, RESEARCH, REVIEW_ONLY);
}
