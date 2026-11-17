package dev.floor42.server.app.mission;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.TrustLevel;
import dev.floor42.server.iam.IamSettings;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The proof available before the assured approvals exist. Without IAM mode there is one local director behind the
 * single-use sign-in link: no stronger proof exists, and the click is the director's. In IAM mode the proof is a strong
 * sign-in this class cannot check: it refuses, so no raise passes on a weaker proof than the one the policy asks for.
 */
@ApplicationScoped
class LocalTrustRaiseStepUp implements TrustRaiseStepUp {

    private final IamSettings iam;

    LocalTrustRaiseStepUp(IamSettings iam) {
        this.iam = iam;
    }

    @Override
    public boolean proven(AgentId agent, TrustLevel to) {
        return !iam.enabled();
    }
}
