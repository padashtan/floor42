package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.workflow.Workflow;

/** The planner's brief: split the objective into tickets the director can weigh one by one (ADR-0017). */
final class PlannerBrief {

    /** Most tickets a planner may propose under any workflow: more is a plan the director cannot weigh. */
    static final int MAX_PLANNED_TICKETS = Workflow.Step.Plan.MAX_TICKETS;

    private PlannerBrief() {}

    static String of(Company c, Mission m, Agent agent) {
        boolean graph = c.workflows().of(m.id()).runnable().map(Workflow::graph).orElse(false);
        return BriefTemplate.named(graph ? "planner-graph" : "planner")
                .with("name", agent.displayName())
                .with("role", agent.role())
                .with("mission", m.id())
                .with("title", m.title())
                .with("objective", m.objective())
                .with("team", TicketSpread.team(c))
                .with("decisions", BriefSections.decisions(c, m))
                .with("lessons", BriefSections.lessons(c, WorkRole.PLANNER))
                .with("maxTickets", maxTickets(c, m.id()))
                .with("tools", tools(c))
                .render();
    }

    /** Where the developers' commands run and what is there, so NEEDS can be weighed; empty without the sandbox. */
    static String tools(Company c) {
        String tools = BriefEnvironment.tools(c);
        if (tools.isEmpty()) return "";
        return "\nThe developers' commands run in a Linux sandbox that sees only the worktree, with no browser"
                + " window and no Windows program. Floor42 asks the director about a ticket that NEEDS an absent tool"
                + " before it runs; plan the work so each such step is a ticket of its own.\n"
                + tools;
    }

    /** The plan step's limit in the mission's pinned workflow (ADR-0050); the default for any other mission. */
    static int maxTickets(Company c, MissionId m) {
        return c.workflows().of(m).runnable().map(Workflow::maxTickets).orElse(MAX_PLANNED_TICKETS);
    }
}
