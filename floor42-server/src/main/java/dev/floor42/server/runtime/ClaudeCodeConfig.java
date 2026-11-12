package dev.floor42.server.runtime;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;

/** How Floor42 starts Claude Code. Tests replace the command with a protocol-faithful fake. */
@ConfigMapping(prefix = "floor42.claude")
public interface ClaudeCodeConfig {

    /** The executable and any fixed leading arguments; Floor42 appends its own flags. */
    @WithDefault("claude")
    List<String> command();

    /** How long a PreToolUse hook may wait for the director before the call is refused (Claude Code's own limit). */
    @WithDefault("600")
    int hookTimeoutSeconds();

    /**
     * A session that shows no sign of life for this long (no line on its stream, no tool call, no hook waiting for the
     * director) is stopped with every process it started, and recorded as failed.
     */
    @WithDefault("1800")
    int sessionTimeoutSeconds();

    /** How long a session waits for the director's answer to a question it asked through Floor42's MCP tool. */
    @WithDefault("1800")
    int questionTimeoutSeconds();
}
