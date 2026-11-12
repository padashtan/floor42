package dev.floor42.server.app;

import dev.floor42.domain.Company;
import dev.floor42.domain.IncidentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.ActionEvent.IncidentRaised;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.MeetingEvent.MeetingCalled;
import dev.floor42.domain.event.RetroEvent.RetroOpened;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.journal.JournalException;
import dev.floor42.server.journal.Unreadable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Rebuilds the company from the journal, leaving out what cannot be read (ADR-0042).
 *
 * <ul>
 *   <li>A row that cannot be decoded (corrupt, or written by a newer version) is left out and listed: the company is
 *       then known to be incomplete, and the director is told.
 *   <li>A row that is read but cannot be applied, while every row before it was applied, stops the start. Each event was
 *       checked by the domain when it was written; failing now means the code that applies it changed meaning, or the
 *       journal was edited. Leaving it out would show a state that never existed, with nothing to say so, and the event
 *       may have changed part of the state before failing.
 *   <li>The same failure after a row was already left out is expected (the event builds on the one missing) and is
 *       left out too: the state is already declared incomplete, and the list says which rows depend on what.
 * </ul>
 */
public final class JournalReplay {

    public record Result(Company company, List<Unreadable> unreadable) {
        public Result {
            unreadable = unreadable == null ? null : Collections.unmodifiableList(new ArrayList<>(unreadable));
        }
    }

    private JournalReplay() {}

    public static Result run(Journal.Read read) {
        Company company = Company.replay(List.of());
        List<Unreadable> unreadable = new ArrayList<>(read.unreadable());
        long firstUnreadable =
                read.unreadable().stream().mapToLong(Unreadable::sequence).min().orElse(Long.MAX_VALUE);
        for (Journal.Entry entry : read.entries()) {
            try {
                company.apply(entry.envelope());
            } catch (RuntimeException e) {
                String detail = cause(e);
                if (entry.sequence() < firstUnreadable) {
                    throw new JournalException(
                            "Event #" + entry.sequence() + " ("
                                    + entry.envelope().type() + ") of the journal cannot be"
                                    + " applied: " + detail + ". Every event before it was. Floor42 does not start"
                                    + " rather than leave it out: the company would show a state that never existed."
                                    + " If a newer Floor42 wrote this journal, start that version again; otherwise"
                                    + " restore a backup made before this event (docs/backups.md).",
                            e);
                }
                unreadable.add(leftOut(entry, detail));
            }
        }
        return result(company, unreadable);
    }

    private static Unreadable leftOut(Journal.Entry entry, String detail) {
        return new Unreadable(
                entry.sequence(),
                entry.envelope().eventId(),
                entry.envelope().type(),
                entry.envelope().mission() == null
                        ? null
                        : entry.envelope().mission().value(),
                Unreadable.Reason.NOT_APPLICABLE,
                detail + ", after an earlier event was left out",
                ids(entry.envelope().event()));
    }

    /** The company, with every number a row left out holds kept from new missions, and the rows in their order. */
    private static Result result(Company company, List<Unreadable> unreadable) {
        unreadable.forEach(u -> reserve(company, u));
        return new Result(
                company,
                unreadable.stream()
                        .sorted(Comparator.comparingLong(Unreadable::sequence))
                        .toList());
    }

    /**
     * A row left out keeps its mission's number: a mission whose creation was left out is never re-created under the
     * same id by this version (ADR-0051), so the newer version, run again, does not find two missions in one.
     */
    private static void reserve(Company company, Unreadable u) {
        if (u.mission() != null) reserveOne(() -> company.missions().reserve(new MissionId(u.mission())));
        for (String id : u.ids()) {
            if (id.startsWith("T-")) reserveOne(() -> company.tickets().reserve(new TicketId(id)));
            else if (id.startsWith("Q-")) reserveOne(() -> company.desk().reserve(new QuestionId(id)));
            else if (id.startsWith("I-")) reserveOne(() -> company.actions().reserveIncident(new IncidentId(id)));
            else reserveOne(() -> company.meetings().reserve(id));
        }
    }

    private static void reserveOne(Runnable reservation) {
        try {
            reservation.run();
        } catch (RuntimeException e) {
            // Handled: an id this version cannot even parse reserves nothing; the row stays listed as unreadable
        }
    }

    /** The numbers an event this version read holds, for a row that could not be applied. */
    private static List<String> ids(DomainEvent event) {
        return switch (event) {
            case TicketCreated t -> List.of(t.ticket().value());
            case RetroOpened r -> List.of(r.ticket().value());
            case QuestionAsked q -> List.of(q.question().value());
            case IncidentRaised i -> List.of(i.incident().value());
            case MeetingCalled m -> List.of(m.meeting());
            default -> List.of();
        };
    }

    /**
     * What failed, without quoting the event: the exception's class, and for a missing reference the JVM's own words,
     * which name methods and fields, never values.
     */
    private static String cause(RuntimeException e) {
        String kind = e.getClass().getSimpleName();
        if (e instanceof NullPointerException && e.getMessage() != null) return kind + " (" + e.getMessage() + ")";
        return kind;
    }
}
