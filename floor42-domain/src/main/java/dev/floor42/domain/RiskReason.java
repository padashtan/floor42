package dev.floor42.domain;

import java.util.Optional;

/** Why a session looks risky. {@code weight} counts toward ALERT; {@code refusal} counts toward a burst. */
public enum RiskReason {
    POLICY_DENIED(1, true),
    COMMAND_DENIED(1, true),
    OUTSIDE_WORKSPACE(2, true),
    BROKER_PATH_NOT_ALLOWED(2, true),
    BROKER_METHOD_NOT_ALLOWED(2, true),
    BROKER_RESOURCE_NOT_GRANTED(2, true),
    BROKER_RESOURCE_UNKNOWN(2, true),
    /** Also taints the session. */
    INJECTION_MARKER(1, false),
    /** Taints the session, raises nothing by itself. */
    UNTRUSTED_READ(0, false),
    /** The agent reaching for its own leash: QUARANTINE at once. */
    OWN_LEASH(0, false),
    /** Derived by {@link SessionRisk}, never given by a caller. */
    BURST(0, false),
    /** Derived by {@link SessionRisk}, never given by a caller. */
    TAINTED_THEN_REFUSED(0, false);

    private final int weight;
    private final boolean refusal;

    RiskReason(int weight, boolean refusal) {
        this.weight = weight;
        this.refusal = refusal;
    }

    public int weight() {
        return weight;
    }

    public boolean refusal() {
        return refusal;
    }

    /** The broker's refusal code as a reason; empty for codes that are not the agent's doing. */
    public static Optional<RiskReason> broker(String code) {
        return switch (code == null ? "" : code) {
            case "PATH_NOT_ALLOWED" -> Optional.of(BROKER_PATH_NOT_ALLOWED);
            case "METHOD_NOT_ALLOWED" -> Optional.of(BROKER_METHOD_NOT_ALLOWED);
            case "RESOURCE_NOT_GRANTED" -> Optional.of(BROKER_RESOURCE_NOT_GRANTED);
            case "RESOURCE_UNKNOWN" -> Optional.of(BROKER_RESOURCE_UNKNOWN);
            default -> Optional.empty();
        };
    }
}
