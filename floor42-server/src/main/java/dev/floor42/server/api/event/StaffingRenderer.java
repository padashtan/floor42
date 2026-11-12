package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.StaffingReview;
import dev.floor42.domain.event.StaffingEvent;
import dev.floor42.domain.event.StaffingEvent.StaffingAdvised;
import dev.floor42.domain.event.StaffingEvent.StaffingReviewRequested;

/** Staffing reviews' lines: the director's request and the planner's advice. */
final class StaffingRenderer implements EventRenderer<StaffingEvent> {

    @Override
    public EventLine render(Company c, StaffingEvent event) {
        return switch (event) {
            case StaffingReviewRequested e ->
                line().agent(e.planner())
                        .ticket(e.ticket())
                        .param("mission", e.mission())
                        .param("ticket", e.ticket())
                        .param("planner", e.planner())
                        .build();
            case StaffingAdvised e -> advised(e);
        };
    }

    /** The advice, and the names of the hires it proposes, joined once for every language. */
    private static EventLine advised(StaffingAdvised e) {
        return line(e.worthIt() ? "hire" : "nohire")
                .ticket(e.ticket())
                .param("mission", e.mission())
                .param("ticket", e.ticket())
                .param("advice", e.advice())
                .param("worthIt", e.worthIt())
                .param("hires", e.hires())
                .param(
                        "names",
                        e.hires().stream().map(StaffingReview.Hire::name).toList())
                .param("parallel", e.parallel())
                .build();
    }
}
