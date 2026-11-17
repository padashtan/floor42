package dev.floor42.server.iam.fga;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link FgaClient} for the tests of the decision points (brokers, countersigning). It is not an
 * evaluator of the model: a check answers what the test told it with {@link #allow} (everything else is refused), and
 * every check is recorded with its contextual tuples. {@link #unavailable} makes every call fail closed. Writes and
 * reads are real (a set of tuples), so a test can see what was written; the unstorable relations are refused exactly as
 * the HTTP client refuses them.
 */
public final class FakeFga implements FgaClient {

    /** One check as received. */
    public record Check(String user, String relation, String object, List<FgaTuple> contextual) {}

    private final Set<FgaTuple> allowed = new LinkedHashSet<>();
    private final Set<FgaTuple> stored = new LinkedHashSet<>();
    private final List<Check> checks = new CopyOnWriteArrayList<>();
    private volatile boolean unavailable;

    public FakeFga allow(String user, String relation, String object) {
        allowed.add(new FgaTuple(user, relation, object));
        return this;
    }

    public FakeFga deny(String user, String relation, String object) {
        allowed.remove(new FgaTuple(user, relation, object));
        return this;
    }

    /** Every later call throws {@link FgaException#UNAVAILABLE}, as a down OpenFGA does. */
    public FakeFga unavailable(boolean down) {
        this.unavailable = down;
        return this;
    }

    public List<Check> checks() {
        return checks;
    }

    public synchronized Set<FgaTuple> stored() {
        return new LinkedHashSet<>(stored);
    }

    @Override
    public boolean check(String user, String relation, String object, List<FgaTuple> contextual) {
        down();
        checks.add(new Check(user, relation, object, List.copyOf(contextual)));
        synchronized (this) {
            return allowed.contains(new FgaTuple(user, relation, object));
        }
    }

    @Override
    public synchronized void write(List<FgaTuple> writes, List<FgaTuple> deletes) {
        down();
        FgaClient.refuseUnstorable(writes, deletes);
        stored.addAll(writes);
        stored.removeAll(deletes);
    }

    @Override
    public synchronized List<FgaTuple> read(String object) {
        down();
        List<FgaTuple> out = new ArrayList<>();
        for (FgaTuple t : stored) if (t.object().equals(object)) out.add(t);
        return out;
    }

    private void down() {
        if (unavailable) throw new FgaException(FgaException.UNAVAILABLE, "OpenFGA is unreachable (fake)");
    }
}
