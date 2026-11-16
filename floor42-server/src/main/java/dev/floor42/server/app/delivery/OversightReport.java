package dev.floor42.server.app.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The human oversight report of one mission (ADR-0075): the journal's facts grouped under four questions (who
 * supervised, what the agents could do, what they did, what humans overrode), each line naming its events. Every text
 * has been through the replay export's sanitizer. No unmasked form exists.
 */
public record OversightReport(
        int format,
        String generator,
        String version,
        String generatedAt,
        String statement,
        MissionPart mission,
        Section supervision,
        Section capabilities,
        Section actions,
        Section overrides,
        List<Mask> masked,
        List<String> limits) {

    /** A list capped at {@link OversightComposer#CAP}; {@code count} is always the full one. */
    public record Section(int count, List<Line> items) {}

    /** {@code seq} numbers the mission's events from 1, as the replay export does; null for a company-wide event. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventRef(Integer seq, String eventId, String type) {}

    /**
     * @param kind a constant the page translates
     * @param actor who caused the first event of the line (the director, an agent id, or floor42)
     * @param scope what the line is about (a tool, a category, COMPANY); null when nothing narrows it
     * @param code an outcome or a decision as a constant the page translates; null when there is none
     * @param origin REAL or SIMULATED, from the first event
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(
            String at,
            String kind,
            String actor,
            String scope,
            String text,
            String code,
            String basis,
            String origin,
            List<EventRef> events) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MissionPart(
            String id, String title, String origin, String status, int events, int real, int simulated) {}

    public record Mask(String kind, int count) {}
}
