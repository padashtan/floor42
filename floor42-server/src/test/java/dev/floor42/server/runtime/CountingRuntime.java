package dev.floor42.server.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Vetoed;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Claude Code runtime that only counts the sessions asked of it: nothing is started, no process, no brief. Not a bean
 * of its own (it would inherit the runtime's scope): a test installs it with QuarkusMock.
 */
@Vetoed
public class CountingRuntime extends ClaudeCodeRuntime {

    public final AtomicInteger launches = new AtomicInteger();

    public CountingRuntime(ObjectMapper json) {
        super(json, new RunningSessions());
    }

    @Override
    public SessionReport run(SessionSpec spec, Governor governor) {
        launches.incrementAndGet();
        throw new IllegalStateException("No live session in this test: " + spec.mission() + " " + spec.ticket());
    }
}
