package dev.floor42.domain.workflow;

import dev.floor42.domain.workflow.Workflow.Step;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema version 2 of {@link WorkflowDocument} (ADR-0052, increment 3), frozen once released: version 1 plus the
 * director's checks. Its keys, steps, ranges and defaults are literals of its own, as version 1's are. It refuses the
 * step kind {@code task} and the key {@code reviewed} as unknown (they are version 3's), and {@code autoAccept} by name.
 */
final class WorkflowDocumentV2 {

    /** Longer step texts need a longer document; the depth stays version 1's. */
    static final int MAX_LENGTH = 32_768;

    private static final Set<String> DOCUMENT_KEYS = Set.of("name", "steps");
    private static final List<String> CHECK_KEYS =
            List.of("step", "key", "title", "does", "why", "handsOver", "slot", "runBy", "blocks");
    private static final Set<String> STEP_KEYS = Set.of(
            "step", "maxTickets", "autoAccept", "key", "title", "does", "why", "handsOver", "slot", "runBy", "blocks");

    private WorkflowDocumentV2() {}

    /** One step, canonical: a check with every key in a fixed order, the others as version 1 writes them. */
    static String write(Step s) {
        return switch (s) {
            case Step.Check c ->
                "{\"step\":\"check\",\"key\":" + Json.quote(c.key()) + ",\"title\":" + Json.quote(c.title())
                        + ",\"does\":" + Json.quote(c.does()) + ",\"why\":" + Json.quote(c.why()) + ",\"handsOver\":"
                        + Json.quote(c.handsOver()) + ",\"slot\":\"checks\",\"runBy\":\"reviewer\",\"blocks\":"
                        + c.blocks() + "}";
            case Step.Plan p
            when p.maxTickets() != WorkflowDocument.MAX_TICKETS ->
                "{\"step\":\"plan\",\"maxTickets\":" + p.maxTickets() + "}";
            default -> "{\"step\":\"" + kind(s) + "\"}";
        };
    }

    private static String kind(Step s) {
        return switch (s) {
            case Step.Plan _ -> "plan";
            case Step.Approval _ -> "approval";
            case Step.Committee _ -> "committee";
            case Step.Produce _ -> "produce";
            case Step.Review _ -> "review";
            case Step.Check _ -> "check";
            case Step.Task _ -> throw new IllegalStateException("A task is not a version 2 step");
            case Step.Accept _ -> "accept";
            case Step.Retro _ -> "retro";
        };
    }

    static Workflow read(Object root) throws Json.Refused {
        return WorkflowDocument.readWorkflow(root, DOCUMENT_KEYS, WorkflowDocumentV2::step);
    }

    static Step step(Object item) throws Json.Refused {
        Map<String, Object> m = WorkflowDocument.object(item, "A step", STEP_KEYS);
        if (m.containsKey("autoAccept")) throw new Json.Refused("Auto-acceptance is not built yet");
        if (!(m.get("step") instanceof String kind)) throw new Json.Refused("A step has no kind");
        if (kind.equals("check")) return check(m);
        for (String k : m.keySet()) {
            if (!k.equals("step") && !k.equals("maxTickets")) {
                throw new Json.Refused("Only a check has \"" + k + "\"");
            }
        }
        return WorkflowDocument.plainStep(kind, m);
    }

    /** Every key written, none defaulted: a check reads exactly as it was saved. */
    private static Step check(Map<String, Object> m) throws Json.Refused {
        for (String k : CHECK_KEYS) if (!m.containsKey(k)) throw new Json.Refused("A check has no \"" + k + "\"");
        if (m.containsKey("maxTickets")) throw new Json.Refused("Only the plan step has maxTickets");
        if (!"checks".equals(m.get("slot"))) throw new Json.Refused("A check runs in the checks slot only");
        if (!"reviewer".equals(m.get("runBy"))) {
            throw new StepRefused("STEP_RUNNER_NOT_ALLOWED", "A check is run by a reviewer");
        }
        if (!(m.get("blocks") instanceof Boolean blocks)) throw new Json.Refused("A check's blocks is true or false");
        return new Step.Check(
                text(m, "key"), text(m, "title"), text(m, "does"), text(m, "why"), text(m, "handsOver"), blocks);
    }

    private static String text(Map<String, Object> m, String key) throws Json.Refused {
        if (!(m.get(key) instanceof String s)) throw new Json.Refused("A check's " + key + " is a text");
        return s;
    }
}
