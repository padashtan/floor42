package dev.floor42.server.api.journal;

import dev.floor42.domain.ActionId;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Origin;
import dev.floor42.domain.ProofStep;
import dev.floor42.domain.event.ActionEvent.ActionAuthorized;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.ActionEvent.ActionRequested;
import dev.floor42.domain.event.DeliveryEvent.ProofRan;
import dev.floor42.domain.event.DeliveryEvent.ProofVerdictGiven;
import dev.floor42.domain.event.DeliveryEvent.UncommittedWorkAccepted;
import dev.floor42.domain.event.DeliveryEvent.UnprovenDeliveryAccepted;
import dev.floor42.domain.event.DeskEvent.QuestionAnswered;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.BudgetSet;
import dev.floor42.domain.event.MissionEvent.MissionCompleted;
import dev.floor42.domain.event.MissionEvent.MissionPlanApproved;
import dev.floor42.domain.event.MissionEvent.MissionPlanRejected;
import dev.floor42.domain.event.MissionEvent.MissionPlanSubmitted;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.SessionEvent.AgentSessionStarted;
import dev.floor42.domain.event.SessionEvent.AgentTokenIssued;
import dev.floor42.domain.event.VerificationEvent.ChecksRan;
import dev.floor42.server.api.journal.ProofDossier.Amount;
import dev.floor42.server.api.journal.ProofDossier.ApprovalFact;
import dev.floor42.server.api.journal.ProofDossier.BasisCount;
import dev.floor42.server.api.journal.ProofDossier.BrokerFact;
import dev.floor42.server.api.journal.ProofDossier.CheckFact;
import dev.floor42.server.api.journal.ProofDossier.CostBound;
import dev.floor42.server.api.journal.ProofDossier.CostPart;
import dev.floor42.server.api.journal.ProofDossier.DelegationFact;
import dev.floor42.server.api.journal.ProofDossier.DeliveryFact;
import dev.floor42.server.api.journal.ProofDossier.DiffFact;
import dev.floor42.server.api.journal.ProofDossier.EventRef;
import dev.floor42.server.api.journal.ProofDossier.IdentityFact;
import dev.floor42.server.api.journal.ProofDossier.MaskCount;
import dev.floor42.server.api.journal.ProofDossier.MissionPart;
import dev.floor42.server.api.journal.ProofDossier.Ref;
import dev.floor42.server.api.journal.ProofDossier.RefusalFact;
import dev.floor42.server.api.journal.ProofDossier.SessionCost;
import dev.floor42.server.api.journal.ProofDossier.SignatureFact;
import dev.floor42.server.app.delivery.MissionReports;
import dev.floor42.server.app.delivery.MissionReports.MissionReport;
import dev.floor42.server.iam.AgentTokenVerifier;
import dev.floor42.server.iam.IdentityAudit;
import dev.floor42.server.iam.MissionDelegation;
import dev.floor42.server.security.Jti;
import dev.floor42.server.security.ReplaySanitizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composes the proof dossier of one mission from its events (ADR-0071), one method per section. Pure: no I/O. Event
 * numbers are the position among the mission's events from 1, as in the replay export.
 */
final class ProofDossierComposer {

    /** The first line of a broker result: {@code Resource <name> <METHOD> <path> -> <status> (exchanged token <jti> for <audience>)}. */
    private static final Pattern BROKER = Pattern.compile(
            "^Resource (\\S+) (\\S+) (\\S+) -> (\\d+) \\(exchanged token (\\S+) for ([^\\s,]+)(?:, fga:[^\\s)]+)?\\)$");

    private final Mission m;
    private final List<EventEnvelope> events;
    private final MissionReport report;
    private final ReplaySanitizer clean;
    private final DeliveryFacts deliveries;
    private final Map<String, Integer> seqOf = new HashMap<>();
    private final Map<ActionId, EventEnvelope> asked = new HashMap<>();
    private final Map<String, AgentSessionStarted> started = new HashMap<>();
    private final Set<String> identityRefusals = new HashSet<>();
    private final List<IdentityFact> identities = new ArrayList<>();
    private final List<BrokerFact> broker = new ArrayList<>();
    private final List<RefusalFact> refusals = new ArrayList<>();
    private final List<ApprovalFact> approvals = new ArrayList<>();
    private final Map<String, Integer> authorized = new LinkedHashMap<>();
    private final List<SessionCost> sessionCosts = new ArrayList<>();
    private final List<CheckFact> checks = new ArrayList<>();
    private final List<DeliveryFact> deliverables = new ArrayList<>();
    private Double budget;
    private EventEnvelope budgetEvent;

