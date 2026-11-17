package dev.floor42.domain.event;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.AuthorizationTuple;
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

    /**
     * The director's authorization of the brokers changed (ADR-0080): grants, mission delegations and MCP ticks are
     * tuples in OpenFGA, and every change of them is one row here, ids only (never a token or a secret). At most 100
     * tuples per list, one OpenFGA call.
     *
     * @param modelId the OpenFGA model the tuples were written against
     * @param reason {@code GRANT}, {@code DELEGATION}, {@code MCP_TICK}, {@code IMPORT}, {@code RECONCILE} or
     *     {@code QUARANTINE}
     * @param imported the tuples come from the files of 1.2.0 (grants.json and the MCP ticks), written once
     * @param reconciled Floor42 found the change in OpenFGA at start without its row (a crash between the write and
     *     its journal row, or someone who wrote behind Floor42's back)
     */
    record AuthorizationChanged(
            List<AuthorizationTuple> writes,
            List<AuthorizationTuple> deletes,
            String modelId,
            String reason,
            boolean imported,
            boolean reconciled)
            implements PreferenceEvent {
        public AuthorizationChanged {
            writes = writes == null ? List.of() : List.copyOf(writes);
            deletes = deletes == null ? List.of() : List.copyOf(deletes);
        }
    }
}
