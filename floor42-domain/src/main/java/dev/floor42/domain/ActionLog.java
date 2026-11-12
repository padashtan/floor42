package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireSystem;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.ActionEvent;
import dev.floor42.domain.event.ActionEvent.ActionAuthorized;
import dev.floor42.domain.event.ActionEvent.ActionConfirmed;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.ActionEvent.ActionFailed;
import dev.floor42.domain.event.ActionEvent.ActionProgressed;
import dev.floor42.domain.event.ActionEvent.ActionRequested;
import dev.floor42.domain.event.ActionEvent.IncidentRaised;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionSuspended;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tool calls (propose, decide, execute), enforced here and not by prompts, and the incidents of calls that ran without
 * Floor42's authorization. Decisions are recorded as Floor42's, not the agent's.
 */
final class ActionLog {

    private static final String RUNTIME_OUTCOMES = "Only the runtime reports tool outcomes";

    private final CompanyView view;
    private final ApprovalPolicy approvals;
    private final WorkEligibility eligibility;
    private final Map<ActionId, Action> actions = new LinkedHashMap<>();
    private final List<Incident> incidents = new ArrayList<>();

    ActionLog(CompanyView view) {
        this.view = view;
        this.approvals = new ApprovalPolicy(view);
        this.eligibility = new WorkEligibility(view);
    }

    List<Action> actions(MissionId mission) {
        return actions.values().stream()
                .filter(a -> a.mission().equals(mission))
                .toList();
    }

    Collection<Action> actionsView() {
        return Collections.unmodifiableCollection(actions.values());
    }

    Optional<Action> action(ActionId id) {
        return Optional.ofNullable(actions.get(id));
    }

    Action existing(ActionId id) {
        Action a = actions.get(id);
        require(a != null, Refusal.UNKNOWN_ACTION, "No action " + id);
        return a;
    }

    List<Incident> incidents() {
        return List.copyOf(incidents);
    }

    /** The highest I-n number ever raised, read or not: a number is never given twice. */
    private transient int highestIncident;

    private IncidentId nextIncidentId() {
        return new IncidentId("I-" + (Math.max(highestIncident, incidents.size()) + 1));
    }

    /** An incident number held by a journal row this version could not read: no new incident takes it. */
    void reserveIncident(IncidentId id) {
        String v = id.value();
        if (v.matches("I-\\d{1,9}")) highestIncident = Math.max(highestIncident, Integer.parseInt(v.substring(2)));
    }

    int incidentCount() {
        return incidents.size();
    }

    ApprovalPolicy approvals() {
        return approvals;
    }

    /**
     * Floor42 decides at once: allowed or denied by the mission policy, or turned into an approval question. A call that
     * leaves the agent's workspace ({@code confinementViolation}, checked by the runtime) is denied before any rule.
     */
    List<EventEnvelope> request(CommandContext ctx, ActionRequested asked, String confinementViolation) {
        ActionId id = asked.action();
        Mission m = view.missionFor(ctx, asked.mission());
        view.existingAgent(asked.agent());
        require(
                ctx.actor().is(asked.agent()) || ctx.actor().kind() == Actor.Kind.SYSTEM,
                Refusal.FORBIDDEN,
                "An action is requested by its agent or by the runtime on its behalf");
        require(!actions.containsKey(id), Refusal.DUPLICATE_ACTION, "Action " + id + " already recorded");
        requireText(asked.tool(), "tool");
        Ticket t = asked.ticket() == null ? null : view.existingTicket(asked.ticket());
        require(
                t == null || t.mission().equals(asked.mission()),
                Refusal.UNKNOWN_TICKET,
                asked.ticket() + " is not part of " + asked.mission());
        Recorder office = view.record(ctx, asked.mission()).emit(asked).as(Actor.system());
        return decide(office, m, t, actions.get(id), confinementViolation);
    }

    private List<EventEnvelope> decide(Recorder office, Mission m, Ticket t, Action a, String confinementViolation) {
        if (m.status != MissionStatus.IN_PROGRESS) {
            return deny(office, a, DenialReason.MISSION_NOT_ACTIVE, DecisionBasis.missionNotActive(m.status));
        }
        if (t != null && !eligibility.worksOn(a.agent(), t)) {
            return deny(office, a, DenialReason.POLICY, DecisionBasis.notOnTicket(a.agent(), t.id()));
        }
        if (confinementViolation != null) {
            return deny(office, a, DenialReason.OUTSIDE_WORKSPACE, confinementViolation);
        }
        return byPolicy(office, m, a);
    }

