package dev.floor42.domain.workflow;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A workflow of the company's list, as the director saved it (ADR-0050). Its document is read when it is applied; a
 * document this build cannot read stays listed, read only, with its reason.
 *
 * @param name as the director wrote it
 * @param reading the workflow, or the reason this build cannot use it
 */
public record CompanyWorkflow(String name, int schemaVersion, WorkflowDocument.Reading reading) {

    /** The built-in workflow's name: never a company workflow's. */
    public static final String BUILT_IN = "built-in";

    private static final int MAX_NAME = 60;
    private static final Pattern NAME_PATTERN = Pattern.compile("[\\p{IsLatin}\\p{Nd} .'_-]+");

    public Optional<Workflow> workflow() {
        return reading instanceof WorkflowDocument.Reading.Read r ? Optional.of(r.workflow()) : Optional.empty();
    }

    /** Why this build cannot use it; empty when it can. */
    public Optional<String> refusal() {
        return reading instanceof WorkflowDocument.Reading.Refused r ? Optional.of(r.reason()) : Optional.empty();
    }

    /**
     * The key names are compared by: NFKC, case folded, blanks trimmed. Two names with one key are one workflow, so a
     * lookalike cannot sit beside the name it imitates.
     */
    public static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    /** Why the director cannot give a company workflow this name; empty when they can. */
    public static Optional<String> nameRefusal(String name) {
        if (name == null || name.isBlank()) return Optional.of("A workflow has a name");
        String n = Normalizer.normalize(name, Normalizer.Form.NFKC).strip();
        if (n.length() > MAX_NAME) return Optional.of("A workflow's name has at most " + MAX_NAME + " characters");
        if (!NAME_PATTERN.matcher(n).matches()) {
            return Optional.of("A workflow's name holds letters, digits, spaces and . ' _ - only");
        }
        if (key(n).equals(BUILT_IN)) return Optional.of("\"" + BUILT_IN + "\" is Floor42's own workflow");
        return Optional.empty();
    }
}
