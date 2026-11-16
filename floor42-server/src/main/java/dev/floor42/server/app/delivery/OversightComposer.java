package dev.floor42.server.app.delivery;

import dev.floor42.domain.ActionId;
import dev.floor42.domain.DenialReason;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.event.ActionEvent.ActionAuthorized;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.ActionEvent.ActionFailed;
import dev.floor42.domain.event.ActionEvent.ActionRequested;
import dev.floor42.domain.event.DeliveryEvent.ProofVerdictGiven;
import dev.floor42.domain.event.DeliveryEvent.UncommittedWorkAccepted;
import dev.floor42.domain.event.DeliveryEvent.UnprovenDeliveryAccepted;
import dev.floor42.domain.event.DeskEvent.ApprovalForgotten;
import dev.floor42.domain.event.DeskEvent.QuestionAnswered;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCancelled;
import dev.floor42.domain.event.MissionEvent.MissionPlanApproved;
import dev.floor42.domain.event.MissionEvent.MissionPlanRejected;
import dev.floor42.domain.event.MissionEvent.MissionResumed;
import dev.floor42.domain.event.MissionEvent.MissionSuspended;
import dev.floor42.domain.event.MissionEvent.PermissionRuleAdded;
import dev.floor42.domain.event.MissionEvent.PermissionRuleRemoved;
import dev.floor42.domain.event.PreferenceEvent.BreakGlassSignIn;
import dev.floor42.domain.event.PreferenceEvent.UnsafeModeChanged;
import dev.floor42.domain.event.RosterEvent.AgentAssignmentsResumed;
import dev.floor42.domain.event.RosterEvent.AgentAssignmentsSuspended;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.SessionEvent.AgentSessionStarted;
import dev.floor42.domain.event.SessionEvent.MissionQuarantined;
import dev.floor42.domain.event.TicketEvent.DirectiveGiven;
import dev.floor42.domain.event.TicketEvent.TicketApproved;
import dev.floor42.domain.event.TicketEvent.TicketCancelled;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.TicketEvent.TicketReopened;
import dev.floor42.domain.event.WorkbenchEvent.DirectorEditedFile;
import dev.floor42.domain.event.WorkbenchEvent.PatchRejected;
import dev.floor42.server.app.delivery.OversightReport.EventRef;
import dev.floor42.server.app.delivery.OversightReport.Line;
import dev.floor42.server.app.delivery.OversightReport.Mask;
import dev.floor42.server.app.delivery.OversightReport.MissionPart;
import dev.floor42.server.app.delivery.OversightReport.Section;
import dev.floor42.server.security.ReplaySanitizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes the oversight report (ADR-0075) from a mission's events and the company-wide events of its span. Pure: no
 * I/O. A fact the events do not hold is a limit code, never a guess.
 */
final class OversightComposer {

    /** Lines per section; the count stays the full one. */
    static final int CAP = 200;

    static final int FORMAT = 1;
    static final String STATEMENT =
            "facts from the journal; every line names its events; what is absent is listed as a limit";
    static final String OBJECTION_CAUSE = "objections-";
    static final String COMPANY = "COMPANY";

    private final Mission m;
    private final List<EventEnvelope> events;
    private final ReplaySanitizer clean;
    private final Map<String, Integer> seqOf = new HashMap<>();
    private final Map<ActionId, EventEnvelope> asked = new HashMap<>();
    private final Map<ActionId, EventEnvelope> authorized = new HashMap<>();
    private final Map<ActionId, EventEnvelope> denied = new HashMap<>();
    private final Map<ActionId, EventEnvelope> done = new HashMap<>();
    private final Map<QuestionId, EventEnvelope> questions = new HashMap<>();
    private final Map<PermissionRule, EventEnvelope> rules = new LinkedHashMap<>();
    private final Map<String, EventEnvelope> sessionStart = new HashMap<>();
    private final List<Line> supervision = new ArrayList<>();
    private final List<Line> actions = new ArrayList<>();
    private final List<Line> overrides = new ArrayList<>();
    private boolean withoutOutcome;

