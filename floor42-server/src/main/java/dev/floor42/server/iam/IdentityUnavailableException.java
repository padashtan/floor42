package dev.floor42.server.iam;

/** A session cannot get its identity token now: it does not start (fail closed). The message never holds a token. */
public class IdentityUnavailableException extends RuntimeException {

    public IdentityUnavailableException(String message) {
        super(message);
    }

    public IdentityUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
