package dev.floor42.server.journal;

import dev.floor42.server.config.DataFolders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * Keeps {@link JournalContinuity}'s record bound to the journal file this daemon opened (ADR-0044, amendment 3).
 *
 * <p>The pool's connections do not stay bound to it: Agroal closes the idle ones, and the next one opens whatever file
 * then has the journal's name. After a file was moved over {@code floor42.db} and five idle minutes, every measure
 * taken through the pool read the forged file, and the stop rewrote the record from it. So the record is measured
 * through an <b>anchor</b>: a read-only connection opened at start and held until the stop, outside the pool. On Linux
 * it keeps reading the file it opened whatever takes the name; the stop copies it as {@code displaced-*} when the name
 * now leads to another file. And a record is only ever rewritten from a journal that continues the last one written,
 * except after a restore the director prepared or their acknowledgement.
 */
final class ContinuityKeeper implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ContinuityKeeper.class);

    private final Path dataDir;
    private final Path journal;
    private final Function<Path, Object> identity;
    private final long markEveryMillis;
    private final Object openedKey;
    private final Connection anchor;
    private final boolean firstCheck;

    private JournalContinuity.Mark lastWritten;
    private JournalContinuity.Break broken;
    private boolean held;
    private boolean replaced;
    private long lastMarkAt;

    /**
     * @param identity a file's identity (its inode on Linux; null where the system gives none, Windows, where a file
     *     SQLite holds cannot be replaced)
     * @param firstCheck no check ended yet since the journal was opened by a Floor42 before 1.8.0 (a hint for the
     *     presentation, see {@link #atStart()}; never trusted to excuse anything)
     */
    ContinuityKeeper(Path dataDir, Function<Path, Object> identity, long markEveryMillis, boolean firstCheck)
            throws SQLException {
        this.firstCheck = firstCheck;
        this.dataDir = dataDir;
        this.journal = dataDir.resolve(DataFolders.JOURNAL);
        this.identity = identity;
        this.markEveryMillis = markEveryMillis;
        this.openedKey = identity.apply(journal);
        this.anchor = BackupStore.anchor(journal);
        if (openedKey != null && !openedKey.equals(identity.apply(journal))) {
            anchor.close();
            throw new SQLException("the journal file was replaced while its anchor was opened");
        }
    }

    /** A file's identity: its inode on Linux, null on Windows or when it cannot be read. */
    static Object fileKey(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            // Handled: no identity: logged at debug, the caller gets null
            LOG.debugf("No identity for %s: %s", file, e);
            return null;
        }
    }

    /**
     * Compares the journal with what the last run recorded. A break is reported, never refused, and the record is then
     * held as it was until the director acknowledges it, so every start reports it again. A data folder whose journal
     * holds no event and has no record is a fresh one: nothing to compare. A missing record on the very first check of
     * a journal an older Floor42 last opened is FIRST_CHECK, shown as a notice: still held until acknowledged.
     */
    synchronized void atStart() throws SQLException, IOException {
        JournalContinuity.Mark now = JournalContinuity.measure(anchor, Instant.now());
        Optional<JournalContinuity.Mark> recorded;
        try {
            recorded = JournalContinuity.read(dataDir);
        } catch (IOException unreadable) {
            // Handled: the record is unreadable: the banner says so (UNREADABLE) and the log too
            hold(new JournalContinuity.Break("UNREADABLE", null, now.events(), now.lastSequence()), null);
            LOG.errorf("The journal's continuity record cannot be read (%s)", unreadable.getMessage());
            return;
        }
        if (recorded.isEmpty() && now.events() > 0) {
            String reason = firstCheck ? JournalContinuity.FIRST_CHECK : "MISSING";
            hold(new JournalContinuity.Break(reason, null, now.events(), now.lastSequence()), null);
            return;
        }
        Optional<JournalContinuity.Break> b =
                recorded.isEmpty() ? Optional.empty() : JournalContinuity.check(recorded.get(), anchor);
        if (b.isPresent()) hold(b.get(), recorded.get());
        else write(now, null);
    }

    private void hold(JournalContinuity.Break b, JournalContinuity.Mark recorded) {
        broken = b;
        held = true;
        lastWritten = recorded;
        JournalContinuity.Mark r = b.recorded();
        if (r == null) {
            LOG.errorf(unrecordedText(b), JournalContinuity.FILE, b.reason(), b.foundEvents());
            return;
        }
        LOG.errorf(
                "The journal does not continue the one the last run saw (%s): it saw %d events up to #%d at %s, the"
                        + " journal holds %d up to #%d. A copy may have been moved over %s. Nothing is refused;"
                        + " Settings and the banner say so until the director acknowledges it.%s",
                b.reason(),
                r.events(),
                r.lastSequence(),
                r.at(),
                b.foundEvents(),
                b.foundLastSequence(),
                journal,
                r.displaced() == null ? "" : " The journal the last run held is kept as " + r.displaced());
    }

    /** What the log says when no record says what the last run saw; each reason has its own explanation. */
    private static String unrecordedText(JournalContinuity.Break b) {
        String head = "Nothing says what the last run saw of the journal (%s %s): the journal holds %d events, and"
                + " whether it went back in time cannot be told. ";
        return head
                + switch (b.reason()) {
                    case JournalContinuity.FIRST_CHECK ->
                        "Expected once: this is the first start of Floor42 1.8.0 on this journal, and the check begins"
                                + " now. The notice says so until the director acknowledges it";
                    case "UNREADABLE" ->
                        "The record exists but cannot be read: something other than Floor42 rewrote it. The banner says"
                                + " so until the director acknowledges it";
                    default ->
                        "The record was deleted or never written: this is not the first start of Floor42 1.8.0. The"
                                + " banner says so until the director acknowledges it";
                };
    }

    synchronized Optional<JournalContinuity.Break> broken() {
        return Optional.ofNullable(broken);
    }

    /** The director has seen the break: the record follows the journal this daemon opened, as it is now. */
    synchronized void acknowledge() throws SQLException, IOException {
        held = false;
        broken = null;
        write(JournalContinuity.measure(anchor, Instant.now()), null);
    }

    /** After appends, at most every {@code markEveryMillis}: a failure is logged, the next append tries again. */
    synchronized void markSoon() {
        if (held || System.currentTimeMillis() - lastMarkAt < markEveryMillis) return;
        try {
            replaced();
            continuing(null);
        } catch (SQLException | IOException e) {
            // Handled: logged; the record is written again soon
            LOG.warnf("Continuity record not written: %s", e);
        }
    }

    /** The journal's name now leads to another file than the one this daemon opened. Sticky, logged once. */
    synchronized boolean replaced() {
        if (!replaced && openedKey != null && !openedKey.equals(identity.apply(journal))) {
            replaced = true;
            LOG.errorf(
                    "The journal file %s was replaced while Floor42 ran: the continuity record follows the file this"
                            + " daemon opened, and its stop keeps that one as displaced-*",
                    journal);
        }
        return replaced;
    }

    /**
     * When the journal file was replaced: the journal this daemon opened, copied through the anchor as {@code
     * displaced-*} (never rotated); null when it was not, or the copy failed (logged).
     */
    synchronized String keepDisplaced(BackupStore store) {
        if (!replaced()) return null;
        try {
            BackupStore.Backup kept = store.takeThrough(anchor, BackupStore.Kind.DISPLACED);
            LOG.errorf(
                    "The journal file was replaced while Floor42 ran: the journal it opened (%d events) is kept as %s."
                            + " The next start reads the file now in place; restore %s from Settings if that one is"
                            + " not yours",
                    kept.events(), kept.name(), kept.name());
            return kept.name();
        } catch (IOException | SQLException | RuntimeException e) {
            // Handled: logged; the replaced journal could not be kept
            LOG.errorf("The journal file was replaced while Floor42 ran, and the one it opened cannot be kept: %s", e);
            return null;
        }
    }

    /**
     * At the stop. After a restore the journal starts a new line; a break still waiting for the director keeps its
     * record, with the copy kept by this stop added.
     */
    synchronized void atStop(String displaced, boolean restored) throws SQLException, IOException {
        if (restored) {
            held = false;
            broken = null;
            write(JournalContinuity.measure(anchor, Instant.now()), null);
        } else if (!held) {
            continuing(displaced);
        } else if (displaced != null) {
            // Without a record (MISSING, UNREADABLE), what this daemon opened is the line the next start compares with.
            write(lastWritten != null ? lastWritten : JournalContinuity.measure(anchor, Instant.now()), displaced);
        }
    }

    /** Writes the record only from a journal that continues the last one written; otherwise holds the break. */
    private void continuing(String displaced) throws SQLException, IOException {
        if (lastWritten != null) {
            Optional<JournalContinuity.Break> b = JournalContinuity.check(lastWritten, anchor);
            if (b.isPresent()) {
                hold(b.get(), lastWritten);
                return;
            }
        }
        write(JournalContinuity.measure(anchor, Instant.now()), displaced);
    }

    private void write(JournalContinuity.Mark m, String displaced) throws IOException {
        JournalContinuity.Mark mark = displaced == null
                ? m
                : new JournalContinuity.Mark(m.events(), m.lastSequence(), m.lastRowHash(), m.at(), displaced);
        write(mark);
    }

    private void write(JournalContinuity.Mark mark) throws IOException {
        JournalContinuity.write(dataDir, mark);
        lastWritten = mark;
        lastMarkAt = System.currentTimeMillis();
    }

    @Override
    public synchronized void close() {
        try {
            anchor.close();
        } catch (SQLException e) {
            // Handled: logged; the anchor is dropped with the pool
            LOG.warnf("The journal's anchor did not close: %s", e);
        }
    }
}