    /** @param events the mission's events then the company-wide ones of its span, in journal order */
    OversightComposer(Mission m, List<EventEnvelope> events, ReplaySanitizer clean) {
        this.m = m;
        this.events = events;
        this.clean = clean;
        int n = 0;
        for (EventEnvelope e : events) if (e.mission() != null) seqOf.put(e.eventId(), ++n);
    }

    static OversightReport compose(
            Mission m, List<EventEnvelope> events, ReplaySanitizer clean, String version, Instant now) {
        return new OversightComposer(m, events, clean).compose(version, now);
    }

    OversightReport compose(String version, Instant now) {
        for (EventEnvelope e : events) index(e);
        for (EventEnvelope e : events) read(e);
        List<Line> capabilities = capabilities();
        return new OversightReport(
                FORMAT,
                "Floor42",
                version,
                now.toString(),
                STATEMENT,
                missionPart(),
                section(supervision),
                section(capabilities),
                section(actions),
                section(overrides),
                masks(),
                limits(capabilities));
    }

    private MissionPart missionPart() {
        List<EventEnvelope> own =
                events.stream().filter(e -> e.mission() != null).toList();
        int real = (int) own.stream().filter(e -> e.origin() == Origin.REAL).count();
        String title = clean.text(0, "title", m.title());
        return new MissionPart(
                m.id().value(), title, m.origin().name(), m.status().name(), own.size(), real, own.size() - real);
    }

    private static Section section(List<Line> all) {
        return new Section(all.size(), all.stream().limit(CAP).toList());
    }

    private List<String> limits(List<Line> capabilities) {
        List<String> out = new ArrayList<>();
        out.add("signIns.notJournaled");
        if (capabilities.isEmpty()) out.add("permissionTable.none");
        if (withoutOutcome) out.add("actions.withoutOutcome");
        out.add("personal.freeText");
        return out;
    }

    private List<Mask> masks() {
        List<Mask> masked = new ArrayList<>();
        clean.counts().forEach((k, n) -> masked.add(new Mask(k.name(), n)));
        return masked;
    }

    /** What later events look up: the request of an action, how it was decided, the questions, the sessions. */
    private void index(EventEnvelope e) {
        switch (e.event()) {
            case ActionRequested r -> asked.put(r.action(), e);
            case ActionAuthorized a -> authorized.put(a.action(), e);
            case ActionDenied d -> denied.put(d.action(), e);
            case ActionExecuted x -> done.put(x.action(), e);
            case ActionFailed f -> done.put(f.action(), e);
            case QuestionAsked q -> questions.put(q.question(), e);
            case AgentSessionStarted s -> sessionStart.put(s.session(), e);
            case PermissionRuleAdded a -> rules.put(a.rule(), e);
            case PermissionRuleRemoved r -> rules.remove(r.rule());
            default -> {}
        }
    }

    private EventRef ref(EventEnvelope e) {
        return new EventRef(seqOf.get(e.eventId()), e.eventId(), e.type());
    }

    private Line line(
            List<Line> into, String kind, String scope, String text, String code, String basis, EventEnvelope... es) {
        List<EventRef> refs = new ArrayList<>();
        for (EventEnvelope e : es) if (e != null) refs.add(ref(e));
        Line l = new Line(
                es[0].occurredAt().toString(),
                kind,
                es[0].actor().id(),
                scope,
                text,
                code,
                basis,
                es[0].origin().name(),
                refs);
        into.add(l);
        return l;
    }

    private String text(EventEnvelope e, String field, String s) {
        return s == null ? null : clean.text(seqOf.getOrDefault(e.eventId(), 0), field, s);
    }

    private boolean inObjectionRound(EventEnvelope e) {
        return e.causationId() != null && e.causationId().startsWith(OBJECTION_CAUSE);
    }

    private void read(EventEnvelope e) {
        switch (e.event()) {
            case ActionRequested r -> action(e);
            case AgentSessionEnded s -> session(e, s);
            default -> {
                plan(e);
                desk(e);
                directive(e);
                work(e);
            }
        }
    }

    private void sup(String kind, String scope, String text, String code, String basis, EventEnvelope... es) {
        line(supervision, kind, scope, text, code, basis, es);
    }

