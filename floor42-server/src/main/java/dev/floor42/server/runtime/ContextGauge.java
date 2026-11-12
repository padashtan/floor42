package dev.floor42.server.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Each live session's context in use, as its last request reported it (W18): kept in memory for the last {@value
 * #CAPACITY} sessions and pushed live, never journaled. The tokens are measured by Claude Code; a share of the window
 * is only an estimate against the model's documented window ({@link ContextWindows}). A headless session tells no
 * categories (system prompt, tools, messages): Floor42 writes only the brief to its input, so it cannot ask for
 * {@code /context}.
 */
@ApplicationScoped
public class ContextGauge {

    static final int CAPACITY = 200;

    /**
     * @param session Floor42's session record ({@code <mission>:<Claude Code session id>}), as the mission views list it
     * @param usedTokens measured by Claude Code: input, cache writes and cache reads of the last request
     * @param windowTokens the model's documented window; null when Floor42 does not know the model
     * @param at when Floor42 read it
     */
    public record ContextReading(
            String session,
            String agent,
            String mission,
            String model,
            long usedTokens,
            Long windowTokens,
            String at) {}

    private record Kept(ContextReading reading, String messageId) {}

    private final Map<String, Kept> last = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Kept> eldest) {
            return size() > CAPACITY;
        }
    };

    private final List<Consumer<ContextReading>> listeners = new CopyOnWriteArrayList<>();

    /**
     * One reading of a session's stream. The lines of one message repeat its usage: the same reading again changes
     * nothing and is not pushed again; a later one replaces it, never adds to it.
     */
    public void observe(String session, String agent, String mission, String model, RuntimeEvent.ContextUsage usage) {
        ContextReading reading;
        synchronized (last) {
            Kept before = last.get(session);
            if (before != null
                    && usage.messageId() != null
                    && usage.messageId().equals(before.messageId())
                    && before.reading().usedTokens() == usage.usedTokens()) return;
            reading = new ContextReading(
                    session,
                    agent,
                    mission,
                    model,
                    usage.usedTokens(),
                    ContextWindows.of(model),
                    Instant.now().toString());
            last.remove(session); // the most recent session last, the first to be forgotten the oldest
            last.put(session, new Kept(reading, usage.messageId()));
        }
        // A listener that fails never stops the session's stream from being read.
        for (Consumer<ContextReading> l : listeners) {
            try {
                l.accept(reading);
            } catch (RuntimeException e) {
                // Handled: logged; the other listeners still get the reading
                LOG.debugf("A context listener failed: %s", e.toString());
            }
        }
    }

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(ContextGauge.class);

    /** The session's last reading, while it is kept. */
    public Optional<ContextReading> last(String session) {
        synchronized (last) {
            return Optional.ofNullable(last.get(session)).map(Kept::reading);
        }
    }

    public AutoCloseable subscribe(Consumer<ContextReading> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
