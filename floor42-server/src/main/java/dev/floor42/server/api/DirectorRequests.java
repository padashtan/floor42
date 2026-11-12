package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.server.api.MissionViews.MissionView;
import dev.floor42.server.app.OfficeService;
import jakarta.ws.rs.NotFoundException;
import java.util.function.Supplier;

/**
 * What the director's resources share: reading the ids of a path, the context a decision of the director is recorded
 * in, and the mission a decision answers with.
 */
public final class DirectorRequests {

    /** Why the director decides: a reason for a dismissal, a rejection, a drop; a summary for a completion. */
    public record Reason(String reason) {}

    private DirectorRequests() {}

    /** An id the path names; a malformed one names nothing (404). */
    static <T> T parse(Supplier<T> id) {
        try {
            return id.get();
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage(), e);
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** The director decides for the company: always real. */
    static CommandContext asDirector(OfficeService office) {
        return office.context(Actor.director(), Origin.REAL);
    }

    /** The director decides on a mission: recorded in its mode, simulated or real. */
    static CommandContext asDirector(OfficeService office, MissionId m) {
        return office.context(Actor.director(), originOf(office, m));
    }

    static Origin originOf(OfficeService office, MissionId m) {
        return office.query(c -> c.missions()
                .find(m)
                .orElseThrow(() -> new NotFoundException("No mission " + m))
                .origin());
    }

    /** The mission as it stands, for a decision that just applied to it. */
    static MissionView missionView(OfficeService office, MissionId m) {
        return office.query(c -> MissionView.of(c, c.missions().find(m).orElseThrow()));
    }

    static MissionId missionOf(OfficeService office, TicketId t) {
        return office.query(c -> c.tickets().find(t).map(Ticket::mission))
                .orElseThrow(() -> new NotFoundException("No ticket " + t));
    }
}
