package dev.floor42.domain.workflow;

import dev.floor42.domain.WorkRole;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema version 3 of {@link WorkflowDocument} (ADR-0061, increment 4 of ADR-0052), frozen once released: version 2
 * plus the director's tasks, {@code {"step":"task","key":...,"title":...,"does":...,"why":...,"handsOver":...,
 * "slot":"beforeProduction"|"beforeAcceptance","runBy":"developer"|"reviewer"|"researcher","reviewed":true}}, every key
 * written. Its keys, steps, ranges and defaults are literals of its own, as the earlier versions' are. It refuses {@code
 * autoAccept} by name.
 */
final class WorkflowDocumentV3 {

    /** As version 2's: the depth stays version 1's. */
    static final int MAX_LENGTH = 32_768;

    private static final Set<String> DOCUMENT_KEYS = Set.of("name", "steps");
    private static final List<String> CHECK_KEYS =
            List.of("step", "key", "title", "does", "why", "handsOver", "slot", "runBy", "blocks");
    private static final List<String> TASK_KEYS =
            List.of("step", "key", "title", "does", "why", "handsOver", "slot", "runBy", "reviewed");
    private static final Set<String> STEP_KEYS = Set.of(
            "step",
            "maxTickets",
            "autoAccept",
            "key",
            "title",
            "does",
            "why",
            "handsOver",
            "slot",
            "runBy",
            "blocks",
            "reviewed");

    private WorkflowDocumentV3() {}

    /** One step, canonical: a check or a task with every key in a fixed order, the others as version 1 writes them. */
    static String write(Step s) {
        return switch (s) {
            case Step.Task t ->
                "{\"step\":\"task\",\"key\":" + Json.quote(t.key()) + ",\"title\":" + Json.quote(t.title())
                        + ",\"does\":" + Json.quote(t.does()) + ",\"why\":" + Json.quote(t.why()) + ",\"handsOver\":"
                        + Json.quote(t.handsOver()) + ",\"slot\":\"" + slotName(t.slot()) + "\",\"runBy\":\""
                        + t.runBy().name().toLowerCase(java.util.Locale.ROOT) + "\",\"reviewed\":" + t.reviewed() + "}";
            default -> WorkflowDocumentV2.write(s);
        };
    }

    private static String slotName(Step.Task.Slot slot) {
        return slot == Step.Task.Slot.BEFORE_PRODUCTION ? "beforeProduction" : "beforeAcceptance";
    }

    static Workflow read(Object root) throws Json.Refused {
        return WorkflowDocument.readWorkflow(root, DOCUMENT_KEYS, WorkflowDocumentV3::step);
    }

    static Step step(Object item) throws Json.Refused {
        Map<String, Object> m = WorkflowDocument.object(item, "A step", STEP_KEYS);
        if (m.containsKey("autoAccept")) throw new Json.Refused("Auto-acceptance is not built yet");
        if (!(m.get("step") instanceof String kind)) throw new Json.Refused("A step has no kind");
        if (kind.equals("check")) return check(m);
        if (kind.equals("task")) return task(m);
        for (String k : m.keySet()) {
            if (!k.equals("step") && !k.equals("maxTickets")) {
                throw new Json.Refused("Only a check or a task has \"" + k + "\"");
            }
        }
        return WorkflowDocument.plainStep(kind, m);
    }

    /** Every key written, none defaulted: a check reads exactly as it was saved. */
    private static Step check(Map<String, Object> m) throws Json.Refused {
        for (String k : CHECK_KEYS) if (!m.containsKey(k)) throw new Json.Refused("A check has no \"" + k + "\"");
        if (m.containsKey("maxTickets") || m.containsKey("reviewed")) {
            throw new Json.Refused("A check has neither maxTickets nor reviewed");
        }
        if (!"checks".equals(m.get("slot"))) throw new Json.Refused("A check runs in the checks slot only");
        if (!"reviewer".equals(m.get("runBy"))) {
            throw new StepRefused("STEP_RUNNER_NOT_ALLOWED", "A check is run by a reviewer");
        }
        if (!(m.get("blocks") instanceof Boolean blocks)) throw new Json.Refused("A check's blocks is true or false");
        return new Step.Check(
                text(m, "key"), text(m, "title"), text(m, "does"), text(m, "why"), text(m, "handsOver"), blocks);
    }

    /** Every key written, none defaulted: a task reads exactly as it was saved. */
    private static Step task(Map<String, Object> m) throws Json.Refused {
        for (String k : TASK_KEYS) if (!m.containsKey(k)) throw new Json.Refused("A task has no \"" + k + "\"");
        if (m.containsKey("maxTickets") || m.containsKey("blocks")) {
            throw new Json.Refused("A task has neither maxTickets nor blocks");
        }
        Step.Task.Slot slot = switch (String.valueOf(m.get("slot"))) {
            case "beforeProduction" -> Step.Task.Slot.BEFORE_PRODUCTION;
            case "beforeAcceptance" -> Step.Task.Slot.BEFORE_ACCEPTANCE;
            default -> throw new Json.Refused("A task runs before production or before acceptance");
        };
        WorkRole runBy = switch (String.valueOf(m.get("runBy"))) {
            case "developer" -> WorkRole.DEVELOPER;
            case "reviewer" -> WorkRole.REVIEWER;
            case "researcher" -> WorkRole.RESEARCHER;
            default ->
                throw new StepRefused(
                        "STEP_RUNNER_NOT_ALLOWED", "A task is run by a developer, a reviewer or a researcher");
        };
        if (!(m.get("reviewed") instanceof Boolean reviewed))
            throw new Json.Refused("A task's reviewed is true or false");
        return new Step.Task(
                text(m, "key"),
                text(m, "title"),
                text(m, "does"),
                text(m, "why"),
                text(m, "handsOver"),
                slot,
                runBy,
                reviewed);
    }

    private static String text(Map<String, Object> m, String key) throws Json.Refused {
        if (!(m.get(key) instanceof String s)) throw new Json.Refused("A step's " + key + " is a text");
        return s;
    }
}
