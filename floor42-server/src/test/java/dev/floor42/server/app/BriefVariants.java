package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Committee;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.Setting;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketKind;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.CommitteeEvent;
import dev.floor42.domain.event.CommitteeEvent.CommitteeConvened;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MeetingEvent;
import dev.floor42.domain.event.MissionEvent.ChecksConfigured;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.MissionEvent.MissionPlanApproved;
import dev.floor42.domain.event.MissionEvent.MissionPlanSubmitted;
import dev.floor42.domain.event.PreferenceEvent.SandboxToolsProbed;
import dev.floor42.domain.event.PreferenceEvent.SettingChanged;
import dev.floor42.domain.event.RosterEvent.AgentHired;
import dev.floor42.domain.event.RosterEvent.AgentRoleAssigned;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.SessionEvent.AgentSessionStarted;
import dev.floor42.domain.event.TicketEvent.TicketAssigned;
import dev.floor42.domain.event.TicketEvent.TicketCompleted;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.TicketEvent.TicketReviewed;
import dev.floor42.domain.event.TicketEvent.TicketStarted;
import dev.floor42.domain.event.TicketEvent.TicketSubmittedForReview;
import dev.floor42.domain.event.VerificationEvent.ChecksRan;
import dev.floor42.domain.event.WorkbenchEvent.DirectorFilesCommitted;
import dev.floor42.domain.event.WorkbenchEvent.PatchAccepted;
import dev.floor42.domain.event.WorkbenchEvent.WorkspacePrepared;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * A journal built by hand for the branches of the briefs the golden journals do not reach: agents writing French, a
 * measured sandbox, the director's double, many director files, work done on another developer's branch, checks older
 * than the delivery, a producer reporting partial work.
 */
final class BriefVariants {

    static final MissionId M = new MissionId("M-1");
    private static final AgentId ADA = new AgentId("ada");
    private static final AgentId BOB = new AgentId("bob");
    private static final AgentId DOUBLE = new AgentId("double");
    private static final TicketId T1 = new TicketId("T-1");
    private static final TicketId T2 = new TicketId("T-2");
    private static final TicketId T3 = new TicketId("T-3");

    private final List<EventEnvelope> events = new ArrayList<>();
    private Instant now = Instant.parse("2026-10-20T08:00:00Z");

    private BriefVariants() {}

    static List<EventEnvelope> journal() {
        BriefVariants v = new BriefVariants();
        v.staff();
        v.settings();
        v.mission();
        v.work();
        v.committee();
        v.meeting();
        return List.copyOf(v.events);
    }

    /** What each worktree of the variants holds, as git would answer: unreadable, dirty, and ready to merge. */
    static List<MissionReports.ReportOutput> outputs() {
        return List.of(
                new MissionReports.ReportOutput(
                        "ada", "C:/w/ada", "f/ada", false, -1, List.of(), 0, List.of(), 0, null),
                new MissionReports.ReportOutput(
                        "bob",
                        "C:/w/bob",
                        "f/bob",
                        true,
                        2,
                        List.of("a.txt", "b.txt"),
                        2,
                        List.of("target/out.jar"),
                        1,
                        "git -C \"C:/repo\" merge --no-ff f/bob"));
    }

    private void staff() {
        for (AgentId a : List.of(ADA, BOB, DOUBLE)) {
            add(null, new AgentHired(a, a.value(), "Developer", "anthropic", "claude-sonnet-5-5", "medium", List.of()));
        }
        add(null, new AgentRoleAssigned(ADA, WorkRole.DEVELOPER));
        add(null, new AgentRoleAssigned(BOB, WorkRole.DEVELOPER));
    }

    private void settings() {
        add(null, new SettingChanged(Setting.AGENT_LANGUAGE, "fr"));
        add(null, new SettingChanged(Setting.SHELL_SANDBOX, "wsl"));
        add(null, new SettingChanged(Setting.SANDBOX_NETWORK, "on"));
        add(null, new SettingChanged(Setting.DIRECTOR_PROFILE, "I weigh costs first and distrust novelty."));
        add(
                null,
                new SandboxToolsProbed(
                        "Ubuntu",
                        "ubuntu",
                        "debian",
                        "24.04",
                        "Ubuntu 24.04",
                        true,
                        List.of("git|/usr/bin/git|2.43.0", "node|/usr/bin/node|", "mvn||"),
                        null));
    }

