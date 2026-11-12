package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;

/**
 * Creating a ticket, with every check the ticket board makes, for an area whose command starts with one (a note made a
 * ticket, a staffing review): the area gets the command, not the board.
 */
@FunctionalInterface
interface TicketCreation {

    List<EventEnvelope> create(
            CommandContext ctx,
            MissionId mission,
            String title,
            String description,
            Priority priority,
            List<TicketId> dependsOn,
            List<String> acceptanceCriteria);
}
