package dev.floor42.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;

/**
 * One run of an agent runtime (a Claude Code session) on a ticket. Token counts are provided by the runtime; the cost
 * is the runtime's own client-side estimate and must be shown as such (ADR-0006, spec section 15).
 */
public final class AgentSession {

    private final String id;
    private final MissionId mission;
    private final TicketId ticket;
    private final AgentId agent;
    private final String model;
    private final String runtimeVersion;
    private final Instant startedAt;
    Instant endedAt;
    Boolean failed;
    String outcome;
    Integer turns;
    Long inputTokensProvided;
    Long outputTokensProvided;
    Double costUsdEstimated;
    Double costUsdBound;
    Double capUsd;
    String continuedFrom;
    String lastTokenId;
    String tokenIssuer;
    Instant tokenExpiresAt;
    transient Risk risk;

    /** The session's latest risk raise; {@code at} is when it was journaled. */
    public record Risk(RiskLevel level, java.util.List<RiskReason> reasons, Instant at) {
        public Risk {
            reasons = reasons == null ? null : Collections.unmodifiableList(new ArrayList<>(reasons));
        }
    }

    /** Empty while the session never raised a risk. */
    public java.util.Optional<Risk> risk() {
        return java.util.Optional.ofNullable(risk);
    }

    AgentSession(
            String id,
            MissionId mission,
            TicketId ticket,
            AgentId agent,
            String model,
            String runtimeVersion,
            Instant startedAt) {
        this.id = id;
        this.mission = mission;
        this.ticket = ticket;
        this.agent = agent;
        this.model = model;
        this.runtimeVersion = runtimeVersion;
        this.startedAt = startedAt;
    }

    public String id() {
        return id;
    }

    public MissionId mission() {
        return mission;
    }

    public TicketId ticket() {
        return ticket;
    }

    public AgentId agent() {
        return agent;
    }

    public String model() {
        return model;
    }

    /** Null when the runtime did not report it. */
    public String runtimeVersion() {
        return runtimeVersion;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public boolean isOpen() {
        return endedAt == null;
    }

    public Instant endedAt() {
        return endedAt;
    }

    /** Null while open. */
    public Boolean failed() {
        return failed;
    }

    public String outcome() {
        return outcome;
    }

    /** The outcome of a session the previous daemon left open: its end is when Floor42 noticed, not when work stopped. */
    public static final String INTERRUPTED = "interrupted";

    /**
     * Time the agent worked, measured by Floor42 from the session's start and end. Zero while open, and for an
     * interrupted session: its recorded end is the next startup, so its length is not work.
     */
    public java.time.Duration workTime() {
        if (endedAt == null || INTERRUPTED.equals(outcome)) return java.time.Duration.ZERO;
        return java.time.Duration.between(startedAt, endedAt);
    }

    /** The session this one continued with Claude Code's {@code --resume}; null for a fresh session. */
    public String continuedFrom() {
        return continuedFrom;
    }

    /** The {@code jti} of the identity token the runtime last obtained for this session (IAM mode); null when none. */
    public String lastTokenId() {
        return lastTokenId;
    }

    public String tokenIssuer() {
        return tokenIssuer;
    }

    public Instant tokenExpiresAt() {
        return tokenExpiresAt;
    }

    public Integer turns() {
        return turns;
    }

    public Long inputTokensProvided() {
        return inputTokensProvided;
    }

    public Long outputTokensProvided() {
        return outputTokensProvided;
    }

    public Double costUsdEstimated() {
        return costUsdEstimated;
    }

    /**
     * The cap this session ran under when it ended without reporting a cost: what the mission's budget counts for it, an
     * upper bound and not a measure. Null when it reported a cost (or has not ended).
     */
    public Double costUsdBound() {
        return costUsdBound;
    }

    /** The cap the session ran under, as journaled at its start; null for a replay and in journals before 1.9.0. */
    public Double capUsd() {
        return capUsd;
    }

    /** What the mission's budget counts for this session, in USD: its estimate, else the bound; null when neither. */
    public Double costUsdCounted() {
        return costUsdEstimated != null ? costUsdEstimated : costUsdBound;
    }
}
