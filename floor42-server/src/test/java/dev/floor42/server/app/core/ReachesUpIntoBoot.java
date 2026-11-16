package dev.floor42.server.app.core;

import dev.floor42.server.app.boot.LiveStartup;

/** A planted violation for ArchitectureRulesTest: the shared kernel may use no other context. */
final class ReachesUpIntoBoot {

    private ReachesUpIntoBoot() {}

    static String name(LiveStartup startup) {
        return startup.toString();
    }
}
