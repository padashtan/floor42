package dev.floor42.server.iam.fga;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory OpenFGA for the tests of the brokers: tuples are really stored, and {@code can_call} is evaluated as
 * the model of {@code iam/openfga/model.fga} says (the real evaluation is the model tests' business, and the container's):
 *
 * <pre>
 *   resource.can_call = granted and (runner from delegated)
 *   mcp_tool.can_call = enabled and (can_call from server)
 * </pre>
 *
 * Every check is recorded with its contextual tuples; {@link #unavailable} makes every call fail as a down OpenFGA does.
 * A check is never cached, as the real client's is never cached.
 */
public class EvaluatingFga implements FgaClient {

    /** One check as received. */
    public record Check(String user, String relation, String object, List<FgaTuple> contextual) {}

    private final Set<FgaTuple> stored = new LinkedHashSet<>();
    private final List<Check> checks = new CopyOnWriteArrayList<>();
    private volatile boolean unavailable;

    public EvaluatingFga unavailable(boolean down) {
        this.unavailable = down;
        return this;
    }

    public List<Check> checks() {
        return checks;
    }

    public synchronized Set<FgaTuple> stored() {
        return new LinkedHashSet<>(stored);
    }

    /** Back to nothing stored, nothing recorded, available. */
    public synchronized void reset() {
        stored.clear();
        checks.clear();
        unavailable = false;
    }

    @Override
    public boolean check(String user, String relation, String object, List<FgaTuple> contextual) {
        down();
        checks.add(new Check(user, relation, object, List.copyOf(contextual)));
        synchronized (this) {
            return holds(user, relation, object, contextual);
        }
    }

    private boolean holds(String user, String relation, String object, List<FgaTuple> contextual) {
        if (relation.equals("countersigner"))
            return reviewer(user, object, contextual) && !has(user, "director", object, contextual);
        if (!relation.equals("can_call")) return has(user, relation, object, contextual);
        if (object.startsWith("resource:")) {
            if (!has(user, "granted", object, contextual)) return false;
            for (FgaTuple d : stored)
                if (d.object().equals(object)
                        && d.relation().equals("delegated")
                        && has(user, "runner", d.user(), contextual)) return true;
            return false;
        }
        if (object.startsWith("mcp_tool:")) {
            if (!has(user, "enabled", object, contextual)) return false;
            for (FgaTuple s : stored)
                if (s.object().equals(object)
                        && s.relation().equals("server")
                        && holds(user, "can_call", s.user(), contextual)) return true;
        }
        return false;
    }

    /** office.reviewer = [role#member]: a tuple naming the role's members, and the user is one of them. */
    private boolean reviewer(String user, String object, List<FgaTuple> contextual) {
        List<FgaTuple> all = new ArrayList<>(stored);
        all.addAll(contextual);
        for (FgaTuple t : all)
            if (t.relation().equals("reviewer")
                    && t.object().equals(object)
                    && t.user().endsWith("#member"))
                if (has(user, "member", t.user().substring(0, t.user().length() - "#member".length()), contextual))
                    return true;
        return false;
    }

    private boolean has(String user, String relation, String object, List<FgaTuple> contextual) {
        List<FgaTuple> all = new ArrayList<>(stored);
        all.addAll(contextual);
        for (FgaTuple t : all)
            if (t.relation().equals(relation)
                    && t.object().equals(object)
                    && (t.user().equals(user) || t.user().equals(wildcard(user)))) return true;
        return false;
    }

    private static String wildcard(String user) {
        return user.substring(0, user.indexOf(':') + 1) + "*";
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