    ProofDossierComposer(
            Mission m,
            List<EventEnvelope> events,
            MissionReport report,
            ReplaySanitizer clean,
            Map<String, SignatureFact> signatures) {
        this.m = m;
        this.events = events;
        this.report = report;
        this.clean = clean;
        this.deliveries = new DeliveryFacts(deliverables, signatures, this::text, e -> ref(null, e));
        for (int i = 0; i < events.size(); i++) seqOf.put(events.get(i).eventId(), i + 1);
    }

    private Ref ref(String basis, EventEnvelope... es) {
        List<EventRef> refs = new ArrayList<>();
        for (EventEnvelope e : es) refs.add(new EventRef(seqOf.get(e.eventId()), e.eventId(), e.type()));
        return new Ref(ProofDossier.JOURNAL, basis, es[0].origin().name(), refs);
    }

    private String text(EventEnvelope e, String field, String s) {
        return s == null ? null : clean.text(seqOf.get(e.eventId()), field, s);
    }

    /** A token id of the shape the identity provider issues stays readable: it is an identifier, not a secret. */
    private String jti(EventEnvelope e, String s) {
        return Jti.isIssued(s) ? s : text(e, "jti", s);
    }

    private String output(EventEnvelope e, String field, String s) {
        return s == null ? null : clean.output(seqOf.get(e.eventId()), field, s);
    }

    ProofDossier compose(Optional<MissionDelegation> delegation, String version, Instant now) {
        index();
        int real = 0;
        for (EventEnvelope e : events) {
            if (e.origin() == Origin.REAL) real++;
            read(e);
        }
        MissionPart part = missionPart(real);
        return new ProofDossier(
                ProofDossiers.FORMAT,
                "Floor42",
                version,
                now.toString(),
                ProofDossiers.STATEMENT,
                part,
                !identities.isEmpty(),
                ProofDossiers.section(identities),
                delegation(delegation),
                ProofDossiers.section(broker),
                ProofDossiers.section(refusals),
                ProofDossiers.section(approvals),
                basisCounts(),
                costs(),
                ProofDossiers.section(checks),
                ProofDossiers.section(deliverables),
                ProofDossiers.section(diff()),
                report.readAt(),
                maskCounts(),
                limits());
    }

    /** The claims are a limit only while a session of the mission ran before they were recorded (ADR-0074). */
    private List<String> limits() {
        boolean older = identities.isEmpty() || identities.stream().anyMatch(i -> i.subject() == null);
        return older
                ? List.of("identity.claimsNotJournaled", "diff.noLineCounts", "personal.freeText")
                : List.of("diff.noLineCounts", "personal.freeText");
    }

    /** What later events look up: the request of an action, the start of a session, the identity refusals. */
    private void index() {
        for (IdentityAudit.Line l : IdentityAudit.lines(m.id(), events))
            if ("REFUSED".equals(l.decision())) identityRefusals.add(l.eventId());
        for (EventEnvelope e : events) {
            if (e.event() instanceof ActionRequested r) asked.put(r.action(), e);
            if (e.event() instanceof AgentSessionStarted s) started.put(s.session(), s);
        }
    }

    private void read(EventEnvelope e) {
        switch (e.event()) {
            case AgentTokenIssued t -> identity(e, t);
            case ActionExecuted x -> brokerCall(e, x);
            case ActionDenied d -> refusal(e, d);
            case ActionAuthorized a -> authorized.merge(a.basis() == null ? "UNKNOWN" : a.basis(), 1, Integer::sum);
            case BudgetSet b -> {
                budget = b.usd();
                budgetEvent = e;
            }
            case AgentSessionEnded s -> sessionCosts.add(sessionCost(e, s, started.get(s.session())));
            case ChecksRan c -> checkRan(e, c);
            case ProofRan p -> proofRan(e, p);
            default -> {
                approval(e);
                deliveries.delivery(e);
            }
        }
    }

