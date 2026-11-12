package dev.floor42.server.iam;

/** The identity provider cannot be reached or answered with a server error: callers fail closed, never default. */
public class IdpUnavailableException extends RuntimeException {

    /** The message never carries a body, a token or a secret. */
    public IdpUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
