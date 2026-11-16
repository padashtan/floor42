package dev.floor42.server.journal;

import dev.floor42.server.security.LogSafe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * What the daemon knows of its journal's hash chain (ADR-0079): the result of the last verification, the signed
 * checkpoints, and whether appends are refused. It signs a checkpoint every N rows, after rows were chained after the
 * fact, and at shutdown; it verifies at start and when the director asks.
 */
public final class JournalIntegrity {

    private static final Logger LOG = Logger.getLogger(JournalIntegrity.class);

    public static final String OK = "OK";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String BROKEN = "BROKEN";
    public static final String UNCHECKED = "UNCHECKED";
    public static final String CHECKPOINT_SIGNATURE = "CHECKPOINT_SIGNATURE";
    public static final String CHECKPOINT_MISMATCH = "CHECKPOINT_MISMATCH";
    /** The chain at the last checkpoint's sequence is not what the checkpoint file outside the database remembers. */
    public static final String CHECKPOINT_ANCHOR = "CHECKPOINT_ANCHOR";
    /** The signing key is missing (or is not the one that signed) while checkpoints exist. */
    public static final String CHECKPOINT_KEY = "CHECKPOINT_KEY";

    public static final String UNCHAINED_TAIL = JournalChain.UNCHAINED_TAIL;

    /**
     * The last checkpoint kept outside the database (sequence, hash, key id): a forger who rewrites rows, recomputes
     * the keyless links and deletes the checkpoints from the database still has to rewrite this file too. Detection
     * only: the file sits in the data folder, like the continuity record (ADR-0044).
     */
    public static final String ANCHOR_FILE = JournalAnchor.FILE;

    /** @param check VERIFIED, INVALID (bad signature), MISMATCH (the chain has another hash there) or OTHER_KEY */
    public record Checkpoint(
            long sequence, String hash, String signedAt, String kind, String keyId, String signature, String check) {}

    /**
     * @param status OK, ACKNOWLEDGED (every break found was acknowledged), BROKEN or UNCHECKED (no check could be made)
     * @param brokenSequence the first row that fails, null when none
     * @param reason a {@link JournalChain} failure, or CHECKPOINT_SIGNATURE or CHECKPOINT_MISMATCH
     * @param pending rows after the chain's end: chained at the next start or append
     * @param keyProblem why no checkpoint can be signed or verified this run; null when the key is fine
     */
    public record Status(
            String status,
            Long brokenSequence,
            String reason,
            long rows,
            long chained,
            long adopted,
            long pending,
            int acknowledged,
            int checkpoints,
            int verified,
            int invalid,
            int otherKey,
            String keyId,
            String keyProblem,
            Checkpoint last,
            boolean writesRefused,
            String checkedAt) {}

    private final DataSource dataSource;
    private final Path dataDir;
    private final int every;
    private JournalSigningKey key;
    private String keyProblem;
    private long lastCheckpointSequence;
    private volatile Status status;
    /** What the first failure of the last verification looked like: an acknowledgement covers that state only. */
    private String lastObserved;

    JournalIntegrity(DataSource dataSource, Path dataDir, int every) {
        this.dataSource = dataSource;
        this.dataDir = dataDir;
        this.every = every > 0 ? every : 1000;
    }

    /** Chains what is not chained yet, signs the anchor when rows were adopted, then verifies. */
    synchronized void start() {
        loadKey();
        long adopted = chainPending();
        if (adopted > 0) {
            // The only place rows are adopted: at a start, said in the log, anchored by a signed checkpoint
            LOG.warnf(
                    "%d journal rows had no link in the hash chain: adopted at this start (ADOPTION checkpoint)",
                    adopted);
            checkpoint("ADOPTION");
        }
        verifyAll();
    }

