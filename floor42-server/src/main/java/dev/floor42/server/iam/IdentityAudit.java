package dev.floor42.server.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.ActionId;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.ActionEvent.ActionExecuted;
import dev.floor42.domain.event.ActionEvent.ActionRequested;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.SessionEvent.AgentTokenIssued;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The identity story of a mission read back from the journal: the audit export (one line per token issued, refused or
 * brokered) and the trust chain of one session. Pure functions of the events: no token is ever read here, only the
 * jti, the refusal code and what the journal already holds.
 */
public final class IdentityAudit {

    /** The broker's tool (T13): its ActionExecuted lines are the BROKER_TOKEN links. */
    public static final String BROKER_TOOL = "call_resource";

    /** Refusals reuse ActionDenied with this detail prefix (AgentAuth). */
    public static final String PREFIX = "identity: ";

    private static final ObjectMapper JSON = new ObjectMapper();

    private IdentityAudit() {}

    /** {@code decision} is ISSUED, REFUSED or EXCHANGED. */
    public record Line(
            Instant at,
            String who,
            String onBehalfOf,
            String tokenId,
            String decision,
            String reason,
            String audience) {}

    public enum Kind {
        DIRECTOR_SIGN_IN,
        DELEGATION,
        AGENT_TOKEN,
        PROCESS_BINDING,
        BROKER_TOKEN,
        REFUSAL
    }

    public record Link(Kind kind, String label, String detail, Instant at, boolean ok, String code) {}

    public record TrustChainView(List<Link> links) {
        public TrustChainView {
            links = links == null ? null : Collections.unmodifiableList(new ArrayList<>(links));
        }
    }

    public static String who(AgentId agent) {
        return "floor42-agent-" + agent.value();
    }

    private static Map<ActionId, ActionRequested> requests(List<EventEnvelope> events) {
        Map<ActionId, ActionRequested> byAction = new HashMap<>();
        for (EventEnvelope e : events) if (e.event() instanceof ActionRequested r) byAction.put(r.action(), r);
        return byAction;
    }

    /** A refusal of the identity checks, or any denial of the broker's tool (its code is all there is to show). */
    private static boolean refusal(ActionDenied d, ActionRequested r) {
        return (d.detail() != null && d.detail().startsWith(PREFIX)) || (r != null && BROKER_TOOL.equals(r.tool()));
    }

    private static String codeOf(ActionDenied d) {
        String detail = d.detail() == null
                ? d.reason().name()
                : d.detail().lines().findFirst().orElse("");
        return detail.startsWith(PREFIX) ? detail.substring(PREFIX.length()) : detail;
    }

    /** What the first line of a broker result says: {@code Resource <name> <METHOD> <path> -> <status> (exchanged token <jti> for <audience>)}. */
    private static final Pattern EXCHANGE =
            Pattern.compile("^Resource \\S+ \\S+ \\S+ -> (\\d+) \\(exchanged token (\\S+) for (\\S+)\\)$");

    private static Optional<Matcher> exchange(ActionExecuted x) {
        String first = x.output() == null ? "" : x.output().lines().findFirst().orElse("");
        Matcher m = EXCHANGE.matcher(first);
        return m.matches() ? Optional.of(m) : Optional.empty();
    }

    public static List<Line> lines(MissionId mission, List<EventEnvelope> events) {
        Map<ActionId, ActionRequested> asked = requests(events);
        Map<AgentId, String> lastJti = new HashMap<>();
        List<Line> out = new ArrayList<>();
        for (EventEnvelope e : events) line(mission, e, asked, lastJti).ifPresent(out::add);
        return out;
    }

    private static Optional<Line> line(
            MissionId mission, EventEnvelope e, Map<ActionId, ActionRequested> asked, Map<AgentId, String> lastJti) {
        if (e.event() instanceof AgentTokenIssued t) {
            if (!t.mission().equals(mission)) return Optional.empty();
            lastJti.put(t.agent(), t.tokenId());
            return Optional.of(
                    line(e, t.agent(), t.tokenId(), "ISSUED", "issuer " + t.issuer(), AgentTokenVerifier.AUDIENCE));
        }
        ActionRequested r = switch (e.event()) {
            case ActionDenied d -> asked.get(d.action());
            case ActionExecuted x -> asked.get(x.action());
            default -> null;
        };
        if (r == null || !r.mission().equals(mission)) return Optional.empty();
        String jti = lastJti.get(r.agent());
        if (e.event() instanceof ActionDenied d)
            return refusal(d, r)
                    ? Optional.of(line(e, r.agent(), jti, "REFUSED", codeOf(d), AgentTokenVerifier.AUDIENCE))
                    : Optional.empty();
        if (!BROKER_TOOL.equals(r.tool())) return Optional.empty();
        return exchange((ActionExecuted) e.event())
                .map(m -> line(e, r.agent(), jti, "EXCHANGED", "broker call, status " + m.group(1), m.group(3)));
    }

