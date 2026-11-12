package dev.floor42.domain.workflow;

/**
 * The saved version a mission's pin copied (ADR-0052), resolved in the life of the name as it stood at the pin.
 *
 * @param current false when the name was removed after the pin: the version belongs to a life of the name that is over,
 *     and a workflow of that name saved since is another one
 */
public record FollowedVersion(WorkflowVersion version, boolean current) {}