    private List<EventEnvelope> byPolicy(Recorder office, Mission m, Action a) {
        PermissionPolicy.Evaluation eval =
                m.policy().evaluate(a.agent(), a.ticket(), a.category(), a.tool(), a.summary());
        if (eval.rule() == null && a.category() == ActionCategory.FLOOR42) {
            return office.authorize(a.id(), DecisionBasis.floor42Tools(), "policy:" + m.id());
        }
        // The director's edits are never overwritten silently: whatever the table says, short of forbidding, ask.
        if (a.category() == ActionCategory.WRITE
                && eval.decision() != Decision.DENY
                && view.editedByDirector(m.id(), a.agent(), a.summary())) {
            return approvals.askForThisCall(office, m, a, ApprovalScope.editedByDirector(a.summary()));
        }
        return switch (eval.decision()) {
            case ALLOW -> office.authorize(a.id(), eval.basis(), "policy:" + m.id());
            case DENY -> deny(office, a, DenialReason.POLICY, eval.basis());
            case ASK -> approvals.askOnce(office, m, a);
            case ASK_ALWAYS -> approvals.askForThisCall(office, m, a, ApprovalScope.askAlways());
        };
    }

    private static List<EventEnvelope> deny(Recorder office, Action a, DenialReason reason, String detail) {
        return office.emit(new ActionDenied(a.id(), reason, detail)).events();
    }

    /** Claude Code's word that an authorized call still runs; nothing once it ended, nor for a time not later. */
    List<EventEnvelope> progress(CommandContext ctx, ActionId id, long seconds) {
        requireSystem(ctx, "Only the runtime reports tool progress");
        Action a = existing(id);
        view.missionFor(ctx, a.mission());
        if (a.stage != ActionStage.AUTHORIZED || a.orphaned) return List.of();
        if (seconds <= 0 || (a.runningSeconds != null && seconds <= a.runningSeconds)) return List.of();
        return view.record(ctx, a.mission())
                .emit(new ActionProgressed(id, seconds))
                .events();
    }

    /**
     * A call Floor42 never authorized did not run under governance: its error is a block, not a failure, and its success
     * is an incident that suspends the mission. The tool result of a denied call only confirms that nothing ran.
     */
    List<EventEnvelope> succeeded(CommandContext ctx, ActionId id, String output) {
        Report report = report(ctx, id);
        Recorder r = report.recorder();
        return switch (report.action().stage) {
            case AUTHORIZED -> r.emit(new ActionExecuted(id, output, true)).events();
            case REQUESTED -> unauthorized(r, report.mission(), report.action(), output);
            case DENIED -> List.of();
            case EXECUTED, FAILED, CONFIRMED -> throw alreadyRecorded(report.action());
        };
    }

    List<EventEnvelope> failed(CommandContext ctx, ActionId id, String output) {
        Report report = report(ctx, id);
        Recorder r = report.recorder();
        return switch (report.action().stage) {
            case AUTHORIZED -> r.emit(new ActionFailed(id, output)).events();
            case REQUESTED ->
                r.emit(new ActionDenied(id, DenialReason.BLOCKED_UPSTREAM, output))
                        .events();
            case DENIED -> List.of();
            case EXECUTED, FAILED, CONFIRMED -> throw alreadyRecorded(report.action());
        };
    }

    private record Report(Action action, Mission mission, Recorder recorder) {}

    private Report report(CommandContext ctx, ActionId id) {
        Action a = existing(id);
        Mission m = view.missionFor(ctx, a.mission());
        requireSystem(ctx, RUNTIME_OUTCOMES);
        return new Report(a, m, view.record(ctx, a.mission()));
    }

    private static DomainException alreadyRecorded(Action a) {
        return new DomainException(
                Refusal.OUTCOME_ALREADY_RECORDED, "Outcome of " + a.id() + " already recorded (" + a.stage + ")");
    }

    private List<EventEnvelope> unauthorized(Recorder r, Mission m, Action a, String output) {
        r.emit(new ActionExecuted(a.id(), output, false))
                .emit(new IncidentRaised(
                        nextIncidentId(),
                        a.mission(),
                        Criticality.CRITICAL,
                        RecordedReason.ranWithout(a.agent(), a.tool(), a.summary()),
                        a.id()));
        if (m.status == MissionStatus.IN_PROGRESS) {
            r.emit(new MissionSuspended(a.mission(), RecordedReason.unauthorizedReason(a.id())));
        }
        return r.events();
    }

