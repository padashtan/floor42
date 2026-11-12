package dev.floor42.server.api.event;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Company;
import dev.floor42.domain.event.DomainEvent;

/**
 * Writes the events of one area as lines: an exhaustive switch over the area's sealed interface, so a new event of the
 * area does not build until it has a line.
 *
 * @param <E> the area's events
 */
interface EventRenderer<E extends DomainEvent> {

    /** The line of the event, read against the company as it stands (who works a ticket, which call an action is). */
    EventLine render(Company c, E event);

    /** The same, for an area whose sentence changes with who caused the event; the others ignore the actor. */
    default EventLine render(Company c, E event, Actor actor) {
        return render(c, event);
    }
}
