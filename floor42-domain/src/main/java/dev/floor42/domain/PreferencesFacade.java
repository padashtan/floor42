package dev.floor42.domain;

import dev.floor42.domain.event.DeskEvent.ApprovalSigned;
import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * The director's company-wide preferences: settings, the default permission table new missions start from, and what
 * Floor42 measured in the shell sandbox. Reached by {@link Company#preferences()}; the rules live in the preferences
 * area.
 */
public final class PreferencesFacade {

    private final Preferences preferences;

    PreferencesFacade(Preferences preferences) {
        this.preferences = preferences;
    }

    /** The value the director set, or empty when the daemon's default applies. */
    public Optional<String> setting(Setting setting) {
        return preferences.setting(setting);
    }

    /** The director's line of the default permission table for a tool, or empty when Floor42's built-in line applies. */
    public Optional<Decision> defaultPermission(String tool) {
        return preferences.defaultPermission(tool);
    }

    /** What Floor42 last measured in the shell sandbox; empty when nothing was measured since it was turned on. */
    public Optional<SandboxTools> sandboxTools() {
        return preferences.sandboxTools();
    }

    /**
     * Whether a measure of the sandbox was journaled for this cause (the causation id its command carried): how Floor42
     * knows, after a restart too, that the measure an answer asked for was taken.
     */
    public boolean measuredFor(String cause) {
        return preferences.measuredFor(cause);
    }

    /**
     * The director changes a company-wide setting; {@code value} null goes back to the daemon's default. Setting the
     * value already in force records nothing.
     */
    public List<EventEnvelope> changeSetting(CommandContext ctx, Setting setting, String value) {
        return preferences.changeSetting(ctx, setting, value);
    }

    /**
     * The same, carried by signatures: turning four eyes off, in an assured context, needs two (a first by the director,
     * a second by a reviewer, ADR-0081) and is refused without them.
     */
    public List<EventEnvelope> changeSetting(
            CommandContext ctx, Setting setting, String value, List<ApprovalSigned> signed) {
        return preferences.changeSetting(ctx, setting, value, signed);
    }

    /** Whether the director allows a REAL mission's shell to run without the sandbox (ADR-0063). */
    public boolean unsafeMode() {
        return preferences.unsafeMode();
    }

    /** The director switches unsafe mode on (a reason is required) or off; the state already in force records nothing. */
    public List<EventEnvelope> setUnsafeMode(CommandContext ctx, boolean on, String reason) {
        return preferences.setUnsafeMode(ctx, on, reason);
    }

    /** The broker authorization tuples the journal holds now (the rows of {@code AuthorizationChanged}, replayed). */
    public java.util.Set<AuthorizationTuple> authorization() {
        return preferences.authorization();
    }

    /**
     * One row for a change of the broker authorization (grants, delegations, MCP ticks): the tuples written and
     * deleted in OpenFGA, ids only.
     */
    public List<EventEnvelope> changeAuthorization(
            CommandContext ctx,
            List<AuthorizationTuple> writes,
            List<AuthorizationTuple> deletes,
            String modelId,
            String reason,
            boolean imported,
            boolean reconciled) {
        return preferences.changeAuthorization(ctx, writes, deletes, modelId, reason, imported, reconciled);
    }

    /** Whether the journal holds the one-shot import of the 1.2.0 grants and ticks ({@code imported: true}). */
    public boolean authorizationImported() {
        return preferences.authorizationImported();
    }

    /** The signatures of an act without a command of its own (a grant, a tick, the identity settings), journaled. */
    public List<EventEnvelope> recordSignatures(
            CommandContext ctx, AssurancePolicy.Act act, List<ApprovalSigned> signed) {
        return preferences.recordSignatures(ctx, act, signed);
    }

    /** The director signed in with the console link while IAM mode is on (ADR-0064): journaled, by session id only. */
    public List<EventEnvelope> recordBreakGlassSignIn(CommandContext ctx, String session) {
        return preferences.recordBreakGlassSignIn(ctx, session);
    }

    /**
     * The director changes a line of the default permission table new work missions start from; {@code decision} null
     * goes back to Floor42's built-in line. Missions already created keep their own table.
     */
    public List<EventEnvelope> setDefaultPermission(
            CommandContext ctx, String tool, ActionCategory category, Decision decision) {
        return preferences.setDefaultPermission(ctx, tool, category, decision);
    }

    /**
     * Floor42 records what it measured in the sandbox (ADR-0033): a real measure, by Floor42 itself. The tool lines are
     * {@code name|path|version}; when the probe could not run, no line and the reason.
     */
    public List<EventEnvelope> recordSandboxProbe(
            CommandContext ctx,
            String distro,
            String osId,
            String osLike,
            String osVersion,
            String osName,
            boolean measured,
            List<String> tools,
            String reason) {
        return preferences.recordSandboxProbe(ctx, distro, osId, osLike, osVersion, osName, measured, tools, reason);
    }
}
