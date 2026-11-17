package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AssurancePolicy.Act;
import dev.floor42.domain.AssurancePolicy.ActKind;
import dev.floor42.domain.AssurancePolicy.Seat;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.DeskEvent.ApprovalSigned;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.iam.BoundTokenIssuer;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.ScriptTokenBindings;
import dev.floor42.server.security.SessionRegistry;
import dev.floor42.server.security.SessionRegistry.Scope;
import dev.floor42.server.security.SessionRegistry.Session;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Issues a script token bound to a Keycloak session once the director's step-up succeeded (ADR-0083): the token
 * opens in the registry, {@code ApiTokenIssued} names its subject and binding, and the offline session goes to the
 * vault. If any step fails nothing is left working.
 */
@ApplicationScoped
public class BoundTokens implements BoundTokenIssuer {

    @Inject
    SessionRegistry sessions;

    @Inject
    OfficeService office;

    @Inject
    ScriptTokenBindings bindings;

    @Override
    public Issued issue(Request request, String subject, Tokens offline, Proof proof, String userAgent) {
        Scope scope = Scope.parse(request.scope()).orElseThrow();
        var issued = sessions.issueScript(scope, request.days(), request.label(), userAgent);
        Session s = issued.session();
        try {
            // The strong signature is journaled before the act, as every assured act's is (ADR-0081).
            Act act = new Act(ActKind.SCRIPT_TOKEN, s.id());
            var signed = new ApprovalSigned(act, Seat.FIRST, subject, proof.acr(), proof.authTime(), proof.idTokenId());
            office.execute(c -> c.preferences()
                    .recordSignatures(office.context(Actor.director(), Origin.REAL), act, List.of(signed)));
            office.execute(c -> c.terminals()
                    .recordBoundApiTokenIssued(
                            office.context(Actor.director(), Origin.REAL),
                            s.id(),
                            s.hash(),
                            scope.wire(),
                            s.expires().toString(),
                            request.label(),
                            subject));
            bindings.bind(s.id(), offline, subject);
        } catch (RuntimeException e) {
            // Not journaled or not bound, not issued: a token the journal or the vault does not know of must not work.
            sessions.revoke(s.id());
            bindings.end(s.id());
            throw e;
        }
        return new Issued(s.id(), issued.secret(), scope.wire(), s.expires().toString());
    }
}
