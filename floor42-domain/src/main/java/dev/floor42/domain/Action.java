package dev.floor42.domain;

import java.time.Instant;
import java.util.Set;

/** One tool call made by an agent, followed from request to confirmation. */
public final class Action {

    private final ActionId id;
    private final MissionId mission;
    private final TicketId ticket;
    private final AgentId agent;
    private final ActionCategory category;
    private final String tool;
    private final String summary;
    private final Instant requestedAt;
    ActionStage stage = ActionStage.REQUESTED;
    /** Why it was authorized or denied, or what the director answered. */
    String decisionBasis;

    DenialReason denialReason;
    String result;
    boolean authorizedByOffice;
    Instant updatedAt;
    /**
     * The agent's session ended while this call was still requested or authorized: Floor42 never saw its outcome (the
     * daemon stopped, or the session was cut). Its stage stays as recorded, and it may have run; the agent is just no
     * longer busy with it.
     */
    boolean orphaned;

    /** How long Claude Code last said the call had been running, in seconds; null when it never said. */
    Long runningSeconds;

    Action(
            ActionId id,
            MissionId mission,
            TicketId ticket,
            AgentId agent,
            ActionCategory category,
            String tool,
            String summary,
            Instant requestedAt) {
        this.id = id;
        this.mission = mission;
        this.ticket = ticket;
        this.agent = agent;
        this.category = category;
        this.tool = tool;
        this.summary = summary;
        this.requestedAt = requestedAt;
        this.updatedAt = requestedAt;
    }

    public ActionId id() {
        return id;
    }

    public MissionId mission() {
        return mission;
    }

    /** Null when the action serves the mission as a whole. */
    public TicketId ticket() {
        return ticket;
    }

    public AgentId agent() {
        return agent;
    }

    public ActionCategory category() {
        return category;
    }

    public String tool() {
        return tool;
    }

    public String summary() {
        return summary;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long runningSeconds() {
        return runningSeconds;
    }

    public boolean orphaned() {
        return orphaned;
    }

    public ActionStage stage() {
        return stage;
    }

    public String decisionBasis() {
        return decisionBasis;
    }

    public DenialReason denialReason() {
        return denialReason;
    }

    /** Tool output or error, abbreviated by the runtime; null until the tool ran or was blocked. */
    public String result() {
        return result;
    }

    /** False if the tool ran without an Floor42 authorization: that is an incident, never a normal path. */
    public boolean authorizedByOffice() {
        return authorizedByOffice;
    }

    /**
     * The codes the resource broker answers a call it declines to make (its {@code Refusal}): an authorization decision
     * of Floor42, not a failure of the tool. RESOURCE_UNREACHABLE (and any upstream error status) is a failure.
     */
    private static final Set<String> BROKER_REFUSALS = Set.of(
            "RESOURCE_UNKNOWN",
            "RESOURCE_NOT_GRANTED",
            "PATH_NOT_ALLOWED",
            "METHOD_NOT_ALLOWED",
            "HOST_NOT_ALLOWED",
            "SCHEME_NOT_ALLOWED",
            "IDENTITY_REFUSED");

    /**
     * IDENTITY_REFUSED is Floor42's own decision (mission completed, delegation revoked); IDENTITY_UNAVAILABLE is the
     * identity provider not answering or giving nothing usable, a failure. Journals written before the two had distinct
     * codes hold the first as IDENTITY_UNAVAILABLE with one of two fixed texts: those rows stay refusals (history, never
     * reworded, so never matched for a new row).
     */
    private static final String IDENTITY_UNAVAILABLE = "IDENTITY_UNAVAILABLE";

    private static final Set<String> LEGACY_IDENTITY_REFUSALS =
            Set.of("The mission is completed: no resource for its agents", "The mission's delegation is revoked");

    /**
     * True when Floor42 refused the call: denied before it ran, or authorized as a tool and then declined by the
     * resource broker. The journal records the second as {@code ActionFailed} whose error starts with the broker's code
     * and a colon, so old journals read the same way.
     */
    public boolean refused() {
        if (stage == ActionStage.DENIED) return true;
        if (stage != ActionStage.FAILED || category != ActionCategory.FLOOR42 || !tool.endsWith("call_resource"))
            return false;
        int colon = result == null ? -1 : result.indexOf(':');
        if (colon <= 0) return false;
        String code = result.substring(0, colon);
        if (code.equals(IDENTITY_UNAVAILABLE))
            return LEGACY_IDENTITY_REFUSALS.contains(result.substring(colon + 1).strip());
        return BROKER_REFUSALS.contains(code);
    }
}
