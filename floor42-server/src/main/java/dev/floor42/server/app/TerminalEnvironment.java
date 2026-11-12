package dev.floor42.server.app;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The environment of the director's own Claude Code in a terminal (ADR-0041). */
final class TerminalEnvironment {

    /** Names Claude Code would take as "started from inside another Claude Code session" or as an agent marker. */
    private static final Set<String> MARKERS = Set.of(
            "CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SSE_PORT", "AI_AGENT");

    private TerminalEnvironment() {}

    /**
     * Whether a variable marks the process as a child of another Claude Code session. A daemon started from one
     * inherits {@code CLAUDE_CODE_CHILD_SESSION}, which turned the transcript saving off in the director's terminal
     * (seen on Claude Code 2.1.316); any other {@code CLAUDE_CODE_*} naming a session or a parent goes too. The
     * director's own settings ({@code CLAUDE_CODE_USE_BEDROCK}, {@code CLAUDE_CODE_GIT_BASH_PATH}...) stay.
     */
    static boolean marker(String upperName) {
        return MARKERS.contains(upperName)
                || (upperName.startsWith("CLAUDE_CODE_")
                        && (upperName.contains("SESSION") || upperName.contains("PARENT")));
    }

    /**
     * The daemon's own environment, without anything of Floor42's (its variables, the director's token, agents'
     * credentials) and without the markers that would make it think it runs inside another Claude Code session; plus
     * a terminal type xterm.js understands.
     */
    static Map<String, String> of(Map<String, String> daemon, String directorToken) {
        Map<String, String> env = new HashMap<>();
        daemon.forEach((k, v) -> {
            String name = k.toUpperCase(Locale.ROOT);
            if (name.startsWith("FLOOR42") || name.startsWith("OFFICE_") || marker(name)) return;
            if (directorToken != null && !directorToken.isEmpty() && v != null && v.contains(directorToken)) return;
            env.put(k, v);
        });
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        return env;
    }
}
