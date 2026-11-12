package dev.floor42.server.app;

import dev.floor42.domain.ActionId;
import dev.floor42.domain.ActionStage;
import dev.floor42.domain.Actor;
import dev.floor42.server.config.DataFolders;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.AgentRuntime.Verdict;
import dev.floor42.server.runtime.AskOutcome;
import dev.floor42.server.runtime.Confinement;
import dev.floor42.server.runtime.RuntimeEvent;
import dev.floor42.server.runtime.RuntimeEvent.SessionEnded;
import dev.floor42.server.runtime.RuntimeEvent.SessionStarted;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import dev.floor42.server.runtime.ShellGuard;
import dev.floor42.server.runtime.ToolCatalog;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Governs one agent session: every tool call becomes a domain action decided by the mission policy, and every result
 * is recorded by Floor42 (the runtime), never by the agent.
 */
public final class MissionGovernor implements Governor {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(MissionGovernor.class);
    private static final int MAX_OUTPUT = 2_000;
    /** Floor42's data directory, which no agent's shell command may reach (ShellGuard). */
    private static final Path DATA_DIR = ConfigProvider.getConfig()
            .getOptionalValue("floor42.data-dir", Path.class)
            .orElse(null);

    /** The only tools a turn of speech at the director's meeting may use (ADR-0049). */
    public static final java.util.List<String> TURN_TOOLS = java.util.List.of("Read", "Glob", "Grep");

    static final String READS_ONLY =
            "a turn at the director's meeting only reads (Read, Glob, Grep): say what you" + " think, change nothing";

    private final OfficeService office;
    private final SessionSpec spec;
    /** A turn of speech: every tool but {@link #TURN_TOOLS} is refused before the mission's table is asked. */
    private final boolean readsOnly;
    /** The session record id once the runtime reported its start; null before. */
    private volatile String sessionRecord;

    private final DirectorAsks asks;

    public MissionGovernor(OfficeService office, SessionSpec spec) {
        this(office, spec, false);
    }

    /** A turn of speech at the director's meeting (ADR-0049) when {@code readsOnly}. */
    MissionGovernor(OfficeService office, SessionSpec spec, boolean readsOnly) {
        this.office = office;
        this.spec = spec;
        this.readsOnly = readsOnly;
        this.asks = new DirectorAsks(office, spec);
    }

    /** Tool-use ids are unique per session only; prefixing with the mission keeps action ids unique company-wide. */
    @Override
    public ActionId actionId(String toolUseId) {
        return new ActionId(spec.mission().value() + ":" + toolUseId);
    }

    @Override
    public Verdict decide(ToolRequested call) {
        if (!call.tool().endsWith("ask_director")) asks.otherToolCall();
        ActionId id = actionId(call.toolUseId());
        office.execute(c -> c.actions()
                .request(
                        office.context(Actor.agent(spec.agent()), spec.origin(), call.toolUseId()),
                        id,
                        spec.mission(),
                        spec.ticket(),
                        spec.agent(),
                        ToolCatalog.category(call.tool()),
                        call.tool(),
                        ToolCatalog.summary(call.tool(), call.input(), spec.workdir()),
                        violation(call)));
        return verdict(id);
    }

    /** Why Floor42 refuses the call before any rule is read; null when the mission's table decides. */
    private String violation(ToolRequested call) {
        if (readsOnly && !TURN_TOOLS.contains(call.tool())) return READS_ONLY;
        return Confinement.violation(call.tool(), call.input(), spec.workdir())
                .or(() -> ShellGuard.violation(
                        call.tool(),
                        call.input(),
                        spec.workdir(),
                        // Office's former data folder too, once the journal was copied from it.
                        DataFolders.guarded(DATA_DIR),
                        ProcessHandle.current().pid()))
                .orElse(null);
    }

    @Override
    public String violationNow(ToolRequested call) {
        return violation(call);
    }

