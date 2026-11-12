package dev.floor42.domain;

import java.util.Optional;
import java.util.function.Function;

/**
 * The ruling's implementing option and what it says writing will be on the implementation ticket (Write and Edit). The
 * label is fixed when the question is asked; {@link #writesNow} tells the screen what the same tables give later.
 */
public final class ImplementOption {

    /** Every implementing option starts so; the words in brackets say what writing will be (1.14.0). */
    static final String PREFIX = "Adopt and implement (";
    /** Since 1.0 and still offered when writing will ask: journaled questions keep it. */
    static final String WRITES_ASK = PREFIX + "writes ask you first)";

    static final String WRITES_ALLOWED = PREFIX + "writes allowed, per your permissions)";
    static final String WRITES_REFUSED = PREFIX + "writes refused, per your permissions)";
    static final String WRITES_MIXED = PREFIX + "writes per your permissions, tool by tool)";

    /** What writing will be: each tool's decision when Write and Edit agree (ask covers ask every time). */
    public enum Writes {
        ALLOW,
        ASK,
        DENY,
        MIXED
    }

    private ImplementOption() {}

    /** Whether the director's answer asks for the implementation, whatever label the question carried. */
    static boolean implementing(String chosen) {
        return chosen.startsWith(PREFIX);
    }

    static String label(Writes writes) {
        return switch (writes) {
            case ALLOW -> WRITES_ALLOWED;
            case ASK -> WRITES_ASK;
            case DENY -> WRITES_REFUSED;
            case MIXED -> WRITES_MIXED;
        };
    }

    /** What an implementing option said, with or without the objection's suffix; empty for any other option. */
    public static Optional<Writes> said(String option) {
        for (Writes w : Writes.values()) {
            if (option.startsWith(label(w))) return Optional.of(w);
        }
        return Optional.empty();
    }

    /**
     * What Write and Edit would be on the implementation ticket if it were planned now: the director's line for the
     * mission where set, the default table otherwise ({@link DefaultPermissions#forImplementation}).
     *
     * @param defaults the director's default line of a tool, if set
     */
    public static Writes writesNow(Mission m, Function<String, Optional<Decision>> defaults) {
        Decision write = writing(m, "Write", defaults);
        if (write != writing(m, "Edit", defaults)) return Writes.MIXED;
        return switch (write) {
            case ALLOW -> Writes.ALLOW;
            case DENY -> Writes.DENY;
            case ASK, ASK_ALWAYS -> Writes.ASK;
        };
    }

    private static Decision writing(Mission m, String tool, Function<String, Optional<Decision>> defaults) {
        Decision byDefault = DefaultPermissions.of(defaults.apply(tool), tool);
        return DefaultPermissions.forImplementation(m, tool, ActionCategory.WRITE, byDefault);
    }
}
