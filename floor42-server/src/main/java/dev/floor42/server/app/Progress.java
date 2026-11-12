package dev.floor42.server.app;

import dev.floor42.domain.Action;
import dev.floor42.domain.ActionStage;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.RiskLevel;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.Workspace;
import dev.floor42.server.runtime.ClaudeCodeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Where every mission stands, at a glance: what runs now, what waits for the director and since when, and whether the
 * mission is stuck by the dispatcher's own rule ({@link LiveMissions#stall}). Floor42's reading of its own journal and
 * worktrees, recomputed on each call; nothing here is recorded.
 */
@ApplicationScoped
public class Progress {

    /** A session that has shown nothing for this long is reported as silent. */
    static final long SILENT_MINUTES = 10;

    /** The phrase of a question an agent asked through Floor42's MCP tool (ask_director): withdrawn without an answer. */
    static final String AGENT_ASKED = "agentAsked";

    /**
     * One line of the panel. {@code kind} says what it is; the other fields are what the screen needs to word it and
     * offer the button that settles it. For what waits for the director, {@code since} is when the wait began and
     * {@code deadline} when Floor42 withdraws it unanswered (an agent's ask_director question), or null.
     */
    public record Item(
            String kind,
            String ticket,
            String agent,
            String question,
            String text,
            String status,
            Integer count,
            String since,
            Long runningSeconds,
            String deadline) {

        static Item of(String kind, String ticket, String agent, String text, Instant since) {
            return new Item(kind, ticket, agent, null, text, null, null, iso(since), null, null);
        }
    }

    /** {@link Stall} as the screen reads it. */
    public record StallView(
            String state,
            String reason,
            String work,
            String ticket,
            String agent,
            String elsewhere,
            String since,
            String until) {

        static StallView of(Stall s) {
            return new StallView(
                    s.state(),
                    s.reason(),
                    s.work(),
                    s.ticket(),
                    s.agent(),
                    s.elsewhere(),
                    iso(s.since()),
                    iso(s.until()));
        }
    }

    public record MissionProgress(
            String mission,
            String title,
            String status,
            int ticketsDone,
            int ticketsAll,
            List<Item> running,
            List<Item> forYou,
            List<Item> stuck,
            StallView stall) {
        public MissionProgress {
            forYou = forYou == null ? null : Collections.unmodifiableList(new ArrayList<>(forYou));
            running = running == null ? null : Collections.unmodifiableList(new ArrayList<>(running));
            stuck = stuck == null ? null : Collections.unmodifiableList(new ArrayList<>(stuck));
        }
    }

    @Inject
    OfficeService office;

    @Inject
    Workspaces workspaces;

    @Inject
    LiveMissions live;

    @Inject
    ClaudeCodeConfig claude;

    public List<MissionProgress> all() {
        List<Mission> active = office.query(c -> c.missions().all().stream()
                .filter(m -> !m.status().isTerminal())
                .toList());
        List<MissionProgress> out = new ArrayList<>();
        for (Mission m : active) out.add(of(m.id()));
        return out;
    }

    MissionProgress of(MissionId id) {
        // Worktrees with changes are read outside the company's lock: git may take a moment.
        List<Workspace> spaces = office.query(c -> c.workshop().workspaces(id));
        // A null count: git did not answer, so what waits there is unknown, never "nothing" (ADR-0055).
        java.util.Map<String, Integer> changes = new java.util.HashMap<>();
        for (Workspace w : spaces) {
            Integer n =
                    workspaces.pendingIfKnown(Path.of(w.path())).map(List::size).orElse(null);
            if (n == null || n > 0) changes.put(w.agent().value(), n);
        }
        Instant now = Instant.now();
        Stall stall = live.stall(id, now);
        long patience = claude.questionTimeoutSeconds();
        return office.query(c -> build(c, c.missions().find(id).orElseThrow(), changes, stall, patience, now));
    }

    private static MissionProgress build(
            Company c, Mission m, java.util.Map<String, Integer> changes, Stall stall, long patience, Instant now) {
        List<Ticket> tickets = c.tickets().of(m.id());
        List<AgentSession> open =
                c.sessions().of(m.id()).stream().filter(AgentSession::isOpen).toList();
        List<Question> questions = c.desk().of(m.id()).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .toList();
        List<Item> running = new ArrayList<>();
        for (AgentSession s : open) running.add(session(c, m, s, now));
        List<Item> forYou = new ArrayList<>();
        for (Question q : questions) forYou.add(question(q, patience));
        approvals(m, tickets, forYou);
        changes.forEach((agent, n) -> workshop(tickets, open, agent, n).ifPresent(forYou::add));
        closing(m, tickets, open, questions).ifPresent(forYou::add);
        int done = (int)
                tickets.stream().filter(t -> t.status() == TicketStatus.DONE).count();
        int all = (int) tickets.stream()
                .filter(t -> t.status() != TicketStatus.CANCELLED)
                .count();
        List<Item> stuck = new ArrayList<>(stuck(stall));
        risks(m, c.sessions().of(m.id()), stuck);
        return new MissionProgress(
                m.id().value(), m.title(), m.status().name(), done, all, running, forYou, stuck, StallView.of(stall));
    }

