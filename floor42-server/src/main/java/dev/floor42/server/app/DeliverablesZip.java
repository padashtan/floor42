package dev.floor42.server.app;

import dev.floor42.server.app.Delivered.Blob;
import dev.floor42.server.app.Delivered.DeliverablesView;
import dev.floor42.server.app.Delivered.DeliveredBranch;
import dev.floor42.server.app.Delivered.DeliveredFile;
import dev.floor42.server.app.Delivered.DeliveredPullRequest;
import dev.floor42.server.app.Delivered.DeliveredReview;
import dev.floor42.server.app.Delivered.DeliveredTicket;
import dev.floor42.server.app.Delivered.Source;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The whole mission as a ZIP: {@code MANIFEST.txt}, each ticket as Markdown, and each branch's files in a folder of its
 * own ({@code committed/} through git, {@code not-committed/} from the worktree). Files past {@link #MAX_ZIP_BYTES} and
 * links are listed in the manifest as left out; a file that cannot be read is replaced by a note saying why.
 */
@ApplicationScoped
public class DeliverablesZip {

    /** The mission's ZIP holds at most this many bytes of files (before compression); the rest is listed as left out. */
    public static final long MAX_ZIP_BYTES = 512L * 1024 * 1024;

    /** One file the ZIP will hold, and where it comes from. */
    private record Planned(String entry, String agent, String path, Source source, String origin) {}

    /** What goes in: the files planned, and the lines saying what was left out and why. */
    private record Plan(List<Planned> files, List<String> leftOut) {}

    private final BlobSource blobs;

    @Inject
    public DeliverablesZip(BlobSource blobs) {
        this.blobs = blobs;
    }

    /** Writes the ZIP; returns how many files it holds. */
    int write(DeliverablesView v, Map<String, ResolvedBranch> branches, OutputStream target) throws IOException {
        Plan plan = plan(v);
        try (ZipOutputStream zip = new ZipOutputStream(target, StandardCharsets.UTF_8)) {
            put(zip, "MANIFEST.txt", manifest(v, plan));
            for (DeliveredTicket t : v.tickets()) {
                put(zip, "tickets/" + safeSegment(t.id()) + ".md", ticketMarkdown(t));
            }
            for (Planned p : plan.files()) copy(zip, p, branches.get(p.agent()));
        }
        return plan.files().size();
    }

    private Plan plan(DeliverablesView v) {
        Plan plan = new Plan(new ArrayList<>(), new ArrayList<>());
        long[] total = {0};
        for (DeliveredBranch b : v.branches()) {
            String folder = "branches/" + safeSegment(b.agent()) + "/";
            for (DeliveredFile f : b.committed()) {
                String origin = "committed at " + f.commit() + " on " + b.branch();
                add(plan, total, b, f, folder + "committed/" + f.path(), Source.COMMITTED, origin);
            }
            for (DeliveredFile f : b.uncommitted()) {
                String origin = "not committed: exists only in the worktree " + b.worktree()
                        + (f.ignored() ? " (ignored by git)" : "");
                add(plan, total, b, f, folder + "not-committed/" + f.path(), Source.WORKTREE, origin);
            }
        }
        return plan;
    }

    private static void add(
            Plan plan, long[] total, DeliveredBranch b, DeliveredFile f, String entry, Source source, String origin) {
        if (f.status().equals("D")) return;
        if (source == Source.WORKTREE && f.link()) {
            plan.leftOut().add(entry + ": a link, never followed");
        } else if (!safeEntry(f.path())) {
            plan.leftOut().add(f.path() + " (" + b.agent() + "): a name Floor42 will not write in a ZIP");
        } else if (total[0] + (f.size() == null ? 0 : f.size()) > MAX_ZIP_BYTES) {
            plan.leftOut().add(entry + ": over the " + (MAX_ZIP_BYTES / (1024 * 1024)) + " MB cap");
        } else {
            total[0] += f.size() == null ? 0 : f.size();
            plan.files().add(new Planned(entry, b.agent(), f.path(), source, origin));
        }
    }

    private void copy(ZipOutputStream zip, Planned p, ResolvedBranch r) throws IOException {
        Blob blob;
        try {
            if (r == null) throw new IllegalStateException("the branch is no longer known");
            // Committed files come from the listing just made; worktree ones are checked again one by one.
            blob = p.source() == Source.WORKTREE
                    ? blobs.fromWorktree(r, p.path())
                    : blobs.fromBranch(r, p.path(), false);
        } catch (RuntimeException e) {
            // Handled: the ZIP says, in place of the file, why Floor42 could not read it; the rest still goes in.
            put(zip, p.entry() + ".unavailable.txt", "Floor42 could not read this file: " + e.getMessage());
            return;
        }
        try {
            zip.putNextEntry(new ZipEntry(p.entry()));
            blob.copyTo(zip);
            zip.closeEntry();
        } finally {
            blob.release();
        }
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** What the ZIP holds and where each file comes from, then each branch, each pull request, what was left out. */
    private static String manifest(DeliverablesView v, Plan plan) {
        StringBuilder s = new StringBuilder(header(v));
        for (DeliveredTicket t : v.tickets()) {
            s.append("tickets/")
                    .append(safeSegment(t.id()))
                    .append(".md\tticket ")
                    .append(t.id());
            s.append(": deliverable text and reviews, from Floor42's journal\n");
        }
        for (Planned p : plan.files())
            s.append(p.entry()).append('\t').append(p.origin()).append('\n');
        for (DeliveredBranch b : v.branches()) s.append(branchLine(b));
        for (DeliveredPullRequest pr : v.pullRequests()) {
            s.append("\nPull request #").append(pr.number()).append(": ").append(pr.url());
            s.append(" (")
                    .append(pr.branch())
                    .append(" into ")
                    .append(pr.base())
                    .append(")\n");
        }
        if (!plan.leftOut().isEmpty()) {
            s.append("\nLeft out:\n");
            for (String l : plan.leftOut()) s.append(l).append('\n');
        }
        return s.toString();
    }

    private static String header(DeliverablesView v) {
        StringBuilder s = new StringBuilder();
        s.append("Floor42: what mission ").append(v.mission()).append(" delivered\n");
        s.append("Title: ").append(v.title()).append("\n");
        s.append("Status: ").append(v.status()).append(" (").append(v.origin()).append(")\n");
        s.append("Repository: ").append(v.repository() == null ? "none" : v.repository());
        if (v.projectHead() != null) {
            s.append(" (")
                    .append(v.projectBranch())
                    .append(" at ")
                    .append(v.projectHead())
                    .append(")");
        }
        s.append("\nWritten: ").append(Instant.now()).append("\n\n");
        s.append("Nothing outside the mission's repository and worktrees is included. Each line says where a file comes"
                + " from.\n\n");
        return s.toString();
    }

    private static String branchLine(DeliveredBranch b) {
        StringBuilder s = new StringBuilder("\nBranch ")
                .append(b.branch())
                .append(" of ")
                .append(b.agent())
                .append(": ")
                .append(b.merged())
                .append(" into the project's branch");
        if (b.notInProject() >= 0) s.append(", ").append(b.notInProject()).append(" commit(s) not in it");
        if (!b.worktreePresent()) s.append("; worktree gone, committed files read from the repository");
        if (b.truncated())
            s.append("; lists cut at ").append(Deliverables.MAX_FILES).append(" files");
        if (b.ignoredHidden() > 0) s.append("; ").append(b.ignoredHidden()).append(" ignored item(s) not included");
        return s.append('\n').toString();
    }

    static String ticketMarkdown(DeliveredTicket t) {
        StringBuilder s = new StringBuilder();
        s.append("# ").append(t.id()).append(" ").append(t.title()).append("\n\n");
        s.append("Status: ").append(t.status());
        if (t.assignee() != null) s.append(" · assignee: ").append(t.assignee());
        s.append("\n\n## Deliverable\n\n")
                .append(t.deliverable() == null ? "(nothing submitted)" : t.deliverable())
                .append("\n");
        int n = 1;
        for (DeliveredReview r : t.reviews()) {
            s.append("\n## Review ")
                    .append(n++)
                    .append(" by ")
                    .append(r.reviewer())
                    .append(": ")
                    .append(r.verdict())
                    .append("\n\n")
                    .append(r.findings() == null ? "" : r.findings())
                    .append("\n");
        }
        return s.toString();
    }

    /** A ZIP entry name that cannot climb out of the folder it is extracted to. */
    static boolean safeEntry(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private static String safeSegment(String s) {
        String out = s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]", "_");
        return out.isEmpty() || out.equals(".") || out.equals("..") ? "_" : out;
    }
}
