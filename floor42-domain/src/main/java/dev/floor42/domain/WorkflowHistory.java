package dev.floor42.domain;

import dev.floor42.domain.workflow.CompanyWorkflow;
import dev.floor42.domain.workflow.FollowedVersion;
import dev.floor42.domain.workflow.MissionWorkflow;
import dev.floor42.domain.workflow.WorkflowDocument;
import dev.floor42.domain.workflow.WorkflowVersion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the company's saves tell, derived while the journal is applied and never written (ADR-0052): the versions of a
 * workflow name, and the version a mission's pin is a copy of. A version is the number of saves of a name since its
 * latest removal, from 1. Kept apart from the book so that all of it stays one field of it.
 */
final class WorkflowHistory {

    private record Save(long at, WorkflowVersion version) {}

    /** When a mission was pinned, and where the life of its workflow's name started then. */
    private record Pin(long at, int start) {}

    /** Every save by name key, in journal order. */
    private final Map<String, List<Save>> saves = new HashMap<>();
    /** Where the current life of a name starts in its saves: after its latest removal. */
    private final Map<String, Integer> since = new HashMap<>();

    private final Map<MissionId, Pin> pinMarks = new HashMap<>();
    /** Counts the saves, removals and pins in journal order, to tell what came before a pin. */
    private long clock;

    void saved(String name, int schemaVersion, Instant at, WorkflowDocument.Reading reading) {
        String key = CompanyWorkflow.key(name);
        List<Save> all = saves.computeIfAbsent(key, k -> new ArrayList<>());
        int number = all.size() - since.getOrDefault(key, 0) + 1;
        all.add(new Save(clock++, new WorkflowVersion(name, number, at, schemaVersion, reading)));
    }

    void removed(String name) {
        String key = CompanyWorkflow.key(name);
        since.put(key, saves.getOrDefault(key, List.of()).size());
        clock++;
    }

    void pinned(MissionId mission, MissionWorkflow pin) {
        int start = pin instanceof MissionWorkflow.Pinned p
                ? since.getOrDefault(CompanyWorkflow.key(p.workflow().name()), 0)
                : 0;
        pinMarks.put(mission, new Pin(clock++, start));
    }

    /** The saves of a name since its latest removal, oldest first. */
    List<WorkflowVersion> versions(String name) {
        if (name == null) return List.of();
        String key = CompanyWorkflow.key(name);
        List<Save> all = saves.getOrDefault(key, List.of());
        return all.subList(since.getOrDefault(key, 0), all.size()).stream()
                .map(Save::version)
                .toList();
    }

    /**
     * The latest save of the pinned name made before the pin, in the life of the name at the pin, whose workflow is the
     * pinned one (the writer is canonical, so equal workflows write equal documents). Removing the name afterwards does
     * not erase it: it is then reported as no longer current. Empty for a copy: never saved, edited on the way, or
     * saved only after the pin.
     */
    Optional<FollowedVersion> followed(MissionId mission, MissionWorkflow pin) {
        if (!(pin instanceof MissionWorkflow.Pinned p) || !pinMarks.containsKey(mission)) return Optional.empty();
        Pin at = pinMarks.get(mission);
        String key = CompanyWorkflow.key(p.workflow().name());
        List<Save> all = saves.getOrDefault(key, List.of());
        String pinned = WorkflowDocument.write(p.workflow());
        for (int i = all.size() - 1; i >= at.start(); i--) {
            Save s = all.get(i);
            if (s.at() < at.at()
                    && s.version().reading() instanceof WorkflowDocument.Reading.Read r
                    && WorkflowDocument.write(r.workflow()).equals(pinned)) {
                return Optional.of(new FollowedVersion(s.version(), since.getOrDefault(key, 0) == at.start()));
            }
        }
        return Optional.empty();
    }
}
