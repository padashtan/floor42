package dev.floor42.server.runtime;

import java.nio.file.Path;

/**
 * A path that {@link Confinement#relative} accepted inside a workspace: relative to its root, with forward slashes,
 * never climbing out, never naming git's own data. Only Confinement makes one.
 *
 * @param root the workspace, absolute and normalised
 * @param relative the path inside it, {@code ""} for the root itself
 */
public record ConfinedPath(Path root, String relative) {

    /** How the path will be used: a write is also refused on what changes what runs later (.claude, .mcp.json). */
    public enum Mode {
        READ,
        WRITE
    }

    /** The file on disk. */
    public Path file() {
        return relative.isEmpty() ? root : root.resolve(relative);
    }

    /** True when the path names the workspace itself, not a file in it. */
    public boolean isRoot() {
        return relative.isEmpty();
    }

    @Override
    public String toString() {
        return relative;
    }
}
