package dev.floor42.server.api;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.ActionId;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Committee;
import dev.floor42.domain.ConsultationKind;
import dev.floor42.domain.Decision;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.ReviewNote;
import dev.floor42.domain.ReviewReservation;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.Setting;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.CommitteeEvent.CommitteeAdvanced;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.event.CommitteeEvent.CommitteeClarified;
import dev.floor42.domain.event.CommitteeEvent.CommitteeContributed;
import dev.floor42.domain.event.DeliveryEvent.MissionAssetsSelected;
import dev.floor42.domain.event.DeliveryEvent.PluginCommandRan;
import dev.floor42.domain.event.DeliveryEvent.PullRequestCheckState;
import dev.floor42.domain.event.DeliveryEvent.PullRequestOpened;
import dev.floor42.domain.event.DeskEvent.ApprovalForgotten;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.MeetingEvent;
import dev.floor42.domain.event.MissionEvent.BudgetSet;
import dev.floor42.domain.event.MissionEvent.ChecksConfigured;
import dev.floor42.domain.event.NotebookEvent.NoteChecked;
import dev.floor42.domain.event.PreferenceEvent.BreakGlassSignIn;
import dev.floor42.domain.event.PreferenceEvent.DefaultPermissionSet;
import dev.floor42.domain.event.PreferenceEvent.SandboxToolsProbed;
import dev.floor42.domain.event.PreferenceEvent.SettingChanged;
import dev.floor42.domain.event.PreferenceEvent.UnsafeModeChanged;
import dev.floor42.domain.event.RecentProjectEvent.ProjectPinned;
import dev.floor42.domain.event.RetroEvent.LessonProposed;
import dev.floor42.domain.event.RetroEvent.RetroConcluded;
import dev.floor42.domain.event.RosterEvent.AgentReconfigured;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.TerminalEvent.ApiTokenRevoked;
import dev.floor42.domain.event.TerminalEvent.TerminalClosed;
import dev.floor42.domain.event.TicketEvent.ConsultationAnswered;
import dev.floor42.domain.event.TicketEvent.ConsultationRequested;
import dev.floor42.domain.event.TicketEvent.ReviewItemsRecorded;
import dev.floor42.domain.event.VerificationEvent.ChecksRan;
import dev.floor42.domain.event.WorkbenchEvent.BranchesMerged;
import dev.floor42.domain.event.WorkbenchEvent.ProjectMerged;
import java.util.ArrayList;
import java.util.List;

/**
 * Every variant of every line whose sentence changes with the event, built by hand: the golden journals do not reach
 * them all (a terminal stopped with the daemon, a plugin removed from the director's Claude Code...). With the golden
 * journals, they make the list of codes the server can emit.
 */
final class EventVariants {

    private static final MissionId M = new MissionId("M-9");
    private static final AgentId A = new AgentId("ivo");
    private static final TicketId T = new TicketId("T-9");

    private EventVariants() {}

