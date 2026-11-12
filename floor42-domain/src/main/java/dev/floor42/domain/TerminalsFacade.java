package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Map;

/**
 * The director's own access to the machine: the terminals they open on a project (ADR-0041) and the script tokens
 * they issue (ADR-0044). Only the facts are journaled, never what is typed nor a token's value. Reached by
 * {@link Company#terminals()}; the rules live in the terminals area.
 */
public final class TerminalsFacade {

    private final Terminals terminals;

    TerminalsFacade(Terminals terminals) {
        this.terminals = terminals;
    }

    /** The director's terminals the journal says are open, id to project, oldest first. */
    public Map<String, String> openTerminals() {
        return terminals.openTerminals();
    }

    /**
     * The director opened their own Claude Code in a terminal (ADR-0041). Only the fact is journaled, never what is typed
     * or printed in it; only the director opens one, only for real, one at a time per project.
     */
    public List<EventEnvelope> open(CommandContext ctx, String project) {
        return terminals.openTerminal(ctx, project);
    }

    /**
     * A terminal closed: by the director ({@code closed}), or as Floor42 saw it ({@code exited} when the program ended,
     * {@code interrupted} when the daemon stopped with it open).
     *
     * @param exitCode nullable: the program's exit code, when the system gave one
     */
    public List<EventEnvelope> close(CommandContext ctx, String terminal, String reason, Integer exitCode) {
        return terminals.closeTerminal(ctx, terminal, reason, exitCode);
    }

    /**
     * The director issued a script token (ADR-0044). Only its SHA-256 is journaled; the value lives in the daemon's
     * memory and was shown once.
     */
    public List<EventEnvelope> recordApiTokenIssued(
            CommandContext ctx, String token, String sha256, String scope, String expiresAt, String label) {
        return terminals.recordApiTokenIssued(ctx, token, sha256, scope, expiresAt, label);
    }

    /** A script token stopped working: revoked alone, or with every session ({@code everywhere}). */
    public List<EventEnvelope> recordApiTokenRevoked(CommandContext ctx, String token, String sha256, String reason) {
        return terminals.recordApiTokenRevoked(ctx, token, sha256, reason);
    }
}
