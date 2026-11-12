package dev.floor42.server.api;

import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.workflow.CompanyWorkflow;
import dev.floor42.domain.workflow.FollowedVersion;
import dev.floor42.domain.workflow.MissionWorkflow;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.WorkflowDocument;
import dev.floor42.domain.workflow.WorkflowFile;
import dev.floor42.domain.workflow.WorkflowProposal;
import dev.floor42.domain.workflow.WorkflowTemplate;
import dev.floor42.domain.workflow.WorkflowVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Workflows as the API shows them (ADR-0050): a step by its schema name and its settings. */
public final class WorkflowViews {

    private WorkflowViews() {}

    /**
     * @param step {@code plan}, {@code approval}, {@code committee}, {@code produce}, {@code review}, {@code accept},
     *     {@code retro}
     * @param maxTickets the plan step's limit; null for the other steps
     * @param check a director's check (schema version 2, ADR-0052); null for Floor42's own steps
     * @param task a director's task (schema version 3, ADR-0061); null for the other steps
     */
    public record StepView(String step, Integer maxTickets, CheckView check, TaskView task) {}

    /**
     * One of the director's tasks, as written; its texts are the director's own, shown as data.
     *
     * @param slot {@code beforeProduction} or {@code beforeAcceptance}
     * @param runBy {@code developer}, {@code reviewer} or {@code researcher}: the role whose permissions it has
     * @param reviewed whether a reviewer reads its report; always true for a developer
     */
    public record TaskView(
            String key,
            String title,
            String does,
            String why,
            String handsOver,
            String slot,
            String runBy,
            boolean reviewed) {
        static TaskView of(Workflow.Step.Task t) {
            return new TaskView(
                    t.key(),
                    t.title(),
                    t.does(),
                    t.why(),
                    t.handsOver(),
                    t.slot() == Workflow.Step.Task.Slot.BEFORE_PRODUCTION ? "beforeProduction" : "beforeAcceptance",
                    t.runBy().name().toLowerCase(java.util.Locale.ROOT),
                    t.reviewed());
        }
    }

    /**
     * One of the director's checks, as written; its texts are the director's own, shown as data.
     *
     * @param slot {@code checks}: after the review of each ticket
     * @param runBy {@code reviewer}: the role whose permissions the check's session has
     * @param blocks true: a verdict that does not validate reopens the ticket; false: advisory
     */
    public record CheckView(
            String key,
            String title,
            String does,
            String why,
            String handsOver,
            String slot,
            String runBy,
            boolean blocks) {
        static CheckView of(Workflow.Step.Check c) {
            return new CheckView(
                    c.key(), c.title(), c.does(), c.why(), c.handsOver(), "checks", "reviewer", c.blocks());
        }
    }

    /** @param mode {@code loop}, or {@code graph} when the planner draws the mission's task graph (ADR-0062) */
    public record WorkflowView(String name, List<StepView> steps, String mode) {
        public WorkflowView {
            steps = steps == null ? null : Collections.unmodifiableList(new ArrayList<>(steps));
        }

        static WorkflowView of(Workflow w) {
            return new WorkflowView(
                    w.name(),
                    w.steps().stream()
                            .map(s -> new StepView(
                                    WorkflowDocument.stepKind(s),
                                    s instanceof Workflow.Step.Plan p ? p.maxTickets() : null,
                                    s instanceof Workflow.Step.Check c ? CheckView.of(c) : null,
                                    s instanceof Workflow.Step.Task t ? TaskView.of(t) : null))
                            .toList(),
                    w.graph() ? "graph" : "loop");
        }
    }

    /**
     * @param pinned false for a mission journaled before 1.9.0 or simulated: it follows the built-in workflow
     * @param workflow what it follows; null when this build cannot read its pin
     * @param refusal why this build does not run it; null when it does
     */
    public record MissionWorkflowView(
            boolean pinned, WorkflowView workflow, Integer schemaVersion, String refusal, FollowsView follows) {
        static MissionWorkflowView of(Company c, MissionId m) {
            return switch (c.workflows().of(m)) {
                case MissionWorkflow.Unpinned u ->
                    new MissionWorkflowView(false, WorkflowView.of(Workflow.BUILT_IN), null, null, null);
                case MissionWorkflow.Pinned p ->
                    new MissionWorkflowView(
                            true,
                            WorkflowView.of(p.workflow()),
                            null,
                            null,
                            c.workflows().followed(m).map(FollowsView::of).orElse(null));
                case MissionWorkflow.Unrunnable u ->
                    new MissionWorkflowView(true, null, u.schemaVersion(), u.reason(), null);
            };
        }
    }

    /**
     * The saved version a pin is a copy of (ADR-0052): found by comparing the pin's document with the company's saves
     * of that name made before the pin, in the life of the name at the pin. A pin that matches none is a copy of none (no
     * follows).
     *
     * @param removed true when the name was removed after the pin: the version belongs to a life of the name that is over
     */
    public record FollowsView(String name, int version, boolean removed) {
        static FollowsView of(FollowedVersion f) {
            return new FollowsView(f.version().name(), f.version().number(), !f.current());
        }
    }

