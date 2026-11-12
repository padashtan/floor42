package dev.floor42.server.runtime;

import dev.floor42.domain.Origin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a session did, as far as the runtime saw it. Counts come from Floor42's decisions; tokens are provided by the
 * runtime; the cost is an estimate.
 */
public record SessionReport(
        Origin origin,
        String sessionId,
        String model,
        int toolCalls,
        int allowed,
        int denied,
        int pending,
        List<String> notes,
        String finalText,
        Long inputTokensProvided,
        Long outputTokensProvided,
        Double costUsdEstimated,
        boolean failed) {
    public SessionReport {
        notes = notes == null ? null : Collections.unmodifiableList(new ArrayList<>(notes));
    }
}
