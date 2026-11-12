package dev.floor42.server.api;

import dev.floor42.domain.Origin;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.journal.BackupStore;
import dev.floor42.server.journal.JournalBackups;
import dev.floor42.server.journal.JournalContinuity;
import dev.floor42.server.journal.PendingRestore;
import dev.floor42.server.journal.SqliteJournal;
import dev.floor42.server.journal.Unreadable;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The journal's health and its copies (ADR-0042): the rows replay left out, the automatic backups, and a restore
 * the director prepares, applied when the daemon stops. Never a payload, never a file swapped under the live journal.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class JournalResource {

    /**
     * @param reason CORRUPT, NEWER_VERSION (written by a newer Floor42: upgrade) or NOT_APPLICABLE
     * @param detail what failed, naming fields, never stored values
     */
    public record UnreadableEventView(
            long sequence, String eventId, String type, String mission, String reason, String detail) {}

    /** @param events the events the copy holds, null when it cannot be read */
    public record BackupView(String name, String kind, long sizeBytes, String createdAt, Long events) {}

    /**
     * @param keptAs the copy of the journal that was replaced, null when there was none
     * @param at when the stop that applied it ran
     * @param matchesJournal a restore reported done is consistent with the journal and the copies; null for one
     *     reported not applied. The whole outcome is reported by a file of the data folder, which a process of the
     *     daemon's OS user could write: never a proof
     */
    public record RestoreOutcomeView(
            boolean restored,
            String backup,
            Long events,
            String keptAs,
            String reason,
            String at,
            Boolean matchesJournal) {}

    /**
     * The journal does not continue what the last run saw (ADR-0044, amended): fewer events, or the last row it saw
     * missing or different; or nothing says what that run saw. Detection only: the record is a file of the data folder.
     *
     * @param reason SHORTER, DIVERGED, MISSING (no record beside a journal that holds events: a deleted record),
     *     FIRST_CHECK (the same on the first check since a Floor42 before 1.8.0: shown as a notice, a hint that a
     *     forger with the journal's rights could also produce), UNREADABLE (a record that cannot be read) or
     *     ANCHOR_FAILED (the check could not be made this run: foundEvents and foundLastSequence are then null)
     * @param recordedEvents what the last run saw; null for the last four, as are the other recorded fields
     * @param displaced a copy of the journal the last run held, kept by its stop when the file was replaced under it
     * @param origin REAL: compared on the files of this data folder, never simulated
     */
    public record ContinuityBreakView(
            String reason,
            Long recordedEvents,
            Long recordedLastSequence,
            String recordedAt,
            Long foundEvents,
            Long foundLastSequence,
            String displaced,
            Origin origin) {}

    /**
     * A file in the data folder that asked this start to restore the journal: refused, never applied (only the
     * director prepares a restore, applied when the daemon stops). A process of the daemon's OS user wrote it.
     *
     * @param backup the copy it named, empty when unreadable
     * @param modifiedAt when it was written, null when unknown
     * @param keptAs where it was moved aside in the data folder, null when it could not be
     */
    public record RefusedRestoreView(String backup, String modifiedAt, String keptAs) {}

    /**
     * Another process holds the journal file: appends fail after SQLite's busy timeout.
     *
     * @param since the last append that failed, since the last one that succeeded
     */
    public record JournalLockedView(String since, String detail) {}

    /**
     * What the journal banner shows besides the rows left out; null or empty when all is well.
     *
     * @param configFiles configuration files read from outside the daemon's own build (for instance through
     *     QUARKUS_CONFIG_LOCATIONS): the director's choice, said so they can check no agent can write them
     */
    public record JournalAlertsView(
            RefusedRestoreView refusedRestore,
            JournalLockedView locked,
            ContinuityBreakView continuity,
            List<String> configFiles) {
        public JournalAlertsView {
            configFiles = configFiles == null ? null : Collections.unmodifiableList(new ArrayList<>(configFiles));
        }
    }

    /**
     * @param disabledReason why no copy is made, null when they are
     * @param disabledCode OFF, IN_AGENT_WORKTREES or IN_GIT_WORKTREE: the code of the reason, null when copies are made
     * @param disabledPath the folder the code speaks of, null for OFF
     * @param lastError the last copy that failed, null since one succeeded
     * @param pendingRestore the copy this daemon restores when it stops, null when none (kept in memory only)
     * @param lastRestore what the last stop did with the restore the director prepared, null when none
     * @param refusedRestore a file that asked this start for a restore, refused; null when none
     * @param restoreLimitBytes the heaviest copy this daemon restores from memory; a heavier one is restored by hand
     */
    public record BackupsView(
            String folder,
            boolean enabled,
            String disabledReason,
            String disabledCode,
            String disabledPath,
            int startupKeep,
            int dailyKeep,
            int manualKeep,
            String lastError,
            String pendingRestore,
            RestoreOutcomeView lastRestore,
            RefusedRestoreView refusedRestore,
            long restoreLimitBytes,
            List<BackupView> backups) {
        public BackupsView {
            backups = backups == null ? null : Collections.unmodifiableList(new ArrayList<>(backups));
        }
    }

    public record RestoreRequest(String name) {}

    @Inject
    OfficeService office;

    @Inject
    JournalBackups backups;

    @Inject
    SqliteJournal journal;

    @Inject
    dev.floor42.server.security.ConfigGuard configGuard;

    @GET
    @Path("/journal/unreadable")
    public List<UnreadableEventView> unreadable() {
        return office.unreadable().stream().map(JournalResource::view).toList();
    }

    @GET
    @Path("/journal/alerts")
    public JournalAlertsView alerts() {
        return new JournalAlertsView(
                view(backups.state().refusedRestore()),
                journal.locked()
                        .map(l -> new JournalLockedView(l.since().toString(), l.detail()))
                        .orElse(null),
                journal.continuityBreak().map(JournalResource::view).orElse(null),
                configGuard.foreignConfigFiles());
    }

    static ContinuityBreakView view(JournalContinuity.Break b) {
        JournalContinuity.Mark r = b.recorded();
        return new ContinuityBreakView(
                b.reason(),
                r == null ? null : r.events(),
                r == null ? null : r.lastSequence(),
                r == null ? null : r.at().toString(),
                b.foundEvents() < 0 ? null : b.foundEvents(),
                b.foundLastSequence() < 0 ? null : b.foundLastSequence(),
                r == null ? null : r.displaced(),
                Origin.REAL);
    }

    /** The director has seen the break: the banner goes, and the record follows the journal again. */
    @POST
    @Path("/journal/alerts/continuity/acknowledge")
    public JournalAlertsView acknowledgeContinuity() {
        journal.acknowledgeContinuity();
        return alerts();
    }

    @GET
    @Path("/backups")
    public BackupsView backups() {
        JournalBackups.State s = backups.state();
        return new BackupsView(
                s.folder(),
                s.enabled(),
                s.disabledReason(),
                s.disabledCode(),
                s.disabledPath(),
                s.startupKeep(),
                s.dailyKeep(),
                s.manualKeep(),
                s.lastError(),
                s.pendingRestore(),
                view(s.lastRestore()),
                view(s.refusedRestore()),
                backups.restoreLimitBytes(),
                s.backups().stream().map(JournalResource::view).toList());
    }

    @POST
    @Path("/backups")
    public BackupsView backUpNow() {
        try {
            if (backups.take(BackupStore.Kind.MANUAL).isEmpty()) {
                throw RefusedException.conflict(
                        "BACKUP_FAILED",
                        backups.state().lastError() == null
                                ? "No journal to copy yet"
                                : backups.state().lastError());
            }
        } catch (IllegalStateException e) {
            throw RefusedException.conflict("BACKUPS_DISABLED", e.getMessage(), e);
        }
        return backups();
    }

    @PUT
    @Path("/backups/restore")
    public BackupsView prepareRestore(RestoreRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw RefusedException.invalid("MISSING_NAME", "Name the backup to restore");
        }
        try {
            backups.prepareRestore(request.name());
        } catch (IllegalArgumentException e) {
            throw new jakarta.ws.rs.NotFoundException(e.getMessage(), e);
        } catch (IOException | SQLException e) {
            throw RefusedException.conflict(
                    "BACKUP_UNREADABLE", "The backup " + request.name() + " cannot be restored: " + e.getMessage(), e);
        }
        return backups();
    }

    @DELETE
    @Path("/backups/restore")
    public BackupsView cancelRestore() {
        backups.cancelRestore();
        return backups();
    }

    private static UnreadableEventView view(Unreadable u) {
        return new UnreadableEventView(
                u.sequence(), u.eventId(), u.type(), u.mission(), u.reason().name(), u.detail());
    }

    private static BackupView view(BackupStore.Backup b) {
        return new BackupView(
                b.name(), b.kind().name(), b.sizeBytes(), b.createdAt().toString(), b.events() < 0 ? null : b.events());
    }

    private static RestoreOutcomeView view(PendingRestore.Outcome o) {
        if (o == null) return null;
        return new RestoreOutcomeView(
                o.restored(),
                o.backup(),
                o.events() < 0 ? null : o.events(),
                o.keptAs(),
                o.reason(),
                o.at().toString(),
                o.matchesJournal());
    }

    private static RefusedRestoreView view(PendingRestore.Refused r) {
        if (r == null) return null;
        return new RefusedRestoreView(
                r.backup(), r.modifiedAt() == null ? null : r.modifiedAt().toString(), r.keptAs());
    }
}
