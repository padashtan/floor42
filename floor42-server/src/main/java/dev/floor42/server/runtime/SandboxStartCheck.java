package dev.floor42.server.runtime;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Refuses to start when {@code floor42.sandbox.assume-available} is set outside the tests: it fakes a working sandbox. */
@ApplicationScoped
class SandboxStartCheck {

    @ConfigProperty(name = "floor42.sandbox.assume-available")
    Optional<Boolean> assumed;

    void start(@Observes StartupEvent e) {
        refuseAssumedOutsideTests(assumed, LaunchMode.current());
    }

    /** Fails the start when the test-only override is set in a real launch. */
    static void refuseAssumedOutsideTests(Optional<Boolean> assumed, LaunchMode mode) {
        if (assumed.isPresent() && mode != LaunchMode.TEST)
            throw new IllegalStateException("floor42.sandbox.assume-available is for tests only and is set in " + mode
                    + " mode: remove it, the sandbox is measured by running a command in it");
    }
}