    /** All tickets ended and nothing waits: the director may close the mission, since the last one ended. */
    private static Optional<Item> closing(
            Mission m, List<Ticket> tickets, List<AgentSession> open, List<Question> questions) {
        boolean allDone =
                !tickets.isEmpty() && tickets.stream().allMatch(t -> t.status().isTerminal());
        if (m.status() != MissionStatus.IN_PROGRESS || !allDone || !open.isEmpty() || !questions.isEmpty())
            return Optional.empty();
        Instant since = tickets.stream()
                .map(Ticket::endedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        return Optional.of(Item.of("CLOSE_MISSION", null, null, null, since));
    }

    /** A session at work, with the call it runs now; "Look again" only where Floor42 drives the work. */
    private static Item session(Company c, Mission m, AgentSession s, Instant now) {
        Action last = c.actions().of(m.id()).stream()
                .filter(a -> a.agent().equals(s.agent()))
                .max(Comparator.comparing(Action::updatedAt))
                .orElse(null);
        Instant seen = last == null || last.updatedAt().isBefore(s.startedAt()) ? s.startedAt() : last.updatedAt();
        boolean live = last != null && last.stage() == ActionStage.AUTHORIZED && !last.orphaned();
        return new Item(
                // A simulated session plays its script: it is never silent for Floor42 to look at again.
                m.origin() == Origin.REAL && now.isAfter(seen.plusSeconds(SILENT_MINUTES * 60)) ? "SILENT" : "SESSION",
                s.ticket().value(),
                s.agent().value(),
                null,
                live ? last.tool() + ": " + last.summary() : null,
                null,
                null,
                seen.toString(),
                live ? last.runningSeconds() : null,
                null);
    }

    private static Item question(Question q, long patience) {
        boolean agentAsked = q.phrase() != null && AGENT_ASKED.equals(q.phrase().code());
        return new Item(
                "QUESTION",
                q.ticket() == null ? null : q.ticket().value(),
                q.askedBy() == null ? null : q.askedBy().value(),
                q.id().value(),
                q.subject(),
                q.kind().name(),
                null,
                iso(q.askedAt()),
                null,
                agentAsked ? iso(q.askedAt().plusSeconds(patience)) : null);
    }

    private static void approvals(Mission m, List<Ticket> tickets, List<Item> forYou) {
        if (m.status() == MissionStatus.AWAITING_APPROVAL) {
            forYou.add(Item.of("APPROVE_PLAN", null, null, null, m.createdAt()));
        }
        for (Ticket t : tickets) {
            if (t.status() == TicketStatus.PROPOSED && m.status() == MissionStatus.IN_PROGRESS) {
                forYou.add(Item.of("APPROVE_TICKET", t.id().value(), null, t.title(), t.createdAt()));
            }
        }
    }

    /**
     * Work a developer left in the workshop, while it does not work; the wait began when its last ticket ended. A null
     * count says git could not tell how much.
     */
    private static Optional<Item> workshop(List<Ticket> tickets, List<AgentSession> open, String agent, Integer n) {
        if (open.stream().anyMatch(s -> s.agent().value().equals(agent))) return Optional.empty();
        Instant since = tickets.stream()
                .filter(t -> t.assignee() != null && t.assignee().value().equals(agent))
                .map(Ticket::endedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
        return Optional.of(new Item("WORKSHOP", null, agent, null, null, null, n, iso(since), null, null));
    }

    private static List<Item> stuck(Stall stall) {
        if (!stall.stuck()) return List.of();
        return List.of(new Item(
                "STUCK",
                stall.ticket(),
                stall.agent(),
                null,
                null,
                stall.work(),
                null,
                iso(stall.since()),
                null,
                null));
    }

    /**
     * Every session of the mission with a raise at ALERT or above newer than the mission's last suspension, most
     * severe first, then latest (ADR-0066). The quarantine route and the progress item read the same list.
     */
    public static List<AgentSession> raised(Mission m, List<AgentSession> all) {
        return all.stream()
                .filter(s -> s.risk()
                        .filter(r -> r.level().compareTo(RiskLevel.ALERT) >= 0)
                        .filter(r -> m.suspendedAt() == null || r.at().isAfter(m.suspendedAt()))
                        .isPresent())
                .sorted(Comparator.comparing(
                                (AgentSession s) -> s.risk().orElseThrow().level())
                        .reversed()
                        .thenComparing(s -> s.risk().orElseThrow().at(), Comparator.reverseOrder()))
                .toList();
    }

    /** One item per raised session: codes only. */
    private static void risks(Mission m, List<AgentSession> all, List<Item> out) {
        for (AgentSession s : raised(m, all)) {
            AgentSession.Risk r = s.risk().orElseThrow();
            String codes = String.join(",", r.reasons().stream().map(Enum::name).toList());
            out.add(new Item(
                    "SESSION_RISK",
                    s.ticket().value(),
                    s.agent().value(),
                    null,
                    codes,
                    r.level().name(),
                    null,
                    iso(r.at()),
                    null,
                    null));
        }
    }

    static String iso(Instant t) {
        return t == null ? null : t.toString();
    }
}
