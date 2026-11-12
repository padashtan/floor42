package dev.floor42.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One ticket of a planner's proposal; dependencies are indexes of earlier proposals (0-based).
 *
 * @param existing tickets of the mission this one also depends on: a graph extension builds on the nodes already there
 *     (ADR-0062)
 */
public record ProposedTicket(
        String title,
        String description,
        List<String> acceptanceCriteria,
        List<Integer> dependsOn,
        List<TicketId> existing) {

    public ProposedTicket(String title, String description, List<String> acceptanceCriteria, List<Integer> dependsOn) {
        this(title, description, acceptanceCriteria, dependsOn, List.of());
    }

    public ProposedTicket {
        existing = existing == null ? List.of() : List.copyOf(existing);

        acceptanceCriteria =
                acceptanceCriteria == null ? null : Collections.unmodifiableList(new ArrayList<>(acceptanceCriteria));
        dependsOn = dependsOn == null ? null : Collections.unmodifiableList(new ArrayList<>(dependsOn));
    }
}
