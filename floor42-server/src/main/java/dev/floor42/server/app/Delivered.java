package dev.floor42.server.app;

import dev.floor42.domain.Ticket;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jboss.logging.Logger;

/** The views of what a mission delivered (ADR-0037), as {@link Deliverables} assembles them and the API serves them. */
public final class Delivered {

    private static final Logger LOG = Logger.getLogger(Delivered.class);

    private Delivered() {}

    /**
     * A review as the journal holds it.
     *
     * @param items its reservations and notes; null for a review journaled before 1.13.0, whose verdict keeps its meaning
     */
    public record DeliveredReview(String reviewer, String verdict, String findings, ReviewItemsView items) {

        public static DeliveredReview of(Ticket.Review r) {
            return new DeliveredReview(r.reviewer().value(), r.verdict().name(), r.findings(), ReviewItemsView.of(r));
        }
    }

    /**
     * What Floor42 kept of a review (ADR-0053): reservations tied to a criterion, and notes, each with a code for why it
     * is one ({@code NOTE}, {@code NO_CRITERION}, {@code UNKNOWN_CRITERION}, {@code NO_CHANGE}).
     *
     * @param declared the verdict the reviewer wrote, which the recorded one may differ from
     */
    public record ReviewItemsView(
            String declared, List<ReviewReservationView> reservations, List<ReviewNoteView> notes) {
        public ReviewItemsView {
            notes = notes == null ? null : Collections.unmodifiableList(new ArrayList<>(notes));
            reservations = reservations == null ? null : Collections.unmodifiableList(new ArrayList<>(reservations));
        }

        static ReviewItemsView of(Ticket.Review r) {
            if (!r.calibrated()) return null;
            return new ReviewItemsView(
                    r.items().declared().name(),
                    r.items().reservations().stream()
                            .map(x -> new ReviewReservationView(x.criterion(), x.criterionText(), x.gap(), x.change()))
                            .toList(),
                    r.items().notes().stream()
                            .map(x -> new ReviewNoteView(x.text(), x.why().name()))
                            .toList());
        }
    }

    public record ReviewReservationView(int criterion, String criterionText, String gap, String change) {}

    public record ReviewNoteView(String text, String why) {}

    /**
     * @param verdict the last review's verdict, null before any review
     * @param deliverable what the assignee submitted, null before any submission
     */
    public record DeliveredTicket(
            String id,
            String title,
            String status,
            String assignee,
            String deliverable,
            String verdict,
            List<DeliveredReview> reviews) {
        public DeliveredTicket {
            reviews = reviews == null ? null : Collections.unmodifiableList(new ArrayList<>(reviews));
        }
    }

    /**
     * One file of a branch or of a worktree.
     *
     * @param status A added, M modified, D deleted, R renamed (committed); for a worktree file also {@code ?} new and
     *     never committed, {@code !} ignored by git
     * @param from the former name of a renamed file, null otherwise
     * @param size in bytes, null when unknown or deleted
     * @param commit the short hash of the last commit of the branch that touched it; null when not committed
     * @param committed true for a file of the branch, false for one that exists only in the worktree
     * @param link a symbolic link or junction: listed, never read
     * @param kind TEXT, IMAGE, PDF or OTHER, from the name: what the screen may try to show (the server checks the
     *     bytes again)
     */
    public record DeliveredFile(
            String path,
            String from,
            String status,
            Long size,
            String commit,
            boolean committed,
            boolean ignored,
            boolean link,
            String kind) {}

    /** @param inProject the project's current branch already holds this commit */
    public record DeliveredCommit(String hash, String subject, String author, String at, boolean inProject) {}

