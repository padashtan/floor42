package dev.floor42.server.iam;

/** The identity provider answered and said no (HTTP 4xx, or a redirect): the request is wrong or the grant is gone. */
public class IdpRefusedException extends IllegalStateException {

    /** The message holds the operation, the status and Keycloak's short error code, never a body or a credential. */
    private final int status;

    public IdpRefusedException(String message) {
        this(message, 0);
    }

    public IdpRefusedException(String message, int status) {
        super(message);
        this.status = status;
    }

    /**
     * The refusal is about the caller (Floor42's own client credential), not about the request's subject: 401 or 403,
     * or Keycloak's {@code invalid_client} / {@code unauthorized_client}. Introspection of a bad token is a 200
     * {@code active:false}, so a 4xx there never says anything about the token.
     */
    public boolean aboutCaller() {
        String m = getMessage() == null ? "" : getMessage();
        return status == 401 || status == 403 || m.contains("invalid_client") || m.contains("unauthorized_client");
    }

    /** The HTTP status, or 0 when the answer was 2xx but unusable. */
    public int status() {
        return status;
    }
}
