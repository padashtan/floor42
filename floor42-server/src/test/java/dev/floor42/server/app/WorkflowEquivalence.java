package dev.floor42.server.app;

import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCompleted;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.WorkflowEvent.WorkflowPinned;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.WorkflowDocument;
import dev.floor42.domain.workflow.WorkflowInterpreter;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterEnd;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterRuling;
import dev.floor42.domain.workflow.WorkflowInterpreter.Opening;
import dev.floor42.domain.workflow.WorkflowInterpreter.Position;
import dev.floor42.server.app.LegacyWorkflowRules.FrozenCommittees;
import dev.floor42.server.app.LegacyWorkflowRules.Phase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The equivalence bench of ADR-0046 and ADR-0050: plays a journal event by event and, after EVERY event, compares EVERY
 * decision the daemon takes now (through {@link WorkflowDecider}, the daemon's {@code MissionInterpreter} unless a
 * mutant is given) with the decision of the rules of c62612d ({@link LegacyWorkflowRules}): for every mission, what
 * the dispatcher would start and whether a ruling is awaited; for every ticket, what it would be given; for every
 * event, whether an implementation or a retrospective follows. How a mission opens is compared over every combination
 * of what can be asked ({@link #openings}).
 *
 * <p>{@link Pins} may pin every REAL mission right after its creation; the {@link Expectation} then says what each
 * decision of a pinned mission must be: c62612d's (the built-in workflow pinned), a refusal (a schema this build cannot
 * read), or c62612d's without the retrospective (a workflow without one).
 */
public final class WorkflowEquivalence {

    /** @param comparisons how many decisions were compared; every one that differed is in {@code disagreements} */
    public record Result(int events, long comparisons, List<String> disagreements) {}

    /** The document every REAL mission pins right after its creation. */
    public record Pins(String document, int schemaVersion) {
        public static Pins of(Workflow w) {
            return new Pins(WorkflowDocument.write(w), WorkflowDocument.versionOf(w));
        }
    }

    /** What a pinned mission's decision must be, given c62612d's. */
    public enum Expectation {
        FORMER,
        REFUSED,
        NO_RETRO;

        Object expect(Decision kind, Object former) {
            return switch (this) {
                case FORMER -> former;
                case NO_RETRO -> kind == Decision.RETRO ? Boolean.FALSE : former;
                case REFUSED -> refused(kind);
            };
        }

        private static Object refused(Decision kind) {
            return switch (kind) {
                case DISPATCH -> new Phase.Nothing();
                case RULING, IMPLEMENTATION, RETRO -> Boolean.FALSE;
                case TICKET -> "NONE";
                case REFUSED -> Boolean.TRUE;
            };
        }
    }

    enum Decision {
        DISPATCH,
        RULING,
        TICKET,
        IMPLEMENTATION,
        RETRO,
        REFUSED
    }

    private final WorkflowDecider decider;
    private final Expectation expectation;
    private final Company company = new Company();
    private final FrozenCommittees frozen = new FrozenCommittees();
    private final Set<MissionId> pinned = new HashSet<>();
    private final Set<MissionId> written = new HashSet<>();
    private final List<String> disagreements = new ArrayList<>();
    private long comparisons;
    private int n;

    private WorkflowEquivalence(WorkflowDecider decider, Expectation expectation) {
        this.decider = decider;
        this.expectation = expectation;
    }

    /** The journal as it is, through the daemon's interpreter: every decision as c62612d took it. */
    public static Result of(Iterable<EventEnvelope> journal) {
        return of(journal, WorkflowDecider.daemon(), null, Expectation.FORMER);
    }

    /** Every REAL mission pinned with {@code pins} right after its creation, in place of a built-in pin the journal holds; a written pin of the journal stays. */
    public static Result of(
            Iterable<EventEnvelope> journal, WorkflowDecider decider, Pins pins, Expectation expectation) {
        WorkflowEquivalence bench = new WorkflowEquivalence(decider, expectation);
        Set<MissionId> own = new HashSet<>();
        String builtIn = WorkflowDocument.write(Workflow.BUILT_IN);
        for (EventEnvelope e : journal) {
            if (!(e.event() instanceof WorkflowPinned p)) continue;
            // A mission the journal pins to a written workflow follows it, not c62612d's rules: left out.
            if (!p.document().equals(builtIn)) {
                own.add(p.mission());
                bench.written.add(p.mission());
            }
        }
        for (EventEnvelope e : journal) {
            if (pins != null
                    && e.origin() == Origin.REAL
                    && e.event() instanceof MissionCreated c
                    && !own.contains(c.mission())) {
                bench.play(e);
                bench.pinned.add(c.mission());
                bench.play(pin(e, c.mission(), pins));
            } else if (pins != null
                    && e.origin() == Origin.REAL
                    && e.event() instanceof WorkflowPinned p
                    && p.document().equals(builtIn)) {
                // The journal's own pin of the built-in workflow: the mutant's pin took its place.
                continue;
            } else {
                bench.play(e);
            }
        }
        return new Result(bench.n, bench.comparisons, List.copyOf(bench.disagreements));
    }

    /** How many REAL missions a run would pin: a mutant over a journal without any proves nothing. */
    public static long realMissions(Iterable<EventEnvelope> journal) {
        long count = 0;
        for (EventEnvelope e : journal) if (e.origin() == Origin.REAL && e.event() instanceof MissionCreated) count++;
        return count;
    }

    private static EventEnvelope pin(EventEnvelope created, MissionId m, Pins pins) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                created.occurredAt(),
                created.origin(),
                created.actor(),
                m,
                null,
                created.eventId(),
                new WorkflowPinned(m, pins.document(), pins.schemaVersion()));
    }

    private void play(EventEnvelope e) {
        company.apply(e);
        frozen.apply(e);
        String at = "#" + (++n) + " " + e.type();
        compareEvent(at, e);
        for (Mission m : company.missions().all()) compareMission(at, m);
    }

    private void compareMission(String at, Mission m) {
        MissionId id = m.id();
        same(
                at + " " + id + " dispatch",
                id,
                Decision.DISPATCH,
                LegacyWorkflowRules.next(company, frozen, id),
                phase(m));
        if (m.status() == MissionStatus.IN_PROGRESS) {
            same(
                    at + " " + id + " ruling awaited",
                    id,
                    Decision.RULING,
                    LegacyWorkflowRules.awaitingRuling(frozen, id),
                    decider.position(company, id) instanceof Position.AwaitingRuling);
        }
        if (pinned.contains(id) && expectation == Expectation.REFUSED && !ended(m)) {
            boolean refused = decider.position(company, id) instanceof Position.Refused;
            same(at + " " + id + " refused", id, Decision.REFUSED, Boolean.FALSE, refused);
        }
        for (Ticket t : company.tickets().of(id)) {
            String now =
                    decider.step(company, t, TaskSelection.roleOf(company, t)).name();
            same(at + " " + t.id(), id, Decision.TICKET, LegacyWorkflowRules.work(company, frozen, t), now);
        }
    }

    private void compareEvent(String at, EventEnvelope e) {
        MissionId ruled = e.event() instanceof CommitteeArbitrated r ? r.mission() : null;
        same(
                at + " implementation follows",
                ruled,
                Decision.IMPLEMENTATION,
                LegacyWorkflowRules.implementsAfter(e),
                implementsAfter(e));
        MissionId ended = endedBy(e);
        same(
                at + " retrospective follows",
                ended,
                Decision.RETRO,
                LegacyWorkflowRules.opensRetro(e),
                opensRetro(e, ended));
    }

    private static boolean ended(Mission m) {
        return m.status() == MissionStatus.COMPLETED || m.status() == MissionStatus.CANCELLED;
    }

    /** As EventReactions does now: REAL events only, then the interpreter's answer to the ruling. */
    private boolean implementsAfter(EventEnvelope e) {
        if (e.origin() != Origin.REAL || !(e.event() instanceof CommitteeArbitrated ruling)) return false;
        return switch (decider.afterRuling(company, ruling)) {
            case AfterRuling.Implement i -> i.committee().mission().equals(ruling.mission());
            case AfterRuling.Decided d -> false;
        };
    }

    private static MissionId endedBy(EventEnvelope e) {
        return e.event() instanceof MissionCompleted c ? c.mission() : null;
    }

    /** As Retrospectives does now: REAL events only, a mission's end, then the interpreter's answer. */
    private boolean opensRetro(EventEnvelope e, MissionId ended) {
        if (e.origin() != Origin.REAL || ended == null) return false;
        return decider.afterEnd(company, ended) instanceof AfterEnd.HoldRetro;
    }

    /** What TaskSelection.next starts now, in the vocabulary of c62612d (the resting time is not journaled). */
    private Phase phase(Mission m) {
        boolean waiting = MissionHolds.waiting(company, m.id());
        return switch (decider.position(company, m.id())) {
            case Position.Deliberating d ->
                waiting
                        ? new Phase.Nothing()
                        : new Phase.CommitteeSession(company.committees()
                                .find(d.committee())
                                .orElseThrow()
                                .ticket());
            case Position.WorkingTickets w -> waiting ? new Phase.Nothing() : tickets(w.committeeTickets());
            case Position.AwaitingRuling r -> waiting ? new Phase.Nothing() : tickets(r.committeeTickets());
            case Position.Drafting _,
                    Position.AwaitingApproval _,
                    Position.Suspended _,
                    Position.Ended _,
                    Position.Refused _ -> new Phase.Nothing();
        };
    }

    /** One committee per mission at c62612d: the queue left out its ticket only. More than one cannot be said then. */
    private static Phase tickets(List<TicketId> leftOut) {
        return switch (leftOut.size()) {
            case 0 -> new Phase.Tickets(null);
            case 1 -> new Phase.Tickets(leftOut.getFirst());
            default -> new Phase.Tickets(new TicketId("several:" + leftOut));
        };
    }

    private void same(String what, MissionId mission, Decision kind, Object former, Object now) {
        if (mission != null && written.contains(mission)) return;
        comparisons++;
        Object expected = mission != null && pinned.contains(mission) ? expectation.expect(kind, former) : former;
        if (!expected.equals(now)) {
            disagreements.add(what + ": expected " + expected + " (c62612d " + former + "), now " + now);
        }
    }

    /**
     * How a mission opens, for every combination of members, panel, repository and planning asked: the choice (or the
     * refusal) of MissionFactory.create at c62612d against MissionFactory.opening now.
     */
    public static Result openings() {
        List<List<String>> members = Arrays.asList(null, List.of(), List.of("architect", "security"));
        List<String> panels = Arrays.asList(null, "", " ", "architecture", "idea", "unknown");
        List<String> repositories = Arrays.asList(null, "", " ", "C:/repo/app");
        WorkflowInterpreter workflow = new WorkflowInterpreter(Workflow.BUILT_IN);
        List<String> disagreements = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long n = 0;
        for (List<String> committee : members) {
            for (String panel : panels) {
                for (String repository : repositories) {
                    for (boolean plan : new boolean[] {false, true}) {
                        String former = outcome(() -> LegacyWorkflowRules.create(repository, committee, plan, panel));
                        String now = outcome(
                                () -> name(MissionFactory.opening(workflow, repository, committee, plan, panel)));
                        seen.add(former);
                        n++;
                        if (!former.equals(now)) {
                            disagreements.add(committee + " " + panel + " " + repository + " " + plan + ": c62612d "
                                    + former + ", now " + now);
                        }
                    }
                }
            }
        }
        if (seen.size() < 5) disagreements.add("only these outcomes were reached: " + seen);
        return new Result(0, n, List.copyOf(disagreements));
    }

    private static String name(Opening opening) {
        return switch (opening) {
            case Opening.ConveneCommittee c -> "decision:" + c.panel();
            case Opening.PlanWithPlanner p -> "planned";
            case Opening.DeliverInOneTicket d -> "delivery";
            case Opening.AwaitStepApproval a -> "awaiting";
        };
    }

    private static String outcome(java.util.function.Supplier<String> choice) {
        try {
            return choice.get();
        } catch (DomainException e) {
            return "refused " + e.code() + ": " + e.getMessage();
        }
    }
}
