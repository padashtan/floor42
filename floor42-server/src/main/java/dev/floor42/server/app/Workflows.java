package dev.floor42.server.app;

import dev.floor42.domain.workflow.MissionInterpreter;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The workflows the daemon follows: each mission's own, the one it pinned or the built-in one (ADR-0050). The one
 * place that chooses; the interpreter holds nothing, it reads the pin from the company it is given.
 */
@Singleton
public class Workflows {

    @Produces
    @Singleton
    MissionInterpreter interpreter() {
        return new MissionInterpreter();
    }
}
