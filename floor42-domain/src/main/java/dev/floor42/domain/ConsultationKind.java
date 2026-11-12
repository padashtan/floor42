package dev.floor42.domain;

/** What a reviewer in doubt may ask for, once per review (ADR-0018). */
public enum ConsultationKind {
    /** Another agent's advice, read only, from a committee role. */
    CONSULT,
    /** A web research, by a session without access to the repository. */
    RESEARCH
}
