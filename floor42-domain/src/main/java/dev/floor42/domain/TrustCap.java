package dev.floor42.domain;

/**
 * The trust cap (ADR-0082): applied to the permission table's decision, never written as a rule. A rule naming the
 * agent outranks the table, so a rule could widen a DENY; a cap is a {@code min} and never does. Restrictiveness goes
 * ALLOW, ASK, ASK_ALWAYS, DENY ({@link Decision} order).
 */
public final class TrustCap {

    private TrustCap() {}

    /** The decision after the cap: never less restrictive than {@code table}. */
    public static Decision cap(TrustLevel level, ActionCategory category, Decision table) {
        if (table == Decision.DENY) return Decision.DENY;
        Decision floor = switch (level) {
            case STANDARD -> Decision.ALLOW;
            case PROBATION ->
                switch (category) {
                    case WRITE -> Decision.ASK;
                    case EXECUTE, NETWORK -> Decision.ASK_ALWAYS;
                    case READ, FLOOR42, OTHER -> Decision.ALLOW;
                };
            case SHADOW ->
                switch (category) {
                    case WRITE, EXECUTE, NETWORK -> Decision.DENY;
                    case READ, FLOOR42, OTHER -> Decision.ALLOW;
                };
        };
        return table.compareTo(floor) >= 0 ? table : floor;
    }

    /** True when the cap, and not the table, turns the call into a proposal the director runs (shadow mode). */
    public static boolean proposes(TrustLevel level, ActionCategory category, Decision table) {
        return table != Decision.DENY && cap(level, category, table) == Decision.DENY;
    }
}
