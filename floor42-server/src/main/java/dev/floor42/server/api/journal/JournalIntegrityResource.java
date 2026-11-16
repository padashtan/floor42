package dev.floor42.server.api.journal;

import dev.floor42.domain.Origin;
import dev.floor42.server.app.core.RefusedException;
import dev.floor42.server.journal.JournalException;
import dev.floor42.server.journal.JournalIntegrity;
import dev.floor42.server.journal.SqliteJournal;
import dev.floor42.server.security.LocalAccessGuard;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The journal's hash chain and its signed checkpoints (ADR-0079). Never a payload: sequences, hashes, counts. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class JournalIntegrityResource {

    /**
     * @param check VERIFIED, INVALID (bad signature), MISMATCH (the chain holds another hash at that sequence) or
     *     OTHER_KEY (signed by a key this daemon does not hold: not verifiable, not a break)
     */
    public record CheckpointView(
            long sequence, String hash, String signedAt, String kind, String keyId, String signature, String check) {}

    /**
     * @param status OK, ACKNOWLEDGED (every break found was acknowledged), BROKEN (appends are refused with
     *     JOURNAL_CHAIN_BROKEN until acknowledged) or UNCHECKED (the check could not run; appends are not refused)
     * @param brokenSequence the first row that fails, null when none
     * @param reason HASH_MISMATCH, PREV_MISMATCH, UNCHAINED_ROW, MISSING_ROW, CHECKPOINT_SIGNATURE or
     *     CHECKPOINT_MISMATCH
     * @param adoptedRows rows chained after the fact (a journal from before 1.3, rows an older version appended): the
     *     chain proves nothing about them before they were adopted
     * @param pendingRows rows after the chain's end, chained at the next start or append
     * @param keyProblem why checkpoints cannot be signed or verified this run, null when the key is fine
     */
    public record IntegrityView(
            String status,
            Long brokenSequence,
            String reason,
            long rows,
            long chainedRows,
            long adoptedRows,
            long pendingRows,
            int acknowledgedBreaks,
            int checkpoints,
            int signaturesVerified,
            int signaturesInvalid,
            int signaturesOtherKey,
            String keyId,
            String keyProblem,
            CheckpointView lastCheckpoint,
            boolean writesRefused,
            String checkedAt,
            Origin origin) {}

    @Inject
    SqliteJournal journal;

    @Inject
    CurrentVertxRequest request;

    @GET
    @Path("/journal/integrity")
    public IntegrityView integrity() {
        return view(journal.integrityStatus()
                .orElseThrow(() -> new JournalException("The journal's chain is not open", null)));
    }

    @POST
    @Path("/journal/integrity/verify")
    public IntegrityView verify() {
        return view(journal.verifyIntegrity());
    }

    @POST
    @Path("/journal/integrity/acknowledge")
    public IntegrityView acknowledge() {
        // The director's own browser only: no script token (whatever its scope) and not the director's token, which a
        // script holds. Writes resume on a chain that is broken, so this is a decision a person takes at the desk.
        if (LocalAccessGuard.viaOf(request.getCurrent()) != LocalAccessGuard.Via.COOKIE) {
            throw new RefusedException(
                    403,
                    "COOKIE_ONLY",
                    "Acknowledging a break in the journal's chain is done from the desk, not by a token");
        }
        return view(journal.acknowledgeIntegrity());
    }

    static IntegrityView view(JournalIntegrity.Status s) {
        JournalIntegrity.Checkpoint c = s.last();
        return new IntegrityView(
                s.status(),
                s.brokenSequence(),
                s.reason(),
                s.rows(),
                s.chained(),
                s.adopted(),
                s.pending(),
                s.acknowledged(),
                s.checkpoints(),
                s.verified(),
                s.invalid(),
                s.otherKey(),
                s.keyId(),
                s.keyProblem(),
                c == null
                        ? null
                        : new CheckpointView(
                                c.sequence(), c.hash(), c.signedAt(), c.kind(), c.keyId(), c.signature(), c.check()),
                s.writesRefused(),
                s.checkedAt(),
                Origin.REAL);
    }
}
