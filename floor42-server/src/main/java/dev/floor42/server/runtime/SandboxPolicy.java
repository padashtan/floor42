package dev.floor42.server.runtime;

/** The director's choices for the shell sandbox (ADR-0028); implemented by the settings. */
public interface SandboxPolicy {

    /** Shell commands and checks run in the WSL sandbox. */
    boolean sandboxEnabled();

    /** The sandbox reaches the network. */
    boolean sandboxNetwork();

    /**
     * The sandbox is imposed whatever the setting says: a REAL mission's shell never runs as the daemon's own OS user
     * unless the director switched unsafe mode on (ADR-0063, pays D16).
     */
    default boolean sandboxRequired() {
        return false;
    }
}
