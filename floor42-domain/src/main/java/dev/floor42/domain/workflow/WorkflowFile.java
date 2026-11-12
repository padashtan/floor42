package dev.floor42.domain.workflow;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The file a workflow is exported to and imported from (ADR-0052):
 * {@code {"format":"floor42-workflow","formatVersion":1,"schemaVersion":N,"document":{...}}}. Nothing is journaled.
 *
 * <p>A file is data from outside, never trusted: it is read with the same bounded reader as a pinned document, every
 * problem is a code (the screen writes the sentence), and nothing in it is executed, opened or followed as a path. A
 * file is only ever read into a {@link Workflow}; saving it goes through the director's save like any other, which
 * writes this build's own schema version (never the file's own text).
 */
public final class WorkflowFile {

    public static final String FORMAT = "floor42-workflow";
    public static final int FORMAT_VERSION = 1;
    /** The most a file may weigh, in bytes. */
    public static final int MAX_BYTES = 64 * 1024;
    /** The most problems reported at once: a file full of them says so, not all of them. */
    public static final int MAX_PROBLEMS = 20;
    /** The longest file text, in characters: the longest document of any schema this build reads, and its envelope. */
    public static final int MAX_LENGTH = WorkflowDocumentV2.MAX_LENGTH + 256;

    private static final Set<String> KEYS = Set.of("format", "formatVersion", "schemaVersion", "document");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_.-]{1,40}");

    private WorkflowFile() {}

    /**
     * One problem, as a code and what it is about.
     *
     * @param code {@code IMPORT_...}, stable, translated by the screen
     * @param subject a key, step kind or number from the file, only when short and plain (letters, digits, {@code _ . -});
     *     null otherwise, so a file cannot put a sentence or a hidden character in a message
     */
    public record Problem(String code, String subject) {}

    /**
     * Every code an import can answer. The screen has a sentence for each, in both languages: a test reads this list
     * (it is written to the web's {@code import-codes.json}), so a code added here without its sentences fails there.
     */
    public enum Code {
        IMPORT_EMPTY,
        IMPORT_TOO_LARGE,
        IMPORT_TOO_LONG,
        IMPORT_NOT_JSON,
        IMPORT_NOT_AN_OBJECT,
        IMPORT_UNKNOWN_KEY,
        IMPORT_NOT_A_WORKFLOW_FILE,
        IMPORT_MISSING,
        IMPORT_FORMAT_VERSION,
        IMPORT_SCHEMA_INVALID,
        IMPORT_SCHEMA_NEWER,
        IMPORT_NAME_MISSING,
        IMPORT_STEPS_MISSING,
        IMPORT_STEP_NOT_AN_OBJECT,
        IMPORT_STEP_KIND_MISSING,
        IMPORT_STEP_UNKNOWN,
        IMPORT_AUTO_ACCEPT,
        IMPORT_MAX_TICKETS_MISPLACED,
        IMPORT_MAX_TICKETS_RANGE,
        IMPORT_STEP_ORDER,
        IMPORT_NO_APPROVAL,
        IMPORT_NO_ACCEPT,
        IMPORT_PRODUCE_UNREVIEWED,
        IMPORT_REVIEW_WITHOUT_PRODUCE,
        IMPORT_NOTHING_TO_RUN,
        IMPORT_DOCUMENT_REFUSED,
        // Schema version 2 (ADR-0052): the director's checks.
        IMPORT_CHECK_INCOMPLETE,
        IMPORT_CHECK_SLOT,
        IMPORT_STEP_RUNNER,
        IMPORT_STEP_TEXT,
        IMPORT_STEP_KEY,
        IMPORT_STEP_KEY_TAKEN,
        IMPORT_TOO_MANY_STEPS,
        IMPORT_STEP_NEEDS_PRODUCTION,
        // Schema version 3 (ADR-0061): the director's tasks.
        IMPORT_TASK_INCOMPLETE,
        IMPORT_TASK_SLOT,
        IMPORT_TASK_UNREVIEWED
    }

    /** What a file reads as. */
    public sealed interface Reading {
        record Read(Workflow workflow, int schemaVersion) implements Reading {}

        record Refused(List<Problem> problems) implements Reading {
            public Refused {
                problems = problems == null ? null : Collections.unmodifiableList(new ArrayList<>(problems));
            }
        }
    }

    /** The file of a company workflow, as saved: its document is kept as written. */
    public static String export(String document, int schemaVersion) {
        return "{\"format\":\"" + FORMAT + "\",\"formatVersion\":" + FORMAT_VERSION + ",\"schemaVersion\":"
                + schemaVersion + ",\"document\":" + document + "}";
    }

    /** Never throws: a file this build cannot read is a list of codes. */
    public static Reading read(String text) {
        List<Problem> problems = new ArrayList<>();
        Object root = parse(text, problems);
        if (!problems.isEmpty()) return new Reading.Refused(problems);
        if (!(root instanceof Map<?, ?> file)) {
            add(problems, Code.IMPORT_NOT_AN_OBJECT, null);
            return new Reading.Refused(problems);
        }
        for (Object k : file.keySet()) if (!KEYS.contains(k)) add(problems, Code.IMPORT_UNKNOWN_KEY, k);
        int schema = envelope(file, problems);
        Object document = file.get("document");
        if (schema >= 1 && schema <= WorkflowDocument.LIBRARY) DocumentCheck.run(document, schema, problems);
        // A version 1 document keeps version 1's length, whatever the file's own cap.
        if (problems.isEmpty() && schema == 1 && Json.write(document).length() > Json.MAX_LENGTH)
            add(problems, Code.IMPORT_TOO_LONG, null);
        if (!problems.isEmpty()) return new Reading.Refused(problems);
        return switch (WorkflowDocument.read(Json.write(document), schema)) {
            case WorkflowDocument.Reading.Read r -> new Reading.Read(r.workflow(), schema);
            case WorkflowDocument.Reading.Refused _ -> {
                // Defensive: the checks above name every refusal the reader has; a new one is still a refusal, not a
                // save.
                add(problems, Code.IMPORT_DOCUMENT_REFUSED, null);
                yield new Reading.Refused(problems);
            }
        };
    }

    /** The text as a value, or the one problem that stops reading it. */
    private static Object parse(String text, List<Problem> problems) {
        if (text == null || text.isEmpty()) {
            add(problems, Code.IMPORT_EMPTY, null);
        } else if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            add(problems, Code.IMPORT_TOO_LARGE, null);
        } else if (text.length() > MAX_LENGTH) {
            add(problems, Code.IMPORT_TOO_LONG, null);
        } else {
            try {
                return Json.parse(text, MAX_LENGTH);
            } catch (Json.Refused e) {
                // Handled: the reader's reason is English and positional; the screen gets the code.
                add(problems, Code.IMPORT_NOT_JSON, null);
            }
        }
        return null;
    }

    /** The file's own keys; returns the schema version (0 when it is missing or not a number). */
    private static int envelope(Map<?, ?> file, List<Problem> problems) {
        if (!FORMAT.equals(file.get("format"))) add(problems, Code.IMPORT_NOT_A_WORKFLOW_FILE, null);
        Object fv = file.get("formatVersion");
        if (fv == null) add(problems, Code.IMPORT_MISSING, "formatVersion");
        else if (!Integer.valueOf(FORMAT_VERSION).equals(fv)) add(problems, Code.IMPORT_FORMAT_VERSION, fv);
        Object sv = file.get("schemaVersion");
        int schema = sv instanceof Integer n ? n : 0;
        if (sv == null) add(problems, Code.IMPORT_MISSING, "schemaVersion");
        else if (schema < 1) add(problems, Code.IMPORT_SCHEMA_INVALID, null);
        else if (schema > WorkflowDocument.LIBRARY) add(problems, Code.IMPORT_SCHEMA_NEWER, schema);
        if (!(file.get("document") instanceof Map<?, ?>)) add(problems, Code.IMPORT_MISSING, "document");
        return schema;
    }

    static void add(List<Problem> problems, Code code, Object subject) {
        if (problems.size() >= MAX_PROBLEMS) return;
        String s = subject == null ? null : String.valueOf(subject);
        problems.add(new Problem(code.name(), s != null && PLAIN.matcher(s).matches() ? s : null));
    }
}
