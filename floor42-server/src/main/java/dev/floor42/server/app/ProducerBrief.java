package dev.floor42.server.app;

import static dev.floor42.server.app.BriefSections.adoptedDecision;
import static dev.floor42.server.app.BriefSections.bullets;
import static dev.floor42.server.app.BriefSections.decisions;
import static dev.floor42.server.app.BriefSections.elsewhere;
import static dev.floor42.server.app.BriefSections.lessons;
import static dev.floor42.server.app.BriefSections.reviews;
import static dev.floor42.server.app.BriefSections.workshop;

import dev.floor42.domain.Agent;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.WorkRole;

/** The developer's brief: its ticket, what the director decided and changed, and what earlier reviews found. */
final class ProducerBrief {

    private ProducerBrief() {}

    static String of(Company c, Mission m, Ticket t, Agent agent) {
        return BriefTemplate.named("producer")
                .with("name", agent.displayName())
                .with("role", agent.role())
                .with("mission", m.id())
                .with("title", m.title())
                .with("objective", m.objective())
                .with("ticket", t.id())
                .with("ticketTitle", t.title())
                .with("description", description(m, t))
                .with("criteria", bullets(t.acceptanceCriteria()))
                .with("environment", BriefEnvironment.of(c) + directorStep(c, t))
                .with("permissions", BriefPermissions.permissions(c, m, agent, t.id()))
                .with("lessons", lessons(c, WorkRole.DEVELOPER))
                .with("decisions", decisions(c, m))
                .with("meeting", BriefSections.meeting(c, m, agent))
                .with("workshop", workshop(c, m, t))
                .with("elsewhere", elsewhere(c, t, agent))
                .with("adopted", adoptedDecision(c, m))
                .with("reviews", reviews(t) + NodeTests.failureBrief(c, t))
                .with("stepReports", TaskBrief.reports(c, m))
                .with("advisory", AdvisoryFindings.of(c, m, t, agent))
                .render();
    }

    /** The step the director said they do themselves, the sandbox lacking its tools; the review holds it for them. */
    private static String directorStep(Company c, Ticket t) {
        var questions = c.desk().of(t.mission());
        return dev.floor42.domain.EnvironmentBlocker.directorStep(questions, t.id())
                .or(() -> dev.floor42.domain.EnvironmentBlocker.missionStep(questions))
                .map(b -> "- The director does the steps that need " + b.missing() + " themselves: do everything else,"
                        + " do not try those steps another way, and end with BLOCKED BY ENVIRONMENT: " + b.missing()
                        + "; step: <the step left to the director>\n")
                .orElse("");
    }

    /** The ticket's own description, unless it only repeats the objective. */
    private static String description(Mission m, Ticket t) {
        return t.description().isBlank() || t.description().equals(m.objective()) ? "" : t.description() + "\n";
    }
}