    private void ovr(String kind, String scope, String text, String code, EventEnvelope... es) {
        line(overrides, kind, scope, text, code, null, es);
    }

    /** The director's decisions on the mission and its tickets, and the stops of agents. */
    private void plan(EventEnvelope e) {
        switch (e.event()) {
            case BreakGlassSignIn b -> sup("BREAK_GLASS_SIGN_IN", COMPANY, null, null, null, e);
            case MissionPlanApproved p -> sup("PLAN_APPROVED", null, null, null, null, e);
            case MissionPlanRejected p -> sup("PLAN_REJECTED", null, text(e, "reason", p.reason()), null, null, e);
            case MissionResumed r -> sup("MISSION_RESUMED", null, null, null, null, e);
            case MissionSuspended s -> sup("MISSION_SUSPENDED", null, text(e, "reason", s.reason()), null, null, e);
            case MissionCancelled c -> sup("MISSION_CANCELLED", null, text(e, "reason", c.reason()), null, null, e);
            case TicketApproved t -> sup("TICKET_APPROVED", t.ticket().value(), null, null, null, e);
            case TicketCancelled t ->
                sup("TICKET_CANCELLED", t.ticket().value(), text(e, "reason", t.reason()), null, null, e);
            case AgentAssignmentsSuspended s ->
                sup("AGENT_SUSPENDED", s.agent().value(), text(e, "reason", s.reason()), null, null, e);
            case AgentAssignmentsResumed r -> sup("AGENT_RESUMED", r.agent().value(), null, null, null, e);
            default -> {}
        }
    }

    /** Approvals, quarantines and the proof verdict. */
    private void desk(EventEnvelope e) {
        switch (e.event()) {
            case MissionQuarantined q -> {
                String agent = q.agent() == null ? null : q.agent().value();
                String why = q.reasons() == null ? null : String.join(", ", q.reasons());
                sup("QUARANTINE", agent, why, quarantineCode(q), q.level(), e);
            }
            case ApprovalForgotten f -> sup("APPROVAL_FORGOTTEN", null, text(e, "reason", f.reason()), null, null, e);
            case QuestionAnswered a -> answered(e, a);
            case ProofVerdictGiven v ->
                sup("PROOF_VERDICT_GIVEN", null, text(e, "note", v.note()), v.verdict(), null, e);
            default -> {}
        }
    }

    /** What a human told the agents to do again, or refused them. */
    private void directive(EventEnvelope e) {
        switch (e.event()) {
            case ActionDenied d -> {
                // A refusal by the director is an override; the other refusals are Floor42's policy, listed with the
                // actions.
                EventEnvelope req = asked.get(d.action());
                if (d.reason() == DenialReason.DIRECTOR && req != null)
                    ovr("DIRECTOR_REFUSED", scopeOf(req), summaryOf(req), "DIRECTOR", e, req);
            }
            case DirectiveGiven d -> {
                String kind = inObjectionRound(e) ? "OBJECTION" : "DIRECTIVE_GIVEN";
                ovr(kind, d.ticket().value(), text(e, "directive", d.directive()), round(e), e);
            }
            case TicketCreated t
            when inObjectionRound(e) -> ovr("OBJECTION", t.ticket().value(), text(e, "title", t.title()), round(e), e);
            case TicketReopened t -> ovr("TICKET_REOPENED", t.ticket().value(), text(e, "reason", t.reason()), null, e);
            default -> {}
        }
    }

    /** What a human changed or accepted in the agents' work. */
    private void work(EventEnvelope e) {
        switch (e.event()) {
            case UnsafeModeChanged u ->
                ovr("UNSAFE_MODE_CHANGED", COMPANY, text(e, "reason", u.reason()), u.on() ? "ON" : "OFF", e);
            case PatchRejected p ->
                ovr("PATCH_REJECTED", p.workspace().value(), text(e, "reason", p.reason()), null, e);
            // The contents of the director's edit are never exported: the path and the note only.
            case DirectorEditedFile f -> {
                String note = f.path() + (f.note() == null ? "" : ": " + f.note());
                ovr("DIRECTOR_EDITED_FILE", f.workspace().value(), text(e, "path", note), null, e);
            }
            case UnprovenDeliveryAccepted u ->
                ovr("UNPROVEN_DELIVERY_ACCEPTED", null, text(e, "state", u.state()), null, e);
            case UncommittedWorkAccepted u ->
                ovr("UNCOMMITTED_WORK_ACCEPTED", null, u.worktrees().size() + " worktrees", null, e);
            default -> {}
        }
    }

