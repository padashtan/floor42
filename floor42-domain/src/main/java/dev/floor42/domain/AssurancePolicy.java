package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;

import dev.floor42.domain.event.DeskEvent.ApprovalSigned;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which acts need more than a click, and what proves it (ADR-0081). A fixed table, not a setting: the one switch is
 * four eyes, and turning it off is itself a four-eyes act. Pure: the proof is the {@link ApprovalSigned} values a
 * server checked against the identity provider, and the domain checks again what it can (the level reached, the
 * freshness, the act, two different people).
 */
public final class AssurancePolicy {

    /** The authentication context class reference Keycloak returns after a second factor. */
    public static final String ACR_STRONG = "floor42-strong";

    /** How old a signature may be when the act is carried out. */
    public static final Duration FRESH = Duration.ofSeconds(120);

    /** How long a first signature waits for the second one. */
    public static final Duration COUNTERSIGN_WINDOW = Duration.ofMinutes(30);

    private static final Duration SKEW = Duration.ofSeconds(30);

    /** The tools that reach the network with a plain read (GET): the only NETWORK calls that keep today's click. */
    private static final Set<String> READ_ONLY_NETWORK = Set.of("WebFetch", "WebSearch");

    private AssurancePolicy() {}

    public enum ActKind {
        PLAN,
        QUESTION,
        RULE,
        GRANT,
        PULL_REQUEST,
        TRUST,
        SCRIPT_TOKEN,
        SETTING,
        IAM_SETTINGS
    }

    public enum Seat {
        FIRST,
        SECOND
    }

    /** BASIC is today's click; STRONG one fresh signature at the strong level; FOUR_EYES two, by two people. */
    public enum Level {
        BASIC,
        STRONG,
        FOUR_EYES
    }

    /** What was signed: the kind of act and the thing it is about (a question id, a rule, a setting). */
    public record Act(ActKind kind, String ref) {
        public Act {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(ref, "ref");
        }
    }

    /** The table: the level an act of this kind needs, with four eyes on or off. */
    public static Level required(ActKind kind, boolean fourEyesOn) {
        return switch (kind) {
            case PLAN, QUESTION, SCRIPT_TOKEN -> Level.STRONG;
            case RULE, GRANT, PULL_REQUEST, TRUST, IAM_SETTINGS -> fourEyesOn ? Level.FOUR_EYES : Level.STRONG;
            case SETTING -> Level.FOUR_EYES;
        };
    }

    /**
     * An ALLOW answer to a call that reaches outside: any NETWORK tool that is not a plain read, which is every remote
     * MCP call. Floor42 knows no HTTP method, so WebFetch and WebSearch (GET) are the only reads. Any other answer, and
     * any other category, keeps the click.
     */
    public static Level forQuestion(Action action) {
        boolean outside = action.category() == ActionCategory.NETWORK && !READ_ONLY_NETWORK.contains(action.tool());
        return outside ? Level.STRONG : Level.BASIC;
    }

    /** A permission rule that sets ALLOW on EXECUTE or NETWORK is risky; any other rule keeps the click. */
    public static Level forRule(ActionCategory category, Decision decision, boolean fourEyesOn) {
        boolean risky = decision == Decision.ALLOW
                && (category == ActionCategory.EXECUTE || category == ActionCategory.NETWORK);
        return risky ? required(ActKind.RULE, fourEyesOn) : Level.BASIC;
    }

    /**
     * The signatures that carried the act, checked, or the refusal. A first signature is fresh ({@link #FRESH}) when the
     * act needs one; with four eyes it may wait {@link #COUNTERSIGN_WINDOW} for the second, which is fresh. Both are at
     * the strong level, for this act, and made by two different subjects. Nothing is recorded here.
     */
    public static List<ApprovalSigned> verify(CommandContext ctx, Act act, Level level, List<ApprovalSigned> signed) {
        if (level == Level.BASIC) return List.of();
        List<ApprovalSigned> s = signed == null ? List.of() : signed;
        int needed = level == Level.FOUR_EYES ? 2 : 1;
        require(
                s.size() >= needed,
                Refusal.ASSURANCE_MISSING,
                "This act needs " + needed + " signature" + (needed == 1 ? "" : "s") + " at the strong level");
        require(s.size() == needed, Refusal.INVALID_ARGUMENT, "Exactly " + needed + " signatures, not " + s.size());
        ApprovalSigned first = s.get(0);
        check(ctx, act, Seat.FIRST, first, level == Level.FOUR_EYES ? COUNTERSIGN_WINDOW : FRESH);
        if (needed == 2) {
            ApprovalSigned second = s.get(1);
            check(ctx, act, Seat.SECOND, second, FRESH);
            require(
                    !first.subject().equals(second.subject()),
                    Refusal.SAME_PERSON,
                    "The second signature is the same person as the first");
        }
        return s;
    }

    private static void check(CommandContext ctx, Act act, Seat seat, ApprovalSigned s, Duration window) {
        require(
                act.equals(s.act()) && s.seat() == seat,
                Refusal.ASSURANCE_MISSING,
                "A signature for another act or another seat does not count");
        require(s.subject() != null && !s.subject().isBlank(), Refusal.ASSURANCE_MISSING, "A signature has no signer");
        require(
                ACR_STRONG.equals(s.acr()),
                Refusal.ASSURANCE_TOO_LOW,
                "The sign-in did not reach " + ACR_STRONG + ": the second factor is missing");
        Instant at = Instant.ofEpochSecond(s.authTime());
        Refusal late =
                seat == Seat.FIRST && window == COUNTERSIGN_WINDOW ? Refusal.SIGNATURE_EXPIRED : Refusal.STEP_UP_STALE;
        require(!at.isBefore(ctx.now().minus(window)), late, "The sign-in is too old: sign in again");
        require(!at.isAfter(ctx.now().plus(SKEW)), Refusal.STEP_UP_STALE, "The sign-in claims a time to come");
    }
}
