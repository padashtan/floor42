package dev.floor42.domain.workflow;

import dev.floor42.domain.workflow.WorkflowFile.Code;
import dev.floor42.domain.workflow.WorkflowFile.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What is wrong with a version 1, 2 or 3 document, as codes, all at once: the reader stops at the first refusal and its
 * reasons are English sentences, an import shows every problem and the screen writes the words. The rules are those of
 * {@link WorkflowDocument}'s frozen readers and of {@link Workflow}'s constructor, repeated here as literals (a test
 * enumerates workflows and checks that the two always agree). The workflow's name is not judged here: the editor shows
 * a name that cannot be saved, under the name field, and the director renames it.
 */
final class DocumentCheck {

    private static final List<String> ORDER =
            List.of("plan", "approval", "committee", "produce", "review", "accept", "retro");
    /** Version 2's order: the director's checks after the review, as many as the director wrote. */
    private static final List<String> ORDER_V2 =
            List.of("plan", "approval", "committee", "produce", "review", "check", "accept", "retro");

    /** Version 3's order (ADR-0061): the tasks of the two slots, as many as the director wrote. */
    private static final List<String> ORDER_V3 = List.of(
            "plan",
            "approval",
            "committee",
            "taskBefore",
            "produce",
            "review",
            "check",
            "taskAfter",
            "accept",
            "retro");

    private static final int MAX_TICKETS = 8;
    private static final int MAX_CHECKS = 4;
    private static final int MAX_TASKS = 4;
    private static final List<String> TASK_KEYS =
            List.of("step", "key", "title", "does", "why", "handsOver", "slot", "runBy", "reviewed");
    private static final List<String> CHECK_KEYS =
            List.of("step", "key", "title", "does", "why", "handsOver", "slot", "runBy", "blocks");
    private static final Pattern KEY =
            Pattern.compile("(?!.*--)[a-z0-9][a-z0-9-]*(?<!-)"); // a slug: no leading, trailing or doubled hyphen

    private DocumentCheck() {}

    /** One step that reads: its kind, and its key for a check (null for Floor42's steps). */
    private record Read(String kind, String key) {}

    static void run(Object document, int schema, List<Problem> out) {
        if (!(document instanceof Map<?, ?> doc)) return;
        for (Object k : doc.keySet())
            if (!Set.of("name", "steps").contains(k)) WorkflowFile.add(out, Code.IMPORT_UNKNOWN_KEY, k);
        if (!(doc.get("name") instanceof String name) || name.isBlank())
            WorkflowFile.add(out, Code.IMPORT_NAME_MISSING, null);
        if (!(doc.get("steps") instanceof List<?> items)) {
            WorkflowFile.add(out, Code.IMPORT_STEPS_MISSING, null);
            return;
        }
        List<Read> steps = new ArrayList<>();
        for (Object item : items) steps.add(step(item, schema, out));
        if (steps.contains(null)) return;
        rules(steps, schema, out);
    }

    /** One step: its kind when it reads, null when it does not (its problems are reported). */
    private static Read step(Object item, int schema, List<Problem> out) {
        if (!(item instanceof Map<?, ?> m)) {
            WorkflowFile.add(out, Code.IMPORT_STEP_NOT_AN_OBJECT, null);
            return null;
        }
        Object kindValue = m.get("step");
        boolean check = schema >= 2 && "check".equals(kindValue);
        boolean task = schema >= 3 && "task".equals(kindValue);
        boolean ok = keys(m, check ? CHECK_KEYS : task ? TASK_KEYS : List.of("step", "maxTickets"), out);
        if (!(kindValue instanceof String kind)) {
            WorkflowFile.add(out, Code.IMPORT_STEP_KIND_MISSING, null);
            return null;
        }
        if (check) return ok && check(m, out) ? new Read(kind, (String) m.get("key")) : null;
        if (task) return ok && task(m, out) ? new Read(taskKind(m), (String) m.get("key")) : null;
        if (!ORDER.contains(kind)) {
            WorkflowFile.add(out, Code.IMPORT_STEP_UNKNOWN, kind);
            return null;
        }
        ok &= maxTickets(m, kind, out);
        return ok ? new Read(kind, null) : null;
    }

    /** The keys a step may hold; false when one is not allowed. */
    private static boolean keys(Map<?, ?> m, List<String> allowed, List<Problem> out) {
        boolean ok = true;
        for (Object k : m.keySet()) {
            if (k.equals("autoAccept")) {
                WorkflowFile.add(out, Code.IMPORT_AUTO_ACCEPT, null);
                ok = false;
            } else if (!allowed.contains(k)) {
                WorkflowFile.add(out, Code.IMPORT_UNKNOWN_KEY, k);
                ok = false;
            }
        }
        return ok;
    }

    /** A check of version 2: every key, its slot and runner, a valid key, texts that follow the step text rule. */
    private static boolean check(Map<?, ?> m, List<Problem> out) {
        int before = out.size();
        for (String k : CHECK_KEYS) if (!m.containsKey(k)) WorkflowFile.add(out, Code.IMPORT_CHECK_INCOMPLETE, k);
        if (m.containsKey("slot") && !"checks".equals(m.get("slot")))
            WorkflowFile.add(out, Code.IMPORT_CHECK_SLOT, null);
        if (m.containsKey("runBy") && !"reviewer".equals(m.get("runBy")))
            WorkflowFile.add(out, Code.IMPORT_STEP_RUNNER, null);
        if (m.containsKey("blocks") && !(m.get("blocks") instanceof Boolean))
            WorkflowFile.add(out, Code.IMPORT_CHECK_INCOMPLETE, "blocks");
        keyAndTexts(m, out);
        return out.size() == before;
    }