    private void identity(EventEnvelope e, AgentTokenIssued t) {
        identities.add(new IdentityFact(
                t.agent().value(),
                IdentityAudit.who(t.agent()),
                "director",
                AgentTokenVerifier.AUDIENCE,
                t.session(),
                t.ticket() == null ? null : t.ticket().value(),
                text(e, "issuer", t.issuer()),
                t.expiresAt(),
                jti(e, t.tokenId()),
                text(e, "subject", ReplaySanitizer.shortSubject(t.subject())),
                text(e, "authorizedParty", t.authorizedParty()),
                text(e, "actor", t.actor()),
                t.scopes(),
                List.of("client", "onBehalfOf", "tokenAudience"),
                ref(null, e)));
    }

    private void brokerCall(EventEnvelope e, ActionExecuted x) {
        EventEnvelope req = asked.get(x.action());
        if (req == null || !(req.event() instanceof ActionRequested r) || !IdentityAudit.BROKER_TOOL.equals(r.tool()))
            return;
        String first = x.output() == null ? "" : x.output().lines().findFirst().orElse("");
        Matcher b = BROKER.matcher(first);
        if (b.matches())
            broker.add(new BrokerFact(
                    r.agent().value(),
                    text(e, "resource", b.group(1)),
                    b.group(2),
                    text(e, "path", b.group(3)),
                    b.group(4),
                    jti(e, b.group(5)),
                    text(e, "audience", b.group(6)),
                    ref(null, req, e)));
    }

    private void refusal(EventEnvelope e, ActionDenied d) {
        EventEnvelope req = asked.get(d.action());
        if (req != null && req.event() instanceof ActionRequested r)
            refusals.add(new RefusalFact(
                    e.occurredAt().toString(),
                    r.agent().value(),
                    r.ticket() == null ? null : r.ticket().value(),
                    r.tool(),
                    r.category() == null ? null : r.category().name(),
                    output(req, "summary", r.summary()),
                    d.reason().name(),
                    output(e, "detail", d.detail()),
                    identityRefusals.contains(e.eventId()),
                    ref(null, req, e)));
    }

    /** The director's decisions: plan, answer, completion, acceptances, proof verdict. */
    private void approval(EventEnvelope e) {
        switch (e.event()) {
            case MissionPlanSubmitted p -> approvals.add(approval(e, "PLAN_SUBMITTED", null));
            case MissionPlanApproved p -> approvals.add(approval(e, "PLAN_APPROVED", null));
            case MissionPlanRejected p -> approvals.add(approval(e, "PLAN_REJECTED", text(e, "reason", p.reason())));
            case QuestionAnswered q -> approvals.add(approval(e, "QUESTION_ANSWERED", text(e, "answer", q.answer())));
            case MissionCompleted c -> approvals.add(approval(e, "MISSION_COMPLETED", text(e, "summary", c.summary())));
            // The proof token of an unproven acceptance is never exported: only its state.
            case UnprovenDeliveryAccepted u ->
                approvals.add(approval(e, "UNPROVEN_DELIVERY_ACCEPTED", text(e, "state", u.state())));
            case UncommittedWorkAccepted u ->
                approvals.add(
                        approval(e, "UNCOMMITTED_WORK_ACCEPTED", u.worktrees().size() + " worktrees"));
            case ProofVerdictGiven v ->
                approvals.add(approval(
                        e,
                        "PROOF_VERDICT_GIVEN",
                        text(e, "verdict", v.verdict() + (v.note() == null ? "" : ": " + v.note()))));
            default -> {}
        }
    }

    private ApprovalFact approval(EventEnvelope e, String kind, String text) {
        return new ApprovalFact(e.occurredAt().toString(), kind, e.actor().id(), text, ref(null, e));
    }

