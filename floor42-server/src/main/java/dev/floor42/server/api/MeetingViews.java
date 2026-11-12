package dev.floor42.server.api;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.Meeting;
import dev.floor42.domain.MeetingNote;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.runtime.RunningSessions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read models of the director's meetings (ADR-0047). Built inside {@code OfficeService.query}, then immutable. */
public final class MeetingViews {

    private MeetingViews() {}

    /** @param attendee null when said to every attendee */
    public record MeetingNoteView(String attendee, String text, String at) {
        static MeetingNoteView of(MeetingNote n) {
            return new MeetingNoteView(
                    n.attendee() == null ? null : n.attendee().value(), n.text(), n.at().toString());
        }
    }

    /**
     * An attendee as the table sees it.
     *
     * @param status the agent's status: {@code IN_MEETING} once seated, another while it still finishes its call
     * @param finishing true while its session still runs: the call under way finishes, no new one starts
     */
    public record AttendeeView(String id, String name, String status, boolean finishing) {}

    /**
     * @param closedAt null while the meeting is open
     * @param place {@code TABLE} (the meeting table) or {@code CONFERENCE} (the conference table, ADR-0049)
     * @param spentUsdCharged what its turns were charged so far against its cap: Claude Code's estimates, and the
     *     budget of a turn it gave none for (an upper bound); never measured
     * @param rounds each "Ask for their view", oldest first
     */
    public record MeetingView(
            String id,
            String mission,
            String missionTitle,
            String agenda,
            String calledAt,
            String closedAt,
            boolean open,
            List<AttendeeView> attendees,
            List<MeetingNoteView> notes,
            String place,
            double spentUsdCharged,
            List<MeetingTurnViews.RoundView> rounds) {
        public MeetingView {
            attendees = attendees == null ? null : Collections.unmodifiableList(new ArrayList<>(attendees));
            notes = notes == null ? null : Collections.unmodifiableList(new ArrayList<>(notes));
            rounds = rounds == null ? null : Collections.unmodifiableList(new ArrayList<>(rounds));
        }

        static MeetingView of(Company c, Meeting m, RunningSessions sessions) {
            return new MeetingView(
                    m.id(),
                    m.mission().value(),
                    c.missions().find(m.mission()).map(x -> x.title()).orElse(""),
                    m.agenda(),
                    m.calledAt().toString(),
                    m.closedAt() == null ? null : m.closedAt().toString(),
                    m.open(),
                    m.attendees().stream()
                            .map(a -> attendee(c, a, m.open() && finishing(c, m, a, sessions)))
                            .toList(),
                    m.notes().stream().map(MeetingNoteView::of).toList(),
                    m.place().name(),
                    c.meetings().spentUsdCharged(m),
                    MeetingTurnViews.of(c, m));
        }

        /** Its work session still runs: a turn of speech at this meeting is no work. */
        private static boolean finishing(Company c, Meeting m, AgentId a, RunningSessions sessions) {
            return sessions != null && sessions.running(a) && !MeetingTurnViews.speaking(c, m, a);
        }

        private static AttendeeView attendee(Company c, AgentId a, boolean finishing) {
            return new AttendeeView(
                    a.value(),
                    c.staff().find(a).map(x -> x.displayName()).orElse(a.value()),
                    c.queries().statusOf(a).name(),
                    finishing);
        }
    }

    /** Who the panel proposes for a meeting about this mission: the agents working on it now. */
    public record Invitation(String mission, List<String> suggested) {
        public Invitation {
            suggested = suggested == null ? null : Collections.unmodifiableList(new ArrayList<>(suggested));
        }

        static Invitation of(Company c, MissionId m) {
            Set<String> who = new LinkedHashSet<>();
            for (Ticket t : c.tickets().of(m)) {
                boolean underWay = t.status() == TicketStatus.IN_PROGRESS || t.status() == TicketStatus.IN_REVIEW;
                if (underWay && t.assignee() != null) who.add(t.assignee().value());
            }
            c.sessions().of(m).stream()
                    .filter(s -> s.isOpen())
                    .forEach(s -> who.add(s.agent().value()));
            who.removeIf(
                    a -> c.staff().find(new AgentId(a)).map(x -> x.dismissed()).orElse(true));
            return new Invitation(m.value(), List.copyOf(who));
        }
    }
}
