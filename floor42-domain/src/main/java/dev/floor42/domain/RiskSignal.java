package dev.floor42.domain;

import java.time.Instant;

/** One observed fact about a session. {@code action} may be null. */
public record RiskSignal(RiskReason reason, Instant at, ActionId action) {}
