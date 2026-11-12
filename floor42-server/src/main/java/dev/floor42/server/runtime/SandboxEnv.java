package dev.floor42.server.runtime;

import java.util.Locale;

/** The environment Floor42 hands to a process it starts towards WSL (taken out of Sandbox, which holds its script). */
public final class SandboxEnv {

    private SandboxEnv() {}

    /**
     * The environment of a wsl.exe Floor42 starts itself: no WSLENV (nothing forwarded into Linux) and none of the
     * secrets Floor42 or Claude Code hold. The sandbox keeps an allow-list of its own inside, whatever arrives.
     */
    public static void scrub(java.util.Map<String, String> env) {
        // Windows names are case-insensitive: Anthropic_Api_Key is the same variable.
        env.keySet().removeIf(name -> {
            String k = name.toUpperCase(Locale.ROOT);
            return k.startsWith("ANTHROPIC")
                    || k.startsWith("CLAUDE")
                    || k.startsWith("FLOOR42")
                    || k.startsWith("OFFICE")
                    || k.equals("AI_AGENT")
                    || k.equals("WSLENV");
        });
    }
}
