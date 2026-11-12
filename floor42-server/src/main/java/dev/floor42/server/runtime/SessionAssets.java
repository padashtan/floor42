package dev.floor42.server.runtime;

import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import java.nio.file.Path;
import java.util.List;

/**
 * Port: the skills and agents the director chose for a mission's sessions (ADR-0040), laid out by the application as
 * plugin folders in the session's own folder. The runtime passes each one with {@code --plugin-dir}, beside the
 * isolating flags, and nothing else: whatever is not returned here does not reach the session.
 */
public interface SessionAssets {

    /**
     * @param sessionDir the session's own folder, under the data directory, kept as evidence
     * @param notes where to say what was chosen and not given (no longer installed, declares hooks or MCP servers)
     * @return the plugin folders to load, absolute; empty when the director chose nothing
     */
    List<Path> pluginDirs(SessionSpec spec, Path sessionDir, List<String> notes);
}