    private static String quarantineCode(MissionQuarantined q) {
        List<String> steps = new ArrayList<>();
        if (q.revoked()) steps.add("REVOKED");
        if (q.suspended()) steps.add("SUSPENDED");
        if (q.stopped()) steps.add("STOPPED");
        return String.join("+", steps);
    }

    private static String round(EventEnvelope e) {
        return e.causationId() != null && e.causationId().startsWith(OBJECTION_CAUSE)
                ? e.causationId().substring(OBJECTION_CAUSE.length())
                : null;
    }

    /** An answer to an approval request carries the tool and the summary of the action it was about. */
    private void answered(EventEnvelope e, QuestionAnswered a) {
        EventEnvelope q = questions.get(a.question());
        EventEnvelope req = null;
        String subject = null;
        if (q != null && q.event() instanceof QuestionAsked qa) {
            subject = qa.subject();
            if (qa.action() != null) req = asked.get(qa.action());
        }
        String answer = text(e, "answer", a.answer() + (req == null ? "" : ": " + summaryOf(req)));
        if (req != null) sup("APPROVAL_ANSWERED", scopeOf(req), answer, null, null, e, q, req);
        else sup("QUESTION_ANSWERED", text(e, "subject", subject), answer, null, null, e, q);
    }

    private String scopeOf(EventEnvelope req) {
        ActionRequested r = (ActionRequested) req.event();
        return r.category() + (r.tool() == null ? "" : " " + r.tool());
    }

    private String summaryOf(EventEnvelope req) {
        ActionRequested r = (ActionRequested) req.event();
        return r.summary() == null ? null : clean.output(seqOf.get(req.eventId()), "summary", r.summary());
    }

    private void session(EventEnvelope e, AgentSessionEnded s) {
        EventEnvelope start = sessionStart.get(s.session());
        String model = start != null && start.event() instanceof AgentSessionStarted st ? st.model() : null;
        EventEnvelope[] es = new EventEnvelope[] {e, start};
        line(
                actions,
                "SESSION",
                text(e, "model", model),
                s.turns() == null ? null : s.turns() + " turns",
                s.failed() ? "FAILED" : "ENDED",
                null,
                es);
    }

    private void action(EventEnvelope req) {
        ActionRequested r = (ActionRequested) req.event();
        EventEnvelope auth = authorized.get(r.action());
        EventEnvelope den = denied.get(r.action());
        EventEnvelope out = done.get(r.action());
        String code;
        if (den != null) code = "DENIED_" + ((ActionDenied) den.event()).reason();
        else if (out != null) code = out.event() instanceof ActionFailed ? "FAILED" : "EXECUTED";
        else code = auth != null ? "AUTHORIZED" : "REQUESTED";
        if (den == null && out == null) withoutOutcome = true;
        String basis = auth != null && auth.event() instanceof ActionAuthorized a ? a.basis() : null;
        line(actions, "ACTION", scopeOf(req), summaryOf(req), code, basis, req, auth, den, out);
    }

    private List<Line> capabilities() {
        List<Line> out = new ArrayList<>();
        for (Map.Entry<PermissionRule, EventEnvelope> r : rules.entrySet()) {
            PermissionRule rule = r.getKey();
            String scope = rule.category() + (rule.tool() == null ? "" : " " + rule.tool());
            String what = (rule.commandPrefix() == null ? "" : "command " + rule.commandPrefix() + "; ")
                    + (rule.agent() == null ? "any agent" : rule.agent().value())
                    + (rule.ticket() == null ? "" : "; ticket " + rule.ticket().value());
            line(
                    out,
                    "PERMISSION_RULE",
                    scope,
                    text(r.getValue(), "rule", what),
                    rule.decision().name(),
                    null,
                    r.getValue());
        }
        return out;
    }
}
