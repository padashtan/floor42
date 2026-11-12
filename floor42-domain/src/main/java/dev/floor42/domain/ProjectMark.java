package dev.floor42.domain;

import java.time.Instant;

/** What the director said of a project on the recent list: pinned at the top, or forgotten (taken off the list). */
public final class ProjectMark {

    private final String path;
    boolean pinned;
    Instant forgottenAt;

    ProjectMark(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public boolean pinned() {
        return pinned;
    }

    /** When the director last took it off the list; null if never, or pinned since. */
    public Instant forgottenAt() {
        return forgottenAt;
    }
}
