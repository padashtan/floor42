package dev.floor42.server.iam.fga;

/** An OpenFGA call that did not end in an answer Floor42 can act on. Every code is a refusal, never an allow. */
public final class FgaException extends RuntimeException {

    /** Unreachable, timeout, key refused, non-200 or an answer that is not what the API documents. */
    public static final String UNAVAILABLE = "FGA_UNAVAILABLE";

    /** The pinned model or the store is unknown to OpenFGA, or a check was refused as invalid for it (400, 404). */
    public static final String MODEL_MISMATCH = "FGA_MODEL_MISMATCH";

    /** A write OpenFGA or Floor42 refused as input (a tuple of a relation that is never stored, too many tuples). */
    public static final String INVALID = "FGA_INVALID";

    /** IAM mode is on and no OpenFGA url is set. */
    public static final String NEEDED = "IAM_NEEDS_FGA";

    private final String code;

    public FgaException(String code, String message) {
        super(message);
        this.code = code;
    }

    public FgaException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
