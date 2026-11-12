package dev.floor42.server.app;

import dev.floor42.domain.workflow.CompanyWorkflow;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.ArrayList;
import java.util.List;

/**
 * The planner's workflow proposal (ADR-0050), read from its plan report: at most one line
 * {@code WORKFLOW: <name>: <step>, <step>...}. Floor42 builds the workflow itself, settings at their defaults, and
 * writes its document; the agent never chooses the text recorded nor the schema version.
 */
final class WorkflowProposals {

    static final String PREFIX = "WORKFLOW:";
    private static final int MAX_LINE = 300;

    private WorkflowProposals() {}

    /** What the report proposes: a workflow, nothing, or why it cannot be recorded. */
    sealed interface Proposal {
        record None() implements Proposal {}

        record Proposed(Workflow workflow) implements Proposal {}

        record Refused(String reason) implements Proposal {}
    }

    static Proposal read(String report) {
        List<String> lines = report == null
                ? List.of()
                : report.lines()
                        .map(String::strip)
                        .filter(l -> l.regionMatches(true, 0, PREFIX, 0, PREFIX.length()))
                        .toList();
        if (lines.isEmpty()) return new Proposal.None();
        if (lines.size() > 1) return new Proposal.Refused("more than one WORKFLOW line");
        String line = lines.getFirst();
        if (line.length() > MAX_LINE) return new Proposal.Refused("a WORKFLOW line longer than " + MAX_LINE);
        String[] parts = line.substring(PREFIX.length()).split(":", 2);
        if (parts.length < 2) return new Proposal.Refused("no name before the steps");
        java.util.Optional<String> name = CompanyWorkflow.nameRefusal(parts[0]);
        if (name.isPresent()) return new Proposal.Refused(name.get());
        try {
            return new Proposal.Proposed(new Workflow(parts[0].strip(), steps(parts[1])));
        } catch (IllegalArgumentException e) {
            // Handled: Workflow refuses the proposal; the reason is logged by the caller, nothing is recorded.
            return new Proposal.Refused(e.getMessage());
        }
    }

    private static List<Step> steps(String list) {
        List<Step> steps = new ArrayList<>();
        for (String raw : list.split(",")) {
            steps.add(
                    switch (raw.strip().toLowerCase(java.util.Locale.ROOT)) {
                        case "plan" -> new Step.Plan();
                        case "approval" -> new Step.Approval();
                        case "committee" -> new Step.Committee();
                        case "produce" -> new Step.Produce();
                        case "review" -> new Step.Review();
                        case "accept" -> new Step.Accept();
                        case "retro" -> new Step.Retro();
                        default -> throw new IllegalArgumentException("unknown step \"" + raw.strip() + "\"");
                    });
        }
        return steps;
    }
}
