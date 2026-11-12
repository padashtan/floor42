package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.SandboxTools;
import dev.floor42.domain.event.PreferenceEvent;
import dev.floor42.domain.event.PreferenceEvent.BreakGlassSignIn;
import dev.floor42.domain.event.PreferenceEvent.DefaultPermissionSet;
import dev.floor42.domain.event.PreferenceEvent.SandboxToolsProbed;
import dev.floor42.domain.event.PreferenceEvent.SettingChanged;
import dev.floor42.domain.event.PreferenceEvent.UnsafeModeChanged;
import java.util.List;

/** The director's company-wide choices and the sandbox measure. */
final class PreferenceRenderer implements EventRenderer<PreferenceEvent> {

    @Override
    public EventLine render(Company c, PreferenceEvent event) {
        return switch (event) {
            case SettingChanged e ->
                line(e.value() == null ? "default" : "value")
                        .param("setting", Lookups.words(e.setting()))
                        .param("value", e.value())
                        .build();
            case SandboxToolsProbed e -> probed(e);
            case UnsafeModeChanged e ->
                line(e.on() ? "on" : "off").param("reason", e.reason()).build();
            case BreakGlassSignIn e -> line().param("session", e.session()).build();
            case DefaultPermissionSet e ->
                line(e.decision() == null ? "builtin" : "value")
                        .param("tool", e.tool())
                        .param("category", e.category())
                        .param("decision", e.decision())
                        .build();
        };
    }

    private static EventLine probed(SandboxToolsProbed e) {
        return line(e.measured() ? "measured" : "unknown")
                .param("distro", e.distro())
                .param("osId", e.osId())
                .param("osLike", e.osLike())
                .param("osVersion", e.osVersion())
                .param("osName", e.osName())
                .param("measured", e.measured())
                .param("tools", e.tools())
                .param("present", present(e.tools()))
                .param("count", e.tools().size())
                .param("reason", e.reason())
                .build();
    }

    /** How many probe lines name a tool found (a path after the name). */
    private static long present(List<String> tools) {
        return tools.stream()
                .filter(l -> SandboxTools.Tool.parse(l)
                        .map(SandboxTools.Tool::present)
                        .orElse(false))
                .count();
    }
}
