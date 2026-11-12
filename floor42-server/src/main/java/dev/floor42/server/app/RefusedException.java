package dev.floor42.server.app;

/**
 * A request Floor42 refuses, with the HTTP status that says why: 400 when the request itself is wrong, 404 when what it names does not exist, 409 when it
 * conflicts with the state of things, 413 when it is too large. One mapper turns it into the documented
 * {@code {code, message}} body; the code is stable and meant for API clients.
 */
public final class RefusedException extends RuntimeException {

    public static final int BAD_REQUEST = 400;
    public static final int NOT_FOUND = 404;
    public static final int CONFLICT = 409;
    public static final int TOO_LARGE = 413;
    public static final int UNAVAILABLE = 503;

    private final int status;
    private final String code;

    public RefusedException(int status, String code, String message) {
        this(status, code, message, null);
    }

    /** A refusal that comes from a lower failure (git, the disk): the cause stays for the log. */
    public RefusedException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public static RefusedException conflict(String code, String message, Throwable cause) {
        return new RefusedException(CONFLICT, code, message, cause);
    }

    public static RefusedException invalid(String code, String message, Throwable cause) {
        return new RefusedException(BAD_REQUEST, code, message, cause);
    }

    /** The request is wrong whatever the state: a missing field, a value out of range, an unreadable content. */
    public static RefusedException invalid(String code, String message) {
        return new RefusedException(BAD_REQUEST, code, message);
    }

    /** What the request names does not exist. */
    public static RefusedException notFound(String code, String message) {
        return new RefusedException(NOT_FOUND, code, message);
    }

    /** The request is well formed but the state of things refuses it now. */
    public static RefusedException conflict(String code, String message) {
        return new RefusedException(CONFLICT, code, message);
    }

    public static RefusedException tooLarge(String code, String message) {
        return new RefusedException(TOO_LARGE, code, message);
    }

    /** A service Floor42 depends on cannot answer now (the identity provider): 503, the message says why. */
    public static RefusedException unavailable(String code, String message) {
        return new RefusedException(UNAVAILABLE, code, message);
    }

    public static RefusedException unavailable(String code, String message, Throwable cause) {
        return new RefusedException(UNAVAILABLE, code, message, cause);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
