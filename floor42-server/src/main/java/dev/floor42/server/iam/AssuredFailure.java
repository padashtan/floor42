package dev.floor42.server.iam;

/** A refusal of the act itself, as the layer that carries it out words it (ADR-0081). */
public final class AssuredFailure extends RuntimeException {
    private final int status;
    private final String code;

    public AssuredFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }
}
