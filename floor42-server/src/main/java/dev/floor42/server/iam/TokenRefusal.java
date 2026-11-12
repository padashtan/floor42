package dev.floor42.server.iam;

/** Why a token is refused; the wire code is the constant's name. */
public enum TokenRefusal {
    MALFORMED,
    ISSUER_UNKNOWN,
    BAD_SIGNATURE,
    EXPIRED,
    WRONG_AUDIENCE,
    WRONG_AGENT,
    WRONG_MISSION,
    WRONG_TICKET,
    SESSION_ENDED,
    WRONG_PROCESS,
    REVOKED,
    KEYCLOAK_UNREACHABLE,
    /** Keycloak answers but refuses Floor42's own floor42-api credential: not unreachable, not a verdict on the token. */
    KEYCLOAK_REFUSED
}
