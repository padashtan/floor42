package dev.floor42.domain;

import static dev.floor42.domain.Origin.REAL;
import static dev.floor42.domain.Origin.SIMULATED;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.event.DeskEvent;
import dev.floor42.domain.event.DomainEvents;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.golden.CompanySnapshot;
import dev.floor42.domain.golden.GoldenFile;
import dev.floor42.domain.workflow.Workflow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The golden master of the company's behaviour: one long scripted day touching every area, every kind of question and
 * many refusals. What each command emitted (or why it was refused) and the state the journal replays to are compared
 * with what the code produced when the master was recorded.
 */
class GoldenScenarioTest {

    static final AgentId GALEN = new AgentId("galen");
    static final AgentId CHRIS = new AgentId("chris");
    static final AgentId DARIUS = new AgentId("darius");
    static final AgentId KEN = new AgentId("ken");
    static final AgentId ANA = new AgentId("ana");
    static final AgentId RITA = new AgentId("rita");
    static final AgentId IVO = new AgentId("ivo");
    static final MissionId M1 = new MissionId("M-1");
    static final MissionId M2 = new MissionId("M-2");
    static final MissionId M3 = new MissionId("M-3");
    static final MissionId M4 = new MissionId("M-4");
    static final String APP = "C:/repo/app";

    final GoldenScenario s = new GoldenScenario();
    final Company c = s.company;

    @Test
    void theScriptedDayEmitsTheRecordedEventsAndReplaysToTheRecordedState() {
        play();

        String live = CompanySnapshot.of(c);
        String replayed = CompanySnapshot.of(Company.replay(s.journal));
        assertThat(GoldenFile.check("scenario/commands.txt", s.record.toString()))
                .isNull();
        assertThat(GoldenFile.check("scenario/snapshot.txt", replayed)).isNull();
        assertThat(replayed).as("the journal replays to the live state").isEqualTo(live);
    }

    @Test
    void theScriptedDayEmitsEveryKindOfEventAndAsksEveryKindOfQuestion() {
        play();

        Set<String> emitted = s.journal.stream().map(EventEnvelope::type).collect(Collectors.toSet());
        Set<String> all = new TreeSet<>(DomainEvents.types().keySet());
        all.removeAll(emitted);
        assertThat(all).as("event types the scenario never emits").isEmpty();
        Set<QuestionKind> asked = s.journal.stream()
                .map(EventEnvelope::event)
                .filter(e -> e instanceof DeskEvent.QuestionAsked)
                .map(e -> ((DeskEvent.QuestionAsked) e).kind())
                .collect(Collectors.toSet());
        assertThat(asked).containsExactlyInAnyOrder(QuestionKind.values());
    }

    void play() {
        staff();
        companyWide();
        notesAndTerminals();
        planning();
        work();
        approvals();
        review();
        workshop();
        budgetAndUsage();
        closing();
        retrospective();
        architectureCommittee();
        ideaCommittee();
        simulated();
        projects();
        meetings();
        workflows();
        deliverablesHome();
        archives();
        reviewItems();
        proofs();
        tasks();
        graphNodes();
        unsafeMode();
        sessionRisk();
    }

    /** A real session's risk goes up (ADR-0066), last so the day recorded before stays as it was. */
    void sessionRisk() {
        s.run(
                "the risk of a real session goes up",
                () -> c.sessions()
                        .riskRaised(
                                s.system(REAL),
                                "s-iam",
                                RiskLevel.ALERT,
                                List.of(RiskReason.OUTSIDE_WORKSPACE),
                                java.time.Instant.parse("2026-10-21T01:00:00Z"),
                                List.of()));
        s.run(
                "a lower risk is refused",
                () -> c.sessions()
                        .riskRaised(
                                s.system(REAL),
                                "s-iam",
                                RiskLevel.NOTICE,
                                List.of(RiskReason.POLICY_DENIED),
                                java.time.Instant.parse("2026-10-21T01:00:00Z"),
                                List.of()));
        s.run(
                "the director quarantines the session",
                () -> c.sessions().quarantined(s.director(REAL), "s-iam", true, false, true));
    }

    /** Unsafe mode (ADR-0063), last so the day recorded before stays as it was. */
    void unsafeMode() {
        s.run("unsafe mode without a reason", () -> c.preferences().setUnsafeMode(s.director(REAL), true, " "));
        s.run("unsafe mode on", () -> c.preferences().setUnsafeMode(s.director(REAL), true, "WSL is not installed"));
        s.run("unsafe mode off", () -> c.preferences().setUnsafeMode(s.director(REAL), false, null));
        s.run("break-glass sign-in", () -> c.preferences().recordBreakGlassSignIn(s.director(REAL), "S-1"));
    }

    /** A delivery's proof (ADR-0058), last so the day recorded before stays as it was. */
    void proofs() {
        MissionId m = c.missions().all().stream()
                .filter(x -> x.origin() == REAL && !x.status().isTerminal())
                .findFirst()
                .orElseThrow()
                .id();
        ProofTarget target = new ProofTarget(CHRIS, "0123456789abcdef", "abc1234", "fedcba9876543210");
        ProofStep ran = new ProofStep(1, "Up", "echo ok", "FLOOR42", null, "PASSED", null, 0, false, 12, "ok");
        ProofStep mine = new ProofStep(
                2,
                "Health",
                "curl localhost",
                "DIRECTOR",
                "NEEDS_DOCKER",
                "NOT_RUN",
                "DIRECTOR_STEP",
                null,
                false,
                0,
                "");
        var proofs = c.deliveries().proofs();
        s.run("a run recorded by the director", () -> proofs.recordRun(s.director(REAL), m, target, List.of(ran)));
        s.run("proof run", () -> proofs.recordRun(s.system(REAL), m, target, List.of(ran, mine)));
        s.run("a witness without words", () -> proofs.witness(s.director(REAL), m, target, 2, false, ""));
        s.run("the director's step fails", () -> proofs.witness(s.director(REAL), m, target, 2, false, "503"));
        s.run("verdict", () -> proofs.verdict(s.director(REAL), m, "NOT_PROVEN", List.of(target), "no"));
        s.run("unproven accepted", () -> proofs.acceptUnproven(s.director(REAL), m, "NOT_PROVEN", "0123456789abcdef"));
        s.ask(
                "the proof",
                () -> List.of(proofs.runs(m), proofs.witnesses(m), proofs.verdict(m), proofs.unprovenAccepted(m)));
    }

    /** Closed missions put away and one brought back (ADR-0054), last so the day recorded before stays as it was. */
    void archives() {
        MissionId open = c.missions().all().stream()
                .filter(x -> x.origin() == REAL && !x.status().isTerminal())
                .findFirst()
                .orElseThrow()
                .id();
        MissionId closed = c.missions().all().stream()
                .filter(x -> x.origin() == REAL
                        && c.missions()
                                .refusalToArchive(s.director(REAL), x.id())
                                .isEmpty())
                .findFirst()
                .orElseThrow()
                .id();
        s.run("archive a mission still open", () -> c.missions().archive(s.director(REAL), open));
        s.run("archive by an agent", () -> c.missions().archive(s.agent(CHRIS, REAL), closed));
        s.run("archive", () -> c.missions().archive(s.director(REAL), closed));
        s.run("archive again", () -> c.missions().archive(s.director(REAL), closed));
        s.ask("archive refused as a value", () -> c.missions().refusalToArchive(s.director(REAL), open));
        s.run("restore", () -> c.missions().unarchive(s.director(REAL), closed));
        s.run("restore again", () -> c.missions().unarchive(s.director(REAL), closed));
        s.run("archive for good", () -> c.missions().archive(s.director(REAL), closed));
    }

    /** A review's reservations and notes (ADR-0053), last so the day recorded before stays as it was. */
    void reviewItems() {
        s.run(
                "a mission to review",
                () -> c.missions()
                        .create(
                                s.director(REAL),
                                "Calibrated",
                                "Review against criteria",
                                "C:/repo/api",
                                Workflow.BUILT_IN));
        MissionId m = c.missions().all().stream()
                .filter(x -> x.title().equals("Calibrated"))
                .findFirst()
                .orElseThrow()
                .id();
        s.run(
                "a ticket with criteria",
                () -> c.tickets()
                        .create(
                                s.agent(GALEN, REAL),
                                m,
                                "Write the guide",
                                "",
                                Priority.NORMAL,
                                List.of(),
                                List.of("covers install", "covers upgrade")));
        TicketId t = c.tickets().of(m).getLast().id();
        s.run("assign it", () -> c.tickets().assign(s.director(REAL), t, CHRIS));
        s.run("submit the plan", () -> c.missions().submitPlan(s.agent(GALEN, REAL), m));
        s.run("its budget", () -> c.verification().setBudget(s.director(REAL), m, 2.0));
        s.run("approve the plan", () -> c.missions().approvePlan(s.director(REAL), m));
        s.run("start the guide", () -> c.tickets().start(s.agent(CHRIS, REAL), t));
        s.run("submit the guide", () -> c.tickets().submitForReview(s.agent(CHRIS, REAL), t, "The guide"));
        s.run(
                "reservations that are notes",
                () -> c.tickets()
                        .review(
                                s.agent(DARIUS, REAL),
                                t,
                                ReviewVerdict.VALIDATED_WITH_RESERVATIONS,
                                "RESERVATION: naming | CHANGE: rename\nRESERVATION C1: thin | CHANGE: add a step",
                                List.of(
                                        ReviewClaim.reservation(null, "naming", "rename"),
                                        ReviewClaim.reservation("C1", "thin", "add a step"),
                                        ReviewClaim.reservation("C7", "unknown", "x"),
                                        ReviewClaim.note("nice"))));
        s.ask("the reviews kept", () -> c.tickets().find(t).map(Ticket::reviews));
        uncommittedWork(m);
    }

    /** The director accepts a mission with work left uncommitted (ADR-0055), recorded with the completion. */
    void uncommittedWork(MissionId m) {
        UncommittedWorktree dirty =
                new UncommittedWorktree("chris", UncommittedWorktree.DIRTY, null, 2, List.of("deliverables/a.md"));
        UncommittedWorktree unknown =
                new UncommittedWorktree("darius", UncommittedWorktree.UNKNOWN, "GIT_FAILED", 0, List.of());
        s.run(
                "an agent accepts uncommitted work",
                () -> c.deliveries().acceptUncommitted(s.agent(CHRIS, REAL), m, List.of(dirty)));
        s.run("nothing to accept", () -> c.deliveries().acceptUncommitted(s.director(REAL), m, List.of()));
        s.run(
                "a clean worktree is not uncommitted",
                () -> c.deliveries()
                        .acceptUncommitted(
                                s.director(REAL),
                                m,
                                List.of(new UncommittedWorktree("chris", "CLEAN", null, 0, List.of()))));
        s.run(
                "unknown without a reason",
                () -> c.deliveries()
                        .acceptUncommitted(
                                s.director(REAL),
                                m,
                                List.of(new UncommittedWorktree(
                                        "chris", UncommittedWorktree.UNKNOWN, null, 0, List.of()))));
        s.run("accept the uncommitted work and close", () -> {
            List<EventEnvelope> events = new java.util.ArrayList<>(
                    c.deliveries().acceptUncommitted(s.director(REAL), m, List.of(dirty, unknown)));
            events.addAll(c.missions().complete(s.director(REAL), m, "Accepted with work left uncommitted"));
            return events;
        });
        s.ask("the uncommitted work accepted", () -> c.deliveries().uncommittedAccepted(m));
        s.run("after the close", () -> c.deliveries().acceptUncommitted(s.director(REAL), m, List.of(dirty)));
    }

    /** Where the accepted deliverables land (ADR-0051), last so the day recorded before stays as it was. */
    void deliverablesHome() {
        MissionId m = c.missions().all().stream()
                .filter(x -> x.title().equals("Pinned") && x.origin() == REAL)
                .findFirst()
                .orElseThrow()
                .id();
        String home = "C:/repo/api/deliverables";
        s.run("destination by an agent", () -> c.deliveries().setDestination(s.agent(CHRIS, REAL), m, home));
        s.run("relative destination", () -> c.deliveries().setDestination(s.director(REAL), m, "deliverables"));
        s.run(
                "copy without a destination",
                () -> c.deliveries().recordCopy(s.director(REAL), m, home, List.of(), 0, false));
        s.run("destination", () -> c.deliveries().setDestination(s.director(REAL), m, home));
        List<DeliveryCopy> files = List.of(
                new DeliveryCopy("chris", "notes.md", "notes.md", DeliveryCopy.WRITTEN, null),
                new DeliveryCopy("chris", "report.pdf", "report (2).pdf", DeliveryCopy.KEPT_BOTH, null),
                new DeliveryCopy("chris", "run.sh", null, DeliveryCopy.LEFT_OUT, "not a document"));
        s.run(
                "copy outside the destination",
                () -> c.deliveries().recordCopy(s.director(REAL), m, "C:/elsewhere/M-9", files, 0, false));
        s.run(
                "deliverables copied",
                () -> c.deliveries().recordCopy(s.director(REAL), m, home + "/" + m.value(), files, 1, false));
        s.ask("the copy", () -> c.deliveries().copy(m));
    }

