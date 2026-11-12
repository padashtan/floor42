package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.DeliveryCopy;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.UncommittedWorktree;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.app.Delivered.Blob;
import dev.floor42.server.app.Delivered.DeliveredBranch;
import dev.floor42.server.app.Delivered.DeliveredFile;
import dev.floor42.server.app.Delivered.Source;
import dev.floor42.server.security.LogSafe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Where a mission's deliverables land (ADR-0051). The director gives a folder when creating the mission; when they
 * accept the delivery, Floor42 copies the mission's documents into {@code <folder>/<mission>/} and journals what it
 * wrote, then the mission completes. Missions without a folder close as before: nothing is copied.
 *
 * <p>One close or cancel of a mission at a time ({@link #closing}): a cancel never runs in the middle of a copy. The
 * same lock, shared, is taken by the workshop's edit, accept and reject ({@link #changing}, from WorkshopResource):
 * those three never land between the check of uncommitted work and the acceptance it leads to. An agent session, the
 * director's terminal or anything outside Floor42 is not covered (ADR-0055).
 */
@ApplicationScoped
public class DeliverablesHome {

    private static final Logger LOG = Logger.getLogger(DeliverablesHome.class);

    /** At most this many bytes copied for one mission, as the ZIP; the rest is left out and said. */
    static final long MAX_BYTES = Deliverables.MAX_ZIP_BYTES;

    /** At most this many bytes for one file, the viewer's cap for images and PDFs. */
    static final long MAX_FILE_BYTES = Deliverables.MAX_VIEW_BYTES;

    private final OfficeService office;
    private final Deliverables deliverables;
    private final Path dataDir;
    private final UncommittedDeliverables uncommitted;
    /** Per mission: a close holds it alone, workshop changes share it. Never waited on: a busy mission refuses. */
    private final ConcurrentHashMap<MissionId, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    @Inject
    public DeliverablesHome(
            OfficeService office,
            Deliverables deliverables,
            UncommittedDeliverables uncommitted,
            @ConfigProperty(name = "floor42.data-dir") Path dataDir) {
        this.office = office;
        this.deliverables = deliverables;
        this.uncommitted = uncommitted;
        this.dataDir = dataDir;
    }

    @Inject
    DeliveryProofs proofs;

    @Inject
    ProofRunner proofRunner;

    /** Whether an acceptance needs the delivery proven or the director's say-so (ADR-0058); off in most tests. */
    @ConfigProperty(name = "floor42.proof.required", defaultValue = "true")
    boolean proofRequired = true;

    /** Test seam: runs once the uncommitted work is measured, before anything is copied or journaled. */
    private volatile Runnable afterCheckForTests = () -> {};

    public void afterCheckForTests(Runnable seam) {
        this.afterCheckForTests = seam == null ? () -> {} : seam;
    }

    /** Test seam: throws to stop a copy in the middle, as a failure no check foresaw would. */
    private volatile java.util.function.Consumer<String> stopForTests = path -> {};

    /** Tests stop the copy at a file of their choice; null restores the plain copy. */
    public void stopForTests(java.util.function.Consumer<String> stop) {
        this.stopForTests = stop == null ? path -> {} : stop;
    }

    /** The folder for a new mission's deliverables, checked on disk; null when the director gave none. */
    public Path destination(String repository, String raw) {
        return DeliveryDestination.resolve(Path.of(repository), raw, dataDir);
    }

    /**
     * Runs a close or a cancel of the mission alone: a second one at the same time is refused (409 CLOSING), and so is
     * one while the delivery's proof runs (409 PROOF_RUNNING, ADR-0058): a run starts under the shared lock, so none
     * can start once this one holds it.
     */
    public <T> T closing(MissionId mission, Supplier<T> action) {
        return held(lock(mission).writeLock(), mission, () -> {
            if (proofRunner.running(mission))
                throw RefusedException.conflict(
                        "PROOF_RUNNING", mission.value() + ": its proof is running: close it once the run ends");
            return action.get();
        });
    }

    /**
     * Runs a change to one of the mission's worktrees (a workshop commit or edit); refused (409 CLOSING) while the
     * mission is being closed, so a close never measures a worktree that changes before it is journaled.
     */
    public <T> T changing(MissionId mission, Supplier<T> action) {
        return held(lock(mission).readLock(), mission, action);
    }

    private ReentrantReadWriteLock lock(MissionId mission) {
        return locks.computeIfAbsent(mission, k -> new ReentrantReadWriteLock());
    }

    private static <T> T held(java.util.concurrent.locks.Lock lock, MissionId mission, Supplier<T> action) {
        if (!lock.tryLock()) {
            throw RefusedException.conflict("CLOSING", mission.value() + " is being closed: try again in a moment");
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** What is not committed in the mission's worktrees now; read under the close lock by {@link #complete}. */
    public UncommittedDeliverables.UncommittedCheck uncommitted(MissionId id) {
        origin(id);
        return uncommitted.check(id);
    }

    /**
     * The director accepts the delivery: the documents are copied when the mission has a destination, then the copy
     * and the completion are journaled in one command. Refused before anything is written when the mission cannot
     * close.
     */
    public void complete(MissionId id, String summary, boolean withIgnored, String acknowledged, String proofSeen) {
        closing(id, () -> {
            CommandContext ctx = office.context(Actor.director(), origin(id));
            // Everything the completion would refuse is refused before a file is written.
            office.query(c -> c.missions().refusalToComplete(ctx, id)).ifPresent(r -> {
                throw new DomainException(r.code(), r.message());
            });
            if (summary == null || summary.isBlank())
                throw new DomainException("MISSING_SUMMARY", "reason is required: the closing summary");
            ProofViews.ProofAcceptanceView unproven = acknowledgedProof(id, proofSeen);
            List<UncommittedWorktree> notClean = acknowledgedUncommitted(id, acknowledged);
            afterCheckForTests.run();
            Optional<String> home = office.query(c -> c.deliveries().destination(id));
            Copy copy = home.isEmpty() ? null : copy(id, Path.of(home.get()), withIgnored);
            return office.execute(c -> {
                List<EventEnvelope> events = new ArrayList<>();
                if (unproven.state() != null)
                    events.addAll(c.deliveries().proofs().acceptUnproven(ctx, id, unproven.state(), unproven.token()));
                if (!notClean.isEmpty()) events.addAll(c.deliveries().acceptUncommitted(ctx, id, notClean));
                if (copy != null)
                    events.addAll(c.deliveries()
                            .recordCopy(ctx, id, copy.folder(), copy.files(), copy.leftOut(), copy.truncated()));
                events.addAll(c.missions().complete(ctx, id, summary));
                return events;
            });
        });
    }

    /**
     * The worktrees that are not clean, once the director has acknowledged exactly this measure; refused (409
     * UNCOMMITTED_DELIVERABLES) before anything is written when they have not, or when the measure changed since.
     */
    private List<UncommittedWorktree> acknowledgedUncommitted(MissionId id, String acknowledged) {
        UncommittedDeliverables.UncommittedCheck check = uncommitted.check(id);
        if (check.clean()) return List.of();
        if (!check.token().equals(acknowledged)) {
            throw RefusedException.conflict(
                    "UNCOMMITTED_DELIVERABLES",
                    id.value() + " has work its worktrees do not hold in a commit, or whose state is unknown: check"
                            + " it, then accept with the token " + check.token());
        }
        return check.notClean();
    }

    /**
     * The proof state, once the director has acknowledged exactly this one (ADR-0058); refused (409 UNPROVEN_DELIVERY)
     * before anything is written when they have not, or when it changed since. Off where the setting says so (tests).
     */
    private ProofViews.ProofAcceptanceView acknowledgedProof(MissionId id, String seen) {
        if (!proofRequired) return new ProofViews.ProofAcceptanceView(null, null);
        ProofViews.ProofAcceptanceView now = proofs.acceptance(id);
        if (now.state() != null && !now.token().equals(seen)) {
            throw RefusedException.conflict(
                    "UNPROVEN_DELIVERY",
                    id.value() + " is not proven (" + now.state() + "): check it, then accept with the token "
                            + now.token());
        }
        return now;
    }

    private Origin origin(MissionId id) {
        return office.query(c -> c.missions().find(id).map(Mission::origin))
                .orElseThrow(() -> new NotFoundException("No mission " + id.value()));
    }

    /** What was copied, as measured. */
    record Copy(String folder, List<DeliveryCopy> files, int leftOut, boolean truncated) {}

    /** One file to copy: whose, which, from the branch or the worktree. */
    record Item(String agent, DeliveredFile file, Source source) {}

    private Copy copy(MissionId id, Path home, boolean withIgnored) {
        Mission m = office.query(c -> c.missions().find(id)).orElseThrow();
        DeliveryDestination.check(Path.of(m.workspace()), home, dataDir);
        DocumentWriter writer = new DocumentWriter(DocumentWriter.prepare(home, id.value()), MAX_FILE_BYTES, MAX_BYTES);
        var view = deliverables.view(id, withIgnored);
        boolean truncated = view.branches().stream().anyMatch(DeliveredBranch::truncated);
        List<Item> items = items(view.branches(), withIgnored);
        int done = 0;
        try {
            for (Item i : items) {
                stopForTests.accept(i.file().path());
                copyOne(writer, id, i.agent(), i.file(), i.source());
                done++;
            }
        } catch (RuntimeException e) {
            // Handled: the copy stops; what was written stays and is journaled, the file that failed and the rest are
            // listed as left out, so the record never claims more than the disk holds
            LOG.warnf(e, "Copy of the deliverables of %s stopped after %d file(s)", LogSafe.of(id), done);
            for (Item i : items.subList(done, items.size())) {
                writer.leftOut(i.agent(), i.file().path(), "not copied: the copy stopped (" + e.getMessage() + ")");
            }
        }
        List<DeliveryCopy> all = writer.outcomes();
        int listed = Math.min(all.size(), DeliveryCopy.MAX_FILES);
        LOG.infof(
                "Deliverables of %s copied into %s: %d file(s) considered",
                LogSafe.of(id), LogSafe.of(writer.folder()), all.size());
        return new Copy(
                writer.folder().toString(), List.copyOf(all.subList(0, listed)), all.size() - listed, truncated);
    }

    /**
     * Each file once per agent: a file committed and changed again in the worktree is copied as it is in the worktree
     * (the newer bytes), never twice.
     */
    static List<Item> items(List<DeliveredBranch> branches, boolean withIgnored) {
        List<Item> out = new ArrayList<>();
        for (DeliveredBranch b : branches) {
            java.util.Set<String> loose = new java.util.HashSet<>();
            for (DeliveredFile f : b.uncommitted()) {
                if (f.ignored() && !withIgnored) continue;
                out.add(new Item(b.agent(), f, Source.WORKTREE));
                loose.add(f.path());
            }
            for (DeliveredFile f : b.committed()) {
                if (!loose.contains(f.path())) out.add(new Item(b.agent(), f, Source.COMMITTED));
            }
        }
        return out;
    }

    private void copyOne(DocumentWriter writer, MissionId id, String agent, DeliveredFile f, Source source) {
        if (f.link() || f.status().equals("D")) return;
        Optional<String> why = DocumentWriter.notADocument(f.path());
        if (why.isPresent()) {
            writer.leftOut(agent, f.path(), why.get());
            return;
        }
        if (!writer.fits(f.size())) {
            writer.leftOut(agent, f.path(), "past the size caps (" + MAX_FILE_BYTES + " bytes a file)");
            return;
        }
        Blob blob;
        try {
            blob = deliverables.file(id, agent, f.path(), source);
        } catch (RuntimeException e) {
            // Handled: a file the listing named but that cannot be read now is left out, with the reason journaled
            writer.leftOut(agent, f.path(), "cannot be read: " + e.getMessage());
            return;
        }
        try {
            writer.write(agent, f.path(), blob.file(), blob.size());
        } finally {
            blob.release();
        }
    }
}
