package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.MissionEvent;
import dev.floor42.domain.event.MissionEvent.BudgetSet;
import dev.floor42.domain.event.MissionEvent.ChecksConfigured;
import dev.floor42.domain.event.MissionEvent.MissionArchived;
import dev.floor42.domain.event.MissionEvent.MissionCancelled;
import dev.floor42.domain.event.MissionEvent.MissionCompleted;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.MissionEvent.MissionPlanApproved;
import dev.floor42.domain.event.MissionEvent.MissionPlanRejected;
import dev.floor42.domain.event.MissionEvent.MissionPlanSubmitted;
import dev.floor42.domain.event.MissionEvent.MissionResumed;
import dev.floor42.domain.event.MissionEvent.MissionSuspended;
import dev.floor42.domain.event.MissionEvent.MissionUnarchived;
import dev.floor42.domain.event.MissionEvent.PermissionRuleAdded;
import dev.floor42.domain.event.MissionEvent.PermissionRuleRemoved;
import dev.floor42.domain.event.MissionEvent.TicketValidationEnabled;

/** A mission's lines: its life, its permission table, its checks and its budget. */
final class MissionRenderer implements EventRenderer<MissionEvent> {

    @Override
    public EventLine render(Company c, MissionEvent event) {
        return switch (event) {
            case MissionCreated e ->
                mission(null, e.mission())
                        .param("title", e.title())
                        .param("objective", e.objective())
                        .param("workspace", e.workspace())
                        .build();
            case MissionPlanSubmitted e -> mission(null, e.mission()).build();
            case MissionPlanApproved e -> mission(null, e.mission()).build();
            case MissionPlanRejected e -> because(e.mission(), e.reason());
            case MissionSuspended e -> because(e.mission(), e.reason());
            case MissionResumed e -> mission(null, e.mission()).build();
            case MissionCompleted e ->
                mission(null, e.mission()).param("summary", e.summary()).build();
            case MissionCancelled e -> because(e.mission(), e.reason());
            case PermissionRuleAdded e ->
                mission(null, e.mission()).param("rule", e.rule()).build();
            case PermissionRuleRemoved e ->
                mission(null, e.mission()).param("rule", e.rule()).build();
            case TicketValidationEnabled e -> mission(null, e.mission()).build();
            case ChecksConfigured e -> checks(e);
            case MissionArchived e -> mission(null, e.mission()).build();
            case MissionUnarchived e -> mission(null, e.mission()).build();
            case BudgetSet e ->
                mission(e.usd() == null ? "none" : "usd", e.mission())
                        .param("usd", e.usd() == null ? null : Lookups.usd(e.usd()))
                        .build();
        };
    }

    private static EventLine checks(ChecksConfigured e) {
        String variant = e.command() == null ? "removed" : e.automatic() ? "automatic" : "manual";
        return mission(variant, e.mission())
                .param("command", e.command())
                .param("automatic", e.automatic())
                .build();
    }

    private static EventLine because(MissionId mission, String reason) {
        return mission(null, mission).param("reason", reason).build();
    }

    private static EventLine.Builder mission(String variant, MissionId mission) {
        return line(variant).param("mission", mission);
    }
}
