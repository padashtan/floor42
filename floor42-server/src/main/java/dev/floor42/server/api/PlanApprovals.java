package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.asDirector;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.iam.PlanApprover;
import dev.floor42.server.iam.StepUpApprovals;
import dev.floor42.server.security.SessionRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;

/**
 * The director's approval of a plan with its cap, journaled as the director's decision. One implementation for both
 * ways in: the approval route with IAM mode off, and the callback of the password step-up with it on.
 */
@ApplicationScoped
public class PlanApprovals implements PlanApprover {

    /** The answer of an approval that waits for the password step-up: follow {@code redirect}. */
    public record StepUp(String redirect) {}

    private final OfficeService office;
    private final StepUpApprovals stepUp;

    PlanApprovals(OfficeService office, StepUpApprovals stepUp) {
        this.office = office;
        this.stepUp = stepUp;
    }

    /**
     * IAM mode: only a browser session signed in through Keycloak may approve; the click records a pending approval
     * and the answer (202) tells the UI where to go to type the password again.
     */
    Response stepUp(
            MissionId m,
            Double budgetUsd,
            boolean noCap,
            List<String> resources,
            Optional<SessionRegistry.Session> session) {
        if (!stepUp.mayApprove(session)) {
            throw new RefusedException(
                    403,
                    "DELEGATION_NEEDS_SIGN_IN",
                    "Plan approval in IAM mode needs the director signed in through Keycloak");
        }
        requireAwaiting(m);
        try {
            return Response.accepted(new StepUp(stepUp.record(m, budgetUsd, noCap, resources, session.get())))
                    .build();
        } catch (IllegalArgumentException e) {
            throw RefusedException.invalid("INVALID_RESOURCES", e.getMessage(), e);
        }
    }

    @Override
    public void approve(MissionId m, Double budgetUsd, boolean noCap) {
        if (noCap && budgetUsd != null) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "Give a budget or choose no cap, not both");
        }
        if (noCap || budgetUsd != null) {
            // Only for a plan that waits: a cap decided for an approval that cannot happen is not recorded.
            office.execute(c -> {
                if (c.missions()
                        .find(m)
                        .map(x -> x.status() != MissionStatus.AWAITING_APPROVAL)
                        .orElse(false)) {
                    throw RefusedException.conflict("INVALID_TRANSITION", m + " has no plan awaiting approval");
                }
                return c.verification().setBudget(asDirector(office, m), m, budgetUsd);
            });
        }
        office.execute(c -> c.missions().approvePlan(asDirector(office, m), m));
    }

    @Override
    public void requireAwaiting(MissionId m) {
        boolean waiting = office.query(c -> c.missions()
                .find(m)
                .map(x -> x.status() == MissionStatus.AWAITING_APPROVAL)
                .orElseThrow(() -> RefusedException.notFound("UNKNOWN_MISSION", "No mission " + m)));
        if (!waiting) throw RefusedException.conflict("INVALID_TRANSITION", m + " has no plan awaiting approval");
    }
}
