package dev.floor42.domain.event;

/** The director's marks on the recent projects list. */
public sealed interface RecentProjectEvent extends DomainEvent {

    /**
     * The director pins a project (a repository real missions worked on) at the top of the recent list, or unpins it.
     * Pinning a forgotten project brings it back.
     */
    record ProjectPinned(String path, boolean pinned) implements RecentProjectEvent {}

    /**
     * The director takes a project off the recent list. Its missions stay as they are; a new mission on it brings it
     * back.
     */
    record ProjectForgotten(String path) implements RecentProjectEvent {}
}
