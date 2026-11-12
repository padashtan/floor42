package dev.floor42.server.app;

import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.TicketEvent.TicketCompleted;
import dev.floor42.domain.event.WorkbenchEvent.BranchesMerged;
import dev.floor42.domain.event.WorkbenchEvent.PatchAccepted;
import dev.floor42.server.journal.Journal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "While you were away": what happened since the last journal sequence the director acknowledged, read from that
 * sequence on (never the whole journal), and what waits for them now. Simulated events are left out; a cost is shown
 * only when Claude Code reported one, and it is Claude Code's estimate. Nothing here is recorded: the acknowledged
 * sequence lives in the director's browser.
 */
@ApplicationScoped
public class AwaySummary {

    /** The most events one summary reads; past it, {@code more} is true and {@code until} says where it stopped. */
    static final int MAX_EVENTS = 20_000;

    /**
     * One line of the summary. {@code kind}: for merged work {@code PATCH} (the director accepted changes into a
     * commit) or {@code BRANCHES} (Floor42 merged developers' branches for a ticket); for waiting, the question's kind;
     * for blocked, {@code STUCK} (the dispatcher's own rule, {@link LiveMissions#stall}) or {@code SUSPENDED}.
     */
    public record AwayLine(String kind, String mission, String ticket, String agent, String text, String at) {}

    /**
     * @param since the sequence asked from (exclusive); the journal's latest when none was given
     * @param until the last sequence read: acknowledge this one
     * @param more true when more events followed than one summary reads
     * @param costUsdEstimated the sum of the costs Claude Code estimated for the sessions that reported one; null when
     *     none did
     */
    public record AwayView(
            long since,
            long until,
            boolean more,
            List<AwayLine> delivered,
            List<AwayLine> merged,
            List<AwayLine> waiting,
            List<AwayLine> blocked,
            int sessions,
            int failedSessions,
            Double costUsdEstimated) {
        public AwayView {
            blocked = blocked == null ? null : Collections.unmodifiableList(new ArrayList<>(blocked));
            delivered = delivered == null ? null : Collections.unmodifiableList(new ArrayList<>(delivered));
            merged = merged == null ? null : Collections.unmodifiableList(new ArrayList<>(merged));
            waiting = waiting == null ? null : Collections.unmodifiableList(new ArrayList<>(waiting));
        }
    }

    @Inject
    Journal journal;

    @Inject
    OfficeService office;

    @Inject
    LiveMissions live;

    public AwayView since(Long sequence) {
        long head = journal.latest(1).stream()
                .mapToLong(Journal.Entry::sequence)
                .max()
                .orElse(0);
        long from = sequence == null ? head : Math.max(0, Math.min(sequence, head));
        List<Journal.Entry> entries = from >= head ? List.of() : journal.since(from, MAX_EVENTS);
        long until = entries.isEmpty() ? from : entries.getLast().sequence();
        Digest d = new Digest();
        entries.stream()
                .map(Journal.Entry::envelope)
                .filter(e -> e.origin() == Origin.REAL)
                .forEach(d::read);
        List<AwayLine> waiting = office.query(c -> c.desk().open().stream()
                .filter(q -> real(c, q.mission()))
                .map(AwaySummary::waitingLine)
                .toList());
        return new AwayView(
                from,
                until,
                until < head,
                d.delivered,
                d.merged,
                waiting,
                blocked(),
                d.sessions,
                d.failed,
                d.costed ? Math.round(d.cost * 10_000) / 10_000.0 : null);
    }

    /** What the real events since the director left delivered, merged and cost. */
    private final class Digest {
        final List<AwayLine> delivered = new ArrayList<>();
        final List<AwayLine> merged = new ArrayList<>();
        int sessions;
        int failed;
        double cost;
        boolean costed;

        void read(EventEnvelope e) {
            String mission = e.mission() == null ? null : e.mission().value();
            String at = e.occurredAt().toString();
            switch (e.event()) {
                case TicketCompleted done -> delivered.add(done(done, mission, at));
                case PatchAccepted p ->
                    merged.add(new AwayLine(
                            "PATCH",
                            mission,
                            null,
                            p.workspace().value(),
                            p.commit() == null ? p.message() : shortCommit(p.commit()) + " " + p.message(),
                            at));
                case BranchesMerged b
                when b.clean() ->
                    merged.add(new AwayLine(
                            "BRANCHES",
                            mission,
                            b.ticket() == null ? null : b.ticket().value(),
                            b.into() == null ? null : b.into().value(),
                            String.join(", ", b.branches()),
                            at));
                case AgentSessionEnded s -> ended(s);
                default -> {}
            }
        }

        private AwayLine done(TicketCompleted done, String mission, String at) {
            String title = office.query(c -> c.tickets()
                    .find(done.ticket())
                    .map(dev.floor42.domain.Ticket::title)
                    .orElse(null));
            String agent = office.query(c -> c.tickets()
                    .find(done.ticket())
                    .map(t -> t.assignee() == null ? null : t.assignee().value())
                    .orElse(null));
            return new AwayLine("DONE", mission, done.ticket().value(), agent, title, at);
        }

        private void ended(AgentSessionEnded s) {
            sessions++;
            if (s.failed()) failed++;
            if (s.costUsdEstimated() != null) {
                cost += s.costUsdEstimated();
                costed = true;
            }
        }
    }

    /** Real missions that do not move: suspended, or stuck by the dispatcher's own rule. */
    private List<AwayLine> blocked() {
        List<MissionId> active = office.query(c -> c.missions().all().stream()
                .filter(m -> m.origin() == Origin.REAL
                        && (m.status() == MissionStatus.IN_PROGRESS || m.status() == MissionStatus.SUSPENDED))
                .map(Mission::id)
                .toList());
        List<AwayLine> blocked = new ArrayList<>();
        Instant now = Instant.now();
        for (MissionId m : active) {
            MissionStatus status =
                    office.query(c -> c.missions().find(m).orElseThrow().status());
            if (status == MissionStatus.SUSPENDED) {
                blocked.add(new AwayLine("SUSPENDED", m.value(), null, null, null, null));
                continue;
            }
            Stall s = live.stall(m, now);
            if (s.stuck()) {
                blocked.add(new AwayLine("STUCK", m.value(), s.ticket(), s.agent(), s.work(), Progress.iso(s.since())));
            }
        }
        return blocked;
    }

    private static boolean real(Company c, MissionId m) {
        return m == null
                || c.missions().find(m).map(x -> x.origin() == Origin.REAL).orElse(false);
    }

    private static AwayLine waitingLine(Question q) {
        return new AwayLine(
                q.kind().name(),
                q.mission() == null ? null : q.mission().value(),
                q.ticket() == null ? null : q.ticket().value(),
                q.askedBy() == null ? null : q.askedBy().value(),
                q.subject(),
                q.askedAt().toString());
    }

    private static String shortCommit(String commit) {
        return commit.length() > 8 ? commit.substring(0, 8) : commit;
    }
}
