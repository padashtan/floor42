package dev.floor42.server.iam.fga;

import java.util.List;

/**
 * What Floor42 asks of OpenFGA, pinned to one store and one model. The decision points (the brokers, countersigning)
 * depend on this interface only; {@link HttpFgaClient} is the real one, the fake in the tests is the other.
 *
 * <p>Every method either answers or throws {@link FgaException}: an answer that is not a clear yes is a refusal.
 */
public interface FgaClient {

    /** The most tuples one {@link #write} may carry. */
    int MAX_WRITE = 100;

    /**
     * Whether {@code user} has {@code relation} on {@code object}, with the contextual tuples of this one request (the
     * session's {@code mission#runner}, the token's {@code role#member}). Never cached, higher consistency.
     */
    boolean check(String user, String relation, String object, List<FgaTuple> contextual);

    /**
     * Writes and deletes in one transaction. Idempotent: a tuple already there or already gone is not an error.
     * {@code runner} (on a mission) and {@code member} (on a role) are never stored, they are contextual only.
     */
    void write(List<FgaTuple> writes, List<FgaTuple> deletes);

    /** Every stored tuple of {@code object}, for the screen and the import check. */
    List<FgaTuple> read(String object);

    /** Refuses what must never be stored; shared by every implementation so the fake cannot drift from the real one. */
    static void refuseUnstorable(List<FgaTuple> writes, List<FgaTuple> deletes) {
        if (writes.size() > MAX_WRITE || deletes.size() > MAX_WRITE)
            throw new FgaException(FgaException.INVALID, "At most " + MAX_WRITE + " tuples per write");
        for (FgaTuple t : writes) {
            boolean runner = t.relation().equals("runner") && t.object().startsWith("mission:");
            boolean member = t.relation().equals("member") && t.object().startsWith("role:");
            if (runner || member)
                throw new FgaException(
                        FgaException.INVALID,
                        "The relation " + t.relation() + " of " + t.object().split(":", 2)[0]
                                + " is contextual, never stored");
        }
    }
}