    /** @param key stable; the screen translates the template's title by this key */
    public record TemplateView(String key, int revision, WorkflowView workflow) {
        static TemplateView of(WorkflowTemplate t) {
            return new TemplateView(t.key(), t.revision(), WorkflowView.of(t.workflow()));
        }
    }

    /**
     * One save of a company workflow; the first is version 1.
     *
     * @param savedAt ISO instant
     * @param refusal why this build cannot read that save (it cannot be restored); null when it can
     */
    public record VersionView(int version, String savedAt, int schemaVersion, WorkflowView workflow, String refusal) {
        static VersionView of(WorkflowVersion v) {
            return new VersionView(
                    v.number(),
                    v.at().toString(),
                    v.schemaVersion(),
                    v.reading() instanceof WorkflowDocument.Reading.Read r ? WorkflowView.of(r.workflow()) : null,
                    v.reading() instanceof WorkflowDocument.Reading.Refused r ? r.reason() : null);
        }
    }

    /** The file of an exported workflow, as it is written to disk; see {@code POST /api/workflows/import}. */
    public record WorkflowFileView(
            String format, int formatVersion, int schemaVersion, java.util.Map<String, Object> document) {
        public WorkflowFileView {
            document = document == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(document));
        }
    }

    /** One problem of an imported file: a stable code, and what it is about when that is short and plain. */
    public record ImportProblemView(String code, String subject) {}

    /**
     * What a file reads as, nothing saved: the workflow to show the director before saving, or the problems by code.
     *
     * @param workflow null when the file has problems
     */
    public record ImportView(WorkflowView workflow, List<ImportProblemView> problems) {
        public ImportView {
            problems = problems == null ? null : Collections.unmodifiableList(new ArrayList<>(problems));
        }

        static ImportView of(WorkflowFile.Reading r) {
            return switch (r) {
                case WorkflowFile.Reading.Read ok -> new ImportView(WorkflowView.of(ok.workflow()), List.of());
                case WorkflowFile.Reading.Refused no ->
                    new ImportView(
                            null,
                            no.problems().stream()
                                    .map(p -> new ImportProblemView(p.code(), p.subject()))
                                    .toList());
            };
        }
    }

    /**
     * @param refusal why this build cannot use it (shown read only, never picked); null when it can
     * @param version the current version: the number of saves since its latest removal
     * @param versions every save since then, oldest first
     */
    public record CompanyWorkflowView(
            String name,
            int schemaVersion,
            WorkflowView workflow,
            String refusal,
            int version,
            List<VersionView> versions) {
        public CompanyWorkflowView {
            versions = versions == null ? null : Collections.unmodifiableList(new ArrayList<>(versions));
        }

        static CompanyWorkflowView of(Company c, CompanyWorkflow w) {
            List<VersionView> versions = c.workflows().versions(w.name()).stream()
                    .map(VersionView::of)
                    .toList();
            return new CompanyWorkflowView(
                    w.name(),
                    w.schemaVersion(),
                    w.workflow().map(WorkflowView::of).orElse(null),
                    w.refusal().orElse(null),
                    versions.size(),
                    versions);
        }
    }

    /** @param status {@code OPEN}, {@code ADOPTED} or {@code DISMISSED} */
    public record ProposalView(
            int number, String mission, String by, String at, WorkflowView workflow, String refusal, String status) {
        static ProposalView of(WorkflowProposal p) {
            return new ProposalView(
                    p.number(),
                    p.mission().value(),
                    p.by(),
                    p.at().toString(),
                    p.workflow().map(WorkflowView::of).orElse(null),
                    p.reading() instanceof WorkflowDocument.Reading.Refused r ? r.reason() : null,
                    p.status().name());
        }
    }

    /**
     * The editor's page: Floor42's own workflow (read only), the company's, and the planner's proposals.
     *
     * @param writesSchema the highest schema version this daemon saves now (ADR-0052: a version is read one release
     *     before it is written); a workflow that needs a higher one is shown and edited but not saved
     */
    public record WorkflowsView(
            WorkflowView builtIn,
            List<TemplateView> templates,
            List<CompanyWorkflowView> workflows,
            List<ProposalView> proposals,
            int writesSchema) {
        public WorkflowsView {
            proposals = proposals == null ? null : Collections.unmodifiableList(new ArrayList<>(proposals));
            templates = templates == null ? null : Collections.unmodifiableList(new ArrayList<>(templates));
            workflows = workflows == null ? null : Collections.unmodifiableList(new ArrayList<>(workflows));
        }

        static WorkflowsView of(Company c) {
            return new WorkflowsView(
                    WorkflowView.of(Workflow.BUILT_IN),
                    WorkflowTemplate.ALL.stream().map(TemplateView::of).toList(),
                    c.workflows().companyWorkflows().stream()
                            .map(w -> CompanyWorkflowView.of(c, w))
                            .toList(),
                    c.workflows().proposals().stream().map(ProposalView::of).toList(),
                    WorkflowDocument.writableVersion());
        }
    }
}
