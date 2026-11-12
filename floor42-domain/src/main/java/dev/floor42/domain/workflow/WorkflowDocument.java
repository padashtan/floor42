package dev.floor42.domain.workflow;

import dev.floor42.domain.workflow.Workflow.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A workflow as the journal keeps it: a JSON document and the version of its schema (ADR-0050). The journal never maps
 * its steps to classes; this is the one place that does, with one reader per schema version. A version's reader is
 * never changed once released: a change of what it accepts is a new version, read by a reader of its own.
 *
 * <p>Version 1: {@code {"name":"...","steps":[{"step":"plan","maxTickets":3},{"step":"approval"},...]}}. A setting at
 * its default is left out when written.
 *
 * <p>Version 2 (ADR-0052): version 1 plus the director's checks, {@code {"step":"check","key":...,"title":...,"does":...,
 * "why":...,"handsOver":...,"slot":"checks","runBy":"reviewer","blocks":true}}, every key written, the step text rule
 * ({@link StepText}), at most {@value WorkflowDocumentV2#MAX_LENGTH} characters. Read by {@link WorkflowDocumentV2}.
 *
 * <p>The writer writes the lowest version that holds the workflow: Floor42's steps only are still version 1, byte for
 * byte as 1.11.0 wrote them. A version is read one release before it is written (the director's answer 4): see
 * {@link #WRITES}; 1.16 writes version 3 (ADR-0061).
 *
 * <p>Version 4 (ADR-0062): version 3 plus {@code "mode":"graph"}, read and written in 1.16.0 together by the director's
 * explicit choice (2026-11-04), the one exception to readers before writers. Read by {@link WorkflowDocumentV4}.
 */
public final class WorkflowDocument {

    /** The highest schema version this build reads. */
    public static final int READS = 4;

    /** The highest version a library workflow (saved, imported, exported) is in: the graph mode is a mission's (ADR-0062). */
    public static final int LIBRARY = 3;

    /**
     * The highest schema version this release writes. A version's writer ships one release after the release whose
     * reader first read it ({@link #READER_SHIPPED}), so a rollback by one release still runs every mission it pinned:
     * raise it only in the release after that one. {@code ReadersBeforeWritersTest} fails the build otherwise.
     */
    public static final int WRITES = 4;

    /**
     * The release that first shipped each version's reader. "internal" marks a release of the private development, before
     * the public numbering started again at 1.0.0: all four readers are in the first public release.
     */
    public static final Map<Integer, String> READER_SHIPPED =
            Map.of(1, "internal 1.9.0", 2, "internal 1.14.0", 3, "internal 1.15.0", 4, "internal 1.16.0");

    static final int MAX_TICKETS = 8;

    private WorkflowDocument() {}

    /** What a document reads as: a workflow, or the reason this build cannot run it. */
    public sealed interface Reading {
        record Read(Workflow workflow) implements Reading {}

        record Refused(String reason) implements Reading {}
    }

    /** A document to journal, with the schema version it is written in. */
    public record Written(String document, int schemaVersion) {}

    /**
     * The document in the lowest schema version that holds the workflow: canonical, so the same workflow always gives
     * the same text. Text only: a command journals {@link #forJournal}.
     */
    public static String write(Workflow w) {
        List<String> steps = new ArrayList<>();
        int version = versionOf(w);
        if (version == 4) return WorkflowDocumentV4.write(w);
        for (Step s : w.steps()) {
            steps.add(
                    switch (version) {
                        case 1 -> V1.write(s);
                        case 2 -> WorkflowDocumentV2.write(s);
                        default -> WorkflowDocumentV3.write(s);
                    });
        }
        return "{\"name\":" + Json.quote(w.name()) + ",\"steps\":[" + String.join(",", steps) + "]}";
    }

    /** The lowest schema version that holds the workflow: 4 in graph mode, 3 with a task, 2 with a check, else 1. */
    public static int versionOf(Workflow w) {
        if (w.graph()) return 4;
        return !w.tasks().isEmpty() ? 3 : w.checks().isEmpty() ? 1 : 2;
    }

    /** Whether this release may journal the workflow's document now. */
    public static boolean writable(Workflow w) {
        return versionOf(w) <= WRITES;
    }

    /** The highest version a command may journal now. */
    public static int writableVersion() {
        return WRITES;
    }

    /**
     * The document a command journals: refused ({@code WORKFLOW_SCHEMA_NOT_WRITABLE}) when its version is one this
     * release only reads (ADR-0052: readers ship one release before writers).
     */
    public static Written forJournal(Workflow w) {
        if (!writable(w)) {
            throw new StepRefused(
                    "WORKFLOW_SCHEMA_NOT_WRITABLE",
                    "This release reads workflow schema " + versionOf(w) + " but writes it only from the next one");
        }
        return new Written(write(w), versionOf(w));
    }

    /** A step's name in the schema: {@code plan}, {@code approval}, {@code check}... */
    public static String stepKind(Step s) {
        return s instanceof Step.Check ? "check" : s instanceof Step.Task ? "task" : V1.kind(s);
    }

    /** Never throws: anything this build cannot read is a reason, shown to the director. */
    public static Reading read(String document, int schemaVersion) {
        if (schemaVersion < 1)
            return new Reading.Refused("The workflow's schema version " + schemaVersion + " is invalid");
        if (schemaVersion > READS) {
            return new Reading.Refused("Written by a newer Floor42 (workflow schema " + schemaVersion
                    + "; this build reads " + READS + "): upgrade to run it");
        }
        try {
            return new Reading.Read(parse(document, schemaVersion));
        } catch (Json.Refused e) {
            // Handled: the reader's own refusal becomes the reason the director reads.
            return new Reading.Refused(e.getMessage());
        } catch (IllegalArgumentException e) {
            // Handled: Workflow's invariants and the steps' ranges refuse the document; their message is the reason.
            return new Reading.Refused(e.getMessage());
        }
    }

    private static Workflow parse(String document, int schemaVersion) throws Json.Refused {
        return switch (schemaVersion) {
            case 1 -> V1.read(Json.parse(document));
            case 2 -> WorkflowDocumentV2.read(Json.parse(document, WorkflowDocumentV2.MAX_LENGTH));
            case 3 -> WorkflowDocumentV3.read(Json.parse(document, WorkflowDocumentV3.MAX_LENGTH));
            default -> WorkflowDocumentV4.read(Json.parse(document, WorkflowDocumentV4.MAX_LENGTH));
        };
    }

    /**
     * The workflow of a document the director sends, read by the newest library reader (version 3: the mode is a mission's): a {@link StepRefused} carries the code
     * of a step's problem, any other refusal is thrown with its reason.
     */
    public static Workflow readCurrent(String document) {
        try {
            return parse(document, LIBRARY);
        } catch (Json.Refused e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** Schema version 1, frozen: its keys, steps, settings, ranges and defaults are literals of its own. */
    private static final class V1 {

        static String write(Step s) {
            String kind = kind(s);
            if (s instanceof Step.Plan p && p.maxTickets() != MAX_TICKETS) {
                return "{\"step\":\"" + kind + "\",\"maxTickets\":" + p.maxTickets() + "}";
            }
            return "{\"step\":\"" + kind + "\"}";
        }

        static String kind(Step s) {
            return switch (s) {
                case Step.Plan _ -> "plan";
                case Step.Approval _ -> "approval";
                case Step.Committee _ -> "committee";
                case Step.Produce _ -> "produce";
                case Step.Review _ -> "review";
                case Step.Accept _ -> "accept";
                case Step.Retro _ -> "retro";
                // Never reached: a workflow with a check or a task is written in a later version (versionOf).
                case Step.Check _, Step.Task _ -> throw new IllegalStateException("Not a version 1 step");
            };
        }

        static Workflow read(Object root) throws Json.Refused {
            return readWorkflow(root, Set.of("name", "steps"), V1::step);
        }

        static Step step(Object item) throws Json.Refused {
            Map<String, Object> m = object(item, "A step", Set.of("step", "maxTickets", "autoAccept"));
            if (m.containsKey("autoAccept")) throw new Json.Refused("Auto-acceptance is not built yet");
            if (!(m.get("step") instanceof String kind)) throw new Json.Refused("A step has no kind");
            return plainStep(kind, m);
        }
    }

    /** One step of a document, read by the reader of its version. */
    @FunctionalInterface
    interface StepReader {
        Step read(Object item) throws Json.Refused;
    }

    /** What every version's reader shares: a document is a name and steps, read step by step. */
    static Workflow readWorkflow(Object root, Set<String> documentKeys, StepReader reader) throws Json.Refused {
        Map<String, Object> doc = object(root, "The document", documentKeys);
        if (!(doc.get("name") instanceof String name)) throw new Json.Refused("The workflow has no name");
        if (!(doc.get("steps") instanceof List<?> list)) throw new Json.Refused("The workflow has no steps");
        List<Step> steps = new ArrayList<>();
        for (Object item : list) steps.add(reader.read(item));
        return new Workflow(name, steps);
    }

    /** A step Floor42 has always had: the seven kinds of version 1, the plan's maxTickets being their only setting. */
    static Step plainStep(String kind, Map<String, Object> m) throws Json.Refused {
        if (m.containsKey("maxTickets") && !kind.equals("plan")) {
            throw new Json.Refused("Only the plan step has maxTickets");
        }
        return switch (kind) {
            case "plan" -> new Step.Plan(maxTickets(m));
            case "approval" -> new Step.Approval();
            case "committee" -> new Step.Committee();
            case "produce" -> new Step.Produce();
            case "review" -> new Step.Review();
            case "accept" -> new Step.Accept();
            case "retro" -> new Step.Retro();
            default -> throw new Json.Refused("Unknown step \"" + kind + "\"");
        };
    }

    private static int maxTickets(Map<String, Object> m) throws Json.Refused {
        Object v = m.getOrDefault("maxTickets", MAX_TICKETS);
        if (!(v instanceof Integer n) || n < 1 || n > MAX_TICKETS) {
            throw new Json.Refused("maxTickets is a number from 1 to " + MAX_TICKETS);
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String what, Set<String> keys) throws Json.Refused {
        if (!(value instanceof Map<?, ?> map)) throw new Json.Refused(what + " is not an object");
        for (Object k : map.keySet()) {
            if (!keys.contains(k)) throw new Json.Refused(what + " has an unknown key \"" + k + "\"");
        }
        return (Map<String, Object>) map;
    }
}
