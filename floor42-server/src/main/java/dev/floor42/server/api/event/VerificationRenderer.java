package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.PlanUsage;
import dev.floor42.domain.event.VerificationEvent;
import dev.floor42.domain.event.VerificationEvent.ChecksRan;
import dev.floor42.domain.event.VerificationEvent.PlanUsageObserved;
import java.util.Locale;

/** What Floor42 measured: checks run in a workspace and the plan's usage windows. The checks' output stays out. */
final class VerificationRenderer implements EventRenderer<VerificationEvent> {

    @Override
    public EventLine render(Company c, VerificationEvent event) {
        return switch (event) {
            case PlanUsageObserved e ->
                line().param("session", e.session())
                        .param("window", PlanUsage.label(e.window()))
                        .param("utilization", e.utilization())
                        .param("percent", String.format(Locale.ROOT, "%.0f", e.utilization() * 100))
                        .param("resetsAtEpochSecond", e.resetsAtEpochSecond())
                        .param("status", e.status())
                        .build();
            case ChecksRan e -> ran(e);
        };
    }

    private static EventLine ran(ChecksRan e) {
        String variant = e.timedOut() ? "timeout" : e.exitCode() != null && e.exitCode() == 0 ? "passed" : "failed";
        return line(variant)
                .agent(e.workspace())
                .param("mission", e.mission())
                .param("workspace", e.workspace())
                .param("command", e.command())
                .param("exitCode", e.exitCode())
                .param("timedOut", e.timedOut())
                .param("durationMs", e.durationMs())
                .param("seconds", e.durationMs() / 1000)
                .param("head", e.head())
                .param("trigger", e.trigger())
                .build();
    }
}
