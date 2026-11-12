package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.Mission;
import dev.floor42.domain.StaffingReview;

/** The planner's staffing review: whether more developers would finish sooner, at a cost worth paying. */
final class StaffingBrief {

    private StaffingBrief() {}

    static String of(Mission m, Agent agent, String facts) {
        return BriefTemplate.named("staffing")
                .with("name", agent.displayName())
                .with("role", agent.role())
                .with("mission", m.id())
                .with("title", m.title())
                .with("facts", facts)
                .with("maxHires", StaffingReview.MAX_HIRES)
                .render();
    }
}
