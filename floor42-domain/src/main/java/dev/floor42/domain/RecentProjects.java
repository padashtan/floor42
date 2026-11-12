package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.RecentProjectEvent;
import dev.floor42.domain.event.RecentProjectEvent.ProjectForgotten;
import dev.floor42.domain.event.RecentProjectEvent.ProjectPinned;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The director's pins and forgets on the recent projects list (ADR-0038), by project path. */
final class RecentProjects {

    private final CompanyView view;
    private final Map<String, ProjectMark> projectMarks = new LinkedHashMap<>();

    RecentProjects(CompanyView view) {
        this.view = view;
    }

    Optional<ProjectMark> projectMark(String path) {
        return Optional.ofNullable(projectMarks.get(path));
    }

    /** Forgotten, not pinned since, and no mission created on it since. */
    boolean projectForgotten(String path) {
        ProjectMark mark = projectMarks.get(path);
        if (mark == null || mark.forgottenAt == null) return false;
        return view.missions().stream()
                .filter(m -> path.equals(m.workspace()))
                .noneMatch(m -> m.createdAt().isAfter(mark.forgottenAt));
    }

    List<EventEnvelope> pin(CommandContext ctx, String path) {
        return mark(ctx, new ProjectPinned(path, true));
    }

    List<EventEnvelope> unpin(CommandContext ctx, String path) {
        return mark(ctx, new ProjectPinned(path, false));
    }

    /** Pinning clears a forget and forgetting unpins: the pin alone says whether anything changes. */
    private List<EventEnvelope> mark(CommandContext ctx, ProjectPinned wanted) {
        requireDirector(ctx);
        known(wanted.path());
        ProjectMark mark = projectMarks.get(wanted.path());
        if ((mark != null && mark.pinned) == wanted.pinned()) return List.of();
        return view.record(ctx, null).emit(wanted).events();
    }

    List<EventEnvelope> forgetProject(CommandContext ctx, String path) {
        requireDirector(ctx);
        known(path);
        if (projectForgotten(path)) return List.of();
        return view.record(ctx, null).emit(new ProjectForgotten(path)).events();
    }

    private void known(String path) {
        ProjectPath project = new ProjectPath(path);
        require(
                view.missions().stream()
                        .anyMatch(m ->
                                m.origin() == Origin.REAL && project.value().equals(m.workspace())),
                Refusal.UNKNOWN_PROJECT,
                "No real mission worked on " + path);
    }

    /** The only way this area's state changes. */
    void apply(RecentProjectEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        switch (event) {
            case ProjectPinned e -> on(e);
            case ProjectForgotten e -> on(e, at);
        }
    }

    void on(ProjectPinned e) {
        ProjectMark mark = projectMarks.computeIfAbsent(e.path(), ProjectMark::new);
        mark.pinned = e.pinned();
        if (e.pinned()) mark.forgottenAt = null;
    }

    void on(ProjectForgotten e, Instant at) {
        ProjectMark mark = projectMarks.computeIfAbsent(e.path(), ProjectMark::new);
        mark.pinned = false;
        mark.forgottenAt = at;
    }
}
