package dev.floor42.server.iam;

import dev.floor42.server.iam.KeycloakClient.Tokens;

/**
 * Issues the script token of a step-up that succeeded (ADR-0083): opens it in the registry, journals {@code ApiTokenIssued}
 * with the subject and the binding, and keeps the offline session. Implemented in the API, as {@link PlanApprover} is:
 * the {@code iam} package does not reach into the application. It throws when nothing was issued; then no token works.
 *
 * @param secret shown once, to the browser that did the step-up
 * @param expires ISO-8601
 */
public interface BoundTokenIssuer {

    record Issued(String id, String secret, String scope, String expires) {}

    /** What the director asked for on the desk, already checked: a known scope, 1 to 30 days, a label of at most 80. */
    record Request(String scope, int days, String label) {}

    /** What the step-up proved, for the signature the journal keeps (ADR-0081: issuing a script token is a strong act). */
    record Proof(String acr, long authTime, String idTokenId) {}

    Issued issue(Request request, String subject, Tokens offline, Proof proof, String userAgent);
}