    void staff() {
        s.run(
                "hire planner",
                () -> c.staff()
                        .hire(
                                s.director(REAL),
                                GALEN,
                                "Galen",
                                "Planner",
                                WorkRole.PLANNER,
                                "claude-sonnet-5-5",
                                "medium"));
        s.run(
                "hire developer",
                () -> c.staff()
                        .hire(
                                s.director(REAL),
                                CHRIS,
                                "Chris",
                                "Developer",
                                WorkRole.DEVELOPER,
                                "claude-sonnet-5-5",
                                "high"));
        s.run(
                "hire reviewer",
                () -> c.staff()
                        .hire(
                                s.director(REAL),
                                DARIUS,
                                "Darius",
                                "Reviewer",
                                WorkRole.REVIEWER,
                                "claude-opus-5-5",
                                null));
        s.run(
                "hire second developer",
                () -> c.staff()
                        .hire(
                                s.director(REAL),
                                KEN,
                                "Ken",
                                "Developer",
                                WorkRole.DEVELOPER,
                                "claude-haiku-5-5",
                                "low"));
        s.run(
                "hire analyst",
                () -> c.staff()
                        .hire(
                                s.director(REAL),
                                ANA,
                                "Ana",
                                "Analyst",
                                WorkRole.ANALYST,
                                "claude-sonnet-5-5",
                                "medium"));
        s.run(
                "hire researcher",
                () -> c.staff()
                        .hire(
                                s.director(REAL),
                                RITA,
                                "Rita",
                                "Researcher",
                                WorkRole.RESEARCHER,
                                "claude-sonnet-5-5",
                                "medium"));
        s.run(
                "hire without a role",
                () -> c.staff()
                        .hire(
                                s.director(REAL),
                                IVO,
                                "Ivo",
                                "Generalist",
                                "anthropic",
                                "claude-sonnet-5-5",
                                "max",
                                List.of("java", "sql")));
        s.run(
                "hire twice",
                () -> c.staff().hire(s.director(REAL), GALEN, "G", "Planner", WorkRole.PLANNER, "m", "low"));
        s.run(
                "an agent hires",
                () -> c.staff()
                        .hire(s.agent(CHRIS, REAL), new AgentId("eve"), "Eve", "x", "anthropic", "m", null, List.of()));
        s.run(
                "hire with a blank name",
                () -> c.staff()
                        .hire(s.director(REAL), new AgentId("eve"), " ", "x", "anthropic", "m", null, List.of()));
        s.run(
                "hire with no work role",
                () -> c.staff().hire(s.director(REAL), new AgentId("eve"), "Eve", "x", (WorkRole) null, "m", null));
        s.run("reconfigure", () -> c.staff().reconfigure(s.director(REAL), CHRIS, " claude-opus-5-5 ", " xhigh "));
        s.run(
                "reconfigure to the same",
                () -> c.staff().reconfigure(s.director(REAL), CHRIS, "claude-opus-5-5", "xhigh"));
        s.run("reconfigure with an unknown effort", () -> c.staff().reconfigure(s.director(REAL), CHRIS, "m", "huge"));
        s.run(
                "reconfigure without effort",
                () -> c.staff().reconfigure(s.director(REAL), IVO, "claude-sonnet-5-5", " "));
        s.run("suspend", () -> c.staff().suspendAssignments(s.director(REAL), IVO, "holidays"));
        s.run("suspend twice", () -> c.staff().suspendAssignments(s.director(REAL), IVO, "again"));
        s.run("resume", () -> c.staff().resumeAssignments(s.director(REAL), IVO));
        s.run("resume twice", () -> c.staff().resumeAssignments(s.director(REAL), IVO));
        s.run("unknown agent", () -> c.staff().resumeAssignments(s.director(REAL), new AgentId("nobody")));
        s.run("mark core", () -> c.staff().markCore(s.director(REAL), CHRIS, true));
        s.run("mark core twice", () -> c.staff().markCore(s.director(REAL), CHRIS, true));
        s.run("mark core in a simulation", () -> c.staff().markCore(s.director(SIMULATED), CHRIS, false));
        s.run("unmark core", () -> c.staff().markCore(s.director(REAL), CHRIS, false));
        s.run("mark core again", () -> c.staff().markCore(s.director(REAL), DARIUS, true));
    }

    void companyWide() {
        s.run("language", () -> c.preferences().changeSetting(s.director(REAL), Setting.AGENT_LANGUAGE, "fr"));
        s.run("language again", () -> c.preferences().changeSetting(s.director(REAL), Setting.AGENT_LANGUAGE, "fr"));
        s.run(
                "parallel sessions",
                () -> c.preferences().changeSetting(s.director(REAL), Setting.PARALLEL_SESSIONS, "2"));
        s.run(
                "unknown parallel value",
                () -> c.preferences().changeSetting(s.director(REAL), Setting.PARALLEL_SESSIONS, "9"));
        s.run(
                "setting by an agent",
                () -> c.preferences().changeSetting(s.agent(CHRIS, REAL), Setting.AGENT_LANGUAGE, "en"));
        s.run("no setting", () -> c.preferences().changeSetting(s.director(REAL), null, "en"));
        s.run("default language", () -> c.preferences().changeSetting(s.director(REAL), Setting.AGENT_LANGUAGE, null));
        s.run(
                "default permission",
                () -> c.preferences()
                        .setDefaultPermission(s.director(REAL), "Bash", ActionCategory.EXECUTE, Decision.DENY));
        s.run(
                "default permission again",
                () -> c.preferences()
                        .setDefaultPermission(s.director(REAL), "Bash", ActionCategory.EXECUTE, Decision.DENY));
        s.run(
                "default permission back",
                () -> c.preferences().setDefaultPermission(s.director(REAL), "Bash", ActionCategory.EXECUTE, null));
        s.run(
                "default permission for reads",
                () -> c.preferences()
                        .setDefaultPermission(s.director(REAL), "Read", ActionCategory.READ, Decision.ALLOW));
        s.run(
                "default permission without category",
                () -> c.preferences().setDefaultPermission(s.director(REAL), "Read", null, Decision.ALLOW));
        s.run(
                "seat",
                () -> c.committees()
                        .defineSeat(
                                s.director(REAL),
                                "lawyer",
                                "Lena",
                                "Lawyer",
                                "claude-opus-5-5",
                                "high",
                                "Reads every risk as a lawyer",
                                List.of("Idea ", "idea", "architecture")));
        s.run(
                "seat changed",
                () -> c.committees()
                        .defineSeat(
                                s.director(REAL),
                                "lawyer",
                                "Lena",
                                "Senior lawyer",
                                "claude-opus-5-5",
                                "high",
                                "Liability first",
                                List.of("idea")));
        s.run(
                "seat on an agent's name",
                () -> c.committees().defineSeat(s.director(REAL), "galen", "G", "T", "m", "e", "l", List.of("idea")));
        s.run(
                "seat with a bad key",
                () -> c.committees().defineSeat(s.director(REAL), "X", "G", "T", "m", "e", "l", List.of("idea")));
        s.run(
                "seat on an unknown panel",
                () -> c.committees().defineSeat(s.director(REAL), "cfo", "G", "T", "m", "e", "l", List.of("budget")));
        s.run(
                "sandbox measured",
                () -> c.preferences()
                        .recordSandboxProbe(
                                s.system(REAL),
                                "Ubuntu",
                                "ubuntu",
                                "debian",
                                "24.04",
                                "Ubuntu 24.04",
                                true,
                                List.of("git|/usr/bin/git|2.43.0", "java|/usr/bin/java|25"),
                                null));
        s.run(
                "sandbox measured by the director",
                () -> c.preferences()
                        .recordSandboxProbe(s.director(REAL), "Ubuntu", null, null, null, null, true, List.of(), null));
        s.run(
                "sandbox with a bad line",
                () -> c.preferences()
                        .recordSandboxProbe(
                                s.system(REAL), "Ubuntu", null, null, null, null, true, List.of("git"), null));
        s.run("sandbox turned on", () -> c.preferences().changeSetting(s.director(REAL), Setting.SHELL_SANDBOX, "wsl"));
        s.run(
                "sandbox not measured",
                () -> c.preferences()
                        .recordSandboxProbe(
                                s.system(REAL),
                                "Ubuntu",
                                null,
                                null,
                                null,
                                null,
                                false,
                                List.of(),
                                "wsl.exe not found"));
        s.run(
                "plugin installed",
                () -> c.deliveries()
                        .recordPluginCommand(
                                s.director(REAL),
                                "FLOOR42",
                                "INSTALL",
                                "pr-review",
                                "claude-plugins-official",
                                List.of("claude plugin install pr-review"),
                                0,
                                true,
                                "1.2.0"));
        s.run(
                "plugin with an unknown target",
                () -> c.deliveries()
                        .recordPluginCommand(
                                s.director(REAL), "ELSEWHERE", "INSTALL", "p", "m", List.of("x"), 0, true, null));
        s.run(
                "plugin by Floor42",
                () -> c.deliveries()
                        .recordPluginCommand(
                                s.system(REAL), "CLAUDE", "UNINSTALL", "p", "m", List.of("x"), 1, false, null));
        s.run(
                "token issued",
                () -> c.terminals()
                        .recordApiTokenIssued(
                                s.director(REAL),
                                "tok-1",
                                "abababababababababababababababababababababababababababababababab",
                                "read",
                                "2026-11-30T00:00:00Z",
                                "ci"));
        s.run(
                "token with a bad scope",
                () -> c.terminals()
                        .recordApiTokenIssued(
                                s.director(REAL),
                                "tok-2",
                                "abababababababababababababababababababababababababababababababab",
                                "admin",
                                "2026-11-30T00:00:00Z",
                                null));
        s.run(
                "token kept in clear",
                () -> c.terminals()
                        .recordApiTokenIssued(
                                s.director(REAL), "tok-2", "secret", "all", "2026-11-30T00:00:00Z", null));
        s.run(
                "token by an agent",
                () -> c.terminals()
                        .recordApiTokenIssued(
                                s.agent(CHRIS, REAL),
                                "tok-2",
                                "abababababababababababababababababababababababababababababababab",
                                "all",
                                "x",
                                null));
        s.run(
                "token in a simulation",
                () -> c.terminals()
                        .recordApiTokenIssued(
                                s.director(SIMULATED),
                                "tok-2",
                                "abababababababababababababababababababababababababababababababab",
                                "all",
                                "x",
                                null));
        s.run(
                "token revoked",
                () -> c.terminals()
                        .recordApiTokenRevoked(
                                s.director(REAL),
                                "tok-1",
                                "abababababababababababababababababababababababababababababababab",
                                "everywhere"));
        s.run(
                "token revoked oddly",
                () -> c.terminals()
                        .recordApiTokenRevoked(
                                s.director(REAL),
                                "tok-1",
                                "abababababababababababababababababababababababababababababababab",
                                "lost"));
    }

    void notesAndTerminals() {
        s.run("note", () -> c.notebook().add(s.director(REAL), "  Make the board faster\nIt lags on big missions  "));
        s.run("second note", () -> c.notebook().add(s.director(REAL), "Rename the tabs"));
        s.run("third note", () -> c.notebook().add(s.director(REAL), "x".repeat(120)));
        s.run("note too long", () -> c.notebook().add(s.director(REAL), "y".repeat(2_001)));
        s.run("note by an agent", () -> c.notebook().add(s.agent(CHRIS, REAL), "mine"));
        s.run("tick", () -> c.notebook().check(s.director(REAL), "N-2", true));
        s.run("tick twice", () -> c.notebook().check(s.director(REAL), "N-2", true));
        s.run("untick", () -> c.notebook().check(s.director(REAL), "N-2", false));
        s.run("remove", () -> c.notebook().remove(s.director(REAL), "N-2"));
        s.run("remove twice", () -> c.notebook().remove(s.director(REAL), "N-2"));
        s.run("note after a removal", () -> c.notebook().add(s.director(REAL), "Ids are never reused"));
        s.run("close a note with a commit", () -> c.notebook().close(s.director(REAL), "N-4", "ABCDEF1"));
        s.run("close it again with another commit", () -> c.notebook().close(s.director(REAL), "N-4", "abcdef2"));
        s.run("close with a bad commit", () -> c.notebook().close(s.director(REAL), "N-4", "nothex"));
        s.run("Floor42 closes a note without a ticket", () -> c.notebook().close(s.system(REAL), "N-1", null));
        s.run("terminal", () -> c.terminals().open(s.director(REAL), "C:/work/app"));
        s.run("terminal twice", () -> c.terminals().open(s.director(REAL), "C:/work/app"));
        s.run("terminal elsewhere", () -> c.terminals().open(s.director(REAL), "C:/work/site"));
        s.run("terminal in a simulation", () -> c.terminals().open(s.director(SIMULATED), "C:/work/x"));
        s.run("terminal closed", () -> c.terminals().close(s.director(REAL), "TERM-1", "closed", null));
        s.run("terminal closed twice", () -> c.terminals().close(s.director(REAL), "TERM-1", "closed", null));
        s.run("terminal closes oddly", () -> c.terminals().close(s.system(REAL), "TERM-2", "vanished", 1));
        s.run("terminal interrupted", () -> c.terminals().close(s.system(REAL), "TERM-2", "interrupted", 130));
        s.run("terminal reopened", () -> c.terminals().open(s.director(REAL), "C:/work/app"));
        s.run("terminal closed by an agent", () -> c.terminals().close(s.agent(CHRIS, REAL), "TERM-3", "closed", null));
    }