    private void checkRan(EventEnvelope e, ChecksRan c) {
        checks.add(new CheckFact(
                e.occurredAt().toString(),
                c.workspace().value(),
                output(e, "command", c.command()),
                c.exitCode(),
                c.timedOut(),
                c.durationMs(),
                c.trigger(),
                ProofDossiers.shortCommit(c.head()),
                "CHECK",
                ref(MissionReports.MEASURED, e)));
    }

    private void proofRan(EventEnvelope e, ProofRan p) {
        for (ProofStep s : p.steps())
            checks.add(new CheckFact(
                    e.occurredAt().toString(),
                    p.target().workspace().value(),
                    output(e, "command", s.command()),
                    s.exitCode(),
                    s.timedOut(),
                    s.durationMs(),
                    s.runner(),
                    ProofDossiers.shortCommit(p.target().head()),
                    "PROOF",
                    ref("FLOOR42".equals(s.runner()) ? MissionReports.MEASURED : MissionReports.REPORTED, e)));
    }

    /** Counted last: every text of the dossier has passed through the sanitizer by then. */
    private List<MaskCount> maskCounts() {
        List<MaskCount> masked = new ArrayList<>();
        clean.counts().forEach((k, n) -> masked.add(new MaskCount(k.name(), n)));
        return masked;
    }

    private List<BasisCount> basisCounts() {
        List<BasisCount> counts = new ArrayList<>();
        authorized.forEach((basis, n) -> counts.add(new BasisCount(basis, n)));
        return counts;
    }

    private List<DiffFact> diff() {
        List<DiffFact> diff = new ArrayList<>();
        Ref git = new Ref(ProofDossier.GIT, null, null, List.of());
        for (MissionReports.ReportOutput o : report.outputs())
            diff.add(new DiffFact(
                    o.agent(),
                    o.branch() == null ? null : clean.text(0, "branch", o.branch()),
                    o.readable(),
                    o.accepted(),
                    o.uncommittedCount(),
                    git));
        return diff;
    }

    private DelegationFact delegation(Optional<MissionDelegation> delegation) {
        return delegation
                .map(d -> new DelegationFact(
                        d.resources().stream()
                                .map(r -> clean.text(0, "resource", r))
                                .toList(),
                        d.grantedAt() == null ? null : d.grantedAt().toString(),
                        d.expiresAt() == null ? null : d.expiresAt().toString(),
                        d.revoked(),
                        new Ref(ProofDossier.STATE, null, null, List.of())))
                .orElse(null);
    }

    private MissionPart missionPart(int real) {
        return new MissionPart(
                m.id().value(),
                clean.text(0, "title", m.title()),
                clean.text(0, "objective", m.objective()),
                m.origin().name(),
                m.status().name(),
                report.outcome(),
                events.size(),
                real,
                events.size() - real);
    }

    /** A simulated session called no model: 0 and SIMULATED, whatever the recording it replays once cost. */
    private SessionCost sessionCost(EventEnvelope e, AgentSessionEnded s, AgentSessionStarted st) {
        String agent = st == null ? null : st.agent().value();
        if (e.origin() == Origin.SIMULATED)
            return new SessionCost(agent, s.session(), 0.0, MissionReports.SIMULATED, ref(MissionReports.SIMULATED, e));
        boolean known = s.costUsdEstimated() != null;
        return new SessionCost(
                agent,
                s.session(),
                s.costUsdEstimated(),
                known ? MissionReports.ESTIMATED : null,
                ref(known ? MissionReports.ESTIMATED : null, e));
    }

    private CostPart costs() {
        MissionReports.ReportTotals t = report.totals();
        Amount total = t == null || t.costUsd() == null || t.costUsd().value() == null
                ? null
                : new Amount(t.costUsd().value(), t.costUsd().basis());
        MissionReports.CostBound b = t == null ? null : t.costBound();
        CostBound bound = b == null || b.costUsd() == null || b.costUsd().value() == null
                ? null
                : new CostBound(b.costUsd().value(), b.sessions(), MissionReports.UPPER_BOUND);
        return new CostPart(budget, budgetEvent == null ? null : ref(null, budgetEvent), sessionCosts, total, bound);
    }
}