    static List<DomainEvent> all() {
        List<DomainEvent> all = new ArrayList<>();
        for (ConsultationKind k : ConsultationKind.values())
            all.add(new ConsultationRequested(T, A, k, "architect", "why?"));
        all.add(new ConsultationAnswered(T, null, "a"));
        all.add(new ConsultationAnswered(T, A, "a"));
        // A review's items (ADR-0053): kept as written, and a "with reservations" whose reservations were all notes.
        all.add(new ReviewItemsRecorded(
                T,
                A,
                ReviewVerdict.VALIDATED_WITH_RESERVATIONS,
                List.of(new ReviewReservation(1, "tests pass", "one test skipped", "run it")),
                List.of(new ReviewNote("naming", ReviewNote.Why.NOTE))));
        all.add(new ReviewItemsRecorded(
                T,
                A,
                ReviewVerdict.VALIDATED_WITH_RESERVATIONS,
                List.of(),
                List.of(new ReviewNote("style", ReviewNote.Why.NO_CRITERION))));
        for (boolean b : List.of(true, false)) {
            all.add(new ActionExecuted(new ActionId("M-9:toolu_9"), "out", b));
            all.add(new AgentSessionEnded("s-123456789", b, "o", 3, 10L, 20L, null, null));
            all.add(new AgentSessionEnded("s-123456789", b, "o", 3, 10L, 20L, 0.25, null));
            all.add(new NoteChecked("N-9", b));
            all.add(new CommitteeArbitrated(M, b, !b, b, 1));
            all.add(new ChecksConfigured(M, "mvn verify", b));
            all.add(new ProjectMerged(M, A, "abc", b, "conflict in a.txt"));
            all.add(new BranchesMerged(M, T, null, List.of("floor42/chris"), b, ""));
            all.add(new BranchesMerged(M, T, A, List.of("floor42/chris"), b, "x"));
            all.add(new SandboxToolsProbed("Ubuntu", "ubuntu", "debian", "24.04", "Ubuntu", b, List.of(), "no wsl"));
            all.add(new PullRequestOpened(M, A, "https://github.com/o/r/pull/9", 9, "b", "main", "origin", b));
            all.add(new ProjectPinned("C:/repo/app", b));
        }
        all.add(new dev.floor42.domain.event.SessionEvent.AgentTokenIssued(
                "s-123456789", A, M, T, "https://id.test/realms/floor42", "2026-10-20T16:00:00Z", "jti-1"));
        all.add(new dev.floor42.domain.event.SessionEvent.SessionRiskRaised(
                "s-r",
                A,
                M,
                T,
                "QUARANTINE",
                List.of("POLICY_DENIED", "BURST"),
                "2026-11-09T10:00:00Z",
                List.of("M-1:t1")));
        all.add(new dev.floor42.domain.event.SessionEvent.MissionQuarantined(
                "s-r", A, M, T, "QUARANTINE", List.of("POLICY_DENIED", "BURST"), true, false, true));
        all.add(new dev.floor42.domain.event.SessionEvent.MissionQuarantined(
                "s-r", A, M, T, "ALERT", List.of("OUTSIDE_WORKSPACE"), false, false, false));
        all.add(new ApprovalForgotten(new QuestionId("Q-9"), "forgotten by the director"));
        all.add(new ApprovalForgotten(new QuestionId("Q-9"), "once"));
        all.add(new ApprovalForgotten(new QuestionId("Q-9"), "asked again by the director, answering Q-10"));
        for (Committee.Stage s : Committee.Stage.values()) all.add(new CommitteeAdvanced(M, s, 2, 1));
        for (Committee.Kind k : Committee.Kind.values()) {
            for (Boolean director : java.util.Arrays.asList(null, false, true)) {
                all.add(new CommitteeContributed(M, A, 2, k, "t", "p", "pos", "o", director, List.of(), 1));
            }
        }
        all.add(new RetroConcluded(M, List.of(), null, "the chair failed"));
        all.add(new RetroConcluded(M, List.of(), "text", null));
        all.add(new RetroConcluded(M, List.of("first", "second"), "text", null));
        all.add(new ChecksConfigured(M, null, false));
        all.add(new ChecksRan(M, A, "mvn", null, true, 120_000, "", "abc", "manual"));
        all.add(new ChecksRan(M, A, "mvn", 0, false, 3_000, "", "abc", "manual"));
        all.add(new ChecksRan(M, A, "mvn", 1, false, 3_000, "", "abc", "manual"));
        all.add(new BudgetSet(M, null));
        all.add(new BudgetSet(M, 2.5));
        all.add(new SettingChanged(Setting.PARALLEL_SESSIONS, null));
        all.add(new SettingChanged(Setting.PARALLEL_SESSIONS, "3"));
        all.add(new DefaultPermissionSet("Bash", ActionCategory.EXECUTE, null));
        all.add(new DefaultPermissionSet("Bash", ActionCategory.EXECUTE, Decision.ASK));
        all.add(new UnsafeModeChanged(true, "WSL is not installed here"));
        all.add(new UnsafeModeChanged(false, null));
        all.add(new BreakGlassSignIn("S-3"));
        all.add(new CommitteeClarified(M, List.of(), 1));
        all.add(new CommitteeClarified(M, List.of("yes"), 1));
        all.add(new LessonProposed("L-9", M, null, "t", null));
        all.add(new LessonProposed("L-9", M, WorkRole.REVIEWER, "t", A));
        all.add(new AgentReconfigured(A, "claude-opus-5-5", null));
        all.add(new AgentReconfigured(A, "claude-opus-5-5", "high"));
        all.addAll(checks());
        all.addAll(plugins());
        all.add(new MissionAssetsSelected(M, List.of()));
        all.add(new MissionAssetsSelected(M, List.of("floor42:p@m/skill/s")));
        for (String reason : List.of("closed", "exited", "interrupted")) {
            all.add(new TerminalClosed("TERM-9", "C:/repo/app", reason, null));
            all.add(new TerminalClosed("TERM-9", "C:/repo/app", reason, 1));
        }
        all.add(new ApiTokenRevoked("TOK-9", "sha", "revoked"));
        all.add(new ApiTokenRevoked("TOK-9", "sha", "everywhere"));
        all.add(new MeetingEvent.MeetingCalled("MT-9", M, List.of(A), null));
        all.add(new MeetingEvent.MeetingCalled("MT-9", M, List.of(A), "the routes"));
        all.add(new MeetingEvent.MeetingNoteGiven("MT-9", null, "for all"));
        all.add(new MeetingEvent.MeetingNoteGiven("MT-9", A, "for one"));
        all.add(new dev.floor42.domain.event.MeetingRoomEvent.MeetingPlaced(
                "MT-9", dev.floor42.domain.MeetingPlace.CONFERENCE));
        all.add(new dev.floor42.domain.event.MeetingRoomEvent.MeetingViewsAsked(
                "MT-9", new TicketId("MT-9.turns"), 1, List.of(A), 0.5, null));
        all.add(new dev.floor42.domain.event.MeetingRoomEvent.MeetingTurnTaken(
                "MT-9", 1, A, "M-9:s1", "my view", null, 0.5));
        all.add(new dev.floor42.domain.event.MeetingRoomEvent.MeetingTurnTaken(
                "MT-9", 1, A, null, null, "the meeting's cost cap is reached", null));
        // Uncommitted work accepted (ADR-0055): files counted, and a worktree Floor42 could not read.
        all.add(new dev.floor42.domain.event.DeliveryEvent.UncommittedWorkAccepted(
                M,
                List.of(new dev.floor42.domain.UncommittedWorktree("ivo", "DIRTY", null, 2, List.of("a.md", "b.md")))));
        all.add(new dev.floor42.domain.event.DeliveryEvent.UncommittedWorkAccepted(
                M, List.of(new dev.floor42.domain.UncommittedWorktree("ivo", "UNKNOWN", "GIT_FAILED", 0, List.of()))));
        // A delivery's proof (ADR-0058): each variant of each line.
        var target = new dev.floor42.domain.ProofTarget(A, "0123456789abcdef", "abc1234", "fedcba9876543210");
        var floor42 = new dev.floor42.domain.ProofStep(
                1, "Up", "echo ok", "FLOOR42", null, "PASSED", null, 0, false, 5, "ok");
        var failed = new dev.floor42.domain.ProofStep(
                1, "Up", "echo ok", "FLOOR42", null, "FAILED", "EXIT_CODE", 1, false, 5, "");
        var mine = new dev.floor42.domain.ProofStep(
                2,
                "Health",
                "curl -s localhost:8080",
                "DIRECTOR",
                "NEEDS_DOCKER",
                "NOT_RUN",
                "DIRECTOR_STEP",
                null,
                false,
                0,
                "");
        all.add(new dev.floor42.domain.event.DeliveryEvent.ProofRan(M, target, List.of(floor42, mine)));
        all.add(new dev.floor42.domain.event.DeliveryEvent.ProofRan(M, target, List.of(failed, mine)));
        all.add(new dev.floor42.domain.event.DeliveryEvent.ProofRan(M, target, List.of(mine)));
        all.add(new dev.floor42.domain.event.DeliveryEvent.ProofStepWitnessed(M, target, 2, true, ""));
        all.add(new dev.floor42.domain.event.DeliveryEvent.ProofStepWitnessed(M, target, 2, false, "503"));
        all.add(new dev.floor42.domain.event.DeliveryEvent.ProofVerdictGiven(M, "PROVEN", List.of(target), ""));
        all.add(new dev.floor42.domain.event.DeliveryEvent.ProofVerdictGiven(M, "NOT_PROVEN", List.of(target), "no"));
        for (String state : List.of("NO_RECIPE", "NOT_RUN", "FAILED", "NOT_PROVEN", "NO_VERDICT"))
            all.add(new dev.floor42.domain.event.DeliveryEvent.UnprovenDeliveryAccepted(M, state, "0123456789abcdef"));
        // A pin this build cannot read (ADR-0050): the golden journals hold readable ones only.
        all.add(new dev.floor42.domain.event.WorkflowEvent.WorkflowPinned(M, "{}", 2));
        return all;
    }

    private static List<DomainEvent> checks() {
        return List.of(
                new PullRequestCheckState(M, 9, List.of()),
                new PullRequestCheckState(M, 9, List.of("build|pass|SUCCESS", "lint|fail|FAILURE")),
                new PullRequestCheckState(M, 9, List.of("build|pass|SUCCESS", "e2e|pending|IN_PROGRESS")),
                new PullRequestCheckState(M, 9, List.of("build|pass|SUCCESS", "docs|skipping|SKIPPED")));
    }

    private static List<DomainEvent> plugins() {
        List<DomainEvent> all = new ArrayList<>();
        for (String target : List.of("FLOOR42", "CLAUDE")) {
            for (String action : List.of("INSTALL", "UNINSTALL")) {
                for (boolean present : List.of(true, false)) {
                    all.add(new PluginCommandRan(
                            target, action, "p", "m", List.of("claude plugin"), 0, present, present ? "1.0" : null));
                }
            }
        }
        return all;
    }
}