    /** A call Claude Code refused itself, never seen by Floor42's hook: recorded as it happened, so nothing is missing. */
    List<EventEnvelope> unseen(CommandContext ctx, ActionRequested call, boolean success, String output) {
        requireSystem(ctx, RUNTIME_OUTCOMES);
        view.missionFor(ctx, call.mission());
        view.existingAgent(call.agent());
        require(
                !actions.containsKey(call.action()),
                Refusal.DUPLICATE_ACTION,
                "Action " + call.action() + " already recorded");
        requireText(call.tool(), "tool");
        List<EventEnvelope> events =
                new ArrayList<>(view.record(ctx, call.mission()).emit(call).events());
        events.addAll(success ? succeeded(ctx, call.action(), output) : failed(ctx, call.action(), output));
        return events;
    }

    /** Independent confirmation of a result (a passing check, a reviewer reproducing it). Never by the same agent. */
    List<EventEnvelope> confirm(CommandContext ctx, ActionId id, String evidence) {
        Action a = existing(id);
        view.missionFor(ctx, a.mission());
        require(!ctx.actor().is(a.agent()), Refusal.SELF_CONFIRMATION, a.agent() + " cannot confirm its own action");
        require(a.stage == ActionStage.EXECUTED, Refusal.INVALID_TRANSITION, id + " has not been executed");
        require(
                a.authorizedByOffice,
                Refusal.UNAUTHORIZED_ACTION,
                id + " ran without authorization and cannot be confirmed");
        requireText(evidence, "evidence");
        return view.record(ctx, a.mission())
                .emit(new ActionConfirmed(id, evidence))
                .events();
    }

    /** The only way this area's state changes. */
    void apply(ActionEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        switch (event) {
            case ActionRequested e -> on(e, at);
            case ActionAuthorized e -> on(e, at);
            case ActionDenied e -> on(e, at);
            case ActionExecuted e -> on(e, at);
            case ActionFailed e -> on(e, at);
            case ActionConfirmed e -> on(e, at);
            case ActionProgressed e -> on(e, at);
            case IncidentRaised e -> on(e, at);
        }
    }

    void on(ActionRequested e, Instant at) {
        actions.put(
                e.action(),
                new Action(e.action(), e.mission(), e.ticket(), e.agent(), e.category(), e.tool(), e.summary(), at));
    }

    void on(ActionAuthorized e, Instant at) {
        Action a = advance(e.action(), ActionStage.AUTHORIZED, at);
        a.decisionBasis = e.basis();
        a.authorizedByOffice = true;
    }

    void on(ActionDenied e, Instant at) {
        Action a = advance(e.action(), ActionStage.DENIED, at);
        a.denialReason = e.reason();
        a.decisionBasis = e.detail();
    }

    void on(ActionExecuted e, Instant at) {
        advance(e.action(), ActionStage.EXECUTED, at).result = e.output();
    }

    void on(ActionFailed e, Instant at) {
        advance(e.action(), ActionStage.FAILED, at).result = e.error();
    }

    void on(ActionConfirmed e, Instant at) {
        advance(e.action(), ActionStage.CONFIRMED, at);
    }

    void on(ActionProgressed e, Instant at) {
        Action a = actions.get(e.action());
        a.runningSeconds = e.seconds();
        a.updatedAt = at;
    }

    void on(IncidentRaised e, Instant at) {
        // Two incidents of one id would merge: the replay stops instead.
        if (incidents.stream().anyMatch(i -> i.id().equals(e.incident())))
            throw new IllegalStateException(e.incident() + " is created twice");
        reserveIncident(e.incident());
        incidents.add(new Incident(e.incident(), e.mission(), e.criticality(), e.description(), e.action(), at));
    }

    /** A session that ended leaves its pending and running calls behind: they will never report. */
    void orphanAfter(AgentSession s) {
        actions.values().stream()
                .filter(a -> a.agent().equals(s.agent()) && a.mission().equals(s.mission()))
                .filter(a -> a.stage == ActionStage.REQUESTED || a.stage == ActionStage.AUTHORIZED)
                .forEach(a -> a.orphaned = true);
    }

    private Action advance(ActionId id, ActionStage next, Instant at) {
        Action a = actions.get(id);
        if (!a.stage.canMoveTo(next)) {
            // Only reachable with a corrupted journal: commands never record an illegal transition.
            throw new IllegalStateException(
                    "Journal holds an illegal transition for " + id + ": " + a.stage + " -> " + next);
        }
        a.stage = next;
        a.updatedAt = at;
        return a;
    }
}
