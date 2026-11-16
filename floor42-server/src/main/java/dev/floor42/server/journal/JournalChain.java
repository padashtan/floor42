package dev.floor42.server.journal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * The hash chain over {@code event_journal} (ADR-0079). The canonical encoding is stated there and here: the hash of a
 * row is SHA-256 over {@link #DOMAIN}, the 32 bytes of the previous row's hash (the genesis is 32 zero bytes), then the
 * twelve stored columns in the order of {@link #COLUMNS}. A column is one byte 0x00 when it is NULL, else 0x01, its
 * UTF-8 length as 4 bytes big-endian and its UTF-8 bytes; an integer column is its decimal text. The columns are read
 * as stored, never re-serialised: the hash does not depend on the event codec.
 */
public final class JournalChain {

    public static final String GENESIS = "0".repeat(64);

    public static final String HASH_MISMATCH = "HASH_MISMATCH";
    public static final String PREV_MISMATCH = "PREV_MISMATCH";
    public static final String UNCHAINED_ROW = "UNCHAINED_ROW";
    public static final String MISSING_ROW = "MISSING_ROW";
    /** Rows after the chain's end found while the daemon runs: another program wrote them, they are never adopted silently. */
    public static final String UNCHAINED_TAIL = "UNCHAINED_TAIL";

    private static final byte[] DOMAIN = "floor42.journal.chain.v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final String COLUMNS = "sequence, event_id, occurred_at, origin, actor_kind, actor_id, mission_id,"
            + " authorization_ref, causation_id, event_type, payload, format_version";
    private static final int BATCH = 5000;

    private JournalChain() {}

    /** @param sequence 0 and the genesis hash for an empty chain */
    record Head(long sequence, String hash) {}

    /**
     * @param firstBroken the first row that fails and was not acknowledged; null when the chain holds
     * @param acknowledged failures at rows the director acknowledged
     * @param pending rows after the chain's end (chained at the next start, not a failure here: the caller judges them)
     * @param observed what the first failure looked like (the stored hash and the recomputed one), so that an
     *     acknowledgement covers that state only; null when none
     * @param firstPending the first row after the chain's end, null when none
     */
    record Verdict(
            Long firstBroken,
            String reason,
            long rows,
            long chained,
            long adopted,
            long pending,
            int acknowledged,
            String observed,
            Long firstPending) {}

    /** Whether the director acknowledged this exact failure: the row, the reason and what it looked like. */
    @FunctionalInterface
    interface Accepted {
        boolean covers(long sequence, String reason, String observed);
    }

    static Head head(Connection c) throws SQLException {
        try (PreparedStatement ps =
                        c.prepareStatement("SELECT sequence, hash FROM journal_chain ORDER BY sequence DESC LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new Head(rs.getLong(1), rs.getString(2)) : new Head(0, GENESIS);
        }
    }

    /** The hash of the row the cursor is on (its first twelve columns, in the order of {@link #COLUMNS}). */
    static String hash(String prevHex, ResultSet row) throws SQLException {
        MessageDigest md = sha256();
        md.update(DOMAIN);
        md.update(hex(prevHex));
        for (int i = 1; i <= 12; i++) {
            String v = row.getString(i);
            if (v == null) {
                md.update((byte) 0);
                continue;
            }
            byte[] b = v.getBytes(StandardCharsets.UTF_8);
            md.update((byte) 1);
            md.update(ByteBuffer.allocate(4).putInt(b.length).array());
            md.update(b);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** A stored hash that is not 64 hex digits is a broken chain, never a crash. */
    private static byte[] hex(String h) {
        try {
            if (h == null || h.length() != 64) throw new IllegalArgumentException("length");
            return HexFormat.of().parseHex(h);
        } catch (IllegalArgumentException e) {
            throw new JournalChainBrokenException("The journal's chain holds a hash that is not a hash");
        }
    }

    static boolean isHash(String h) {
        try {
            hex(h);
            return true;
        } catch (JournalChainBrokenException e) {
            // Handled: the answer is "no"
            return false;
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Link(long sequence, String prev, String hash) {}

    /**
     * Chains every row after the chain's end, inside the caller's transaction.
     *
     * @param adopted true for rows that were not appended through the chain (a journal from before 1.3, or rows an
     *     older version or another program appended): marked, so the status says the chain proves nothing about them
     *     before this moment
     * @return the number of rows chained
     */
    static long chainPending(Connection c, boolean adopted) throws SQLException {
        Head h = head(c);
        long total = 0;
        while (true) {
            List<Link> links = nextLinks(c, h);
            if (links.isEmpty()) return total;
            insert(c, links, adopted);
            total += links.size();
            Link last = links.get(links.size() - 1);
            h = new Head(last.sequence(), last.hash());
            if (links.size() < BATCH) return total;
        }
    }

    private static List<Link> nextLinks(Connection c, Head h) throws SQLException {
        List<Link> links = new ArrayList<>();
        String prev = h.hash();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM event_journal WHERE sequence > ? ORDER BY sequence LIMIT ?")) {
            ps.setLong(1, h.sequence());
            ps.setInt(2, BATCH);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String hash = hash(prev, rs);
                    links.add(new Link(rs.getLong(1), prev, hash));
                    prev = hash;
                }
            }
        }
        return links;
    }

    private static void insert(Connection c, List<Link> links, boolean adopted) throws SQLException {
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO journal_chain (sequence, prev_hash, hash, adopted) VALUES (?, ?, ?, ?)")) {
            for (Link l : links) {
                ins.setLong(1, l.sequence());
                ins.setString(2, l.prev());
                ins.setString(3, l.hash());
                ins.setInt(4, adopted ? 1 : 0);
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    /**
     * Recomputes every row. A failure at a sequence in {@code accepted} is counted and passed over (the stored link is
     * trusted from there), so a later, different break is still found.
     */
    static Verdict verify(Connection c, Set<Long> accepted) throws SQLException {
        return verify(c, (seq, reason, observed) -> accepted.contains(seq));
    }

    static Verdict verify(Connection c, Accepted accepted) throws SQLException {
        Scan scan = new Scan(head(c).sequence(), accepted);
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS.replace("sequence", "j.sequence")
                        + ", c.prev_hash, c.hash FROM event_journal j"
                        + " LEFT JOIN journal_chain c ON c.sequence = j.sequence ORDER BY j.sequence");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next() && scan.row(rs)) {
                // each row is judged by scan.row
            }
        }
        for (long missing : missingRows(c)) if (!scan.missing(c, missing)) break;
        return new Verdict(
                scan.broken,
                scan.reason,
                scalar(c, "SELECT count(*) FROM event_journal"),
                scan.chained,
                scalar(c, "SELECT count(*) FROM journal_chain WHERE adopted = 1"),
                scan.pending,
                scan.acked,
                scan.observed,
                scan.firstPending);
    }

    /** The running state of one verification. */
    private static final class Scan {
        final long headSeq;
        final Accepted accepted;
        String prev = GENESIS;
        String observed;
        Long firstPending;
        long chained;
        long pending;
        int acked;
        Long broken;
        String reason;

        Scan(long headSeq, Accepted accepted) {
            this.headSeq = headSeq;
            this.accepted = accepted;
        }

        /** @return false once a failure nobody acknowledged is found: the scan stops there */
        boolean row(ResultSet rs) throws SQLException {
            long seq = rs.getLong(1);
            String stored = rs.getString(14);
            if (stored == null && seq > headSeq) {
                pending++;
                if (firstPending == null) firstPending = seq;
                return true;
            }
            String computed = stored == null ? null : recompute(rs);
            String failure = failure(rs, stored, computed);
            String seen = (stored == null ? "-" : stored) + ":" + (computed == null ? "-" : computed);
            if (failure != null && !accepted.covers(seq, failure, seen)) {
                broken = seq;
                reason = failure;
                observed = seen;
                return false;
            }
            if (failure != null) acked++;
            if (stored != null && isHash(stored)) prev = stored;
            chained++;
            return true;
        }

        private String recompute(ResultSet rs) throws SQLException {
            try {
                return hash(prev, rs);
            } catch (JournalChainBrokenException e) {
                // Handled: a hash that is not a hash is a failure at this row
                return null;
            }
        }

        private String failure(ResultSet rs, String stored, String computed) throws SQLException {
            if (stored == null) return UNCHAINED_ROW;
            if (!prev.equals(rs.getString(13))) return PREV_MISMATCH;
            return stored.equals(computed) ? null : HASH_MISMATCH;
        }

        /** @return false once a missing row nobody acknowledged is found and no earlier break exists */
        boolean missing(Connection c, long seq) throws SQLException {
            String seen = "gone:" + scalarText(c, "SELECT hash FROM journal_chain WHERE sequence = " + seq);
            if (accepted.covers(seq, MISSING_ROW, seen)) {
                acked++;
                return true;
            }
            if (broken == null || seq < broken) {
                broken = seq;
                reason = MISSING_ROW;
                observed = seen;
            }
            return false;
        }
    }

    private static String scalarText(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : "-";
        }
    }

    /** Chain rows whose journal row is gone, in order. */
    private static List<Long> missingRows(Connection c) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT c.sequence FROM journal_chain c"
                        + " LEFT JOIN event_journal j ON j.sequence = c.sequence WHERE j.sequence IS NULL"
                        + " ORDER BY c.sequence");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getLong(1));
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
