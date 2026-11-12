package dev.floor42.domain;

import java.util.Optional;

/**
 * What an approval answer covers, and the English sentence Floor42 writes for it in the question's context. The
 * journal keeps that sentence as it always was; {@link #read} gives back its code and values, so a screen writes it in
 * its own language (ADR-0021) without the journal ever changing.
 */
public final class ApprovalScope {

    static final String APPLIES = "\nYour answer applies to ";
    static final String FOR_THE_MISSION = " for the rest of this mission";
    static final String COMMAND = "this exact command from ";
    static final String EVERY = "every ";
    static final String REQUEST_FROM = " request from ";
    static final String EDITED = "this call only: you edited ";
    static final String IN_THE_WORKSHOP = " in the workshop";
    static final String ASK_ALWAYS = "this call only (setting: ask every time)";

    /** The codes {@link #read} gives, each with the values that fill it ({@code summary} always). */
    public static final String CODE_COMMAND = "approvalCommand";

    public static final String CODE_TOOL = "approvalTool";
    public static final String CODE_EDITED = "approvalEdited";
    public static final String CODE_ASK_ALWAYS = "approvalAskAlways";

    private ApprovalScope() {}

    /** The scope of a remembered answer: the exact command for a shell call, else every call of the same tool. */
    static String of(Action a) {
        return a.category() == ActionCategory.EXECUTE
                ? COMMAND + a.agent()
                : EVERY + a.tool() + REQUEST_FROM + a.agent();
    }

    /** A remembered answer: it covers its scope until the mission ends. */
    static String forTheMission(Action a) {
        return of(a) + FOR_THE_MISSION;
    }

    /** A question for one call, because the director edited the file the call writes. */
    static String editedByDirector(String path) {
        return EDITED + path + IN_THE_WORKSHOP;
    }

    /** A question for one call, because the table says to ask every time. */
    static String askAlways() {
        return ASK_ALWAYS;
    }

    /** The context of an approval question: the action's summary, then what the answer covers. */
    static String context(String summary, String coverage) {
        return summary + APPLIES + coverage + ".";
    }

    /** The code and values of a context {@link #context} wrote; empty for any other text (an agent's own words). */
    public static Optional<Phrase> read(String context) {
        if (context == null || !context.endsWith(".")) return Optional.empty();
        int at = context.lastIndexOf(APPLIES);
        if (at < 0) return Optional.empty();
        String summary = context.substring(0, at);
        String coverage = context.substring(at + APPLIES.length(), context.length() - 1);
        if (coverage.equals(ASK_ALWAYS)) return Optional.of(Phrase.of(CODE_ASK_ALWAYS, "summary", summary));
        if (coverage.startsWith(EDITED) && coverage.endsWith(IN_THE_WORKSHOP)) {
            String path = between(coverage, EDITED, IN_THE_WORKSHOP);
            return Optional.of(Phrase.of(CODE_EDITED, "summary", summary, "path", path));
        }
        if (!coverage.endsWith(FOR_THE_MISSION)) return Optional.empty();
        String scope = coverage.substring(0, coverage.length() - FOR_THE_MISSION.length());
        if (scope.startsWith(COMMAND) && scope.length() > COMMAND.length()) {
            return Optional.of(Phrase.of(CODE_COMMAND, "summary", summary, "agent", scope.substring(COMMAND.length())));
        }
        int from = scope.lastIndexOf(REQUEST_FROM);
        if (scope.startsWith(EVERY) && from > EVERY.length()) {
            String tool = scope.substring(EVERY.length(), from);
            String agent = scope.substring(from + REQUEST_FROM.length());
            return Optional.of(Phrase.of(CODE_TOOL, "summary", summary, "tool", tool, "agent", agent));
        }
        return Optional.empty();
    }

    private static String between(String text, String prefix, String suffix) {
        return text.substring(prefix.length(), Math.max(prefix.length(), text.length() - suffix.length()));
    }
}
