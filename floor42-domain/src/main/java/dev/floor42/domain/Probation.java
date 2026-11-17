package dev.floor42.domain;

/** The probation rules (ADR-0082), pure. Narrowing is automatic; widening is only ever proposed. */
public final class Probation {

    /**
     * Accepted patches, since the last change of level and with no incident, after which a raise is proposed to the
     * director. A named constant: changing it is a code change with its row in {@code ProbationTest}.
     */
    public static final int ACCEPTED_FOR_RAISE = 5;

    private Probation() {}

    /** True when the agent earned a proposal: below STANDARD and enough accepted patches. */
    public static boolean raiseProposed(TrustLevel level, int acceptedSinceChange) {
        return level != TrustLevel.STANDARD && acceptedSinceChange >= ACCEPTED_FOR_RAISE;
    }
}