    /** A task of version 3: every key, its slot and runner, a valid key, texts that follow the step text rule. */
    private static boolean task(Map<?, ?> m, List<Problem> out) {
        int before = out.size();
        for (String k : TASK_KEYS) if (!m.containsKey(k)) WorkflowFile.add(out, Code.IMPORT_TASK_INCOMPLETE, k);
        if (m.containsKey("slot")
                && !Set.of("beforeProduction", "beforeAcceptance").contains(m.get("slot")))
            WorkflowFile.add(out, Code.IMPORT_TASK_SLOT, null);
        if (m.containsKey("runBy")
                && !Set.of("developer", "reviewer", "researcher").contains(m.get("runBy")))
            WorkflowFile.add(out, Code.IMPORT_STEP_RUNNER, null);
        if (m.containsKey("reviewed") && !(m.get("reviewed") instanceof Boolean))
            WorkflowFile.add(out, Code.IMPORT_TASK_INCOMPLETE, "reviewed");
        if ("developer".equals(m.get("runBy")) && Boolean.FALSE.equals(m.get("reviewed")))
            WorkflowFile.add(out, Code.IMPORT_TASK_UNREVIEWED, null);
        keyAndTexts(m, out);
        return out.size() == before;
    }

    /** What a check and a task share: a valid key, texts that follow the step text rule. */
    private static void keyAndTexts(Map<?, ?> m, List<Problem> out) {
        if (m.containsKey("key")
                && !(m.get("key") instanceof String key
                        && key.length() <= 40
                        && KEY.matcher(key).matches())) WorkflowFile.add(out, Code.IMPORT_STEP_KEY, null);
        for (StepText.Field f : StepText.Field.values()) {
            if (!m.containsKey(f.key())) continue;
            Object text = m.get(f.key());
            if (!(text instanceof String s) || StepText.check(f, s).isPresent())
                WorkflowFile.add(out, Code.IMPORT_STEP_TEXT, f.key());
        }
    }

    /** Where a task stands in the order: before production or before the acceptance. */
    private static String taskKind(Map<?, ?> m) {
        return "beforeProduction".equals(m.get("slot")) ? "taskBefore" : "taskAfter";
    }

    private static boolean maxTickets(Map<?, ?> m, String kind, List<Problem> out) {
        if (!m.containsKey("maxTickets")) return true;
        if (!kind.equals("plan")) {
            WorkflowFile.add(out, Code.IMPORT_MAX_TICKETS_MISPLACED, kind);
            return false;
        }
        if (m.get("maxTickets") instanceof Integer n && n >= 1 && n <= MAX_TICKETS) return true;
        WorkflowFile.add(out, Code.IMPORT_MAX_TICKETS_RANGE, null);
        return false;
    }

    /** The order and the director's rules of ADR-0046 and ADR-0052, on steps that all read. */
    private static void rules(List<Read> steps, int schema, List<Problem> out) {
        List<String> order = schema >= 3 ? ORDER_V3 : schema >= 2 ? ORDER_V2 : ORDER;
        List<String> kinds = steps.stream().map(Read::kind).toList();
        for (int i = 1; i < kinds.size(); i++) {
            int now = order.indexOf(kinds.get(i));
            int before = order.indexOf(kinds.get(i - 1));
            if (now < before
                    || (now == before
                            && !List.of("check", "taskBefore", "taskAfter").contains(kinds.get(i))))
                WorkflowFile.add(out, Code.IMPORT_STEP_ORDER, kinds.get(i));
        }
        if (!kinds.contains("approval")) WorkflowFile.add(out, Code.IMPORT_NO_APPROVAL, null);
        if (!kinds.contains("accept")) WorkflowFile.add(out, Code.IMPORT_NO_ACCEPT, null);
        if (kinds.contains("produce") && !kinds.contains("review"))
            WorkflowFile.add(out, Code.IMPORT_PRODUCE_UNREVIEWED, null);
        if (kinds.contains("review") && !kinds.contains("produce"))
            WorkflowFile.add(out, Code.IMPORT_REVIEW_WITHOUT_PRODUCE, null);
        boolean tasks = kinds.contains("taskBefore") || kinds.contains("taskAfter");
        if (!kinds.contains("produce") && !kinds.contains("committee") && !tasks)
            WorkflowFile.add(out, Code.IMPORT_NOTHING_TO_RUN, null);
        List<String> keys = steps.stream().map(Read::key).filter(k -> k != null).toList();
        if (keys.isEmpty()) return;
        long checks = kinds.stream().filter("check"::equals).count();
        long taskCount = kinds.stream().filter(k -> k.startsWith("task")).count();
        if (!kinds.contains("produce") && (checks > 0 || kinds.contains("taskBefore")))
            WorkflowFile.add(out, Code.IMPORT_STEP_NEEDS_PRODUCTION, null);
        if (checks > MAX_CHECKS || taskCount > MAX_TASKS) WorkflowFile.add(out, Code.IMPORT_TOO_MANY_STEPS, null);
        if (keys.stream().distinct().count() < keys.size()) WorkflowFile.add(out, Code.IMPORT_STEP_KEY_TAKEN, null);
    }
}
