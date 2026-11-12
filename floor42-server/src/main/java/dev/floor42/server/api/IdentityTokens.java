package dev.floor42.server.api;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.SessionEvent.AgentTokenIssued;
import dev.floor42.server.iam.IamSessions;
import dev.floor42.server.iam.SimulatedIssuer;
import dev.floor42.server.journal.Journal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tokens the screen may show: a live session's decoded header and claims with 8 characters of the signature, and,
 * for a token only the journal remembers (a restart, an ended session), what the journal holds. Never a token.
 */
public final class IdentityTokens {

    static final int JOURNAL_WINDOW = 5000;

    /** {@code origin} is REAL or SIMULATED; header and claims are empty when only the journal remembers the token. */
    public record TokenView(
            String agent,
            String session,
            String launch,
            String jti,
            Map<String, Object> header,
            Map<String, Object> claims,
            String signaturePrefix,
            Instant expiresAt,
            String origin) {
        public TokenView {
            claims = claims == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(claims));
            header = header == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(header));
        }
    }

    private IdentityTokens() {}

    static List<TokenView> list(IamSessions sessions, Journal journal) {
        List<TokenView> out = new ArrayList<>();
        Set<String> live = new HashSet<>();
        for (IamSessions.Shown s : sessions.shown()) {
            live.add(s.launch());
            out.add(shown(s));
        }
        for (Journal.Entry entry : journal.latest(JOURNAL_WINDOW))
            if (entry.envelope().event() instanceof AgentTokenIssued t && !live.contains(t.session()))
                out.add(remembered(entry.envelope(), t));
        return out;
    }

    private static TokenView shown(IamSessions.Shown s) {
        Object exp = s.payload().get("exp");
        return new TokenView(
                s.claims().agent().value(),
                s.launch(),
                s.launch(),
                s.jti(),
                s.header(),
                s.payload(),
                s.signaturePrefix(),
                exp instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null,
                SimulatedIssuer.ISSUER.equals(s.payload().get("iss")) ? "SIMULATED" : "REAL");
    }

    private static TokenView remembered(EventEnvelope e, AgentTokenIssued t) {
        return new TokenView(
                t.agent().value(),
                t.session(),
                t.session(),
                t.tokenId(),
                Map.of(),
                Map.of("iss", t.issuer(), "jti", t.tokenId(), "exp", t.expiresAt()),
                "",
                Instant.parse(t.expiresAt()),
                e.origin().name());
    }
}
