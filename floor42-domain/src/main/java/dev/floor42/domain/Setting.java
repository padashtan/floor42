package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Company-wide settings the director changes from the settings panel. Each change is journaled; a setting never changed
 * takes the daemon's default. The domain only checks that a value is acceptable.
 */
public enum Setting {
    /** How many sessions may run at once on one mission (ADR-0019). */
    PARALLEL_SESSIONS(List.of("1", "2", "3", "4")),
    /** A session is held when a usage window would likely pass this fraction (ADR-0016). */
    PLAN_LIMIT_THRESHOLD(List.of()),
    /** The model retrospective turns run on (ADR-0020). */
    RETRO_MODEL(List.of("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")),
    /** The language agents write their prose in; the marker lines Floor42 reads stay in English. */
    AGENT_LANGUAGE(List.of("en", "fr")),
    /** How the director thinks, in their own words: the lens of their double on an idea committee. Free text. */
    DIRECTOR_PROFILE(List.of()),
    /**
     * The most one session may spend, in USD as Claude Code estimates it: Claude Code stops the session once a turn
     * passes it (ADR-0025).
     */
    SESSION_BUDGET_USD(List.of()),
    /** The look of the office and its screens: one decade each. */
    OFFICE_THEME(List.of("1980s", "1990s", "2000s", "2020s")),
    /**
     * Where shell commands and checks run: on the director's machine as is ("off"), or isolated by the OS in a WSL2
     * sandbox that only sees the worktree ("wsl", ADR-0028).
     */
    SHELL_SANDBOX(List.of("off", "wsl")),
    /** Whether the sandbox reaches the network. */
    SANDBOX_NETWORK(List.of("off", "on")),
    /**
     * Kinds of Claude Code stream line Floor42 does not know that the director accepted, comma-separated (such as
     * {@code system/new_thing}): Floor42 records them in the session, what looks like a secret masked, and stops
     * warning.
     */
    ACCEPTED_STREAM_LINES(List.of()),
    /**
     * How many minutes a mission may sit with work the dispatcher would start and nothing running before Floor42 calls
     * it stuck.
     */
    STALL_MINUTES(List.of("1", "2", "5", "10", "15", "30", "60")),
    /**
     * The budget offered for a new mission when its plan is approved, in USD as Claude Code estimates it, or "none" to
     * offer no amount. The director still decides each mission's cap; this is only what the approval proposes.
     */
    MISSION_BUDGET_USD(List.of());

    /** The bounds of a mission's default cap, in USD. */
    public static final double MISSION_BUDGET_MIN = 0.05;

    public static final double MISSION_BUDGET_MAX = 1_000;

    /** The value of {@link #MISSION_BUDGET_USD} that offers no default amount. */
    public static final String NO_DEFAULT = "none";

    /** The bounds of a session's cap, in USD. */
    public static final double SESSION_BUDGET_MIN = 0.05;

    public static final double SESSION_BUDGET_MAX = 100;

    /** The longest director's profile kept: it goes into every brief of the double. */
    public static final int PROFILE_MAX = 2_000;

    private final List<String> options;

    Setting(List<String> options) {
        this.options = options == null ? null : Collections.unmodifiableList(new ArrayList<>(options));
    }

    /** The accepted values, or empty when any value in range is accepted. */
    public List<String> options() {
        return options;
    }

    /** The value as stored, or a refusal naming what is accepted. */
    String normalize(String value) {
        require(value != null && !value.isBlank(), Refusal.INVALID_ARGUMENT, "A value is required for " + this);
        if (this == DIRECTOR_PROFILE) {
            require(
                    value.strip().length() <= PROFILE_MAX,
                    Refusal.INVALID_ARGUMENT,
                    "The profile is at most " + PROFILE_MAX + " characters");
            return value.strip();
        }
        if (this == ACCEPTED_STREAM_LINES) return streamLines(value);
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (this == SESSION_BUDGET_USD) {
            return amount(
                    v, SESSION_BUDGET_MIN, SESSION_BUDGET_MAX, "The cap is an amount in USD, such as 2.5", "The cap");
        }
        if (this == MISSION_BUDGET_USD) {
            if (v.equals(NO_DEFAULT)) return NO_DEFAULT;
            return amount(
                    v,
                    MISSION_BUDGET_MIN,
                    MISSION_BUDGET_MAX,
                    "The default cap is an amount in USD, such as 5, or none",
                    "The default cap");
        }
        if (this == PLAN_LIMIT_THRESHOLD) {
            double d = number(v, "The threshold is a fraction, such as 0.9");
            require(d >= 0.5 && d <= 1.0, Refusal.INVALID_ARGUMENT, "The threshold is between 0.5 and 1.0");
            return Double.toString(d);
        }
        require(options.contains(v), Refusal.INVALID_ARGUMENT, this + " is one of " + String.join(", ", options));
        return v;
    }

    private static String streamLines(String value) {
        List<String> kinds = java.util.Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(k -> !k.isEmpty())
                .distinct()
                .toList();
        require(!kinds.isEmpty() && kinds.size() <= 30, Refusal.INVALID_ARGUMENT, "Between 1 and 30 kinds of line");
        for (String k : kinds) {
            require(
                    k.matches("[A-Za-z0-9_./:()-]{1,60}"),
                    Refusal.INVALID_ARGUMENT,
                    "A kind of line is such as system/new_thing, not " + k);
        }
        return String.join(",", kinds);
    }

    /** An amount in USD within its range, as stored. */
    private static String amount(String v, double min, double max, String notANumber, String what) {
        double d = number(v, notANumber);
        require(d >= min && d <= max, Refusal.INVALID_ARGUMENT, what + " is between " + min + " and " + max + " USD");
        return Double.toString(d);
    }

    private static double number(String v, String notANumber) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new DomainException(Refusal.INVALID_ARGUMENT, notANumber, e);
        }
    }
}
