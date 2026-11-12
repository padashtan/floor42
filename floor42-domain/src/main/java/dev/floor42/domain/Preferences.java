package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireReal;
import static dev.floor42.domain.Rules.requireSystem;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.PreferenceEvent;
import dev.floor42.domain.event.PreferenceEvent.BreakGlassSignIn;
import dev.floor42.domain.event.PreferenceEvent.DefaultPermissionSet;
import dev.floor42.domain.event.PreferenceEvent.SandboxToolsProbed;
import dev.floor42.domain.event.PreferenceEvent.SettingChanged;
import dev.floor42.domain.event.PreferenceEvent.UnsafeModeChanged;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** The director's company-wide choices (settings, default permission table) and Floor42's last sandbox measure. */
final class Preferences {

    private final CompanyView view;
    private final Map<Setting, String> settings = new EnumMap<>(Setting.class);
    /** The company's default permission table, by tool: only the lines the director changed. */
    private final Map<String, Decision> defaultPermissions = new TreeMap<>();
    /** The last measure of the sandbox's tools, null when none holds (ADR-0033). */
    private SandboxTools sandboxTools;
    /** Whether the director allows a REAL mission's shell to run without the sandbox (ADR-0063). */
    private boolean unsafeMode;
    /**
     * The causes journaled with the sandbox's measures (the question of an "I install it" answer, for the measure that
     * answer asked for): read back from the journal, so whether that measure was taken survives a restart.
     */
    private final Set<String> measureCauses = new HashSet<>();

    Preferences(CompanyView view) {
        this.view = view;
    }

    Optional<String> setting(Setting setting) {
        return Optional.ofNullable(settings.get(setting));
    }

    Optional<Decision> defaultPermission(String tool) {
        return Optional.ofNullable(defaultPermissions.get(tool));
    }

    Optional<SandboxTools> sandboxTools() {
        return Optional.ofNullable(sandboxTools);
    }

    boolean measuredFor(String cause) {
        return cause != null && measureCauses.contains(cause);
    }

    List<EventEnvelope> changeSetting(CommandContext ctx, Setting setting, String value) {
        requireDirector(ctx);
        require(setting != null, Refusal.INVALID_ARGUMENT, "Which setting?");
        String v = value == null ? null : setting.normalize(value);
        if (Objects.equals(v, settings.get(setting))) return List.of();
        return view.record(ctx, null).emit(new SettingChanged(setting, v)).events();
    }

    boolean unsafeMode() {
        return unsafeMode;
    }

    /** Switching on needs a reason; setting the state already in force records nothing. */
    List<EventEnvelope> setUnsafeMode(CommandContext ctx, boolean on, String reason) {
        requireDirector(ctx);
        requireReal(ctx, "INVALID_ARGUMENT", "Unsafe mode is a real decision or nothing");
        if (on) requireText(reason, "reason");
        if (on == unsafeMode) return List.of();
        return view.record(ctx, null)
                .emit(new UnsafeModeChanged(on, reason == null || reason.isBlank() ? null : reason.strip()))
                .events();
    }

    /** The console link signed the director in while IAM mode is on; the session is named by its id, nothing secret. */
    List<EventEnvelope> recordBreakGlassSignIn(CommandContext ctx, String session) {
        requireDirector(ctx);
        requireReal(ctx, "INVALID_ARGUMENT", "A sign-in is real or nothing");
        requireText(session, "session");
        return view.record(ctx, null).emit(new BreakGlassSignIn(session)).events();
    }

    List<EventEnvelope> setDefaultPermission(
            CommandContext ctx, String tool, ActionCategory category, Decision decision) {
        requireDirector(ctx);
        requireText(tool, "tool");
        require(category != null, Refusal.INVALID_ARGUMENT, "Which category?");
        if (Objects.equals(decision, defaultPermissions.get(tool))) return List.of();
        return view.record(ctx, null)
                .emit(new DefaultPermissionSet(tool, category, decision))
                .events();
    }

    List<EventEnvelope> recordSandboxProbe(
            CommandContext ctx,
            String distro,
            String osId,
            String osLike,
            String osVersion,
            String osName,
            boolean measured,
            List<String> tools,
            String reason) {
        requireSystem(ctx, "Only Floor42 records what it measured");
        requireReal(ctx, "INVALID_ARGUMENT", "A measure of the sandbox is real or nothing");
        requireText(distro, "distro");
        List<String> lines = tools == null ? List.of() : List.copyOf(tools);
        require(measured || lines.isEmpty(), Refusal.INVALID_ARGUMENT, "Nothing measured, no tool line");
        require(
                measured || reason != null && !reason.isBlank(),
                Refusal.INVALID_ARGUMENT,
                "Say why nothing was measured");
        require(
                lines.stream().allMatch(l -> SandboxTools.Tool.parse(l).isPresent()),
                Refusal.INVALID_ARGUMENT,
                "A tool line is name|path|version");
        return view.record(ctx, null)
                .emit(new SandboxToolsProbed(distro, osId, osLike, osVersion, osName, measured, lines, reason))
                .events();
    }

    /** The only way this area's state changes. */
    void apply(PreferenceEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        switch (event) {
            case SettingChanged e -> on(e);
            case UnsafeModeChanged e -> unsafeMode = e.on();
            case BreakGlassSignIn ignored -> {}
            case DefaultPermissionSet e -> on(e);
            case SandboxToolsProbed e -> on(e, at, envelope.causationId());
        }
    }

    void on(SettingChanged e) {
        if (e.value() == null) settings.remove(e.setting());
        else settings.put(e.setting(), e.value());
        // Turning the sandbox on may follow an install or a repair: the last measure no longer holds.
        if (e.setting() == Setting.SHELL_SANDBOX && "wsl".equals(e.value())) sandboxTools = null;
    }

    void on(DefaultPermissionSet e) {
        if (e.decision() == null) defaultPermissions.remove(e.tool());
        else defaultPermissions.put(e.tool(), e.decision());
    }

    void on(SandboxToolsProbed e, Instant at, String cause) {
        if (cause != null) measureCauses.add(cause);
        sandboxTools = new SandboxTools(
                e.distro(),
                e.osId(),
                e.osLike(),
                e.osVersion(),
                e.osName(),
                e.measured(),
                e.tools() == null
                        ? List.of()
                        : e.tools().stream()
                                .flatMap(l -> SandboxTools.Tool.parse(l).stream())
                                .toList(),
                e.reason(),
                at);
    }
}
