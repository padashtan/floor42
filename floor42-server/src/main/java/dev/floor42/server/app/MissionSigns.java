package dev.floor42.server.app;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.EventEnvelope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stall clock: when something last happened to each mission (an event of it, a task of it starting or ending), in
 * memory. Before any sign is seen it starts with the daemon, so a restart never reports a stall at once.
 */
@ApplicationScoped
public class MissionSigns {

    private final OfficeService office;
    private final OfficeSettings settings;
    private final Map<MissionId, Instant> lastSign = new ConcurrentHashMap<>();
    private final Instant startedAt = Instant.now();

    @Inject
    MissionSigns(OfficeService office, OfficeSettings settings) {
        this.office = office;
        this.settings = settings;
    }

    /** Every stored event is a sign of its mission. */
    void listen() {
        office.subscribe(this::observe);
    }

    /** Runs under the office monitor: records only. */
    private void observe(List<EventEnvelope> events) {
        for (EventEnvelope e : events) {
            if (e.mission() != null) seen(e.mission(), e.occurredAt());
        }
    }

    void mark(MissionId m) {
        seen(m, Instant.now());
    }

    private void seen(MissionId m, Instant at) {
        lastSign.merge(m, at, (a, b) -> a.isAfter(b) ? a : b);
    }

    /** Where the stall clock of this mission starts. */
    Instant since(MissionId m) {
        Instant last = lastSign.get(m);
        return last != null && last.isAfter(startedAt) ? last : startedAt;
    }

    /** How long a mission may show no sign before it is stuck: the stall setting. */
    Duration patience() {
        return Duration.ofMinutes(settings.stallMinutes());
    }
}