    private void mission() {
        add(M, new MissionCreated(M, "Variants", "Show every branch of the briefs", "C:/repo"));
        add(
                M,
                new DirectorFilesCommitted(
                        M,
                        "c0ffee",
                        IntStream.rangeClosed(1, 45)
                                .mapToObj(i -> "brief/file-" + i + ".md")
                                .toList()));
        add(M, new ChecksConfigured(M, "mvn verify", true));
        add(M, new MissionPlanSubmitted(M));
        add(M, new MissionPlanApproved(M));
        add(M, new WorkspacePrepared(M, ADA, "C:/w/ada", "f/ada", "base"));
        add(M, new WorkspacePrepared(M, BOB, "C:/w/bob", "f/bob", "base"));
    }

    private void work() {
        add(M, ticket(T1, "Build the gateway", List.of()));
        add(M, new TicketAssigned(T1, ADA));
        add(M, new TicketStarted(T1));
        add(M, new TicketSubmittedForReview(T1, "Built most of it.\nVERDICT: PARTIAL - two routes left"));
        add(M, new TicketReviewed(T1, BOB, ReviewVerdict.VALIDATED, "Fine as far as it goes."));
        add(M, new TicketCompleted(T1));
        add(M, new PatchAccepted(M, ADA, "1234567890", "Gateway", List.of("Gateway.java")));
        add(M, ticket(T2, "Route the APIs", List.of(T1)));
        add(M, new TicketAssigned(T2, BOB));
        add(M, new TicketStarted(T2));
        add(M, new ChecksRan(M, BOB, "mvn verify", 1, false, 1200, "BUILD FAILURE", "abc", "director"));
        add(M, new AgentSessionStarted("s-1", M, T2, BOB, "claude-sonnet-5-5", null, null));
        add(M, new AgentSessionEnded("s-1", false, "done", 3, 10L, 20L, 0.5, null));
        add(M, new TicketSubmittedForReview(T2, "Routed.\nVERDICT: DONE"));
    }

    private void committee() {
        add(M, ticket(T3, "Choose the gateway", List.of()));
        add(
                M,
                new CommitteeConvened(
                        M,
                        T3,
                        "Which gateway?",
                        List.of("ada", "double"),
                        List.of(CommitteeRoles.CHAIR, CommitteeRoles.DOUBLE),
                        CommitteeRoles.IDEA,
                        true,
                        3,
                        "double",
                        1));
        add(
                M,
                new CommitteeEvent.CommitteeContributed(
                        M,
                        DOUBLE,
                        1,
                        Committee.Kind.ANALYSIS,
                        "Mine.\nPROPOSAL: none",
                        "none",
                        "NEW",
                        null,
                        true,
                        null,
                        1));
    }

    /** The director's meeting (ADR-0047): what was said reaches the attendees' next briefs. */
    private void meeting() {
        add(M, new MeetingEvent.MeetingCalled("MT-1", M, List.of(BOB, ADA), "The routes"));
        add(M, new MeetingEvent.MeetingNoteGiven("MT-1", null, "Keep every route behind the gateway"));
        add(M, new MeetingEvent.MeetingNoteGiven("MT-1", BOB, "Add the health route first"));
        add(M, new MeetingEvent.MeetingClosed("MT-1", M, List.of(BOB, ADA)));
    }

    private static TicketCreated ticket(TicketId id, String title, List<TicketId> dependsOn) {
        return new TicketCreated(id, M, title, "", Priority.NORMAL, dependsOn, List.of("it works"), TicketKind.WORK);
    }

    private void add(MissionId mission, DomainEvent event) {
        now = now.plusSeconds(60);
        events.add(
                new EventEnvelope("v" + events.size(), now, Origin.REAL, Actor.director(), mission, null, null, event));
    }
}
