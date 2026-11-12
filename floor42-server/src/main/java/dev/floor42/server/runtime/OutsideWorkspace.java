package dev.floor42.server.runtime;

/**
 * A path {@link Confinement#relative} refused: outside the workspace, through a link, into git's own data, or (for a
 * write) into what changes what runs later. Reaches the API as 400 {@code OUTSIDE_WORKSPACE}, its message saying why.
 */
public final class OutsideWorkspace extends RuntimeException {

    public OutsideWorkspace(String reason) {
        super(reason);
    }
}
