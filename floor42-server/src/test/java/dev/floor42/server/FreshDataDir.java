package dev.floor42.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import java.util.UUID;

/**
 * A data folder of its own for every boot of the daemon under test. {@code ${quarkus.uuid}} (application.properties) is
 * drawn once per JVM, so a daemon booted again in the same JVM (another test profile in between) replayed the journal
 * of the one before. With test JVMs side by side, Surefire hands the classes out one at a time and a JVM goes back to
 * the default daemon often: each boot gets its own {@code floor42.test.boot-id}, which names the folder. A test profile
 * or a test resource that sets {@code floor42.data-dir} itself is not affected.
 */
@QuarkusTestResource(value = FreshDataDir.class, restrictToAnnotatedClass = false)
public class FreshDataDir implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        return Map.of("floor42.test.boot-id", "boot-" + UUID.randomUUID());
    }

    @Override
    public void stop() {
        // Nothing to undo: the folder stays under target/ for a look after a failure; the next clean removes it.
    }
}
