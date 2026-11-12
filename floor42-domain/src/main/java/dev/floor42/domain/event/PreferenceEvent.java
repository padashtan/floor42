package dev.floor42.domain.event;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Decision;
import dev.floor42.domain.Setting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The director's company-wide choices and the sandbox measure. */
public sealed interface PreferenceEvent extends DomainEvent {

    /** @param value nullable: back to the daemon's default */
    record SettingChanged(Setting setting, String value) implements PreferenceEvent {}

    /**
     * Floor42 measured which tools the shell sandbox has (ADR-0033), by one run in it: no project path is in it, only
     * the distribution and tool names. {@code tools} are lines {@code name|path|version}, path empty for a tool
     * measured missing. Not measured ({@code measured} false, {@code reason} says why): every tool stays unknown.
     *
     * @param osId nullable, as the other os fields: /etc/os-release's ID, ID_LIKE, VERSION_ID and PRETTY_NAME
     */
    record SandboxToolsProbed(
            String distro,
            String osId,
            String osLike,
            String osVersion,
            String osName,
            boolean measured,
            List<String> tools,
            String reason)
            implements PreferenceEvent {
        public SandboxToolsProbed {
            tools = tools == null ? null : Collections.unmodifiableList(new ArrayList<>(tools));
        }
    }

    /**
     * The director switched "unsafe mode" on or off (ADR-0063): with it on, a REAL mission's shell may run without the
     * WSL sandbox, as the daemon's own OS user. Journaled so the weakened state is never silent.
     *
     * @param reason why, required when switching on; nullable when switching off
     */
    record UnsafeModeChanged(boolean on, String reason) implements PreferenceEvent {}

    /**
     * The director signed in with the single-use console link while IAM mode is on, instead of Keycloak (ADR-0064,
     * decision 8: the break-glass path). Journaled so the way around the identity provider is never silent. Origin
     * REAL. Only the session's id, which the sessions list shows anyway: never the link, the token or the cookie.
     */
    record BreakGlassSignIn(String session) implements PreferenceEvent {}

    /**
     * A line of the company's default permission table, which every new work mission starts from (D3); a null decision
     * goes back to Floor42's built-in line.
     */
    record DefaultPermissionSet(String tool, ActionCategory category, Decision decision) implements PreferenceEvent {}
}
