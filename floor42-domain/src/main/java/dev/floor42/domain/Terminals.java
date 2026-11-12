package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireReal;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TerminalEvent;
import dev.floor42.domain.event.TerminalEvent.ApiTokenIssued;
import dev.floor42.domain.event.TerminalEvent.ApiTokenRevoked;
import dev.floor42.domain.event.TerminalEvent.TerminalClosed;
import dev.floor42.domain.event.TerminalEvent.TerminalOpened;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The director's own ways in: Claude Code terminals (ADR-0041) and script tokens (ADR-0044). Only the facts are
 * journaled: never what is typed in a terminal, never a token's value.
 */
final class Terminals {

    private static final Set<String> CLOSE_REASONS = Set.of("closed", "exited", "interrupted");
    private static final Set<String> TOKEN_SCOPES = Set.of("read", "missions:write", "all");
    private static final Set<String> REVOCATIONS = Set.of("revoked", "everywhere");
    private static final String SHA_256 = "[0-9a-f]{64}";

    private final CompanyView view;
    /** The director's own terminals now open: id to project. Ids are never reused. */
    private final Map<String, String> openTerminals = new LinkedHashMap<>();

    private int terminalsEver;

    Terminals(CompanyView view) {
        this.view = view;
    }

    Map<String, String> openTerminals() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(openTerminals));
    }

    List<EventEnvelope> openTerminal(CommandContext ctx, String project) {
        requireDirector(ctx);
        requireReal(ctx, "INVALID_ARGUMENT", "A terminal is real or nothing");
        requireText(project, "project");
        require(
                !openTerminals.containsValue(project),
                Refusal.TERMINAL_OPEN,
                "A terminal is already open on " + project);
        return view.record(ctx, null)
                .emit(new TerminalOpened("TERM-" + (terminalsEver + 1), project))
                .events();
    }

    List<EventEnvelope> closeTerminal(CommandContext ctx, String terminal, String reason, Integer exitCode) {
        require(
                ctx.actor().isDirector() || ctx.actor().kind() == Actor.Kind.SYSTEM,
                Refusal.FORBIDDEN,
                "Only the director or Floor42 closes a terminal");
        requireReal(ctx, "INVALID_ARGUMENT", "A terminal is real or nothing");
        String project = openTerminals.get(terminal);
        require(project != null, Refusal.UNKNOWN_TERMINAL, "No open terminal " + terminal);
        require(
                CLOSE_REASONS.contains(reason),
                Refusal.INVALID_ARGUMENT,
                "A terminal closes as closed, exited or interrupted");
        return view.record(ctx, null)
                .emit(new TerminalClosed(terminal, project, reason, exitCode))
                .events();
    }

    List<EventEnvelope> recordApiTokenIssued(
            CommandContext ctx, String token, String sha256, String scope, String expiresAt, String label) {
        requireDirector(ctx);
        requireReal(ctx, "INVALID_ARGUMENT", "A token is real or nothing");
        requireText(token, "token");
        require(
                sha256 != null && sha256.matches(SHA_256),
                Refusal.INVALID_ARGUMENT,
                "A token is journaled by its SHA-256 only");
        require(
                TOKEN_SCOPES.contains(scope),
                Refusal.INVALID_ARGUMENT,
                "A token's scope is read, missions:write or all");
        requireText(expiresAt, "expiresAt");
        return view.record(ctx, null)
                .emit(new ApiTokenIssued(token, sha256, scope, expiresAt, label))
                .events();
    }

    List<EventEnvelope> recordApiTokenRevoked(CommandContext ctx, String token, String sha256, String reason) {
        requireDirector(ctx);
        requireReal(ctx, "INVALID_ARGUMENT", "A token is real or nothing");
        requireText(token, "token");
        require(sha256 != null && sha256.matches(SHA_256), Refusal.INVALID_ARGUMENT, "SHA-256 only");
        require(REVOCATIONS.contains(reason), Refusal.INVALID_ARGUMENT, "A token is revoked alone or everywhere");
        return view.record(ctx, null)
                .emit(new ApiTokenRevoked(token, sha256, reason))
                .events();
    }

    /** The only way this area's state changes. */
    void apply(TerminalEvent event, EventEnvelope envelope) {
        switch (event) {
            case TerminalOpened e -> on(e);
            case TerminalClosed e -> on(e);
            case ApiTokenIssued e -> on(e);
            case ApiTokenRevoked e -> on(e);
        }
    }

    void on(TerminalOpened e) {
        openTerminals.put(e.terminal(), e.project());
        terminalsEver++;
    }

    void on(TerminalClosed e) {
        openTerminals.remove(e.terminal());
    }

    /** Script tokens live in the daemon's memory only (ADR-0044): the journal records the facts, no state. */
    void on(ApiTokenIssued e) {
        // Nothing in the company's state changes.
    }

    void on(ApiTokenRevoked e) {
        // Nothing in the company's state changes.
    }
}
