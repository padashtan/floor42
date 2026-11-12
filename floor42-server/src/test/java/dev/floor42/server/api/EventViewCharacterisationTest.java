package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Company;
import dev.floor42.domain.GoldenJournals;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.golden.GoldenFile;
import dev.floor42.server.api.event.EnglishLines;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the UI receives for every event of the domain's golden journals, compared with what the code produced when the
 * master was recorded ({@code src/test/resources/golden/event-views.txt}; {@code -Dfloor42.golden.record=true}
 * records it again). Each event is rendered against the company as it stood right after it, as the live stream does.
 * A line seen earlier is written once. Its third column is the English line a replay file writes ({@link
 * EnglishLines}); the rest is the event view as the UI receives it.
 */
class EventViewCharacterisationTest {

    @Test
    void everyEventOfTheGoldenJournalsRendersAsRecorded() {
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        rendered().forEach((journal, views) -> {
            StringBuilder lines = new StringBuilder();
            for (EventView v : views) {
                String line = line(v);
                if (seen.add(line)) lines.append(line).append('\n');
            }
            if (!lines.isEmpty()) out.append("== ").append(journal).append('\n').append(lines);
        });
        assertThat(GoldenFile.check("event-views.txt", out.toString())).isNull();
    }

    private static Map<String, List<EventView>> rendered;

    /**
     * Every event of the golden journals as the UI receives it, journal by journal, then every hand-built variant
     * ({@link EventVariants}) rendered against an empty company.
     */
    static synchronized Map<String, List<EventView>> rendered() {
        if (rendered != null) return rendered;
        Map<String, List<EventView>> all = new LinkedHashMap<>();
        GoldenJournals.all().forEach((name, events) -> {
            List<EventView> views = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                views.add(EventView.of(Company.replay(events.subList(0, i + 1)), events.get(i)));
            }
            all.put(name, views);
        });
        Company empty = Company.replay(List.of());
        all.put(
                "variants",
                EventVariants.all().stream()
                        .map(e -> EventView.of(empty, envelope(e)))
                        .toList());
        rendered = all;
        return all;
    }

    private static EventEnvelope envelope(DomainEvent event) {
        return new EventEnvelope(
                "e", Instant.parse("2026-10-20T08:00:00Z"), Origin.REAL, Actor.director(), null, null, null, event);
    }

    private static String line(EventView v) {
        String line = v.type() + " | " + v.code() + " | " + EnglishLines.of(v.code(), v.params())
                + " | agent=" + v.agent() + " ticket=" + v.ticket() + " question=" + v.question()
                + " action=" + v.action() + " tool=" + v.tool() + " category=" + v.category()
                + " summary=" + v.summary() + " stage=" + v.stage() + " ran=" + v.ran()
                + " | " + v.origin() + " " + v.actor() + " " + v.mission()
                + " | " + v.params();
        return line.replace("\r", "\\r").replace("\n", "\\n");
    }
}
