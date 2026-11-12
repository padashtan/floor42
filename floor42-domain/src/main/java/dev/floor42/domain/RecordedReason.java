package dev.floor42.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reasons and descriptions Floor42 writes itself into events (a mission suspended for an unauthorized call, its
 * incident, a question withdrawn, a retrospective closed early). The journal keeps the English sentence; {@link #read}
 * gives back its code and values for a screen in another language. A reason someone typed has no code.
 */
public final class RecordedReason {

    static final String UNAUTHORIZED = "unauthorized execution of action ";
    static final String RETRO_CLOSED_EARLY = "closed by the director before every turn was heard";
    static final String SENT_BACK = "sent back with a constraint";
    static final String MISSION_CANCELLED = "mission cancelled";
    static final String ASKED_BACK = "asked back by the director: ";
    static final String RAN_WITHOUT = " without an Floor42 authorization: ";

    public static final String CODE_UNAUTHORIZED = "unauthorizedExecution";
    public static final String CODE_RETRO_CLOSED_EARLY = "retroClosedEarly";
    public static final String CODE_SENT_BACK = "sentBack";
    public static final String CODE_MISSION_CANCELLED = "missionCancelled";
    public static final String CODE_ASKED_BACK = "askedBack";
    public static final String CODE_RAN_WITHOUT = "ranWithoutAuthorization";

    private static final Pattern RAN =
            Pattern.compile("(\\S+) ran (\\S+) without an Floor42 authorization: (.*)", Pattern.DOTALL);

    private RecordedReason() {}

    static String unauthorizedReason(ActionId action) {
        return UNAUTHORIZED + action;
    }

    static String retroClosedEarly() {
        return RETRO_CLOSED_EARLY;
    }

    static String sentBack() {
        return SENT_BACK;
    }

    static String missionCancelled() {
        return MISSION_CANCELLED;
    }

    /** The question was withdrawn because the director asked back (ADR-0059); the text is theirs, kept as typed. */
    static String askedBack(String text) {
        return ASKED_BACK + text;
    }

    /** The director's words when this reason is an ask-back; empty for any other. */
    public static Optional<String> askedBackText(String text) {
        return text != null && text.startsWith(ASKED_BACK)
                ? Optional.of(text.substring(ASKED_BACK.length()))
                : Optional.empty();
    }

    static String ranWithout(AgentId agent, String tool, String summary) {
        return agent + " ran " + tool + RAN_WITHOUT + summary;
    }

    /** The code and values of a sentence this class wrote; empty for any other text. */
    public static Optional<Phrase> read(String text) {
        if (text == null) return Optional.empty();
        if (text.equals(RETRO_CLOSED_EARLY)) return Optional.of(Phrase.of(CODE_RETRO_CLOSED_EARLY));
        if (text.equals(SENT_BACK)) return Optional.of(Phrase.of(CODE_SENT_BACK));
        if (text.equals(MISSION_CANCELLED)) return Optional.of(Phrase.of(CODE_MISSION_CANCELLED));
        Optional<String> back = askedBackText(text);
        if (back.isPresent()) return Optional.of(Phrase.of(CODE_ASKED_BACK, "text", back.get()));
        if (text.startsWith(UNAUTHORIZED)) {
            String action = text.substring(UNAUTHORIZED.length());
            if (!action.isEmpty() && !action.contains(" "))
                return Optional.of(Phrase.of(CODE_UNAUTHORIZED, "action", action));
        }
        Matcher m = RAN.matcher(text);
        if (m.matches()) {
            return Optional.of(
                    Phrase.of(CODE_RAN_WITHOUT, "agent", m.group(1), "tool", m.group(2), "summary", m.group(3)));
        }
        return Optional.empty();
    }
}
