package dev.floor42.server.app;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a speaker reads during its turn at the director's meeting (ADR-0049): its own worktree of the mission when it
 * has one, else the worktree of the mission's first producer that has one (where the work is), else the mission's
 * repository. Read from the journal; a folder that is gone is skipped.
 */
final class MeetingFolders {

    private MeetingFolders() {}

    static String of(Company c, MissionId m, AgentId speaker) {
        return worktree(c, m, speaker)
                .or(() -> c.tickets().of(m).stream()
                        .map(Ticket::assignee)
                        .filter(Objects::nonNull)
                        .distinct()
                        .map(a -> worktree(c, m, a))
                        .flatMap(Optional::stream)
                        .findFirst())
                .orElseGet(() -> c.missions().find(m).orElseThrow().workspace());
    }

    private static Optional<String> worktree(Company c, MissionId m, AgentId agent) {
        return c.workshop().workspace(m, agent).map(Workspace::path).filter(p -> Files.isDirectory(Path.of(p)));
    }
}
