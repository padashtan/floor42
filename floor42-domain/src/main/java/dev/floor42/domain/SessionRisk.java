package dev.floor42.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The risk of one agent session, from signals only. Pure and deterministic: no model, no clock, no I/O. Thresholds are
 * constants; changing one is a code change with its row in the test table. Not thread safe: the caller locks.
 */
public final class SessionRisk {

    /** Burst window, inclusive at both ends. */
    public static final Duration WINDOW = Duration.ofMinutes(5);

    /** Refusals within {@link #WINDOW}: QUARANTINE is proposed. */
    public static final int BURST_REFUSALS = 3;

    /** Summed weight of the signals: one strong or two weak ones make an ALERT. */
    public static final int ALERT_SCORE = 2;

    /** Action ids kept for the event, the latest. */
    public static final int MAX_ACTIONS = 10;

    private static final Pattern LEASH_PATH = Pattern.compile(
            "(^|[/\\s])(\\.claude(/|\\s|$)|\\.git/hooks(/|\\s|$)|settings(\\.local)?\\.json(\\s|$)|\\.env(\\s|$))");
    private static final Pattern LEASH_ENV = Pattern.compile("^\\s*(printenv\\b.*|env\\s*(\\|.*)?|set\\s*(\\|.*)?)$");

    private final Set<RiskReason> reasons = new LinkedHashSet<>();
    private final Deque<ActionId> actions = new ArrayDeque<>();
    private final Deque<Instant> burst = new ArrayDeque<>();
    private Instant first;
    private int score;
    private boolean tainted;
    private RiskLevel level = RiskLevel.NONE;

    public RiskLevel level() {
        return level;
    }

    /** Empty when the level does not go up. Throws IllegalArgumentException for BURST or TAINTED_THEN_REFUSED. */
    public Optional<RiskRaise> add(RiskSignal s) {
        RiskReason r = s.reason();
        if (r == RiskReason.BURST || r == RiskReason.TAINTED_THEN_REFUSED) {
            throw new IllegalArgumentException(r + " is derived, never given");
        }
        if (first == null) {
            first = s.at();
        }
        reasons.add(r);
        score += r.weight();
        if (s.action() != null) {
            actions.addLast(s.action());
            if (actions.size() > MAX_ACTIONS) {
                actions.removeFirst();
            }
        }
        if (r == RiskReason.INJECTION_MARKER || r == RiskReason.UNTRUSTED_READ) {
            tainted = true;
        }
        if (r.refusal()) {
            noteRefusal(s.at());
        }
        RiskLevel next = compute();
        if (next.compareTo(level) <= 0) {
            return Optional.empty();
        }
        level = next;
        return Optional.of(new RiskRaise(next, new ArrayList<>(reasons), first, List.copyOf(actions)));
    }

    private void noteRefusal(Instant at) {
        if (tainted) {
            reasons.add(RiskReason.TAINTED_THEN_REFUSED);
        }
        burst.addLast(at);
        if (burst.size() > BURST_REFUSALS) {
            burst.removeFirst();
        }
        if (burst.size() == BURST_REFUSALS
                && !Duration.between(burst.peekFirst(), burst.peekLast())
                        .minus(WINDOW)
                        .isPositive()) {
            reasons.add(RiskReason.BURST);
        }
    }

    private RiskLevel compute() {
        if (reasons.contains(RiskReason.OWN_LEASH)
                || reasons.contains(RiskReason.BURST)
                || reasons.contains(RiskReason.TAINTED_THEN_REFUSED)) {
            return RiskLevel.QUARANTINE;
        }
        if (score >= ALERT_SCORE) {
            return RiskLevel.ALERT;
        }
        return score >= 1 ? RiskLevel.NOTICE : RiskLevel.NONE;
    }

    /** The agent reaching for its own leash: .claude/, .git/hooks, settings(.local).json, a .env file, or a bare env, printenv or set in Bash. */
    public static boolean ownLeash(String tool, String summary) {
        if (summary == null) {
            return false;
        }
        return LEASH_PATH.matcher(summary).find()
                || ("Bash".equals(tool) && LEASH_ENV.matcher(summary).matches());
    }
}