    void planning() {
        s.run("real mission without repository", () -> c.missions().create(s.director(REAL), "App", "Ship it"));
        s.run(
                "mission",
                () -> c.missions().create(s.director(REAL), " App ", " Ship the app ", APP, Workflow.BUILT_IN));
        s.run("mission by an agent", () -> c.missions().create(s.agent(GALEN, REAL), "x", "y", APP, Workflow.BUILT_IN));
        s.run(
                "planning ticket",
                () -> c.tickets()
                        .create(s.agent(GALEN, REAL), M1, " Plan the work ", null, null, List.of(), List.of("A plan")));
        s.run(
                "build ticket",
                () -> c.tickets()
                        .create(
                                s.director(REAL),
                                M1,
                                "Build",
                                "Build it",
                                Priority.HIGH,
                                List.of(new TicketId("T-1")),
                                List.of("Builds")));
        s.run(
                "ticket on an unknown dependency",
                () -> c.tickets()
                        .create(s.director(REAL), M1, "x", "", Priority.LOW, List.of(new TicketId("T-99")), List.of()));
        s.run(
                "ticket without title",
                () -> c.tickets().create(s.director(REAL), M1, " ", "", Priority.LOW, List.of(), List.of()));
        s.run(
                "read rule",
                () -> c.missions()
                        .addPermission(
                                s.director(REAL),
                                M1,
                                new PermissionRule(null, ActionCategory.READ, null, Decision.ALLOW)));
        s.run(
                "rule by an agent",
                () -> c.missions()
                        .addPermission(
                                s.agent(CHRIS, REAL),
                                M1,
                                new PermissionRule(CHRIS, ActionCategory.EXECUTE, null, Decision.ALLOW)));
        s.run(
                "rule for an unknown agent",
                () -> c.missions()
                        .addPermission(
                                s.director(REAL),
                                M1,
                                new PermissionRule(new AgentId("nobody"), ActionCategory.READ, null, Decision.ALLOW)));
        s.run(
                "mvn for chris",
                () -> c.missions()
                        .addPermission(
                                s.director(REAL),
                                M1,
                                new PermissionRule(CHRIS, ActionCategory.EXECUTE, "mvn", Decision.ALLOW)));
        s.run(
                "write asks",
                () -> c.missions()
                        .setToolPermission(s.director(REAL), M1, "Write", ActionCategory.WRITE, Decision.ASK));
        s.run(
                "write asks again",
                () -> c.missions()
                        .setToolPermission(s.director(REAL), M1, "Write", ActionCategory.WRITE, Decision.ASK));
        s.run(
                "write allowed",
                () -> c.missions()
                        .setToolPermission(s.director(REAL), M1, "Write", ActionCategory.WRITE, Decision.ALLOW));
        s.run(
                "bash asks",
                () -> c.missions()
                        .setToolPermission(s.director(REAL), M1, "Bash", ActionCategory.EXECUTE, Decision.ASK));
        s.run(
                "web asks every time",
                () -> c.missions()
                        .setToolPermission(
                                s.director(REAL), M1, "WebFetch", ActionCategory.NETWORK, Decision.ASK_ALWAYS));
        s.run(
                "grep denied",
                () -> c.missions().setToolPermission(s.director(REAL), M1, "Grep", ActionCategory.READ, Decision.DENY));
        s.run(
                "remove an unknown rule",
                () -> c.missions()
                        .removePermission(
                                s.director(REAL),
                                M1,
                                PermissionRule.forTool("Glob", ActionCategory.READ, Decision.DENY)));
        s.run(
                "glob denied",
                () -> c.missions().setToolPermission(s.director(REAL), M1, "Glob", ActionCategory.READ, Decision.DENY));
        s.run(
                "glob rule removed",
                () -> c.missions()
                        .removePermission(
                                s.director(REAL),
                                M1,
                                PermissionRule.forTool("Glob", ActionCategory.READ, Decision.DENY)));
        s.run("assign the planner", () -> c.tickets().assign(s.director(REAL), new TicketId("T-1"), GALEN));
        s.run("assign an unknown ticket", () -> c.tickets().assign(s.director(REAL), new TicketId("T-99"), GALEN));
        s.run("start before approval", () -> c.tickets().start(s.agent(GALEN, REAL), new TicketId("T-1")));
        s.run("submit the plan", () -> c.missions().submitPlan(s.agent(GALEN, REAL), M1));
        s.run("submit it twice", () -> c.missions().submitPlan(s.agent(GALEN, REAL), M1));
        s.run("approve without a budget", () -> c.missions().approvePlan(s.director(REAL), M1));
        s.run("reject the plan", () -> c.missions().rejectPlan(s.director(REAL), M1, "Add a test ticket"));
        s.run("reject without a plan", () -> c.missions().rejectPlan(s.director(REAL), M1, "again"));
        s.run(
                "test ticket",
                () -> c.tickets().create(s.agent(GALEN, REAL), M1, "Tests", "", Priority.LOW, List.of(), List.of()));
        s.run("submit again", () -> c.missions().submitPlan(s.agent(GALEN, REAL), M1));
        s.run("budget of zero", () -> c.verification().setBudget(s.director(REAL), M1, 0.0));
        s.run("budget", () -> c.verification().setBudget(s.director(REAL), M1, 5.0));
        s.run("approve", () -> c.missions().approvePlan(s.director(REAL), M1));
        s.run("validation", () -> c.missions().enableTicketValidation(s.director(REAL), M1));
        s.run("validation twice", () -> c.missions().enableTicketValidation(s.director(REAL), M1));
        s.run("start the planning", () -> c.tickets().start(s.agent(GALEN, REAL), new TicketId("T-1")));
        s.run(
                "planner's session",
                () -> c.sessions()
                        .start(s.system(REAL), "s-plan", new TicketId("T-1"), GALEN, "claude-sonnet-5-5", "2.1.0"));
        s.run(
                "planner's session ends",
                () -> c.sessions().end(s.system(REAL), "s-plan", false, "completed", 3, 1000L, 200L, 0.10));
        s.run("start by someone else", () -> c.tickets().start(s.agent(CHRIS, REAL), new TicketId("T-3")));
        s.run(
                "propose by someone else",
                () -> c.tickets()
                        .propose(
                                s.agent(CHRIS, REAL),
                                new TicketId("T-1"),
                                CHRIS,
                                List.of(new ProposedTicket("x", null, null, null))));
        s.run(
                "propose with a later dependency",
                () -> c.tickets()
                        .propose(
                                s.agent(GALEN, REAL),
                                new TicketId("T-1"),
                                CHRIS,
                                List.of(
                                        new ProposedTicket("x", null, null, List.of(1)),
                                        new ProposedTicket("y", null, null, null))));
        s.run(
                "propose nothing",
                () -> c.tickets().propose(s.agent(GALEN, REAL), new TicketId("T-1"), CHRIS, List.of()));
        s.run(
                "propose",
                () -> c.tickets()
                        .propose(
                                s.agent(GALEN, REAL),
                                new TicketId("T-1"),
                                CHRIS,
                                List.of(
                                        new ProposedTicket(" Login ", " The login page ", List.of("Logs in"), null),
                                        new ProposedTicket("Logout", null, null, List.of(0)),
                                        new ProposedTicket("Profile", "", List.of(), List.of(0, 1)))));
        s.run(
                "approve two",
                () -> c.tickets().approve(s.director(REAL), M1, List.of(new TicketId("T-4"), new TicketId("T-5"))));
        s.run("approve one twice", () -> c.tickets().approve(s.director(REAL), M1, List.of(new TicketId("T-5"))));
        s.run(
                "approve with one refused",
                () -> c.tickets().approve(s.director(REAL), M1, List.of(new TicketId("T-6"), new TicketId("T-4"))));
        s.run("approve none", () -> c.tickets().approve(s.director(REAL), M1, List.of()));
        s.run(
                "edit a proposal",
                () -> c.tickets()
                        .edit(
                                s.director(REAL),
                                new TicketId("T-6"),
                                " Profile page ",
                                " Show the profile ",
                                List.of(" Shows it ", " ")));
        s.run("edit by an agent", () -> c.tickets().edit(s.agent(GALEN, REAL), new TicketId("T-6"), "x", null, null));
        s.run("cancel a proposal", () -> c.tickets().cancel(s.director(REAL), new TicketId("T-6"), "Later"));
        s.run("cancel it twice", () -> c.tickets().cancel(s.director(REAL), new TicketId("T-6"), "Later"));
        s.run("approve the tests", () -> c.tickets().approve(s.director(REAL), M1, List.of(new TicketId("T-3"))));
        s.run("assign the build", () -> c.tickets().assign(s.director(REAL), new TicketId("T-2"), KEN));
        s.run("assign the tests", () -> c.tickets().assign(s.director(REAL), new TicketId("T-3"), KEN));
    }

    void work() {
        s.run(
                "workspace",
                () -> c.workshop()
                        .prepareWorkspace(s.system(REAL), M1, CHRIS, "C:/ws/chris", "floor42/chris", "base000"));
        s.run(
                "workspace for ken",
                () -> c.workshop().prepareWorkspace(s.system(REAL), M1, KEN, "C:/ws/ken", "floor42/ken", "base000"));
        s.run("workspace twice", () -> c.workshop().prepareWorkspace(s.system(REAL), M1, CHRIS, "C:/ws/x", "b", "c"));
        s.run(
                "workspace by the director",
                () -> c.workshop().prepareWorkspace(s.director(REAL), M1, DARIUS, "C:/ws/d", "b", "c"));
        s.run(
                "director edits a file",
                () -> c.workshop()
                        .recordDirectorEdit(s.director(REAL), M1, CHRIS, "src\\App.java", "a fix", "--- a\n+++ b"));
        s.run(
                "director edits without a diff",
                () -> c.workshop().recordDirectorEdit(s.director(REAL), M1, CHRIS, "src/App.java", null, " "));
        s.run("start the login", () -> c.tickets().start(s.agent(CHRIS, REAL), new TicketId("T-4")));
        s.run("start the logout early", () -> c.tickets().start(s.agent(CHRIS, REAL), new TicketId("T-5")));
        s.run(
                "session by an agent",
                () -> c.sessions().start(s.agent(CHRIS, REAL), "s1", new TicketId("T-4"), CHRIS, "m", null));
        s.run(
                "session on someone else's ticket",
                () -> c.sessions().start(s.system(REAL), "s1", new TicketId("T-4"), KEN, "m", null));
        s.run(
                "session",
                () -> c.sessions().start(s.system(REAL), "s1", new TicketId("T-4"), CHRIS, "claude-opus-5-5", "2.1.0"));
        s.run("session twice", () -> c.sessions().start(s.system(REAL), "s1", new TicketId("T-4"), CHRIS, "m", null));
        s.run("remark", () -> c.sessions().recordRemark(s.system(REAL), "s1", "  Reading   the\ncode  "));
        s.run("blank remark", () -> c.sessions().recordRemark(s.system(REAL), "s1", "   "));
        s.run("long remark", () -> c.sessions().recordRemark(s.system(REAL), "s1", "z".repeat(400)));
        s.run("runtime line", () -> c.sessions().recordRuntimeLine(s.system(REAL), "s1", "thinking", "Let me look"));
        s.run(
                "long runtime line",
                () -> c.sessions().recordRuntimeLine(s.system(REAL), "s1", "tool_result", "w".repeat(2_500)));
        s.run("runtime line without kind", () -> c.sessions().recordRuntimeLine(s.system(REAL), "s1", "", "x"));
        s.run(
                "edit while an agent works",
                () -> c.workshop().recordDirectorEdit(s.director(REAL), M1, CHRIS, "a.txt", null, "d"));
        s.check("workshop while an agent works", () -> c.workshop().checkAccess(s.director(REAL), M1, CHRIS));
        s.run(
                "read allowed",
                () -> c.actions()
                        .request(
                                s.caused(Actor.agent(CHRIS), REAL, "toolu_1"),
                                new ActionId("a1"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.READ,
                                "Read",
                                "pom.xml"));
        s.run("read executed", () -> c.actions().recordOutcome(s.system(REAL), new ActionId("a1"), true, "<project/>"));
        s.run(
                "read executed twice",
                () -> c.actions().recordOutcome(s.system(REAL), new ActionId("a1"), true, "again"));
        s.run("self confirmation", () -> c.actions().confirm(s.agent(CHRIS, REAL), new ActionId("a1"), "I checked"));
        s.run("confirmation", () -> c.actions().confirm(s.agent(DARIUS, REAL), new ActionId("a1"), "Same file"));
        s.run(
                "mvn allowed",
                () -> c.actions()
                        .request(
                                s.system(REAL),
                                new ActionId("a2"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "mvn test"));
        s.run("progress", () -> c.actions().recordProgress(s.system(REAL), new ActionId("a2"), 10));
        s.run("progress backwards", () -> c.actions().recordProgress(s.system(REAL), new ActionId("a2"), 5));
        s.run("progress by an agent", () -> c.actions().recordProgress(s.agent(CHRIS, REAL), new ActionId("a2"), 20));
        s.run(
                "mvn failed",
                () -> c.actions().recordOutcome(s.system(REAL), new ActionId("a2"), false, "BUILD FAILURE"));
        s.run("progress after the end", () -> c.actions().recordProgress(s.system(REAL), new ActionId("a2"), 30));
        s.run("confirm a failure", () -> c.actions().confirm(s.agent(DARIUS, REAL), new ActionId("a2"), "x"));
        s.run(
                "grep denied by the table",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("a3"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.READ,
                                "Grep",
                                "TODO"));
        s.run(
                "write on the director's file",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("a4"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.WRITE,
                                "Write",
                                "src\\App.java"));
        s.run(
                "write elsewhere",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("a5"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.WRITE,
                                "Write",
                                "src/Other.java"));
        s.run(
                "Floor42 tool",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("a6"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.FLOOR42,
                                "mcp__floor42__ask",
                                "question"));
        s.run(
                "outside the workspace",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("a7"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.READ,
                                "Read",
                                "C:/secret",
                                "C:/secret is outside C:/ws/chris"));
        s.run(
                "not on that ticket",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("a8"),
                                M1,
                                new TicketId("T-2"),
                                CHRIS,
                                ActionCategory.READ,
                                "Read",
                                "x"));
        s.run(
                "for someone else",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("a9"),
                                M1,
                                null,
                                KEN,
                                ActionCategory.READ,
                                "Read",
                                "x"));
        s.run(
                "action twice",
                () -> c.actions()
                        .request(
                                s.system(REAL), new ActionId("a1"), M1, null, CHRIS, ActionCategory.READ, "Read", "x"));
        s.run(
                "action on another mission's ticket",
                () -> c.actions()
                        .request(
                                s.system(REAL),
                                new ActionId("a9"),
                                M1,
                                new TicketId("T-99"),
                                CHRIS,
                                ActionCategory.READ,
                                "Read",
                                "x"));
    }

