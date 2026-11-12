package dev.floor42.server.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.Origin;
import jakarta.enterprise.inject.Vetoed;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A Claude Code runtime that starts nothing: it keeps each spec it is asked to run, lets the test look at the world
 * while the "session" runs, and answers a validating review. Installed with QuarkusMock, like {@link CountingRuntime}.
 */
@Vetoed
public class CapturingRuntime extends ClaudeCodeRuntime {

    public final List<SessionSpec> specs = new CopyOnWriteArrayList<>();
    private final Consumer<SessionSpec> during;

    public CapturingRuntime(ObjectMapper json, Consumer<SessionSpec> during) {
        super(json, new RunningSessions());
        this.during = during;
    }

    @Override
    public SessionReport run(SessionSpec spec, Governor governor) {
        specs.add(spec);
        during.accept(spec);
        return new SessionReport(
                Origin.REAL,
                "s-1",
                "claude-sonnet-5-5",
                0,
                0,
                0,
                0,
                List.of(),
                "fine\nVERDICT: VALIDATED",
                null,
                null,
                null,
                false);
    }
}