    private static Line line(
            EventEnvelope e, AgentId agent, String jti, String decision, String reason, String audience) {
        return new Line(e.occurredAt(), who(agent), "director", jti, decision, reason, audience);
    }

    /** One JSON object per line. */
    public static String ndjson(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", l.at().toString());
            m.put("who", l.who());
            m.put("onBehalfOf", l.onBehalfOf());
            m.put("tokenId", l.tokenId());
            m.put("decision", l.decision());
            m.put("reason", l.reason());
            m.put("audience", l.audience());
            try {
                sb.append(JSON.writeValueAsString(m)).append('\n');
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("cannot write an audit line", e);
            }
        }
        return sb.toString();
    }

    /**
     * The chain of one session, left to right: the director's sign-in, the mission's delegation, the agent token, the
     * process it is bound to, each broker call, and each refusal (red, with its code). The refusals of the in-memory
     * ring come first; a journaled refusal of the same agent and ticket counts only when the ring holds fewer of that
     * code (the ring is lost at a restart, the journal is not).
     */
    public static TrustChainView chain(
            String launch,
            List<EventEnvelope> events,
            Optional<MissionDelegation> delegation,
            OptionalLong pid,
            List<IdentityRefusals.Entry> ring) {
        AgentTokenIssued issued = null;
        Instant issuedAt = null;
        for (EventEnvelope e : events)
            if (e.event() instanceof AgentTokenIssued t && t.session().equals(launch)) {
                issued = t;
                issuedAt = e.occurredAt();
            }
        List<Link> links = new ArrayList<>();
        if (issued != null) head(links, issued, issuedAt, delegation, pid);
        List<Link> refusals = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (IdentityRefusals.Entry r : ring)
            if (launch.equals(r.launch())) {
                seen.merge(r.code(), 1, Integer::sum);
                refusals.add(new Link(
                        Kind.REFUSAL, r.code(), r.jti() == null ? "" : "jti " + r.jti(), r.at(), false, r.code()));
            }
        if (issued != null) tail(links, refusals, seen, issued, events);
        links.addAll(refusals);
        return new TrustChainView(links);
    }

    private static void head(
            List<Link> links,
            AgentTokenIssued t,
            Instant at,
            Optional<MissionDelegation> delegation,
            OptionalLong pid) {
        delegation.ifPresent(d -> {
            links.add(new Link(Kind.DIRECTOR_SIGN_IN, "director", "PKCE", d.grantedAt(), true, null));
            links.add(new Link(
                    Kind.DELEGATION,
                    d.mission().value(),
                    "until " + d.expiresAt() + (d.revoked() ? ", revoked" : ""),
                    d.grantedAt(),
                    !d.revoked(),
                    d.revoked() ? "REVOKED" : null));
        });
        links.add(new Link(
                Kind.AGENT_TOKEN,
                who(t.agent()),
                "jti " + t.tokenId() + ", issuer " + t.issuer() + ", until " + t.expiresAt(),
                at,
                true,
                null));
        pid.ifPresent(p -> links.add(new Link(Kind.PROCESS_BINDING, "pid " + p, "bound at launch", at, true, null)));
    }

    private static void tail(
            List<Link> links,
            List<Link> refusals,
            Map<String, Integer> seen,
            AgentTokenIssued t,
            List<EventEnvelope> events) {
        Map<ActionId, ActionRequested> asked = requests(events);
        Map<String, Integer> journaled = new HashMap<>();
        for (EventEnvelope e : events) {
            if (e.event() instanceof ActionExecuted x
                    && asked.get(x.action()) instanceof ActionRequested r
                    && BROKER_TOOL.equals(r.tool())
                    && same(r, t))
                exchange(x)
                        .ifPresent(m -> links.add(new Link(
                                Kind.BROKER_TOKEN,
                                m.group(3),
                                "jti " + m.group(2) + ", status " + m.group(1),
                                e.occurredAt(),
                                true,
                                null)));
            if (e.event() instanceof ActionDenied d
                    && asked.get(d.action()) instanceof ActionRequested r
                    && refusal(d, r)
                    && same(r, t)) {
                String code = codeOf(d);
                if (journaled.merge(code, 1, Integer::sum) > seen.getOrDefault(code, 0))
                    refusals.add(new Link(Kind.REFUSAL, code, "", e.occurredAt(), false, code));
            }
        }
    }

    private static boolean same(ActionRequested r, AgentTokenIssued t) {
        return r.agent().equals(t.agent())
                && r.mission().equals(t.mission())
                && java.util.Objects.equals(r.ticket(), t.ticket());
    }
}
