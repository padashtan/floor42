package dev.floor42.server.iam;

import dev.floor42.server.iam.fga.FgaClient;
import dev.floor42.server.iam.fga.FgaTuple;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * Who may be the second pair of eyes: Keycloak says who holds the reviewer role, OpenFGA decides (ADR-0081, ADR-0080):
 * {@code check(user:<sub>, countersigner, office:floor42)} with the contextual tuple
 * {@code role:floor42-reviewer#member@user:<sub>} when the token holds the role, so "reviewer but not director" is
 * the model's rule. The facts the model needs travel as contextual tuples of the one check, never stored:
 * {@code office:floor42#reviewer@role:floor42-reviewer#member}, the signer's role membership, and the first signer as
 * {@code office:floor42#director}. Fail closed: a decision point that is not ready (OpenFGA away) refuses every
 * countersignature as unavailable.
 *
 * <p>At start the decider is the OpenFGA client of N1 ({@link FgaClient#check}); the tests wire a fake by hand.
 */
@ApplicationScoped
public class Countersigners {

    public static final String REVIEWER_ROLE = "floor42-reviewer";
    /** Required to open the desk (ADR-0081): any other realm user, a reviewer included, is refused. */
    public static final String DIRECTOR_ROLE = "floor42-director";

    /** One tuple, as OpenFGA reads it. */
    public record Tuple(String user, String relation, String object) {}

    /** The authorization decision point's check. */
    @FunctionalInterface
    public interface Check {
        boolean check(String user, String relation, String object, List<Tuple> contextual);
    }

    /** The decision point could not answer: the countersignature is refused (503 {@code FGA_UNAVAILABLE}). */
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Null until wired: nothing can be countersigned then. */
    volatile Check check;

    @Inject
    FgaClient fga;

    @PostConstruct
    void wireToOpenFga() {
        wire((user, relation, object, contextual) -> fga.check(
                user,
                relation,
                object,
                contextual.stream()
                        .map(t -> new FgaTuple(t.user(), t.relation(), t.object()))
                        .toList()));
    }

    public void wire(Check decider) {
        this.check = decider;
    }

    /** True when {@code sub} may countersign; throws {@link Unavailable} when the decision point cannot answer. */
    boolean mayCountersign(String sub, boolean holdsReviewerRole, String directorSub) {
        Check c = check;
        if (c == null) throw new Unavailable("No authorization decision point is wired", null);
        List<Tuple> contextual = new ArrayList<>();
        contextual.add(new Tuple("role:" + REVIEWER_ROLE + "#member", "reviewer", "office:floor42"));
        contextual.add(new Tuple("user:" + directorSub, "director", "office:floor42"));
        if (holdsReviewerRole) contextual.add(new Tuple("user:" + sub, "member", "role:" + REVIEWER_ROLE));
        try {
            return c.check("user:" + sub, "countersigner", "office:floor42", contextual);
        } catch (RuntimeException e) {
            // Handled: any failure of the decision point is "cannot tell", never an allow.
            throw new Unavailable("The authorization decision point failed", e);
        }
    }

    /** Why a countersignature is refused: the status and code the callback answers with. */
    record Refusal(int status, String code, String message) {}

    /** Null when {@code sub} may countersign; else the refusal. No roles known (a bad access token) is a refusal. */
    Refusal refusal(java.util.Optional<java.util.Set<String>> roles, String sub, String directorSub) {
        try {
            if (roles.isPresent() && mayCountersign(sub, roles.get().contains(REVIEWER_ROLE), directorSub)) return null;
        } catch (Unavailable e) {
            // Handled: no decision point means nobody countersigns (503).
            return new Refusal(
                    503, "FGA_UNAVAILABLE", "The authorization service cannot answer: nobody can countersign now");
        }
        return new Refusal(403, "NOT_A_REVIEWER", "This person may not countersign: the reviewer role is needed");
    }
}
