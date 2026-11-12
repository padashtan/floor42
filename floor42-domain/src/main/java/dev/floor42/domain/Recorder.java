package dev.floor42.domain;

import dev.floor42.domain.event.ActionEvent.ActionAuthorized;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Builds the envelopes of one command and hands each one to the company as soon as it is recorded: a command reads the
 * state its own earlier events made (ids derived from sizes, a committee moving on).
 */
final class Recorder {

    private final CommandContext ctx;
    private final MissionId mission;
    private final List<EventEnvelope> events;
    private final Actor actor;
    private final Consumer<EventEnvelope> company;

    Recorder(CommandContext ctx, MissionId mission, Consumer<EventEnvelope> company) {
        this(ctx, mission, new ArrayList<>(), ctx.actor(), company);
    }

    private Recorder(
            CommandContext ctx,
            MissionId mission,
            List<EventEnvelope> events,
            Actor actor,
            Consumer<EventEnvelope> company) {
        this.ctx = ctx;
        this.mission = mission;
        this.events = events;
        this.actor = actor;
        this.company = company;
    }

    /** Same command, events attributed to another actor (Floor42 deciding on an agent request). */
    Recorder as(Actor other) {
        return new Recorder(ctx, mission, events, other, company);
    }

    /** Same command and actor, events filed under another mission (a dismissal releasing tickets). */
    Recorder on(MissionId other) {
        return new Recorder(ctx, other, events, actor, company);
    }

    Recorder emit(DomainEvent event) {
        return emit(event, null);
    }

    Recorder emit(DomainEvent event, String authorizationRef) {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID().toString(),
                ctx.now(),
                ctx.origin(),
                actor,
                mission,
                authorizationRef,
                ctx.causationId(),
                event);
        company.accept(envelope);
        events.add(envelope);
        return this;
    }

    List<EventEnvelope> authorize(ActionId id, String basis, String authorizationRef) {
        return emit(new ActionAuthorized(id, basis), authorizationRef).events;
    }

    /** The events recorded so far by this command, in order; the caller may add the events of a further step. */
    List<EventEnvelope> events() {
        return events;
    }
}
