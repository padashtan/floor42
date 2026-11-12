package dev.floor42.domain;

/** How worried Floor42 is about a session. Only goes up within a session. */
public enum RiskLevel {
    NONE,
    NOTICE,
    ALERT,
    QUARANTINE
}
