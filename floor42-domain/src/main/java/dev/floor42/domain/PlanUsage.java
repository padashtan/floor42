package dev.floor42.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The plan's usage windows (five hours, seven days) as Claude Code reports them during sessions: measured by the
 * runtime, read by Floor42. From consecutive readings Floor42 estimates what one session adds to a window, per model. A
 * reading whose window has reset since tells nothing about the new window: it is unknown, never zero.
 */
public final class PlanUsage {

    /**
     * @param utilization fraction of the window used, 0 to 1, as reported
     * @param status the runtime's verdict for the window ("allowed", "allowed_warning", "rejected"...)
     * @param session the Floor42 session record during which it was observed
     */
    public record Reading(
            String window, double utilization, Instant resetsAt, String status, String session, Instant observedAt) {

        public boolean current(Instant now) {
            return resetsAt.isAfter(now);
        }

        public boolean blocked() {
            return status != null && !status.startsWith("allowed");
        }
    }

    /** A session started now would likely be cut: the window, how full it is, what a session adds, when it resets. */
    public record Risk(String window, double utilization, Double expectedIncrease, Instant resetsAt) {}

    private final List<Reading> readings = new ArrayList<>();

    /** How the director reads a window's name. */
    public static String label(String window) {
        return switch (window) {
            case "five_hour" -> "5-hour";
            case "seven_day" -> "weekly";
            default -> window.replace('_', ' ');
        };
    }

    void add(Reading r) {
        readings.add(r);
    }

    /** The last reading of each window, in the order windows were first seen. */
    public Map<String, Reading> latest() {
        Map<String, Reading> last = new LinkedHashMap<>();
        for (Reading r : readings) last.put(r.window(), r);
        return last;
    }

    /**
     * Median increase of the window over one session of this model (any model when null). Claude Code reports the
     * windows as a session starts, so a reading shows the usage before its session: what a session used is the next
     * session's first reading minus its own first reading, when both belong to the same window period. Empty without
     * such a pair. An estimate: use outside Floor42 between the two readings counts too.
     */
    public Optional<Double> expectedIncrease(String window, String model, Function<String, String> modelOfSession) {
        // The first reading of each session, in order.
        List<Reading> firsts = new ArrayList<>();
        for (Reading r : readings) {
            if (!r.window().equals(window)) continue;
            if (firsts.isEmpty() || !firsts.getLast().session().equals(r.session())) firsts.add(r);
        }
        List<Double> deltas = new ArrayList<>();
        for (int i = 0; i + 1 < firsts.size(); i++) {
            Reading start = firsts.get(i);
            Reading next = firsts.get(i + 1);
            if (!start.resetsAt().equals(next.resetsAt())) continue; // the window reset in between
            if (model != null && !model.equals(modelOfSession.apply(start.session()))) continue;
            deltas.add(Math.max(0, next.utilization() - start.utilization()));
        }
        if (deltas.isEmpty()) return Optional.empty();
        List<Double> sorted = deltas.stream().sorted().toList();
        int n = sorted.size();
        return Optional.of(n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2);
    }

    /**
     * The first current window that a session of this model would likely exhaust: already refused by the runtime, or
     * its utilization plus the expected increase reaching the threshold. Unknown windows are no risk to report.
     */
    public Optional<Risk> risk(String model, double threshold, Instant now, Function<String, String> modelOfSession) {
        for (Reading r : latest().values()) {
            if (!r.current(now)) continue;
            // This model's history first; without it, any model's, rather than nothing.
            Double expected = expectedIncrease(r.window(), model, modelOfSession)
                    .or(() -> expectedIncrease(r.window(), null, modelOfSession))
                    .orElse(null);
            double projected = r.utilization() + (expected == null ? 0 : expected);
            if (r.blocked() || projected >= threshold) {
                return Optional.of(new Risk(r.window(), r.utilization(), expected, r.resetsAt()));
            }
        }
        return Optional.empty();
    }
}