    /**
     * One agent's branch.
     *
     * @param worktree the worktree's folder; null when Floor42 has no record of one
     * @param worktreePresent false when the folder is gone: committed files are still read from the repository
     * @param legacy the worktree sits in Office's former data folder (made before 1.5.0)
     * @param start the commit the worktree was made from (short); null when unknown
     * @param tip the branch's last commit (short); null when git cannot read it
     * @param merged NOTHING (no commit beyond the start), MERGED, PARTIAL, NOT_MERGED, or UNKNOWN
     * @param notInProject commits of the branch the project's current branch lacks; -1 when unknown
     * @param ignoredHidden ignored entries (a folder counts once) not listed because "show ignored" is off
     * @param truncated a list was cut at {@link Deliverables#MAX_FILES}
     * @param tickets the tickets this agent was assigned
     */
    public record DeliveredBranch(
            String agent,
            String branch,
            String worktree,
            boolean worktreePresent,
            boolean legacy,
            String start,
            String tip,
            String merged,
            int notInProject,
            List<DeliveredCommit> commits,
            List<DeliveredFile> committed,
            List<DeliveredFile> uncommitted,
            int ignoredHidden,
            boolean truncated,
            List<String> tickets) {
        public DeliveredBranch {
            commits = commits == null ? null : Collections.unmodifiableList(new ArrayList<>(commits));
            committed = committed == null ? null : Collections.unmodifiableList(new ArrayList<>(committed));
            tickets = tickets == null ? null : Collections.unmodifiableList(new ArrayList<>(tickets));
            uncommitted = uncommitted == null ? null : Collections.unmodifiableList(new ArrayList<>(uncommitted));
        }
    }

    public record DeliveredPullRequest(
            int number, String url, String agent, String branch, String base, String openedAt) {}

    /**
     * @param available false when the repository cannot be read by git: tickets are still listed
     * @param projectBranch the project's current branch; empty on a detached HEAD
     * @param projectHead its last commit (short)
     * @param ignoredShown ignored files are listed
     */
    public record DeliverablesView(
            String mission,
            String title,
            String status,
            String origin,
            String repository,
            boolean available,
            String projectBranch,
            String projectHead,
            boolean ignoredShown,
            List<DeliveredTicket> tickets,
            List<DeliveredBranch> branches,
            List<DeliveredPullRequest> pullRequests,
            long maxViewText,
            long maxViewBytes,
            long maxZipBytes) {
        public DeliverablesView {
            branches = branches == null ? null : Collections.unmodifiableList(new ArrayList<>(branches));
            pullRequests = pullRequests == null ? null : Collections.unmodifiableList(new ArrayList<>(pullRequests));
            tickets = tickets == null ? null : Collections.unmodifiableList(new ArrayList<>(tickets));
        }
    }

    /** Where a file comes from: the branch (through git) or the worktree (on disk). */
    public enum Source {
        COMMITTED,
        WORKTREE
    }

    /**
     * A file ready to be sent: its bytes are in {@code file}; {@code temporary} files are deleted once sent.
     *
     * @param name the file's own name, without folders
     * @param head its first bytes, to tell what it really is
     * @param channel the file opened once when it was checked; every read goes through it ({@link BlobBytes}). Null
     *     only for a blob made by hand (a test): the path is then opened again, without following a link
     */
    public record Blob(String name, Path file, boolean temporary, long size, byte[] head, FileChannel channel) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Blob p
                    && java.util.Objects.equals(name, p.name)
                    && java.util.Objects.equals(file, p.file)
                    && temporary == p.temporary
                    && size == p.size
                    && java.util.Arrays.equals(head, p.head)
                    && java.util.Objects.equals(channel, p.channel);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, file, temporary, size, java.util.Arrays.hashCode(head), channel);
        }

        @Override
        public String toString() {
            return "Blob[" + "name=" + name + ", file=" + file + ", size=" + size + "]";
        }

        public Blob(String name, Path file, boolean temporary, long size, byte[] head) {
            this(name, file, temporary, size, head, null);
        }

        /** At most {@code max} of its first bytes, never past {@link #size}. */
        public byte[] first(int max) throws IOException {
            return BlobBytes.first(this, max);
        }

        /** Its bytes, cut at {@link #size}: a file grown after the check sends no more than was checked. */
        public void copyTo(OutputStream out) throws IOException {
            BlobBytes.copy(this, out);
        }

        public void release() {
            if (channel != null && channel.isOpen()) {
                BlobBytes.closed();
                try {
                    channel.close();
                } catch (IOException e) {
                    // Handled: closing a read channel fails only on a broken handle; nothing is left to send
                    LOG.debugf("Channel of %s not closed: %s", file, e.getMessage());
                }
            }
            if (!temporary) return;
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // Handled: the file is already sent; a temporary copy left behind is only logged.
                LOG.debugf("Temporary copy %s left behind: %s", file, e.getMessage());
            }
        }
    }

    static String shortHash(String hash) {
        return hash == null ? null : hash.substring(0, Math.min(10, hash.length()));
    }
}
