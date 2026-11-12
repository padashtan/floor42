package dev.floor42.domain.event;

/** The director's terminals and script tokens. */
public sealed interface TerminalEvent extends DomainEvent {

    /** The director opened their own Claude Code in a terminal on {@code project}. Nothing typed or printed is kept. */
    record TerminalOpened(String terminal, String project) implements TerminalEvent {}

    /**
     * @param reason {@code closed} by the director, {@code exited} by itself, {@code interrupted} by the daemon's stop
     * @param exitCode nullable: the program's exit code, when the system gave one
     */
    record TerminalClosed(String terminal, String project, String reason, Integer exitCode) implements TerminalEvent {}

    /**
     * The director issued a script token. The journal keeps its SHA-256, never the value, which was shown once.
     *
     * @param scope {@code read}, {@code missions:write} or {@code all}
     * @param expiresAt ISO-8601, at most 30 days after issue
     * @param label nullable: what the director named it
     */
    record ApiTokenIssued(String token, String sha256, String scope, String expiresAt, String label)
            implements TerminalEvent {}

    /**
     * A script token stopped working before its expiry.
     *
     * @param reason {@code revoked} (one token) or {@code everywhere} (the director signed out everywhere)
     */
    record ApiTokenRevoked(String token, String sha256, String reason) implements TerminalEvent {}
}
