package dev.floor42.server.security;

/**
 * The credentials of live agent sessions (one per Claude Code process, minted at its start and revoked at its end).
 * They open the agents' own routes ({@code /hooks}, {@code /mcp}) and never a director route: the access guard
 * refuses them there with 403 (ADR-0034).
 */
public interface AgentCredentials {

    /** Whether the value is the credential of a live agent session. */
    boolean isLive(String candidate);
}