    private void loadKey() {
        try {
            if (!JournalSigningKey.exists(dataDir) && checkpointsWitnessed()) {
                // A key that vanished beside checkpoints is not replaced: a new one would turn a deletion into "other
                // key"
                keyProblem = "KEY_MISSING";
                LOG.error("The journal's signing key is missing while checkpoints exist: no new key is made");
            } else {
                key = JournalSigningKey.loadOrCreate(dataDir);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // Handled: logged; no checkpoint is signed or verified this run, the chain still is
            keyProblem = e.getClass().getSimpleName();
            LOG.errorf("The journal's signing key cannot be used: %s", LogSafe.of(e));
        }
    }

    /** @return the rows adopted; 0 when none or when the chain could not be prepared (logged) */
    private long chainPending() {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long adopted = JournalChain.chainPending(c, true);
                c.commit();
                lastCheckpointSequence = scalar(c, "SELECT coalesce(max(sequence), 0) FROM journal_checkpoint");
                c.commit();
                return adopted;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException | RuntimeException e) {
            // Handled: logged; the status says no check was made, writes are not refused on a check that did not run
            LOG.errorf("The journal's chain cannot be prepared: %s", LogSafe.of(e));
            return 0;
        }
    }

    public Status status() {
        return status;
    }

    boolean refusing() {
        Status s = status;
        return s != null && s.writesRefused();
    }

    /** True when the database or the anchor file remembers a checkpoint (so a missing key is a loss, not a first run). */
    private boolean checkpointsWitnessed() {
        if (JournalAnchor.exists(dataDir)) return true;
        try (Connection c = dataSource.getConnection()) {
            return scalar(c, "SELECT count(*) FROM journal_checkpoint") > 0;
        } catch (SQLException e) {
            // Handled: unreadable counts as "there are checkpoints", the safe side
            return true;
        }
    }

    /**
     * Before an append, inside its transaction: rows after the chain's end at this point were written by another
     * program while the daemon runs. They are never adopted silently: the chain is BROKEN (UNCHAINED_TAIL) and the
     * append refused, unless the director acknowledged exactly that tail.
     */
    synchronized void guardTail(Connection c) throws SQLException {
        long head = JournalChain.head(c).sequence();
        long tail = scalar(c, "SELECT count(*) FROM event_journal WHERE sequence > " + head);
        if (tail == 0) return;
        Status s = verifyAll();
        if (s.writesRefused()) {
            throw new JournalChainBrokenException(
                    "The journal's hash chain is broken: a row was written outside Floor42."
                            + " Look at GET /api/journal/integrity, then acknowledge it as the director");
        }
        LOG.warnf("%d journal rows after the chain's end were acknowledged by the director: adopted", tail);
        JournalChain.chainPending(c, true);
    }

    /** The anchor of a journal that was restored belongs to the journal before it. */
    synchronized void forgetAnchor() {
        try {
            JournalAnchor.forget(dataDir);
        } catch (IOException e) {
            // Handled: logged; the next start reports the anchor if it does not fit
            LOG.warnf("The journal's checkpoint anchor could not be removed after a restore: %s", LogSafe.of(e));
        }
    }

    /** Signs a checkpoint when {@code every} rows were appended since the last one. */
    synchronized void afterAppend() {
        try (Connection c = dataSource.getConnection()) {
            if (JournalChain.head(c).sequence() - lastCheckpointSequence >= every) checkpoint("PERIODIC");
        } catch (SQLException e) {
            // Handled: logged; the next append tries again
            LOG.warnf("The journal's checkpoint could not be considered: %s", LogSafe.of(e));
        }
    }