    @Override
    public Verdict awaitDecision(ToolRequested call, Duration patience) {
        ActionId id = actionId(call.toolUseId());
        CompletableFuture<Void> settled = new CompletableFuture<>();
        try (AutoCloseable ignored = office.subscribe(events -> {
            if (verdict(id) != Verdict.PENDING || missionClosed()) settled.complete(null);
        })) {
            if (verdict(id) == Verdict.PENDING && !missionClosed()) {
                settled.get(patience.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException e) {
            // Handled: nobody answered in time: the call stays pending
            return Verdict.PENDING;
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the call stays pending
            Thread.currentThread().interrupt();
            return Verdict.PENDING;
        } catch (Exception e) {
            throw new IllegalStateException("Waiting for the director failed", e);
        }
        return verdict(id);
    }

    /** A blocking question on the director's desk, asked by the agent; the session waits for it (ADR-0027). */
    @Override
    public AskOutcome askDirector(
            String question,
            java.util.List<String> options,
            String recommendation,
            String toolUseId,
            Duration patience) {
        return asks.ask(question, options, recommendation, toolUseId, patience);
    }

    @Override
    public void unreplayable(String toolUseId, String reason) {
        ActionId id = actionId(toolUseId);
        if (verdict(id) != Verdict.ALLOW) return;
        office.execute(c -> c.actions()
                .recordOutcome(
                        office.context(Actor.system(), spec.origin(), toolUseId),
                        id,
                        false,
                        "Replay limit: " + reason));
    }

    /** Session lifecycle: Floor42 records it from what the runtime observed. */
    @Override
    public void observed(RuntimeEvent event) {
        switch (event) {
            case SessionStarted s -> started(s);
            case RuntimeEvent.ToolProgress progress -> progressed(progress);
            case RuntimeEvent.Unfamiliar line -> {
                String record = openRecord();
                if (record == null) return;
                office.execute(c -> c.sessions()
                        .recordRuntimeLine(
                                office.context(Actor.system(), spec.origin()), record, line.kind(), line.content()));
            }
            case RuntimeEvent.AgentText text -> {
                String record = openRecord();
                if (record == null) return;
                office.execute(c ->
                        c.sessions().recordRemark(office.context(Actor.system(), spec.origin()), record, text.text()));
            }
            case SessionEnded e -> ended(e);
            case RuntimeEvent.PlanUsageReported usage -> planUsage(usage);
            case RuntimeEvent.TokenIssued t -> tokenIssued(t);
            default -> {}
        }
    }

    private void progressed(RuntimeEvent.ToolProgress progress) {
        ActionId id = actionId(progress.toolUseId());
        if (office.query(c -> c.actions().find(id).isEmpty())) return;
        office.execute(c -> c.actions()
                .recordProgress(
                        office.context(Actor.system(), spec.origin(), progress.toolUseId()), id, progress.seconds()));
    }

    private void tokenIssued(RuntimeEvent.TokenIssued t) {
        String record = openRecord();
        if (record == null) return;
        office.execute(c -> c.sessions()
                .tokenIssued(
                        office.context(Actor.system(), spec.origin()), record, t.issuer(), t.expiresAt(), t.jti()));
    }

    /** A call whose caller's identity token failed: journaled as requested and refused upstream, never decided. */
    @Override
    public void refuse(ToolRequested call, String detail) {
        ActionId id = actionId(call.toolUseId());
        if (office.query(c -> c.actions().find(id).isPresent())) return;
        try {
            office.execute(c -> c.actions()
                    .recordUnseenCall(
                            office.context(Actor.system(), spec.origin(), call.toolUseId()),
                            id,
                            spec.mission(),
                            spec.ticket(),
                            spec.agent(),
                            ToolCatalog.category(call.tool()),
                            call.tool(),
                            ToolCatalog.summary(call.tool(), call.input(), spec.workdir()),
                            false,
                            detail));
        } catch (RuntimeException e) {
            // Handled: the mission closed meanwhile; the refusal is still answered to the caller, only not journaled
            LOG.debugf("Refusal of %s not journaled: %s", call.toolUseId(), e.getMessage());
        }
    }

    private void started(SessionStarted s) {
        // Session ids are unique per runtime only (a replayed recording reuses its own): prefix with the mission.
        String record = spec.mission().value() + ":" + s.sessionId();
        office.execute(c -> c.sessions()
                .start(
                        office.context(Actor.system(), spec.origin(), s.sessionId()),
                        record,
                        spec.ticket(),
                        spec.agent(),
                        s.model(),
                        s.runtimeVersion(),
                        spec.origin() == dev.floor42.domain.Origin.REAL ? s.capUsd() : null));
        sessionRecord = record;
        // Started with --resume (ADR-0038): the journal says which session it carries on.
        if (spec.resume() == null) return;
        String from = spec.mission().value() + ":" + spec.resume();
        if (office.query(c -> c.sessions().find(from).isPresent())) {
            office.execute(c -> c.sessions()
                    .recordContinuation(office.context(Actor.system(), spec.origin(), s.sessionId()), record, from));
        }
    }

    /**
     * The session's record while it is open in the journal; null before it started, or once closed (as interrupted by
     * a restart, for instance: the journal keeps that record).
     */
    private String openRecord() {
        String record = sessionRecord;
        if (record == null) return null;
        boolean closed = office.query(
                c -> c.sessions().find(record).map(s -> !s.isOpen()).orElse(true));
        return closed ? null : record;
    }

    private void ended(SessionEnded e) {
        asks.sessionEnded();
        if (sessionRecord == null) {
            UnnamedLaunch.record(office, spec, e.capUsd(), e.launch()); // nothing more to end: the caller reports it
            return;
        }
        String record = openRecord();
        if (record == null) return; // closed already (as interrupted by a restart, for instance)
        office.execute(c -> c.sessions()
                .end(
                        office.context(Actor.system(), spec.origin()),
                        record,
                        e.error(),
                        e.capReached() ? "stopped at its cost cap" : e.error() ? "failed" : "completed",
                        e.turns(),
                        e.inputTokens(),
                        e.outputTokens(),
                        // A replay calls no model: the recording's estimate belongs to the day it was made.
                        spec.origin() == dev.floor42.domain.Origin.REAL ? e.estimatedCostUsd() : null,
                        // No cost reported (killed, stalled, held for a meeting): the budget counts the cap, as a
                        // bound.
                        spec.origin() == dev.floor42.domain.Origin.REAL ? e.capUsd() : null));
    }

    private void planUsage(RuntimeEvent.PlanUsageReported usage) {
        // A recording replays another day's plan: only live sessions tell today's usage.
        if (sessionRecord == null || spec.origin() != dev.floor42.domain.Origin.REAL) return;
        for (RuntimeEvent.UsageWindow w : usage.windows()) {
            if (w.equals(lastReported.get(w.name()))) continue; // the same reading again
            lastReported.put(w.name(), w);
            String record = sessionRecord;
            office.execute(c -> c.verification()
                    .observePlanUsage(
                            office.context(Actor.system(), spec.origin()),
                            record,
                            w.name(),
                            w.utilization(),
                            w.resetsAtEpochSecond(),
                            w.status()));
        }
    }

    private final java.util.Map<String, RuntimeEvent.UsageWindow> lastReported = new java.util.HashMap<>();

    @Override
    public String explain(String toolUseId) {
        return office.query(c -> c.actions()
                .find(actionId(toolUseId))
                .map(a -> a.decisionBasis())
                .orElse(null));
    }

    @Override
    public boolean authorized(String toolUseId) {
        return office.query(c -> c.actions()
                .find(actionId(toolUseId))
                .filter(a -> a.stage() == ActionStage.AUTHORIZED)
                .isPresent());
    }

    /** Re-entrant read: also called from subscribers, while the service lock is held. */
    private Verdict verdict(ActionId id) {
        ActionStage stage = office.query(c -> c.actions().find(id).orElseThrow().stage());
        return switch (stage) {
            case AUTHORIZED -> Verdict.ALLOW;
            case REQUESTED -> Verdict.PENDING;
            default -> Verdict.DENY;
        };
    }

    private boolean missionClosed() {
        return office.query(
                c -> c.missions().find(spec.mission()).orElseThrow().status().isTerminal());
    }

    @Override
    public void completed(ToolCompleted result) {
        ActionId id = actionId(result.toolUseId());
        String output = result.output().length() > MAX_OUTPUT
                ? result.output().substring(0, MAX_OUTPUT) + "…"
                : result.output();
        if (office.query(c -> c.actions().find(id).isPresent())) {
            office.execute(c -> c.actions()
                    .recordOutcome(
                            office.context(Actor.system(), spec.origin(), result.toolUseId()),
                            id,
                            !result.error(),
                            output));
            return;
        }
        unseen(result, id, output);
    }

    /**
     * Claude Code refused the call itself, before the hook (a tool this session does not offer): recorded as seen in
     * the stream, so the session goes on and the journal has no hole.
     */
    private void unseen(ToolCompleted result, ActionId id, String output) {
        ToolRequested call = requested.get(result.toolUseId());
        String tool = call == null ? "unknown" : call.tool();
        office.execute(c -> c.actions()
                .recordUnseenCall(
                        office.context(Actor.system(), spec.origin(), result.toolUseId()),
                        id,
                        spec.mission(),
                        spec.ticket(),
                        spec.agent(),
                        ToolCatalog.category(tool),
                        tool,
                        call == null ? null : ToolCatalog.summary(tool, call.input(), spec.workdir()),
                        !result.error(),
                        output));
    }

    /** Tool calls as the stream announced them, to record one the hook never saw. */
    private final java.util.Map<String, ToolRequested> requested = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void announced(ToolRequested call) {
        requested.put(call.toolUseId(), call);
    }
}
