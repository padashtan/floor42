package dev.floor42.server.api;

import dev.floor42.domain.Action;
import dev.floor42.domain.ActionStage;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Ticket;
import dev.floor42.server.app.CommitteeRoles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An agent's profile: what it was hired as (declared), and what it actually did on real missions (measured from the
 * journal). Strengths and weaknesses are facts with their source, never opinions about the agent.
 *
 * @param duties what Floor42 gives this agent to do, as wired in the code
 * @param focus what this agent looks at first when it analyses (committee roles only), as configured in Floor42
 * @param modelTrait what the model generally offers, from the provider's line-up (general knowledge, not measured)
 * @param facts observed facts worth knowing, each one computed from the journal
 */
public record AgentProfileView(
        String id,
        String name,
        String role,
        String provider,
        String model,
        String effort,
        boolean suspended,
        String status,
        List<String> skills,
        List<String> duties,
        String focus,
        String modelTrait,
        Record record,
        List<Fact> facts,
        List<TicketLine> tickets) {
    public AgentProfileView {
        duties = duties == null ? null : Collections.unmodifiableList(new ArrayList<>(duties));
        facts = facts == null ? null : Collections.unmodifiableList(new ArrayList<>(facts));
        skills = skills == null ? null : Collections.unmodifiableList(new ArrayList<>(skills));
        tickets = tickets == null ? null : Collections.unmodifiableList(new ArrayList<>(tickets));
    }

    /** Counts over real missions only; costs are the runtime's estimates. */
    public record Record(
            int missions,
            int sessions,
            int failedSessions,
            Double costUsdEstimated,
            long inputTokensProvided,
            long outputTokensProvided,
            int ticketsAssigned,
            int ticketsDone,
            Map<String, Integer> verdictsReceived,
            Map<String, Integer> verdictsGiven,
            Map<String, Integer> actions,
            Map<String, Integer> denials,
            int consultationsAnswered,
            long workMs) {
        public Record {
            actions = actions == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(actions));
            denials = denials == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(denials));
            verdictsGiven =
                    verdictsGiven == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(verdictsGiven));
            verdictsReceived = verdictsReceived == null
                    ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(verdictsReceived));
        }
    }

    /** @param kind STRENGTH, WATCH or INFO; @param basis where the fact comes from */
    /** @param code and params let the screen write the text in its language (ADR-0021, lot 3) */
    public record Fact(String kind, String text, String basis, String code, java.util.Map<String, String> params) {
        public Fact {
            params = params == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }

        Fact(String kind, String text, String basis, String code, String... keysAndValues) {
            this(
                    kind,
                    text,
                    basis,
                    code,
                    dev.floor42.domain.Phrase.of(code, keysAndValues).params());
        }
    }

    /** @param workMs time of this agent's ended sessions on the ticket, measured by Floor42 */
    public record TicketLine(
            String mission, String ticket, String title, String status, String lastVerdict, long workMs) {}

    static AgentProfileView of(Company c, Agent a) {
        Counts n = new Counts(a.id());
        c.missions().all().stream().filter(m -> m.origin() == Origin.REAL).forEach(m -> n.mission(c, m));
        Record record = n.record();
        CommitteeRoles.Role role = CommitteeRoles.of(a.id(), c);
        return new AgentProfileView(
                a.id().value(),
                a.displayName(),
                a.role(),
                a.provider(),
                a.model(),
                a.effort(),
                a.suspended(),
                c.queries().statusOf(a.id()).name(),
                a.skills(),
                dev.floor42.server.app.LiveMissions.duties(a),
                role == null ? null : role.lens(),
                trait(a.model()),
                record,
                facts(record),
                n.lines.reversed().stream().limit(12).toList());
    }

    /** What the journal counts for one agent over the real missions, mission by mission. */
    private static final class Counts {
        final AgentId agent;
        int missions;
        int sessions;
        int failed;
        double cost;
        boolean anyCost;
        long in;
        long out;
        long work;
        int assigned;
        int done;
        int consulted;
        final Map<String, Integer> received = new TreeMap<>();
        final Map<String, Integer> given = new TreeMap<>();
        final Map<String, Integer> actions = new TreeMap<>();
        final Map<String, Integer> denials = new TreeMap<>();
        final List<TicketLine> lines = new ArrayList<>();

        Counts(AgentId agent) {
            this.agent = agent;
        }

        void mission(Company c, Mission m) {
            boolean involved = false;
            for (AgentSession s : c.sessions().of(m.id())) {
                if (!s.agent().equals(agent)) continue;
                // A turn of speech at the director's meeting (ADR-0049): its cost and tokens, no session nor work time.
                if (dev.floor42.server.app.TurnSessions.isTurn(c, s)) spend(s);
                else involved |= session(s);
            }
            for (Ticket t : c.tickets().of(m.id())) involved |= ticket(c, m, t);
            for (Action x : c.actions().of(m.id())) {
                if (x.agent().equals(agent)) action(x);
            }
            if (involved) missions++;
        }

        private boolean session(AgentSession s) {
            sessions++;
            if (Boolean.TRUE.equals(s.failed())) failed++;
            spend(s);
            work += workMs(s);
            return true;
        }

        private void spend(AgentSession s) {
            if (s.costUsdEstimated() != null) {
                cost += s.costUsdEstimated();
                anyCost = true;
            }
            if (s.inputTokensProvided() != null) in += s.inputTokensProvided();
            if (s.outputTokensProvided() != null) out += s.outputTokensProvided();
        }

        /** Its own tickets, the reviews it gave and the consultations it answered; true when it was assigned. */
        private boolean ticket(Company c, Mission m, Ticket t) {
            boolean own = agent.equals(t.assignee());
            if (own) assigned(c, m, t);
            for (Ticket.Review r : t.reviews()) {
                if (r.reviewer().equals(agent)) given.merge(r.verdict().name(), 1, Integer::sum);
            }
            for (Ticket.Consultation k : t.consultations()) {
                if (agent.equals(k.answeredBy())) consulted++;
            }
            return own;
        }

        private void assigned(Company c, Mission m, Ticket t) {
            assigned++;
            if (t.status() == dev.floor42.domain.TicketStatus.DONE) done++;
            String last = t.reviews().isEmpty()
                    ? null
                    : t.reviews().getLast().verdict().name();
            for (Ticket.Review r : t.reviews()) received.merge(r.verdict().name(), 1, Integer::sum);
            long spent = c.sessions().of(m.id()).stream()
                    .filter(s -> s.agent().equals(agent) && s.ticket().equals(t.id()))
                    .mapToLong(AgentProfileView::workMs)
                    .sum();
            lines.add(new TicketLine(
                    m.id().value(), t.id().value(), t.title(), t.status().name(), last, spent));
        }

        private void action(Action x) {
            actions.merge(x.stage().name(), 1, Integer::sum);
            if (x.stage() == ActionStage.DENIED && x.denialReason() != null) {
                denials.merge(x.denialReason().name(), 1, Integer::sum);
            }
        }

        Record record() {
            return new Record(
                    missions,
                    sessions,
                    failed,
                    anyCost ? Math.round(cost * 10_000) / 10_000.0 : null,
                    in,
                    out,
                    assigned,
                    done,
                    received,
                    given,
                    actions,
                    denials,
                    consulted,
                    work);
        }
    }

    /** How long a session ran, from Floor42's own clock; a session still running counts nothing yet. */
    static long workMs(AgentSession s) {
        return s.workTime().toMillis();
    }

    /** General traits of the model family, as the provider positions them. Labelled as such on screen. */
    static String trait(String model) {
        String m = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("haiku")) return "Fastest and cheapest of the line-up; fine for focused, well-specified work";
        if (m.contains("sonnet")) return "Balance of capability, speed and cost; the usual choice for coding work";
        if (m.contains("opus")) return "Most capable for long, hard reasoning; the most expensive per session";
        if (m.contains("fable")) return "Newest family; check its cost per session on the usage page";
        return null;
    }

    /** What the record says that is worth reading first. Only thresholds over counted facts, never judgement. */
    static List<Fact> facts(Record r) {
        List<Fact> f = new ArrayList<>();
        if (r.sessions() == 0) {
            f.add(new Fact("INFO", "No real session yet: nothing measured", JOURNAL, "none"));
            return f;
        }
        reviews(f, r);
        conduct(f, r);
        if (r.costUsdEstimated() != null) f.add(cost(r));
        return f;
    }

    private static final String JOURNAL = "journal, real missions";

    /** The reviews of its work, and those it gave. */
    private static void reviews(List<Fact> f, Record r) {
        int validated = r.verdictsReceived().getOrDefault("VALIDATED", 0)
                + r.verdictsReceived().getOrDefault("VALIDATED_WITH_RESERVATIONS", 0);
        int rejected = r.verdictsReceived().getOrDefault("REJECTED", 0);
        if (validated > 0 && rejected == 0) {
            f.add(new Fact(
                    "STRENGTH",
                    validated + " review(s) of its work validated, none rejected",
                    JOURNAL,
                    "validated",
                    "validated",
                    Integer.toString(validated)));
        } else if (rejected > 0) {
            f.add(new Fact(
                    "WATCH",
                    rejected + " of " + (validated + rejected) + " review(s) of its work rejected",
                    JOURNAL,
                    "rejected",
                    "rejected",
                    Integer.toString(rejected),
                    "total",
                    Integer.toString(validated + rejected)));
        }
        if (r.verdictsGiven().isEmpty()) return;
        int g = r.verdictsGiven().values().stream().mapToInt(Integer::intValue).sum();
        int gRejected = r.verdictsGiven().getOrDefault("REJECTED", 0);
        f.add(new Fact(
                "INFO",
                "Reviewed " + g + " deliverable(s), rejected " + gRejected,
                JOURNAL,
                "reviewed",
                "given",
                Integer.toString(g),
                "rejected",
                Integer.toString(gRejected)));
    }

    /** Its calls against the permissions and the worktree, and its failed sessions. */
    private static void conduct(List<Fact> f, Record r) {
        int outside = r.denials().getOrDefault("OUTSIDE_WORKSPACE", 0);
        if (outside > 0) {
            f.add(new Fact(
                    "WATCH",
                    outside + " attempt(s) to reach files outside its worktree, blocked by Floor42",
                    JOURNAL,
                    "outside",
                    "count",
                    Integer.toString(outside)));
        }
        int denied = r.actions().getOrDefault("DENIED", 0);
        int total = r.actions().values().stream().mapToInt(Integer::intValue).sum();
        if (total >= 5 && denied == 0) {
            f.add(new Fact(
                    "STRENGTH",
                    "Stayed within its permissions: " + total + " actions, none denied",
                    JOURNAL,
                    "permissions",
                    "total",
                    Integer.toString(total)));
        }
        if (r.failedSessions() == 0) return;
        f.add(new Fact(
                "WATCH",
                r.failedSessions() + " of " + r.sessions() + " session(s) failed",
                JOURNAL,
                "failed",
                "failed",
                Integer.toString(r.failedSessions()),
                "sessions",
                Integer.toString(r.sessions())));
    }

    private static Fact cost(Record r) {
        return new Fact(
                "INFO",
                String.format(
                        java.util.Locale.ROOT,
                        "About %.3f USD per session (%.4f USD over %d)",
                        r.costUsdEstimated() / r.sessions(),
                        r.costUsdEstimated(),
                        r.sessions()),
                "estimated by Claude Code",
                "cost",
                "per",
                String.format(java.util.Locale.ROOT, "%.3f", r.costUsdEstimated() / r.sessions()),
                "total",
                String.format(java.util.Locale.ROOT, "%.4f", r.costUsdEstimated()),
                "sessions",
                Integer.toString(r.sessions()));
    }
}