    /** At shutdown; nothing is signed twice for one sequence. */
    synchronized void checkpoint(String kind) {
        if (key == null) return;
        try (Connection c = dataSource.getConnection()) {
            JournalChain.Head h = JournalChain.head(c);
            if (h.sequence() == 0 || h.sequence() == lastCheckpointSequence) return;
            String at = Instant.now().toString();
            String sig = key.sign(message(h.sequence(), h.hash(), at, key.keyId()));
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO journal_checkpoint"
                    + " (sequence, hash, signed_at, key_id, signature, kind) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, h.sequence());
                ps.setString(2, h.hash());
                ps.setString(3, at);
                ps.setString(4, key.keyId());
                ps.setString(5, sig);
                ps.setString(6, kind);
                ps.executeUpdate();
            }
            lastCheckpointSequence = h.sequence();
            JournalAnchor.write(dataDir, h.sequence(), h.hash(), key.keyId());
        } catch (SQLException | GeneralSecurityException | IOException e) {
            // Handled: logged; a checkpoint is a proof added on top, not a condition of the write
            LOG.warnf("A journal checkpoint was not signed: %s", LogSafe.of(e));
        }
    }

    /** What a checkpoint's signature covers: its sequence, hash, time and key, one per line. */
    static byte[] message(long sequence, String hash, String signedAt, String keyId) {
        return ("floor42.journal.checkpoint.v1\n" + sequence + "\n" + hash + "\n" + signedAt + "\n" + keyId)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The director has seen the first break: writes resume, the status keeps naming it as acknowledged. */
    synchronized Status acknowledge() {
        Status s = status;
        if (s == null || !BROKEN.equals(s.status())) return s;
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("INSERT INTO journal_chain_ack"
                        + " (broken_sequence, reason, acknowledged_at, observed) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, s.brokenSequence());
            ps.setString(2, s.reason());
            ps.setString(3, Instant.now().toString());
            ps.setString(4, lastObserved == null ? "" : lastObserved);
            ps.executeUpdate();
            LOG.warnf(
                    "The director acknowledged a break in the journal's chain at sequence %d (%s)",
                    s.brokenSequence(), s.reason());
        } catch (SQLException e) {
            throw new JournalException("Cannot record the acknowledgement of the journal's chain", e);
        }
        return verifyAll();
    }

    /** Recomputes the chain and checks the checkpoints; the result is kept as the status. */
    synchronized Status verifyAll() {
        try (Connection c = dataSource.getConnection()) {
            JournalChain.Accepted accepted = accepted(c);
            JournalChain.Verdict v = JournalChain.verify(c, accepted);
            Findings f = new Findings(v.firstBroken(), v.reason(), v.acknowledged(), v.observed());
            if (v.pending() > 0) {
                // At start the pending rows were adopted before this ran: any left were written by someone else
                f.fail(v.firstPending(), UNCHAINED_TAIL, "tail:" + v.pending(), accepted);
            }
            List<Checkpoint> checked = new ArrayList<>();
            for (Checkpoint cp : checkpoints(c)) checked.add(f.add(cp, check(c, cp), accepted));
            anchorFindings(c, f, accepted);
            lastObserved = f.observed;
            return status = status(f, v, checked);
        } catch (SQLException | RuntimeException e) {
            // Handled: logged; nothing is claimed, and no write is refused for a check that did not run
            LOG.errorf("The journal's chain cannot be verified: %s", LogSafe.of(e));
            return status = status(null, null, List.of());
        }
    }

    /** The checkpoint kept outside the database, and the key: what deleting checkpoints or the key cannot hide. */
    private void anchorFindings(Connection c, Findings f, JournalChain.Accepted accepted) throws SQLException {
        JournalAnchor.Anchor a;
        try {
            a = JournalAnchor.read(dataDir);
        } catch (IOException e) {
            // Handled: an anchor that cannot be read is a break, not a reason to trust the database alone
            f.fail(0, CHECKPOINT_ANCHOR, "unreadable", accepted);
            return;
        }
        if ("KEY_MISSING".equals(keyProblem)) {
            long at = a != null ? a.sequence() : scalar(c, "SELECT coalesce(min(sequence), 0) FROM journal_checkpoint");
            f.fail(at, CHECKPOINT_KEY, "key-missing", accepted);
        }
        if (a == null) return;
        if (key != null && !key.keyId().equals(a.keyId())) {
            f.fail(a.sequence(), CHECKPOINT_KEY, "anchor:" + a.keyId(), accepted);
            return;
        }
        String chain;
        try (PreparedStatement ps = c.prepareStatement("SELECT hash FROM journal_chain WHERE sequence = ?")) {
            ps.setLong(1, a.sequence());
            try (ResultSet rs = ps.executeQuery()) {
                chain = rs.next() ? rs.getString(1) : "-";
            }
        }
        if (!a.hash().equals(chain))
            f.fail(a.sequence(), CHECKPOINT_ANCHOR, "anchor:" + a.hash() + ":" + chain, accepted);
    }

    /** @param f null when no check could be made */
    private Status status(Findings f, JournalChain.Verdict v, List<Checkpoint> checked) {
        String keyId = key == null ? null : key.keyId();
        String at = Instant.now().toString();
        if (f == null)
            return new Status(UNCHECKED, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, keyId, keyProblem, null, false, at);
        String state = f.broken != null ? BROKEN : f.acked > 0 ? ACKNOWLEDGED : OK;
        Checkpoint last = checked.isEmpty() ? null : checked.get(checked.size() - 1);
        return new Status(
                state,
                f.broken,
                f.reason,
                v.rows(),
                v.chained(),
                v.adopted(),
                v.pending(),
                f.acked,
                checked.size(),
                f.verified,
                f.invalid,
                f.otherKey,
                keyId,
                keyProblem,
                last,
                f.broken != null,
                at);
    }

    /** What the chain and the checkpoints say together: the first break, and how the checkpoints fared. */
    private static final class Findings {
        Long broken;
        String reason;
        int acked;
        int verified;
        int invalid;
        int otherKey;
        String observed;

        Findings(Long broken, String reason, int acked, String observed) {
            this.broken = broken;
            this.reason = reason;
            this.acked = acked;
            this.observed = observed;
        }

        /** A failure outside the rows: acknowledged when the director saw exactly this, else the earliest is kept. */
        void fail(long sequence, String why, String seen, JournalChain.Accepted accepted) {
            if (accepted.covers(sequence, why, seen)) {
                acked++;
            } else if (broken == null || sequence < broken) {
                broken = sequence;
                reason = why;
                observed = seen;
            }
        }

        Checkpoint add(Checkpoint cp, String check, JournalChain.Accepted accepted) {
            switch (check) {
                case "VERIFIED" -> verified++;
                case "OTHER_KEY" -> otherKey++;
                default -> failed(cp, check, accepted);
            }
            return new Checkpoint(
                    cp.sequence(), cp.hash(), cp.signedAt(), cp.kind(), cp.keyId(), cp.signature(), check);
        }

        private void failed(Checkpoint cp, String check, JournalChain.Accepted accepted) {
            invalid++;
            fail(
                    cp.sequence(),
                    "INVALID".equals(check) ? CHECKPOINT_SIGNATURE : CHECKPOINT_MISMATCH,
                    "cp:" + cp.hash() + ":" + cp.signature(),
                    accepted);
        }
    }

    /** What the director acknowledged: a failure is covered only when its row, reason and look are the same. */
    private static JournalChain.Accepted accepted(Connection c) throws SQLException {
        Set<String> keys = new HashSet<>();
        try (PreparedStatement ps =
                        c.prepareStatement("SELECT broken_sequence, reason, observed FROM journal_chain_ack");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) keys.add(rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
        }
        return (seq, reason, observed) -> keys.contains(seq + "|" + reason + "|" + observed);
    }

    private String check(Connection c, Checkpoint cp) throws SQLException {
        if (key == null || !key.keyId().equals(cp.keyId())) return "OTHER_KEY";
        if (!key.verify(message(cp.sequence(), cp.hash(), cp.signedAt(), cp.keyId()), cp.signature())) {
            return "INVALID";
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT hash FROM journal_chain WHERE sequence = ?")) {
            ps.setLong(1, cp.sequence());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString(1).equals(cp.hash()) ? "VERIFIED" : "MISMATCH";
            }
        }
    }

    private static List<Checkpoint> checkpoints(Connection c) throws SQLException {
        List<Checkpoint> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                        "SELECT sequence, hash, signed_at, kind, key_id, signature FROM journal_checkpoint ORDER BY id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Checkpoint(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        null));
            }
        }
        return out;
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
}
