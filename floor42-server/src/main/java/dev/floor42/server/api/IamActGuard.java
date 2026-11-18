package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AssurancePolicy;
import dev.floor42.domain.Origin;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.iam.AssuredActs;
import dev.floor42.server.iam.IamSettings;
import dev.floor42.server.security.LocalAccessGuard;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.Supplier;

/**
 * The step-up of the acts of {@code /api/iam} that widen what an agent may do or change who the identity provider is
 * (ADR-0081): a grant, an MCP tick, a resource's address, the identity settings. In IAM mode such an act is carried out
 * only with the signatures the table asks for (one strong, two with four eyes); a token, the director's own included,
 * gets 403 {@code HUMAN_ONLY}, a break-glass sign-in 403 {@code STEP_UP_NEEDS_SIGN_IN}. The signatures are journaled
 * ({@code ApprovalSigned}) before the act runs. With IAM mode off, or when the change widens nothing, the act just runs.
 */
@ApplicationScoped
public class IamActGuard {

    @Inject
    AssuredActs acts;

    @Inject
    IamSettings iam;

    @Inject
    OfficeService office;

    @Inject
    dev.floor42.server.app.core.OfficeSettings settings;

    @Inject
    CurrentVertxRequest current;

    /**
     * Runs {@code act} now when no proof is needed; else throws the answer the caller follows (403, or 202 with the
     * sign-in path) and runs {@code act} only once the signatures are in.
     */
    public <T> T run(AssurancePolicy.ActKind kind, String ref, boolean sensitive, Supplier<T> act) {
        if (!sensitive || !iam.enabled()) return act.get();
        if (LocalAccessGuard.viaOf(current.getCurrent()) != LocalAccessGuard.Via.COOKIE)
            throw AssuranceResponses.humanOnly();
        var session = LocalAccessGuard.sessionOf(current.getCurrent());
        if (!acts.mayAssure(session)) throw AssuranceResponses.needsSignIn();
        AssurancePolicy.Act what = new AssurancePolicy.Act(kind, ref);
        boolean fourEyes =
                AssurancePolicy.required(kind, "on".equals(settings.valueOf(dev.floor42.domain.Setting.FOUR_EYES)))
                        == AssurancePolicy.Level.FOUR_EYES;
        String path = acts.record(what, fourEyes, session.get(), signed -> {
            try {
                office.execute(c ->
                        c.preferences().recordSignatures(office.context(Actor.director(), Origin.REAL), what, signed));
                act.get();
            } catch (dev.floor42.server.app.core.RefusedException e) {
                throw AssuranceResponses.failure(e);
            }
        });
        throw AssuranceResponses.followStepUp(path);
    }
}
