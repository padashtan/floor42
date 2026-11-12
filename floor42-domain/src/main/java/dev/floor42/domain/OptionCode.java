package dev.floor42.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The stable meaning of an option Floor42 itself offers in a question, apart from the English label shown and journaled
 * as the answer. Code compares codes, never labels: a label may be reworded, a code stays. A question records the code
 * of each option ({@code QuestionAsked.optionCodes}, ADR-0009 amendment 2026-10-31); a question journaled before that has
 * none, and its options are read by their label, current or former ({@link #legacyLabels}).
 */
public enum OptionCode {
    RETRY("Retry"),
    LEAVE_IT("Leave it"),
    GO_ON_WITHOUT("Go on without it"),
    DROP_TICKET("Drop the ticket"),
    ACCEPT_AS_IS("Accept as is"),
    SEND_BACK("Send back to work"),
    REVIEW_AGAIN("Request another review"),
    RAISE_BUDGET("Raise the budget by 50%"),
    STOP_HERE("Stop here"),
    RUN_ANYWAY("Run anyway"),
    WAIT_FOR_RESET("Wait for the reset"),
    HIRE("Hire them"),
    HIRE_ANYWAY("Hire anyway"),
    NOT_NOW("Not now"),
    DO_IT_MYSELF("I do the step myself"),
    ALLOW_EXCEPTION("I allow an exception"),
    INSTALL_MISSING("I install what is missing"),
    WITHOUT_DOCKER("Go on without Docker");

    private final String label;
    private final List<String> legacyLabels;

    OptionCode(String label, String... legacyLabels) {
        this.label = label;
        this.legacyLabels = List.of(legacyLabels);
    }

    /** The English label shown to the director and recorded as the answer. */
    public String label() {
        return label;
    }

    /** Labels this option had before: a question journaled then is still read right. Empty so far. */
    public List<String> legacyLabels() {
        return legacyLabels;
    }

    /** The option of this label, current or former; null for an option Floor42 does not know (an agent's). */
    public static OptionCode byLabel(String label) {
        if (label == null) return null;
        for (OptionCode c : values()) {
            if (c.label.equals(label) || c.legacyLabels.contains(label)) return c;
        }
        return null;
    }

    /** The code of each of Floor42's own options, by position; null for any other. */
    public static List<String> codesOf(List<String> options) {
        List<String> codes = new ArrayList<>();
        for (String o : options) {
            OptionCode c = byLabel(o);
            codes.add(c == null ? null : c.name());
        }
        return Collections.unmodifiableList(codes);
    }

    /** No code for any option: a question whose options an agent wrote, or whose labels are not Floor42's. */
    public static List<String> none(List<String> options) {
        return Collections.unmodifiableList(new ArrayList<>(Collections.nCopies(options.size(), (String) null)));
    }
}
