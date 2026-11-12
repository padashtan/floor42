package dev.floor42.server.iam;

/** IAM mode is on and the vault holds no floor42-api secret: no session token can be introspected. */
public class ApiSecretMissingException extends IllegalStateException {

    public ApiSecretMissingException() {
        super("No floor42-api secret in the vault");
    }
}