    void approvals() {
        s.run(
                "bash asks the director",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("b1"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "rm -rf build"));
        s.run(
                "same command waits",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("b2"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "rm -rf build"));
        s.run(
                "another command asks",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("b3"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "git push"));
        s.run(
                "web asks every time",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("b4"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.NETWORK,
                                "WebFetch",
                                "https://example.org"));
        s.run(
                "web asks every time again",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("b5"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.NETWORK,
                                "WebFetch",
                                "https://example.org"));
        s.ask("status while waiting", () -> c.queries().statusOf(CHRIS));
        s.run(
                "answer off the options",
                () -> c.desk().answer(s.director(REAL), s.questionFor(M1, new ActionId("b1")), "maybe"));
        s.run(
                "answer by an agent",
                () -> c.desk().answer(s.agent(CHRIS, REAL), s.questionFor(M1, new ActionId("b1")), "ALLOW"));
        s.run("allow", () -> c.desk().answer(s.director(REAL), s.questionFor(M1, new ActionId("b1")), " allow "));
        s.run("allow twice", () -> c.desk().answer(s.director(REAL), s.questionFor(M1, new ActionId("b1")), "ALLOW"));
        s.run("deny the push", () -> c.desk().answer(s.director(REAL), s.questionFor(M1, new ActionId("b3")), "DENY"));
        s.run(
                "allow one web call",
                () -> c.desk().answer(s.director(REAL), s.questionFor(M1, new ActionId("b4")), "ALLOW"));
        s.run(
                "allow the second web call",
                () -> c.desk().answer(s.director(REAL), s.questionFor(M1, new ActionId("b5")), "allow"));
        s.run(
                "deny the write on the director's file",
                () -> c.desk().answer(s.director(REAL), s.questionFor(M1, new ActionId("a4")), "deny"));
        s.run(
                "remembered allow",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("b6"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "rm -rf build"));
        s.run(
                "remembered deny",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("b7"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "git push"));
        s.run(
                "forget a call-only answer",
                () -> c.desk().forgetApproval(s.director(REAL), s.questionFor(M1, new ActionId("b4"))));
        s.run("forget", () -> c.desk().forgetApproval(s.director(REAL), s.questionFor(M1, new ActionId("b1"))));
        s.run("forget twice", () -> c.desk().forgetApproval(s.director(REAL), s.questionFor(M1, new ActionId("b1"))));
        s.run(
                "asks again",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("b8"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "rm -rf build"));
        s.run(
                "withdraw by another agent",
                () -> c.desk().withdraw(s.agent(KEN, REAL), s.questionFor(M1, new ActionId("b8")), "mine"));
        s.run("withdraw", () -> c.desk().withdraw(s.director(REAL), s.questionFor(M1, new ActionId("b8")), "not now"));
        s.run(
                "withdraw twice",
                () -> c.desk().withdraw(s.director(REAL), s.questionFor(M1, new ActionId("b8")), "not now"));
        s.run(
                "refused upstream",
                () -> c.actions().recordOutcome(s.system(REAL), new ActionId("b8"), false, "refused by Claude Code"));
        s.run(
                "denied call's result",
                () -> c.actions().recordOutcome(s.system(REAL), new ActionId("a3"), false, "nothing ran"));
        s.run(
                "unseen call refused",
                () -> c.actions()
                        .recordUnseenCall(
                                s.system(REAL),
                                new ActionId("u1"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "curl x",
                                false,
                                "not offered"));
        s.run(
                "unseen call by an agent",
                () -> c.actions()
                        .recordUnseenCall(
                                s.agent(CHRIS, REAL),
                                new ActionId("u2"),
                                M1,
                                null,
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "x",
                                true,
                                "ok"));
        s.run(
                "unseen call ran",
                () -> c.actions()
                        .recordUnseenCall(
                                s.system(REAL),
                                new ActionId("u2"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "curl y",
                                true,
                                "200"));
        s.ask("status of a suspended mission's agent", () -> c.queries().statusOf(CHRIS));
        s.run(
                "unauthorized while suspended",
                () -> c.actions()
                        .recordUnseenCall(
                                s.system(REAL),
                                new ActionId("u3"),
                                M1,
                                null,
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "curl z",
                                true,
                                "200"));
        s.run("resume a suspended mission", () -> c.missions().resume(s.director(REAL), M1));
        s.run("resume it twice", () -> c.missions().resume(s.director(REAL), M1));
        s.run("suspend by hand", () -> c.missions().suspend(s.director(REAL), M1, "lunch"));
        s.run(
                "request while suspended",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("b9"),
                                M1,
                                new TicketId("T-4"),
                                CHRIS,
                                ActionCategory.READ,
                                "Read",
                                "x"));
        s.run("resume", () -> c.missions().resume(s.director(REAL), M1));
        s.run(
                "open question on the ticket",
                () -> c.desk()
                        .ask(
                                s.agent(CHRIS, REAL),
                                M1,
                                "Which locale?",
                                "fr or en",
                                List.of("fr", "en"),
                                "fr",
                                null,
                                true,
                                new TicketId("T-4")));
        s.run(
                "question by the director",
                () -> c.desk().ask(s.director(REAL), M1, "x", null, null, null, null, false, null));
        s.run(
                "question on an unknown ticket",
                () -> c.desk().ask(s.system(REAL), M1, "x", null, null, null, null, false, new TicketId("T-99")));
        s.run(
                "Floor42's question with a phrase",
                () -> c.desk()
                        .ask(
                                s.system(REAL),
                                M1,
                                "Disk almost full",
                                "2% left",
                                null,
                                null,
                                Criticality.LOW,
                                false,
                                null,
                                Phrase.of("disk", "left", "2%")));
        s.run(
                "submit while blocked",
                () -> c.tickets().submitForReview(s.agent(CHRIS, REAL), new TicketId("T-4"), "done"));
        s.run("answer the locale", () -> c.desk().answer(s.director(REAL), s.questionAbout(M1, "Which locale?"), "EN"));
        s.run(
                "answer free text",
                () -> c.desk().answer(s.director(REAL), s.questionAbout(M1, "Disk almost full"), " cleaned up "));
    }

    void review() {
        s.run(
                "usage",
                () -> c.verification()
                        .observePlanUsage(s.system(REAL), "s1", "five_hour", 0.42, 1_800_000_000L, "allowed"));
        s.run(
                "usage later",
                () -> c.verification()
                        .observePlanUsage(s.system(REAL), "s1", "seven_day", 0.61, 1_800_500_000L, "allowed_warning"));
        s.run(
                "usage of an unknown session",
                () -> c.verification().observePlanUsage(s.system(REAL), "nope", "five_hour", 0.1, 1L, "allowed"));
        s.run(
                "negative usage",
                () -> c.verification().observePlanUsage(s.system(REAL), "s1", "five_hour", -0.1, 1L, "allowed"));
        s.run("dismiss while at work", () -> c.staff().dismiss(s.director(REAL), CHRIS, "budget"));
        s.run(
                "session ends",
                () -> c.sessions().end(s.system(REAL), "s1", false, "completed", 12, 50_000L, 4_000L, 1.25));
        s.run("session ends twice", () -> c.sessions().end(s.system(REAL), "s1", true, "x", null, null, null, null));
        s.run("remark after the end", () -> c.sessions().recordRemark(s.system(REAL), "s1", "late"));
        s.run(
                "second session",
                () -> c.sessions().start(s.system(REAL), "s2", new TicketId("T-4"), CHRIS, "claude-opus-5-5", "2.1.0"));
        s.run("continuation while running", () -> c.sessions().requestContinuation(s.director(REAL), "s2"));
        s.run(
                "second session fails",
                () -> c.sessions().end(s.system(REAL), "s2", true, "error_max_turns", 40, null, null, 0.75));
        s.run("continue an older session", () -> c.sessions().requestContinuation(s.director(REAL), "s1"));
        s.run("continuation by an agent", () -> c.sessions().requestContinuation(s.agent(CHRIS, REAL), "s2"));
        s.run("continuation", () -> c.sessions().requestContinuation(s.director(REAL), "s2"));
        s.ask("continuation requested", () -> c.sessions().continuationRequested(M1, new TicketId("T-4"), CHRIS));
        s.run(
                "third session",
                () -> c.sessions().start(s.system(REAL), "s3", new TicketId("T-4"), CHRIS, "claude-opus-5-5", "2.1.0"));
        s.run("continued from itself", () -> c.sessions().recordContinuation(s.system(REAL), "s3", "s3"));
        s.run("continued from another agent's", () -> c.sessions().recordContinuation(s.system(REAL), "s3", "s-plan"));
        s.run("continued", () -> c.sessions().recordContinuation(s.system(REAL), "s3", "s2"));
        s.run(
                "third session ends",
                () -> c.sessions().end(s.system(REAL), "s3", false, "completed", 5, 2_000L, 300L, null));
        s.run(
                "merge by the director",
                () -> c.workshop()
                        .recordMerge(s.director(REAL), new TicketId("T-5"), CHRIS, List.of("floor42/ken"), true, null));
        s.run(
                "merge of nothing",
                () -> c.workshop().recordMerge(s.system(REAL), new TicketId("T-5"), CHRIS, List.of(), true, null));
        s.run(
                "merge",
                () -> c.workshop()
                        .recordMerge(
                                s.system(REAL),
                                new TicketId("T-5"),
                                CHRIS,
                                List.of("floor42/ken"),
                                false,
                                "conflict in App.java"));
        s.run(
                "submit by someone else",
                () -> c.tickets().submitForReview(s.agent(KEN, REAL), new TicketId("T-4"), "done"));
        s.run(
                "submit without deliverable",
                () -> c.tickets().submitForReview(s.agent(CHRIS, REAL), new TicketId("T-4"), " "));
        s.run(
                "submit",
                () -> c.tickets().submitForReview(s.agent(CHRIS, REAL), new TicketId("T-4"), "Login page with tests"));
        s.run(
                "reviewer's session",
                () -> c.sessions()
                        .start(s.system(REAL), "s-rev", new TicketId("T-4"), DARIUS, "claude-opus-5-5", "2.1.0"));
        s.ask("reviewing", () -> c.queries().statusOf(DARIUS));
        s.run(
                "consult by the producer",
                () -> c.tickets()
                        .requestConsultation(
                                s.agent(CHRIS, REAL),
                                new TicketId("T-4"),
                                ConsultationKind.CONSULT,
                                "security",
                                "Safe?"));
        s.run(
                "consult without a role",
                () -> c.tickets()
                        .requestConsultation(
                                s.agent(DARIUS, REAL), new TicketId("T-4"), ConsultationKind.CONSULT, " ", "Safe?"));
        s.run(
                "consult",
                () -> c.tickets()
                        .requestConsultation(
                                s.agent(DARIUS, REAL),
                                new TicketId("T-4"),
                                ConsultationKind.CONSULT,
                                " security ",
                                " Is the token check enough? "));
        s.run(
                "consult twice",
                () -> c.tickets()
                        .requestConsultation(
                                s.agent(DARIUS, REAL), new TicketId("T-4"), ConsultationKind.RESEARCH, null, "again"));
        s.run(
                "answer by the producer",
                () -> c.tickets().answerConsultation(s.agent(CHRIS, REAL), new TicketId("T-4"), "yes"));
        s.run(
                "answer by the director",
                () -> c.tickets().answerConsultation(s.director(REAL), new TicketId("T-4"), "yes"));
        s.run(
                "answer the consultation",
                () -> c.tickets()
                        .answerConsultation(s.agent(GALEN, REAL), new TicketId("T-4"), "Check the audience too"));
        s.run(
                "answer it twice",
                () -> c.tickets().answerConsultation(s.agent(GALEN, REAL), new TicketId("T-4"), "again"));
        s.run(
                "self review",
                () -> c.tickets().review(s.agent(CHRIS, REAL), new TicketId("T-4"), ReviewVerdict.VALIDATED, "fine"));
        s.run(
                "review by the director",
                () -> c.tickets().review(s.director(REAL), new TicketId("T-4"), ReviewVerdict.VALIDATED, "fine"));
        s.run(
                "reject",
                () -> c.tickets()
                        .review(
                                s.agent(DARIUS, REAL),
                                new TicketId("T-4"),
                                ReviewVerdict.REJECTED,
                                "No audience check"));
        s.run(
                "reviewer's session ends",
                () -> c.sessions().end(s.system(REAL), "s-rev", false, "completed", 4, 900L, 100L, 0.30));
        s.run(
                "submit again",
                () -> c.tickets()
                        .submitForReview(s.agent(CHRIS, REAL), new TicketId("T-4"), "With the audience check"));
        s.run(
                "research",
                () -> c.tickets()
                        .requestConsultation(
                                s.agent(DARIUS, REAL),
                                new TicketId("T-4"),
                                ConsultationKind.RESEARCH,
                                "ignored",
                                "RFC 7519 audience?"));
        s.run(
                "Floor42 answers the research",
                () -> c.tickets()
                        .answerConsultation(
                                s.system(REAL), new TicketId("T-4"), "Research is not allowed on this mission"));
        s.run(
                "unverifiable",
                () -> c.tickets()
                        .review(
                                s.agent(DARIUS, REAL),
                                new TicketId("T-4"),
                                ReviewVerdict.UNVERIFIABLE,
                                "Cannot run the browser"));
        s.ask(
                "escalation options",
                () -> c.desk()
                        .find(s.openQuestion(M1, QuestionKind.REVIEW_ESCALATION))
                        .map(Question::options));
        s.run(
                "request another review",
                () -> c.desk()
                        .answer(
                                s.director(REAL),
                                s.openQuestion(M1, QuestionKind.REVIEW_ESCALATION),
                                "Request another review"));
        s.run(
                "unverifiable again",
                () -> c.tickets()
                        .review(
                                s.agent(DARIUS, REAL),
                                new TicketId("T-4"),
                                ReviewVerdict.UNVERIFIABLE,
                                "Still cannot"));
        s.run(
                "send back",
                () -> c.desk()
                        .answer(
                                s.director(REAL),
                                s.openQuestion(M1, QuestionKind.REVIEW_ESCALATION),
                                "send back to work"));
        s.run(
                "submit a third time",
                () -> c.tickets().submitForReview(s.agent(CHRIS, REAL), new TicketId("T-4"), "Third try"));
        s.run(
                "unverifiable a third time",
                () -> c.tickets()
                        .review(s.agent(DARIUS, REAL), new TicketId("T-4"), ReviewVerdict.UNVERIFIABLE, "No browser"));
        s.run(
                "accept as is",
                () -> c.desk()
                        .answer(s.director(REAL), s.openQuestion(M1, QuestionKind.REVIEW_ESCALATION), "Accept as is"));
        s.run("start the logout", () -> c.tickets().start(s.agent(CHRIS, REAL), new TicketId("T-5")));
        s.run(
                "submit the logout",
                () -> c.tickets().submitForReview(s.agent(CHRIS, REAL), new TicketId("T-5"), "Logout"));
        s.run(
                "validate with reservations",
                () -> c.tickets()
                        .review(
                                s.agent(DARIUS, REAL),
                                new TicketId("T-5"),
                                ReviewVerdict.VALIDATED_WITH_RESERVATIONS,
                                "Fine, no test for expiry"));
        s.run("start the build", () -> c.tickets().start(s.agent(KEN, REAL), new TicketId("T-2")));
        s.run(
                "ken's session",
                () -> c.sessions()
                        .start(s.system(REAL), "s-ken", new TicketId("T-2"), KEN, "claude-haiku-5-5", "2.1.0"));
        s.ask("handed back on dismissal", () -> c.queries().handedBackOnDismissal(KEN));
        s.run("dismiss ken at work", () -> c.staff().dismiss(s.director(REAL), KEN, "cost"));
        s.run(
                "ken's session ends",
                () -> c.sessions().end(s.system(REAL), "s-ken", false, "completed", 2, 100L, 10L, 0.05));
        s.run("release before the dismissal", () -> c.tickets().release(s.system(REAL), new TicketId("T-2")));
        s.run("dismiss without a reason", () -> c.staff().dismiss(s.director(REAL), KEN, " "));
        s.run("dismiss ken", () -> c.staff().dismiss(s.director(REAL), KEN, " cost "));
        s.run("dismiss ken twice", () -> c.staff().dismiss(s.director(REAL), KEN, "cost"));
        s.run("assign to a dismissed agent", () -> c.tickets().assign(s.director(REAL), new TicketId("T-3"), KEN));
        s.run(
                "session for a dismissed agent",
                () -> c.sessions().start(s.system(REAL), "s-k2", new TicketId("T-2"), KEN, "m", null));
        s.run("suspend ivo", () -> c.staff().suspendAssignments(s.director(REAL), IVO, "busy"));
        s.run("assign to a suspended agent", () -> c.tickets().assign(s.director(REAL), new TicketId("T-2"), IVO));
        s.run("assign the build to chris", () -> c.tickets().assign(s.director(REAL), new TicketId("T-2"), CHRIS));
    }

    void workshop() {
        s.run(
                "checks without a repository command",
                () -> c.verification().configureChecks(s.director(REAL), M1, " ", true));
        s.run("checks", () -> c.verification().configureChecks(s.director(REAL), M1, " mvn -q verify ", true));
        s.check("checks can run", () -> c.verification().checkChecksCanRun(s.director(REAL), M1, CHRIS));
        s.check("checks on no workspace", () -> c.verification().checkChecksCanRun(s.director(REAL), M1, DARIUS));
        s.run(
                "checks ran",
                () -> c.verification()
                        .recordChecks(
                                s.system(REAL),
                                M1,
                                CHRIS,
                                "mvn -q verify",
                                1,
                                false,
                                12_345L,
                                "FAIL",
                                "abc1234",
                                "manual"));
        s.run(
                "checks timed out",
                () -> c.verification()
                        .recordChecks(
                                s.system(REAL), M1, CHRIS, "mvn -q verify", null, true, 600_000L, "", null, "auto"));
        s.run(
                "checks by the director",
                () -> c.verification()
                        .recordChecks(s.director(REAL), M1, CHRIS, "x", 0, false, 1L, "", null, "manual"));
        s.run(
                "patch accepted",
                () -> c.workshop()
                        .acceptPatch(
                                s.director(REAL),
                                M1,
                                CHRIS,
                                "abc1234",
                                "Login page",
                                List.of("src/Login.java", "src/App.java")));
        s.run(
                "empty patch",
                () -> c.workshop().acceptPatch(s.director(REAL), M1, CHRIS, "abc1235", "Nothing", List.of()));
        s.run(
                "patch rejected",
                () -> c.workshop()
                        .rejectPatch(
                                s.director(REAL),
                                M1,
                                KEN,
                                "Wrong approach",
                                "archive/ken-1.patch",
                                List.of("build.gradle")));
        s.run(
                "patch on no workspace",
                () -> c.workshop().acceptPatch(s.director(REAL), M1, DARIUS, "x", "y", List.of("z")));
        s.ask("commit for the login", () -> c.queries().commitFor(new TicketId("T-4")));
        s.ask("commit for the build", () -> c.queries().commitFor(new TicketId("T-2")));
        s.run(
                "directive on a done ticket",
                () -> c.workshop().giveDirective(s.director(REAL), new TicketId("T-5"), "Add the expiry test"));
        s.run(
                "directive on a cancelled ticket",
                () -> c.workshop().giveDirective(s.director(REAL), new TicketId("T-6"), "x"));
        s.run(
                "directive on a ticket to do",
                () -> c.workshop().giveDirective(s.director(REAL), new TicketId("T-2"), "Use Gradle"));
        s.run("blank directive", () -> c.workshop().giveDirective(s.director(REAL), new TicketId("T-2"), " "));
        s.run(
                "director's files",
                () -> c.workshop()
                        .recordDirectorFiles(
                                s.director(REAL), M1, "f1e2d3c", List.of("docs/spec.md", "docs/logo.png")));
        s.run(
                "director's files again",
                () -> c.workshop()
                        .recordDirectorFiles(s.director(REAL), M1, "f1e2d3d", List.of("docs/spec.md", "docs/more.md")));
        s.run(
                "director's files without files",
                () -> c.workshop().recordDirectorFiles(s.director(REAL), M1, "f1e2d3e", List.of()));
        s.run(
                "project merged",
                () -> c.workshop().recordProjectMerged(s.director(REAL), M1, CHRIS, "f1e2d3d", true, null));
        s.run(
                "project merged with conflicts",
                () -> c.workshop().recordProjectMerged(s.director(REAL), M1, KEN, "f1e2d3d", false, "conflict"));
        s.run(
                "project merged on no workspace",
                () -> c.workshop().recordProjectMerged(s.director(REAL), M1, DARIUS, "f1e2d3d", true, null));
        s.check("pull request allowed", () -> c.deliveries().checkPullRequest(s.director(REAL), M1));
        s.check("pull request by Floor42", () -> c.deliveries().checkPullRequest(s.system(REAL), M1));
        s.run(
                "pull request",
                () -> c.deliveries()
                        .recordPullRequest(
                                s.director(REAL),
                                M1,
                                CHRIS,
                                "https://github.com/o/r/pull/7",
                                7,
                                "floor42/chris",
                                "main",
                                "origin",
                                false));
        s.run(
                "pull request twice",
                () -> c.deliveries()
                        .recordPullRequest(
                                s.director(REAL),
                                M1,
                                CHRIS,
                                "https://github.com/o/r/pull/7",
                                7,
                                "floor42/chris",
                                "main",
                                "origin",
                                true));
        s.run(
                "pull request into itself",
                () -> c.deliveries()
                        .recordPullRequest(s.director(REAL), M1, null, "u", 8, "main", "main", "origin", false));
        s.run(
                "pull request found",
                () -> c.deliveries()
                        .recordPullRequest(
                                s.director(REAL),
                                M1,
                                null,
                                "https://github.com/o/r/pull/9",
                                9,
                                "director/work",
                                "main",
                                "origin",
                                true));
        s.run(
                "pull request checks",
                () -> c.deliveries()
                        .recordPullRequestChecks(
                                s.director(REAL), M1, 7, List.of("build|pass|SUCCESS", "lint|fail|FAILURE")));
        s.run(
                "pull request checks again",
                () -> c.deliveries().recordPullRequestChecks(s.director(REAL), M1, 7, List.of("build|pass|SUCCESS")));
        s.run(
                "checks of an unknown pull request",
                () -> c.deliveries().recordPullRequestChecks(s.director(REAL), M1, 99, List.of()));
        s.run(
                "malformed check line",
                () -> c.deliveries().recordPullRequestChecks(s.director(REAL), M1, 7, List.of("build")));
        s.run(
                "assets",
                () -> c.deliveries()
                        .selectMissionAssets(
                                s.director(REAL),
                                M1,
                                List.of(
                                        "floor42:pr-review@claude-plugins-official/agent/code-reviewer",
                                        "claude:docs@market/skill/write",
                                        "claude:docs@market/skill/write")));
        s.run(
                "assets with a bad item",
                () -> c.deliveries().selectMissionAssets(s.director(REAL), M1, List.of("anything")));
        s.run("assets by an agent", () -> c.deliveries().selectMissionAssets(s.agent(CHRIS, REAL), M1, List.of()));
    }

    void budgetAndUsage() {
        s.run("staffing review", () -> c.staff().requestStaffingReview(s.director(REAL), M1, GALEN));
        s.run("staffing review twice", () -> c.staff().requestStaffingReview(s.director(REAL), M1, GALEN));
        s.run(
                "advice by someone else",
                () -> c.staff().adviseStaffing(s.agent(CHRIS, REAL), M1, "Hire", true, List.of(), null, null));
        s.run(
                "advice with too wide parallelism",
                () -> c.staff().adviseStaffing(s.agent(GALEN, REAL), M1, "Hire", true, List.of(), 9, null));
        s.run(
                "advice",
                () -> c.staff()
                        .adviseStaffing(
                                s.agent(GALEN, REAL),
                                M1,
                                "Two more developers",
                                true,
                                List.of(
                                        new StaffingReview.Hire("Lou", "Backend developer"),
                                        new StaffingReview.Hire("Max", "UI developer")),
                                3,
                                "Estimated by Floor42: 2 days sooner"));
        s.run(
                "advice twice",
                () -> c.staff().adviseStaffing(s.agent(GALEN, REAL), M1, "again", false, List.of(), null, null));
        s.ask("staffing ticket", () -> c.staff().isStaffingTicket(s.lastTicket(M1)));
        s.run(
                "hire them",
                () -> c.desk().answer(s.director(REAL), s.openQuestion(M1, QuestionKind.STAFFING), "hire them"));
        s.run("second staffing review", () -> c.staff().requestStaffingReview(s.director(REAL), M1, GALEN));
        s.run(
                "Floor42 says why there is no advice",
                () -> c.staff().adviseStaffing(s.system(REAL), M1, "No measure yet", false, null, null, null));
        s.run("third staffing review", () -> c.staff().requestStaffingReview(s.director(REAL), M1, GALEN));
        s.run(
                "advice against",
                () -> c.staff()
                        .adviseStaffing(
                                s.agent(GALEN, REAL),
                                M1,
                                "Not worth it",
                                false,
                                List.of(new StaffingReview.Hire("Lou", "Backend developer")),
                                null,
                                null));
        s.run("not now", () -> c.desk().answer(s.director(REAL), s.openQuestion(M1, QuestionKind.STAFFING), "Not now"));
        s.ask(
                "plan limit risk",
                () -> c.verification().planLimitRisk("claude-opus-5-5", 0.4, Instant.parse("2026-10-20T10:00:00Z")));
        s.run(
                "plan limit",
                () -> c.verification()
                        .reportPlanLimit(
                                s.system(REAL),
                                M1,
                                "claude-opus-5-5",
                                new PlanUsage.Risk("five_hour", 0.92, 0.04, Instant.parse("2026-10-20T13:00:00Z"))));
        s.run(
                "plan limit twice",
                () -> c.verification()
                        .reportPlanLimit(
                                s.system(REAL),
                                M1,
                                "claude-opus-5-5",
                                new PlanUsage.Risk("five_hour", 0.95, null, Instant.parse("2026-10-20T13:00:00Z"))));
        s.run(
                "plan limit by the director",
                () -> c.verification()
                        .reportPlanLimit(
                                s.director(REAL), M1, "m", new PlanUsage.Risk("five_hour", 0.9, null, Instant.EPOCH)));
        s.run(
                "run anyway",
                () -> c.desk().answer(s.director(REAL), s.openQuestion(M1, QuestionKind.PLAN_LIMIT), "Run anyway"));
        s.run(
                "plan limit, small sessions",
                () -> c.verification()
                        .reportPlanLimit(
                                s.system(REAL),
                                M1,
                                "claude-opus-5-5",
                                new PlanUsage.Risk("seven_day", 0.97, 0.001, Instant.parse("2026-10-25T00:00:00Z"))));
        s.run(
                "wait for the reset",
                () -> c.desk()
                        .answer(s.director(REAL), s.openQuestion(M1, QuestionKind.PLAN_LIMIT), "Wait for the reset"));
        s.run(
                "plan limit, unknown increase",
                () -> c.verification()
                        .reportPlanLimit(
                                s.system(REAL),
                                M1,
                                "claude-opus-5-5",
                                new PlanUsage.Risk("five_hour", 0.99, null, Instant.parse("2026-10-20T13:00:00Z"))));
        s.run(
                "stop here",
                () -> c.desk().answer(s.director(REAL), s.openQuestion(M1, QuestionKind.PLAN_LIMIT), "Stop here"));
        s.run("budget not reached", () -> c.verification().reportBudgetReached(s.system(REAL), M1));
        s.run("smaller budget", () -> c.verification().setBudget(s.director(REAL), M1, 1.5));
        s.ask("budget left", () -> c.verification().budgetLeftUsd(M1));
        s.run("budget reached", () -> c.verification().reportBudgetReached(s.system(REAL), M1));
        s.run("budget reached twice", () -> c.verification().reportBudgetReached(s.system(REAL), M1));
        s.ask(
                "check an answer by its code",
                () -> c.desk()
                        .checkAnswer(
                                s.director(REAL), s.openQuestion(M1, QuestionKind.BUDGET_REACHED), "raise_budget"));
        s.ask(
                "check a wrong answer",
                () -> c.desk().checkAnswer(s.director(REAL), s.openQuestion(M1, QuestionKind.BUDGET_REACHED), "more"));
        s.run(
                "raise the budget",
                () -> c.desk()
                        .answer(
                                s.director(REAL),
                                s.openQuestion(M1, QuestionKind.BUDGET_REACHED),
                                "Raise the budget by 50%"));
        s.run("budget lowered again", () -> c.verification().setBudget(s.director(REAL), M1, 2.0));
        s.run("budget reached again", () -> c.verification().reportBudgetReached(s.system(REAL), M1));
        s.run(
                "stop at the budget",
                () -> c.desk().answer(s.director(REAL), s.openQuestion(M1, QuestionKind.BUDGET_REACHED), "STOP_HERE"));
        s.run("no budget", () -> c.verification().setBudget(s.director(REAL), M1, null));
    }

    void closing() {
        s.run("note into a ticket", () -> c.notebook().turnIntoTicket(s.director(REAL), "N-1", M1));
        s.run("note into a ticket twice", () -> c.notebook().turnIntoTicket(s.director(REAL), "N-1", M1));
        s.run("long note into a ticket", () -> c.notebook().turnIntoTicket(s.director(REAL), "N-3", M1));
        s.run("Floor42 closes a note whose ticket is open", () -> c.notebook().close(s.system(REAL), "N-1", null));
        TicketId noteTicket = c.notebook().notes().getFirst().ticket();
        s.run("assign the note's ticket", () -> c.tickets().assign(s.director(REAL), noteTicket, CHRIS));
        s.run("start it", () -> c.tickets().start(s.agent(CHRIS, REAL), noteTicket));
        s.run("submit it", () -> c.tickets().submitForReview(s.agent(CHRIS, REAL), noteTicket, "Faster board"));
        s.run(
                "validate it",
                () -> c.tickets().review(s.agent(DARIUS, REAL), noteTicket, ReviewVerdict.VALIDATED, "Fast"));
        s.run(
                "Floor42 closes the note",
                () -> c.notebook().close(s.system(REAL), "N-1", c.queries().commitFor(noteTicket)));
        s.check("complete with work open", () -> c.missions().checkComplete(s.director(REAL), M1));
        s.run("complete by an agent", () -> c.missions().complete(s.agent(GALEN, REAL), M1, "done"));
        for (Ticket t : c.tickets().of(M1)) {
            if (!t.status().isTerminal()) {
                s.run("cancel " + t.id().value(), () -> c.tickets().cancel(s.director(REAL), t.id(), "Out of scope"));
            }
        }
        s.check("complete with a blocking question", () -> c.missions().checkComplete(s.director(REAL), M1));
        for (Question q : c.desk().of(M1)) {
            if (q.status() == QuestionStatus.OPEN) {
                s.run("withdraw " + q.id().value(), () -> c.desk().withdraw(s.director(REAL), q.id(), "closing"));
            }
        }
        s.check("complete with actions under way", () -> c.missions().checkComplete(s.director(REAL), M1));
        for (Action a : c.actions().of(M1)) {
            if (a.stage() == ActionStage.REQUESTED || a.stage() == ActionStage.AUTHORIZED) {
                s.run(
                        "outcome of " + a.id().value(),
                        () -> c.actions().recordOutcome(s.system(REAL), a.id(), false, "stopped"));
            }
        }
        s.run("complete without summary", () -> c.missions().complete(s.director(REAL), M1, " "));
        s.run("complete", () -> c.missions().complete(s.director(REAL), M1, "Login shipped"));
        s.run(
                "ticket on a closed mission",
                () -> c.tickets().create(s.director(REAL), M1, "late", "", null, List.of(), List.of()));
        s.run(
                "question on a closed mission",
                () -> c.desk().ask(s.system(REAL), M1, "late", null, null, null, null, false, null));
        s.run("budget on a closed mission", () -> c.verification().setBudget(s.director(REAL), M1, 3.0));
        s.run("cancel a closed mission", () -> c.missions().cancel(s.director(REAL), M1, "x"));
    }

    void retrospective() {
        s.run("retro by the director", () -> c.retros().open(s.director(REAL), M1, GALEN, List.of(GALEN), "facts"));
        s.run(
                "retro with someone who did not work",
                () -> c.retros().open(s.system(REAL), M1, GALEN, List.of(GALEN, ANA), "facts"));
        s.run(
                "retro with a dismissed agent",
                () -> c.retros().open(s.system(REAL), M1, GALEN, List.of(GALEN, KEN), "facts"));
        s.run(
                "retro with the chair outside",
                () -> c.retros().open(s.system(REAL), M1, ANA, List.of(GALEN, CHRIS), "facts"));
        s.run(
                "retro",
                () -> c.retros()
                        .open(s.system(REAL), M1, GALEN, List.of(GALEN, CHRIS, DARIUS), "12 sessions, 2 rejections"));
        s.run("retro twice", () -> c.retros().open(s.system(REAL), M1, GALEN, List.of(GALEN), "facts"));
        TicketId retroTicket = c.retros().find(M1).map(Retro::ticket).orElse(new TicketId("T-none"));
        s.ask("in retro", () -> c.queries().statusOf(CHRIS));
        s.run(
                "retro session",
                () -> c.sessions().start(s.system(REAL), "s-retro", retroTicket, CHRIS, "claude-haiku-5-5", "2.1.0"));
        s.run(
                "retro session ends",
                () -> c.sessions().end(s.system(REAL), "s-retro", false, "completed", 1, 10L, 10L, 0.01));
        s.run("contribution by Floor42", () -> c.retros().contribute(s.system(REAL), M1, List.of(), List.of(), "x"));
        s.run(
                "contribution",
                () -> c.retros().contribute(s.agent(CHRIS, REAL), M1, List.of("Tests first"), null, "Good flow"));
        s.run("contribution twice", () -> c.retros().contribute(s.agent(CHRIS, REAL), M1, null, null, "again"));
        s.run(
                "conclude too early",
                () -> c.retros().conclude(s.agent(GALEN, REAL), M1, List.of(), "x", null, List.of()));
        s.run("skip", () -> c.retros().skip(s.system(REAL), M1, DARIUS, "usage window"));
        s.run("skip by the director", () -> c.retros().skip(s.director(REAL), M1, GALEN, "x"));
        s.run(
                "chair speaks",
                () -> c.retros()
                        .contribute(
                                s.agent(GALEN, REAL),
                                M1,
                                List.of("Plan held"),
                                List.of("Budget surprises"),
                                "Mostly fine"));
        s.run(
                "conclude by someone else",
                () -> c.retros().conclude(s.agent(CHRIS, REAL), M1, List.of(), "x", null, List.of()));
        s.run(
                "conclude with a blank lesson",
                () -> c.retros()
                        .conclude(
                                s.agent(GALEN, REAL),
                                M1,
                                List.of(),
                                "x",
                                null,
                                List.of(new Lesson.Proposal(null, " "))));
        s.run(
                "conclude",
                () -> c.retros()
                        .conclude(
                                s.agent(GALEN, REAL),
                                M1,
                                List.of("Keep tests first"),
                                "We shipped",
                                null,
                                List.of(
                                        new Lesson.Proposal(WorkRole.DEVELOPER, " Write the test first "),
                                        new Lesson.Proposal(null, "Say what was not verified"),
                                        new Lesson.Proposal(WorkRole.REVIEWER, "Run the browser"))));
        s.run("conclude twice", () -> c.retros().conclude(s.agent(GALEN, REAL), M1, List.of(), "x", null, List.of()));
        s.run("accept by an agent", () -> c.retros().acceptLesson(s.agent(CHRIS, REAL), "L-1", null));
        s.run("accept a too long lesson", () -> c.retros().acceptLesson(s.director(REAL), "L-1", "q".repeat(501)));
        s.run("accept", () -> c.retros().acceptLesson(s.director(REAL), "L-1", null));
        s.run("accept edited", () -> c.retros().acceptLesson(s.director(REAL), "L-2", " Say what you did not verify "));
        s.run("accept twice", () -> c.retros().acceptLesson(s.director(REAL), "L-1", null));
        s.run("reject", () -> c.retros().rejectLesson(s.director(REAL), "L-3", " "));
        s.run("retire", () -> c.retros().retireLesson(s.director(REAL), "L-1", "Obvious now"));
        s.run("retire a rejected one", () -> c.retros().retireLesson(s.director(REAL), "L-3", null));
        s.run("unknown lesson", () -> c.retros().rejectLesson(s.director(REAL), "L-9", null));
    }

    void architectureCommittee() {
        s.run(
                "decision mission",
                () -> c.missions()
                        .create(
                                s.director(REAL),
                                "Gateway?",
                                "Put a gateway in front of the APIs?",
                                "C:/repo/api",
                                Workflow.BUILT_IN));
        s.run(
                "decision ticket",
                () -> c.tickets().create(s.director(REAL), M2, "Decide: gateway", "", null, List.of(), List.of()));
        TicketId decision = s.lastTicket(M2);
        s.run("assign the chair", () -> c.tickets().assign(s.director(REAL), decision, GALEN));
        s.run(
                "committee with one member",
                () -> c.committees()
                        .convene(s.director(REAL), M2, decision, "Gateway?", List.of(GALEN), List.of("Architect")));
        s.run(
                "committee with a chair who is not the assignee",
                () -> c.committees()
                        .convene(
                                s.director(REAL), M2, decision, "Gateway?", List.of(CHRIS, DARIUS), List.of("a", "b")));
        s.run(
                "committee with a dismissed member",
                () -> c.committees()
                        .convene(s.director(REAL), M2, decision, "Gateway?", List.of(GALEN, KEN), List.of("a", "b")));
        s.run(
                "committee",
                () -> c.committees()
                        .convene(
                                s.director(REAL),
                                M2,
                                decision,
                                "Gateway?",
                                List.of(GALEN, DARIUS, CHRIS),
                                List.of("Architect", "Security", "Developer")));
        s.run(
                "committee twice",
                () -> c.committees()
                        .convene(
                                s.director(REAL), M2, decision, "Gateway?", List.of(GALEN, DARIUS), List.of("a", "b")));
        s.run("submit", () -> c.missions().submitPlan(s.agent(GALEN, REAL), M2));
        s.run("no cap", () -> c.verification().setBudget(s.director(REAL), M2, null));
        s.run("approve", () -> c.missions().approvePlan(s.director(REAL), M2));
        s.run("start deciding", () -> c.tickets().start(s.agent(GALEN, REAL), decision));
        s.run(
                "member's session",
                () -> c.sessions().start(s.system(REAL), "s-c1", decision, DARIUS, "claude-opus-5-5", "2.1.0"));
        s.run(
                "member's session ends",
                () -> c.sessions().end(s.system(REAL), "s-c1", false, "completed", 2, 10L, 10L, 0.02));
        s.run(
                "chair's session",
                () -> c.sessions().start(s.system(REAL), "s-c2", decision, GALEN, "claude-opus-5-5", "2.1.0"));
        s.run(
                "chair's session ends",
                () -> c.sessions().end(s.system(REAL), "s-c2", false, "completed", 2, 10L, 10L, 0.02));
        s.ask("in committee", () -> c.queries().statusOf(DARIUS));
        s.run(
                "contribution of the wrong kind",
                () -> c.committees()
                        .contribute(
                                s.agent(GALEN, REAL),
                                new CommitteeRef(M2, 1),
                                GALEN,
                                Committee.Kind.REBUTTAL,
                                "x",
                                null,
                                null,
                                null));
        s.run(
                "contribution for someone else",
                () -> c.committees()
                        .contribute(
                                s.agent(CHRIS, REAL),
                                new CommitteeRef(M2, 1),
                                DARIUS,
                                Committee.Kind.ANALYSIS,
                                "x",
                                null,
                                null,
                                null));
        s.run(
                "analysis with questions",
                () -> c.committees()
                        .contribute(
                                s.agent(GALEN, REAL),
                                new CommitteeRef(M2, 1),
                                GALEN,
                                Committee.Kind.ANALYSIS,
                                "Gateway",
                                "Gateway",
                                " NEW ",
                                " ",
                                List.of(" Which SLA? ", " ", "Which budget?")));
        s.run(
                "analysis with an objection",
                () -> c.committees()
                        .contribute(
                                s.agent(DARIUS, REAL),
                                new CommitteeRef(M2, 1),
                                DARIUS,
                                Committee.Kind.ANALYSIS,
                                "mTLS",
                                "Gateway with mTLS",
                                "NEW",
                                "Tokens are not checked per API"));
        s.run(
                "analysis twice",
                () -> c.committees()
                        .contribute(
                                s.agent(DARIUS, REAL),
                                new CommitteeRef(M2, 1),
                                DARIUS,
                                Committee.Kind.ANALYSIS,
                                "again",
                                null,
                                null,
                                null));
        s.run(
                "last analysis",
                () -> c.committees()
                        .contribute(
                                s.agent(CHRIS, REAL),
                                new CommitteeRef(M2, 1),
                                CHRIS,
                                Committee.Kind.ANALYSIS,
                                "Fine",
                                "Gateway",
                                "NEW",
                                null));
        s.run(
                "director without a seat",
                () -> c.committees()
                        .contributeAsDirector(s.director(REAL), new CommitteeRef(M2, 1), "me", null, null, null));
        s.run(
                "rebuttal",
                () -> c.committees()
                        .contribute(
                                s.agent(GALEN, REAL),
                                new CommitteeRef(M2, 1),
                                GALEN,
                                Committee.Kind.REBUTTAL,
                                "Still a gateway",
                                "Gateway",
                                "MAINTAINED",
                                null));
        s.run(
                "rebuttal with the objection",
                () -> c.committees()
                        .contribute(
                                s.agent(DARIUS, REAL),
                                new CommitteeRef(M2, 1),
                                DARIUS,
                                Committee.Kind.REBUTTAL,
                                "Still",
                                "Gateway with mTLS",
                                "MAINTAINED",
                                "Tokens still not checked"));
        s.run(
                "last rebuttal",
                () -> c.committees()
                        .contribute(
                                s.agent(CHRIS, REAL),
                                new CommitteeRef(M2, 1),
                                CHRIS,
                                Committee.Kind.REBUTTAL,
                                "Ok",
                                "Gateway",
                                "MAINTAINED",
                                null));
        s.run(
                "synthesis",
                () -> c.committees()
                        .contribute(
                                s.agent(GALEN, REAL),
                                new CommitteeRef(M2, 1),
                                GALEN,
                                Committee.Kind.SYNTHESIS,
                                "# Record",
                                "Adopt a gateway",
                                null,
                                null));
        s.run(
                "clarification by someone else",
                () -> c.committees().clarify(s.agent(CHRIS, REAL), new CommitteeRef(M2, 1), "note", List.of()));
        s.run(
                "clarification",
                () -> c.committees()
                        .clarify(
                                s.agent(ANA, REAL),
                                new CommitteeRef(M2, 1),
                                "Two questions for you",
                                List.of(
                                        new Committee.Clarification(
                                                " Which SLA? ",
                                                "Uptime",
                                                List.of(" 99.9 ", "99.99", "99.9", " "),
                                                "99.99"),
                                        new Committee.Clarification("Which budget?", null, null, " Small "),
                                        new Committee.Clarification(" ", null, null, null),
                                        new Committee.Clarification("Which region?", null, List.of("EU"), "US"))));
        s.run("answer the SLA", () -> c.desk().answer(s.director(REAL), s.questionAbout(M2, "Which SLA?"), "99.9"));
        QuestionId budget = c.desk().of(M2).stream()
                .filter(q -> q.subject().equals("Which budget?"))
                .findFirst()
                .map(Question::id)
                .orElse(new QuestionId("Q-none"));
        s.run("withdraw the budget question", () -> c.desk().withdraw(s.agent(ANA, REAL), budget, "settled"));
        s.run(
                "answer the region",
                () -> c.desk().answer(s.director(REAL), s.questionAbout(M2, "Which region?"), "EU only"));
        s.run(
                "second rebuttal",
                () -> c.committees()
                        .contribute(
                                s.agent(GALEN, REAL),
                                new CommitteeRef(M2, 1),
                                GALEN,
                                Committee.Kind.REBUTTAL,
                                "With 99.9",
                                "Gateway",
                                "MAINTAINED",
                                null,
                                List.of("Which vendor?")));
        s.run(
                "second rebuttal with the objection",
                () -> c.committees()
                        .contribute(
                                s.agent(DARIUS, REAL),
                                new CommitteeRef(M2, 1),
                                DARIUS,
                                Committee.Kind.REBUTTAL,
                                "Still",
                                "Gateway with mTLS",
                                "MAINTAINED",
                                "Tokens still not checked"));
        s.run(
                "last second rebuttal",
                () -> c.committees()
                        .contribute(
                                s.agent(CHRIS, REAL),
                                new CommitteeRef(M2, 1),
                                CHRIS,
                                Committee.Kind.REBUTTAL,
                                "Ok",
                                "Gateway",
                                "MAINTAINED",
                                null));
        s.run(
                "second synthesis",
                () -> c.committees()
                        .contribute(
                                s.agent(GALEN, REAL),
                                new CommitteeRef(M2, 1),
                                GALEN,
                                Committee.Kind.SYNTHESIS,
                                "# Record 2",
                                null,
                                null,
                                null));
        s.run(
                "clarification without questions",
                () -> c.committees()
                        .clarify(s.agent(ANA, REAL), new CommitteeRef(M2, 1), "I settled the vendor myself", null));
        s.run(
                "send back while waiting? no: send back",
                () -> c.committees().sendBack(s.director(REAL), new CommitteeRef(M2, 1), " Must run on-premises "));
        s.run("send back twice", () -> c.committees().sendBack(s.director(REAL), new CommitteeRef(M2, 1), "x"));
        s.run(
                "third rebuttal",
                () -> c.committees()
                        .contribute(
                                s.agent(GALEN, REAL),
                                new CommitteeRef(M2, 1),
                                GALEN,
                                Committee.Kind.REBUTTAL,
                                "On-prem gateway",
                                "Gateway",
                                "MAINTAINED",
                                null));
        s.run(
                "third rebuttal, objection withdrawn",
                () -> c.committees()
                        .contribute(
                                s.agent(DARIUS, REAL),
                                new CommitteeRef(M2, 1),
                                DARIUS,
                                Committee.Kind.REBUTTAL,
                                "Fine with mTLS",
                                "Gateway with mTLS",
                                "ADOPTED galen",
                                null));
        s.run(
                "last third rebuttal",
                () -> c.committees()
                        .contribute(
                                s.agent(CHRIS, REAL),
                                new CommitteeRef(M2, 1),
                                CHRIS,
                                Committee.Kind.REBUTTAL,
                                "Ok",
                                "Gateway",
                                "MAINTAINED",
                                null));
        s.run(
                "third synthesis",
                () -> c.committees()
                        .contribute(
                                s.agent(GALEN, REAL),
                                new CommitteeRef(M2, 1),
                                GALEN,
                                Committee.Kind.SYNTHESIS,
                                "# Record 3",
                                "On-prem gateway",
                                null,
                                null));
        s.ask(
                "arbitration options",
                () -> c.desk()
                        .find(s.openQuestion(M2, QuestionKind.COMMITTEE_ARBITRATION))
                        .map(Question::options));
        s.run(
                "adopt and implement",
                () -> c.desk()
                        .answer(
                                s.director(REAL),
                                s.openQuestion(M2, QuestionKind.COMMITTEE_ARBITRATION),
                                c.desk()
                                        .find(s.openQuestion(M2, QuestionKind.COMMITTEE_ARBITRATION))
                                        .map(q -> q.options().get(1))
                                        .orElse("x")));
        s.run(
                "directive on the ruled decision",
                () -> c.workshop().giveDirective(s.director(REAL), decision, "Use Kong"));
        s.run(
                "implementation ticket",
                () -> c.tickets()
                        .create(s.director(REAL), M2, "Implement the gateway", "", null, List.of(), List.of()));
        TicketId impl = s.lastTicket(M2);
        s.run("assign the implementation", () -> c.tickets().assign(s.director(REAL), impl, CHRIS));
        s.ask("directive goes to", () -> c.workshop().checkDirective(s.director(REAL), decision));
        s.run(
                "directive through the decision",
                () -> c.workshop().giveDirective(s.director(REAL), decision, "Use Kong"));
        s.run(
                "a question before cancelling",
                () -> c.desk()
                        .ask(
                                s.agent(CHRIS, REAL),
                                M2,
                                "Kong or Envoy?",
                                null,
                                List.of(),
                                null,
                                Criticality.HIGH,
                                true,
                                impl));
        s.run("start the implementation", () -> c.tickets().start(s.agent(CHRIS, REAL), impl));
        s.run(
                "read asks",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, REAL),
                                new ActionId("c1"),
                                M2,
                                impl,
                                CHRIS,
                                ActionCategory.READ,
                                "Read",
                                "kong.yml"));
        s.run("cancel without reason", () -> c.missions().cancel(s.director(REAL), M2, " "));
        s.run("cancel", () -> c.missions().cancel(s.director(REAL), M2, "Bought a product instead"));
        s.run(
                "retro of a cancelled mission",
                () -> c.retros().open(s.system(REAL), M2, GALEN, List.of(GALEN, DARIUS), "Cancelled"));
        s.run("close the retro now by Floor42", () -> c.retros().closeNow(s.system(REAL), M2));
        s.run("close the retro now", () -> c.retros().closeNow(s.director(REAL), M2));
        s.run("Floor42 concludes a closed retro", () -> c.retros().conclude(s.system(REAL), M2, null, null, "x", null));
    }

    void ideaCommittee() {
        s.run(
                "idea mission",
                () -> c.missions()
                        .create(
                                s.director(REAL),
                                "Idea",
                                "A marketplace for tools?",
                                "C:/repo/idea",
                                Workflow.BUILT_IN));
        s.run(
                "idea ticket",
                () -> c.tickets().create(s.director(REAL), M3, "Decide: idea", "", null, List.of(), List.of()));
        TicketId idea = s.lastTicket(M3);
        s.run("assign the moderator", () -> c.tickets().assign(s.director(REAL), idea, GALEN));
        s.run(
                "moderated committee too small",
                () -> c.committees()
                        .convene(
                                s.director(REAL),
                                M3,
                                idea,
                                "Marketplace?",
                                List.of(GALEN, DARIUS),
                                List.of("Moderator", "Lawyer"),
                                new Committee.Format("idea", true, 2, null)));
        s.run(
                "director's seat on the chair",
                () -> c.committees()
                        .convene(
                                s.director(REAL),
                                M3,
                                idea,
                                "Marketplace?",
                                List.of(GALEN, DARIUS, CHRIS),
                                List.of("M", "L", "D"),
                                new Committee.Format("idea", true, 2, GALEN)));
        s.run(
                "committee without format",
                () -> c.committees()
                        .convene(
                                s.director(REAL),
                                M3,
                                idea,
                                "Marketplace?",
                                List.of(GALEN, DARIUS, CHRIS),
                                List.of("M", "L", "D"),
                                null));
        s.run(
                "idea committee",
                () -> c.committees()
                        .convene(
                                s.director(REAL),
                                M3,
                                idea,
                                "Marketplace?",
                                List.of(GALEN, DARIUS, CHRIS),
                                List.of("Moderator", "Lawyer", "Your double"),
                                new Committee.Format("idea", true, 2, CHRIS)));
        s.run("submit", () -> c.missions().submitPlan(s.agent(GALEN, REAL), M3));
        s.run(
                "director speaks before the plan is approved",
                () -> c.committees()
                        .contributeAsDirector(s.director(REAL), new CommitteeRef(M3, 1), "x", null, null, null));
        s.run("budget", () -> c.verification().setBudget(s.director(REAL), M3, 10.0));
        s.run("approve", () -> c.missions().approvePlan(s.director(REAL), M3));
        s.run("start", () -> c.tickets().start(s.agent(GALEN, REAL), idea));
        s.run(
                "the director speaks",
                () -> c.committees()
                        .contributeAsDirector(
                                s.director(REAL),
                                new CommitteeRef(M3, 1),
                                " My analysis ",
                                "Rent, not sell",
                                "NEW",
                                null));
        s.run(
                "the director speaks by an agent",
                () -> c.committees()
                        .contributeAsDirector(s.agent(CHRIS, REAL), new CommitteeRef(M3, 1), "x", null, null, null));
        s.run(
                "the lawyer",
                () -> c.committees()
                        .contribute(
                                s.agent(DARIUS, REAL),
                                new CommitteeRef(M3, 1),
                                DARIUS,
                                Committee.Kind.ANALYSIS,
                                "Liability",
                                "Sell with insurance",
                                "NEW",
                                "Liability for defects"));
        s.run(
                "debate, the double",
                () -> c.committees()
                        .contribute(
                                s.agent(CHRIS, REAL),
                                new CommitteeRef(M3, 1),
                                CHRIS,
                                Committee.Kind.REBUTTAL,
                                "Rent",
                                "Rent",
                                "MAINTAINED",
                                null));
        s.run(
                "the director again",
                () -> c.committees()
                        .contributeAsDirector(
                                s.director(REAL),
                                new CommitteeRef(M3, 1),
                                "Changed my mind",
                                null,
                                "ADOPTED darius",
                                null));
        s.run(
                "the lawyer debates",
                () -> c.committees()
                        .contribute(
                                s.agent(DARIUS, REAL),
                                new CommitteeRef(M3, 1),
                                DARIUS,
                                Committee.Kind.REBUTTAL,
                                "Insurance",
                                "Sell with insurance",
                                "MAINTAINED",
                                "Liability for defects"));
        s.run(
                "second debate round, lawyer",
                () -> c.committees()
                        .contribute(
                                s.agent(DARIUS, REAL),
                                new CommitteeRef(M3, 1),
                                DARIUS,
                                Committee.Kind.REBUTTAL,
                                "Same",
                                "Sell with insurance",
                                "MAINTAINED",
                                "Liability for defects"));
        s.run(
                "second debate round, double",
                () -> c.committees()
                        .contribute(
                                s.agent(CHRIS, REAL),
                                new CommitteeRef(M3, 1),
                                CHRIS,
                                Committee.Kind.REBUTTAL,
                                "Same",
                                "Rent",
                                "MAINTAINED",
                                null));
        s.run(
                "director in the synthesis",
                () -> c.committees()
                        .contributeAsDirector(s.director(REAL), new CommitteeRef(M3, 1), "x", null, null, null));
        s.run(
                "moderator's record",
                () -> c.committees()
                        .contribute(
                                s.agent(GALEN, REAL),
                                new CommitteeRef(M3, 1),
                                GALEN,
                                Committee.Kind.SYNTHESIS,
                                "# Record",
                                "Sell with insurance",
                                null,
                                null));
        s.run(
                "reject",
                () -> c.desk()
                        .answer(s.director(REAL), s.openQuestion(M3, QuestionKind.COMMITTEE_ARBITRATION), "reject"));
        s.run(
                "contribution after the ruling",
                () -> c.committees()
                        .contribute(
                                s.agent(DARIUS, REAL),
                                new CommitteeRef(M3, 1),
                                DARIUS,
                                Committee.Kind.REBUTTAL,
                                "x",
                                null,
                                null,
                                null));
        s.run(
                "send back a ruled committee",
                () -> c.committees().sendBack(s.director(REAL), new CommitteeRef(M3, 1), "x"));
        s.run("directive with no implementation", () -> c.workshop().giveDirective(s.director(REAL), idea, "x"));
        s.run("complete", () -> c.missions().complete(s.system(REAL), M3, "Rejected the idea"));
    }

    void simulated() {
        s.run(
                "simulated mission with a repository",
                () -> c.missions().create(s.director(SIMULATED), "Sim", "Try", "C:/repo", Workflow.BUILT_IN));
        s.run("simulated mission", () -> c.missions().create(s.director(SIMULATED), "Sim", "Try"));
        s.run(
                "real command on a simulated mission",
                () -> c.tickets().create(s.director(REAL), M4, "x", "", null, List.of(), List.of()));
        s.run(
                "simulated ticket",
                () -> c.tickets().create(s.agent(GALEN, SIMULATED), M4, "Try", "", Priority.LOW, List.of(), List.of()));
        s.run(
                "planning ticket by kind",
                () -> c.tickets()
                        .create(
                                s.director(SIMULATED),
                                M4,
                                "Replan",
                                "",
                                null,
                                List.of(),
                                List.of(),
                                TicketKind.PLANNING));
        s.run(
                "ticket without kind",
                () -> c.tickets()
                        .create(
                                s.director(SIMULATED),
                                M4,
                                "Plan: legacy-looking title",
                                "",
                                null,
                                List.of(),
                                List.of(),
                                null));
        s.run("simulated assets", () -> c.deliveries().selectMissionAssets(s.director(SIMULATED), M4, List.of()));
        s.run(
                "simulated pull request",
                () -> c.deliveries()
                        .recordPullRequest(s.director(SIMULATED), M4, null, "u", 1, "a", "b", "origin", false));
        s.run(
                "simulated workspace",
                () -> c.workshop().prepareWorkspace(s.system(SIMULATED), M4, CHRIS, "p", "b", "c"));
        s.run(
                "simulated checks",
                () -> c.verification().configureChecks(s.director(SIMULATED), M4, "mvn verify", false));
        s.run("simulated empty plan", () -> c.missions().submitPlan(s.agent(GALEN, SIMULATED), M4));
        s.run(
                "simulated assign",
                () -> c.tickets()
                        .assign(
                                s.director(SIMULATED),
                                c.tickets().of(M4).getFirst().id(),
                                CHRIS));
        s.run("simulated submit", () -> c.missions().submitPlan(s.agent(GALEN, SIMULATED), M4));
        s.run("simulated approve", () -> c.missions().approvePlan(s.director(SIMULATED), M4));
        s.run(
                "simulated start",
                () -> c.tickets()
                        .start(
                                s.agent(CHRIS, SIMULATED),
                                c.tickets().of(M4).getFirst().id()));
        s.run(
                "simulated session",
                () -> c.sessions()
                        .start(
                                s.system(SIMULATED),
                                "sim-1",
                                c.tickets().of(M4).getFirst().id(),
                                CHRIS,
                                "claude-sonnet-5-5",
                                null));
        s.run("simulated continuation", () -> c.sessions().requestContinuation(s.director(SIMULATED), "sim-1"));
        s.run(
                "simulated session ends",
                () -> c.sessions().end(s.system(SIMULATED), "sim-1", false, "completed", 1, null, null, null));
        s.run(
                "simulated action",
                () -> c.actions()
                        .request(
                                s.agent(CHRIS, SIMULATED),
                                new ActionId("sim-a1"),
                                M4,
                                c.tickets().of(M4).getFirst().id(),
                                CHRIS,
                                ActionCategory.EXECUTE,
                                "Bash",
                                "ls"));
        s.run("simulated suspend", () -> c.missions().suspend(s.director(SIMULATED), M4, "pause"));
        s.ask("paused", () -> c.queries().statusOf(CHRIS));
        s.run("simulated cancel", () -> c.missions().cancel(s.director(SIMULATED), M4, "enough"));
        s.run("simulated cancel twice", () -> c.missions().cancel(s.director(SIMULATED), M4, "enough"));
    }

    void projects() {
        s.run("pin", () -> c.projects().pin(s.director(REAL), APP, true));
        s.run("pin twice", () -> c.projects().pin(s.director(REAL), APP, true));
        s.run("pin an unknown project", () -> c.projects().pin(s.director(REAL), "C:/elsewhere", true));
        s.run("pin by an agent", () -> c.projects().pin(s.agent(CHRIS, REAL), APP, true));
        s.run("forget", () -> c.projects().forget(s.director(REAL), APP));
        s.run("forget twice", () -> c.projects().forget(s.director(REAL), APP));
        s.run("pin after forgetting", () -> c.projects().pin(s.director(REAL), APP, true));
        s.run("unpin", () -> c.projects().pin(s.director(REAL), APP, false));
        s.run("forget the api", () -> c.projects().forget(s.director(REAL), "C:/repo/api"));
        s.run(
                "a new mission on the forgotten project",
                () -> c.missions()
                        .create(s.director(REAL), "Again", "Back to the API", "C:/repo/api", Workflow.BUILT_IN));
        s.ask("forgotten", () -> c.projects().forgotten("C:/repo/api"));
        s.run("dismiss ivo", () -> c.staff().dismiss(s.director(REAL), IVO, "Never worked"));
        s.run("mark a dismissed agent core", () -> c.staff().markCore(s.director(REAL), IVO, true));
    }

    /** The director's meetings (ADR-0047): appended last, so the earlier record keeps its positions. */
    void meetings() {
        MissionId m5 = new MissionId("M-5");
        s.run("meeting on a closed mission", () -> c.meetings().call(s.director(REAL), M1, List.of(CHRIS), null));
        s.run("meeting with a dismissed agent", () -> c.meetings().call(s.director(REAL), m5, List.of(IVO), null));
        s.run("meeting called by an agent", () -> c.meetings().call(s.agent(CHRIS, REAL), m5, List.of(CHRIS), null));
        s.run(
                "call a meeting",
                () -> c.meetings().call(s.director(REAL), m5, List.of(CHRIS, DARIUS), "Where we go next"));
        s.ask("in the meeting", () -> c.queries().statusOf(CHRIS));
        s.run("a second meeting on the mission", () -> c.meetings().call(s.director(REAL), m5, List.of(KEN), null));
        s.run(
                "note for all",
                () -> c.meetings()
                        .note(s.director(REAL), "MT-1", null, "Keep the API stable", MeetingNoteUse.NOTE_ONLY));
        s.run(
                "note for chris",
                () -> c.meetings()
                        .note(s.director(REAL), "MT-1", CHRIS, "Start with the tests", MeetingNoteUse.NOTE_ONLY));
        s.run(
                "note as a directive with no ticket under way",
                () -> c.meetings().note(s.director(REAL), "MT-1", CHRIS, "x", MeetingNoteUse.ALSO_DIRECTIVE));
        s.run(
                "note for someone absent",
                () -> c.meetings().note(s.director(REAL), "MT-1", KEN, "x", MeetingNoteUse.NOTE_ONLY));
        s.ask("notes for darius", () -> c.meetings().notesFor(m5, DARIUS));
        s.run("close the meeting", () -> c.meetings().close(s.director(REAL), "MT-1"));
        s.run("close it twice", () -> c.meetings().close(s.director(REAL), "MT-1"));
        s.ask("after the meeting", () -> c.queries().statusOf(CHRIS));
        voices();
    }

    /** Turns of speech at the meeting (ADR-0049): asked in an order, taken in that order, one not heard. */
    void voices() {
        MissionId m6 = new MissionId("M-6");
        s.run(
                "a mission to talk about",
                () -> c.missions()
                        .create(s.director(REAL), "Release", "A release ready to ship", APP, Workflow.BUILT_IN));
        s.run(
                "its ticket",
                () -> c.tickets()
                        .create(
                                s.director(REAL),
                                m6,
                                "Write the release notes",
                                "",
                                Priority.LOW,
                                List.of(),
                                List.of()));
        s.run("its plan", () -> c.missions().submitPlan(s.agent(GALEN, REAL), m6));
        s.run("its budget", () -> c.verification().setBudget(s.director(REAL), m6, 5.0));
        s.run("approve it", () -> c.missions().approvePlan(s.director(REAL), m6));
        s.run(
                "call a meeting at the conference table",
                () -> c.meetings()
                        .call(
                                s.director(REAL),
                                m6,
                                List.of(CHRIS, DARIUS),
                                "Review the release notes",
                                MeetingPlace.CONFERENCE));
        s.run(
                "ask for their views",
                () -> c.meetings().askForViews(s.director(REAL), "MT-2", List.of(DARIUS, CHRIS), 0.5, "Is it good?"));
        s.ask(
                "the meeting's ticket",
                () -> c.meetings().find("MT-2").map(Meeting::ticket).flatMap(c.tickets()::find));
        s.ask("the mission's tickets", () -> c.tickets().of(m6));
        s.run(
                "a second round while one is under way",
                () -> c.meetings().askForViews(s.director(REAL), "MT-2", List.of(CHRIS), 0.5, null));
        s.run(
                "assign the meeting's ticket",
                () -> c.tickets().assign(s.director(REAL), new TicketId("MT-2.turns"), CHRIS));
        s.run(
                "chris speaks out of turn",
                () -> c.meetings()
                        .recordTurn(s.system(REAL), "MT-2", SpokenTurn.heard(1, CHRIS, null, "Me first", 0.1)));
        s.run(
                "darius speaks",
                () -> c.meetings()
                        .recordTurn(s.system(REAL), "MT-2", SpokenTurn.heard(1, DARIUS, null, "Tighten it", 0.1)));
        s.run(
                "chris is not heard",
                () -> c.meetings()
                        .recordTurn(
                                s.system(REAL),
                                "MT-2",
                                SpokenTurn.notHeard(1, CHRIS, null, "the meeting's cost cap is reached", null)));
        s.ask(
                "spent",
                () -> c.meetings().spentUsdCharged(c.meetings().find("MT-2").orElseThrow()));
        s.run("close it", () -> c.meetings().close(s.director(REAL), "MT-2"));
    }

    /** Workflows (ADR-0050): appended last, so the earlier record keeps its positions. */
    void workflows() {
        Workflow light = new Workflow(
                "Light",
                List.of(
                        new Workflow.Step.Plan(3),
                        new Workflow.Step.Approval(),
                        new Workflow.Step.Produce(),
                        new Workflow.Step.Review(),
                        new Workflow.Step.Accept()));
        Workflow talk = new Workflow(
                "Talk",
                List.of(new Workflow.Step.Approval(), new Workflow.Step.Committee(), new Workflow.Step.Accept()));
        s.run("save a workflow", () -> c.workflows().save(s.director(REAL), light, false, null));
        s.run("save it again without replacing", () -> c.workflows().save(s.director(REAL), light, false, null));
        s.run("an agent saves a workflow", () -> c.workflows().save(s.agent(CHRIS, REAL), talk, false, null));
        s.run(
                "a mission pins its workflow",
                () -> c.missions()
                        .create(s.director(REAL), "Pinned", "Follow the light workflow", "C:/repo/api", light));
        s.run(
                "a simulated mission pins one",
                () -> c.missions().create(s.director(SIMULATED), "Pinned", "Never", null, light));
        MissionId pinned = c.missions().all().stream()
                .filter(m -> m.title().equals("Pinned"))
                .findFirst()
                .orElseThrow()
                .id();
        s.run("replace the workflow", () -> c.workflows().save(s.director(REAL), talk.renamed("Light"), true, null));
        s.ask("the pinned mission follows its copy", () -> c.workflows().of(pinned));
        s.run(
                "an agent who is not its planner proposes",
                () -> c.workflows().propose(s.agent(CHRIS, REAL), pinned, talk));
        s.run(
                "its planning ticket",
                () -> c.tickets()
                        .create(
                                s.director(REAL),
                                pinned,
                                "Plan: Pinned",
                                "Plan it",
                                Priority.NORMAL,
                                List.of(),
                                List.of("c"),
                                TicketKind.PLANNING));
        TicketId planning = c.tickets().of(pinned).getLast().id();
        s.run("the planner takes it", () -> c.tickets().assign(s.director(REAL), planning, GALEN));
        s.run("the planner proposes", () -> c.workflows().propose(s.agent(GALEN, REAL), pinned, talk));
        s.run(
                "the planner proposes again",
                () -> c.workflows().propose(s.agent(GALEN, REAL), pinned, light.renamed("Quick")));
        s.run(
                "adopt the first proposal, edited",
                () -> c.workflows().save(s.director(REAL), talk.renamed("Talk it over"), false, 1));
        s.run("dismiss the second", () -> c.workflows().dismissProposal(s.director(REAL), 2));
        s.run("dismiss it twice", () -> c.workflows().dismissProposal(s.director(REAL), 2));
        s.run("remove a workflow", () -> c.workflows().remove(s.director(REAL), "Light"));
        s.ask("the company's workflows", () -> c.workflows().companyWorkflows());
        s.ask("the proposals", () -> c.workflows().proposals());
    }

    /** Workflow tasks (ADR-0061): appended last, so the earlier record keeps its positions. */
    void tasks() {
        Workflow research = new Workflow(
                "Research",
                List.of(
                        new Workflow.Step.Approval(),
                        new Workflow.Step.Task(
                                "research",
                                "Research",
                                "Research the objective",
                                "The director needs sourced facts",
                                "A report with sources",
                                Workflow.Step.Task.Slot.BEFORE_ACCEPTANCE,
                                WorkRole.RESEARCHER,
                                false),
                        new Workflow.Step.Accept()));
        s.run(
                "a mission pins a workflow of a task",
                () -> c.missions().create(s.director(REAL), "Research", "Look it up", "C:/repo/api", research));
        MissionId researching = c.missions().all().stream()
                .filter(m -> m.title().equals("Research"))
                .findFirst()
                .orElseThrow()
                .id();
        s.run("its plan opens with no ticket", () -> c.missions().submitPlan(s.agent(GALEN, REAL), researching));
        s.run("it cannot be sent back", () -> c.missions().rejectPlan(s.director(REAL), researching, "again"));
        s.run("its budget", () -> c.verification().setBudget(s.director(REAL), researching, 2.0));
        s.run("approve it: the task opens", () -> c.missions().approvePlan(s.director(REAL), researching));
        s.ask("the ticket the task opened", () -> c.workflows().linked(researching));
    }

    /** Graph nodes (ADR-0062): appended last, so the earlier record keeps its positions. */
    void graphNodes() {
        s.run(
                "a mission pins a workflow in graph mode",
                () -> c.missions()
                        .create(s.director(REAL), "Graph", "Draw it", "C:/repo/api", Workflow.BUILT_IN.asGraph()));
        MissionId graphing = c.missions().all().stream()
                .filter(m -> m.title().equals("Graph"))
                .findFirst()
                .orElseThrow()
                .id();
        s.run("its tickets wait for validation", () -> c.missions().enableTicketValidation(s.director(REAL), graphing));
        s.run(
                "the planner proposes a node with its notes and its test",
                () -> c.tickets()
                        .create(
                                s.agent(GALEN, REAL),
                                graphing,
                                "Read the code",
                                "Reads it.\nRisk: low: it only reads\nTier: light",
                                Priority.NORMAL,
                                List.of(),
                                List.of("a summary", "Test: cat SUMMARY.md => it prints the summary")));
        TicketId node = c.tickets().of(graphing).getFirst().id();
        s.run(
                "approve it, the director making it high risk",
                () -> c.tickets().approve(s.director(REAL), graphing, List.of(node), Map.of(node, "high")));
        s.ask("the node", () -> c.workflows().nodeOf(node));
        s.run(
                "token issued to an ended session",
                () -> c.sessions()
                        .tokenIssued(
                                s.system(REAL), "s1", "https://id.test/realms/floor42", java.time.Instant.EPOCH, "j0"));
        TicketId notes = c.tickets().of(new MissionId("M-6")).getFirst().id();
        s.run("the release notes go to chris", () -> c.tickets().assign(s.director(REAL), notes, CHRIS));
        s.run("chris starts them", () -> c.tickets().start(s.agent(CHRIS, REAL), notes));
        s.run(
                "an identity session opens",
                () -> c.sessions().start(s.system(REAL), "s-iam", notes, CHRIS, "claude-opus-5-5", "2.1.0"));
        s.run(
                "token issued",
                () -> c.sessions()
                        .tokenIssued(
                                s.system(REAL),
                                "s-iam",
                                "https://id.test/realms/floor42",
                                java.time.Instant.parse("2026-10-21T01:00:00Z"),
                                "jti-1"));
        MissionId simIam = new MissionId("M-11");
        s.run("a simulated mission to badge in", () -> c.missions().create(s.director(SIMULATED), "Badge", "Token"));
        s.run(
                "its ticket",
                () -> c.tickets()
                        .create(s.director(SIMULATED), simIam, "Badge in", "", Priority.LOW, List.of(), List.of()));
        TicketId badge = c.tickets().of(simIam).getFirst().id();
        s.run("simulated badge assign", () -> c.tickets().assign(s.director(SIMULATED), badge, CHRIS));
        s.run("simulated badge submit", () -> c.missions().submitPlan(s.agent(GALEN, SIMULATED), simIam));
        s.run("simulated badge approve", () -> c.missions().approvePlan(s.director(SIMULATED), simIam));
        s.run("simulated badge start", () -> c.tickets().start(s.agent(CHRIS, SIMULATED), badge));
        s.run(
                "simulated identity session",
                () -> c.sessions().start(s.system(SIMULATED), "sim-iam", badge, CHRIS, "claude-sonnet-5-5", null));
        s.run(
                "simulated token issued",
                () -> c.sessions()
                        .tokenIssued(
                                s.system(SIMULATED),
                                "sim-iam",
                                "urn:floor42:simulated",
                                java.time.Instant.parse("2026-10-21T01:00:00Z"),
                                "sim-jti-1"));
    }
}
