package dev.floor42.server.app.mission;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.TrustLevel;

/**
 * The hook for the proof a raise of an agent's trust level needs (ADR-0082, ADR-0081). Widening rights is the one
 * trust change that needs proof: a fresh strong sign-in and, when four eyes is on, a second signature. That mechanism
 * is the assured approvals' (step-up and four eyes); it replaces {@link LocalTrustRaiseStepUp} by providing a bean of
 * its own ({@code @Alternative @Priority}). The domain refuses a raise without the proof
 * ({@code TRUST_RAISE_NEEDS_STEP_UP}), so a wrong answer here can only refuse.
 */
public interface TrustRaiseStepUp {

    /** True when the director holds the proof for raising {@code agent} to {@code to}, now. */
    boolean proven(AgentId agent, TrustLevel to);
}
