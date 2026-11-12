package dev.floor42.server.app;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/** The director's own terminal (ADR-0041). Tests replace the command with a fake interactive program. */
@ConfigMapping(prefix = "floor42.terminal")
public interface TerminalConfig {

    /** The director's own Claude Code, started as is: no isolation flag, no hook, no prompt. */
    @WithDefault("claude")
    List<String> command();

    /**
     * Whether Floor42 passes {@code --settings} with a status line that copies what Claude Code reports (its cost) to a
     * file Floor42 reads, then runs the director's own status line command, if any. The only setting Floor42 adds.
     */
    @WithDefault("true")
    boolean costFromStatusLine();

    /** Claude Code's user folder, where the director's own status line is read (default CLAUDE_CONFIG_DIR, ~/.claude). */
    Optional<String> claudeHome();

    /** What a terminal keeps of its screen in memory for a browser that reattaches (never written anywhere). */
    @WithDefault("262144")
    int replayBytes();
}
