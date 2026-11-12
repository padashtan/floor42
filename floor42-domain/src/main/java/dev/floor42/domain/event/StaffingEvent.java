package dev.floor42.domain.event;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Staffing reviews: the planner's advice on reinforcing the team. */
public sealed interface StaffingEvent extends DomainEvent {

    /** The director asked the planner whether to reinforce the team; the review runs on its own ticket. */
    record StaffingReviewRequested(MissionId mission, TicketId ticket, AgentId planner) implements StaffingEvent {}

    /**
     * The planner's advice, as it wrote it: its ticket is done with the advice as deliverable. {@code parallel} is
     * null to keep the setting.
     */
    record StaffingAdvised(
            MissionId mission,
            TicketId ticket,
            String advice,
            boolean worthIt,
            List<dev.floor42.domain.StaffingReview.Hire> hires,
            Integer parallel)
            implements StaffingEvent {
        public StaffingAdvised {
            hires = hires == null ? null : Collections.unmodifiableList(new ArrayList<>(hires));
        }
    }
}
