package dev.floor42.domain.event;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import java.time.Instant;
import java.util.Objects;

/**
 * An event with its provenance (ADR-0005). Origin and actor are mandatory: an event cannot exist without saying whether
 * it is real or simulated, and who caused it.
 *
 * @param mission nullable for company-wide events (hiring)
 * @param authorizationRef nullable; the decision that allowed what this event records
 * @param causationId nullable; the event or external signal that led to it
 */
public record EventEnvelope(
        String eventId,
        Instant occurredAt,
        Origin origin,
        Actor actor,
        MissionId mission,
        String authorizationRef,
        String causationId,
        DomainEvent event) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(event, "event");
    }

    public String type() {
        return event.getClass().getSimpleName();
    }
}
