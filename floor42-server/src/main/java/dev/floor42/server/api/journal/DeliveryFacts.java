package dev.floor42.server.api.journal;

import dev.floor42.domain.event.DeliveryEvent.DeliverablesCopied;
import dev.floor42.domain.event.DeliveryEvent.DeliveryEvidenceRecorded;
import dev.floor42.domain.event.DeliveryEvent.PullRequestMerged;
import dev.floor42.domain.event.DeliveryEvent.PullRequestOpened;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.WorkbenchEvent.BranchesMerged;
import dev.floor42.domain.event.WorkbenchEvent.PatchAccepted;
import dev.floor42.domain.event.WorkbenchEvent.ProjectMerged;
import dev.floor42.server.api.journal.ProofDossier.DeliveryFact;
import dev.floor42.server.api.journal.ProofDossier.Ref;
import dev.floor42.server.api.journal.ProofDossier.SignatureFact;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The deliveries section of the proof dossier: what reached the outside, read from the events. */
final class DeliveryFacts {

    /** A text of the dossier, passed through the sanitizer for its event and field. */
    interface Text {
        String of(EventEnvelope e, String field, String s);
    }

    private final List<DeliveryFact> out;
    private final Map<String, SignatureFact> signatures;
    private final Text text;
    private final Function<EventEnvelope, Ref> ref;

    DeliveryFacts(
            List<DeliveryFact> out,
            Map<String, SignatureFact> signatures,
            Text text,
            Function<EventEnvelope, Ref> ref) {
        this.out = out;
        this.signatures = signatures;
        this.text = text;
        this.ref = ref;
    }

    /** What reached the outside: copies, pull requests, patches; merges follow. */
    void delivery(EventEnvelope e) {
        switch (e.event()) {
            case DeliverablesCopied c ->
                delivered(
                        e,
                        "DELIVERABLES_COPIED",
                        text.of(e, "folder", c.folder()) + (c.leftOut() > 0 ? ", left out " + c.leftOut() : ""),
                        null,
                        c.files().size());
            case PullRequestOpened p ->
                delivered(
                        e,
                        "PULL_REQUEST_OPENED",
                        text.of(e, "branch", "#" + p.number() + " " + p.branch() + " -> " + p.base()),
                        null,
                        null);
            default -> checked(e);
        }
    }

    private void checked(EventEnvelope e) {
        switch (e.event()) {
            case DeliveryEvidenceRecorded d ->
                delivered(
                        e,
                        "DELIVERY_EVIDENCE",
                        "#" + d.number() + " " + d.conclusion() + ", "
                                + d.checks().size() + " checks",
                        ProofDossiers.shortCommit(d.commit()),
                        null);
            case PullRequestMerged m ->
                delivered(
                        e,
                        "PULL_REQUEST_MERGED",
                        "#" + m.number() + " " + text.of(e, "approval", m.approval()),
                        ProofDossiers.shortCommit(m.commit()),
                        null);
            case PatchAccepted p ->
                delivered(
                        e,
                        "PATCH_ACCEPTED",
                        text.of(e, "message", p.message()),
                        ProofDossiers.shortCommit(p.commit()),
                        p.files() == null ? 0 : p.files().size(),
                        signatures.get(p.commit()));
            default -> merge(e);
        }
    }

    private void merge(EventEnvelope e) {
        switch (e.event()) {
            case ProjectMerged p ->
                delivered(
                        e,
                        "PROJECT_MERGED",
                        p.clean() ? "clean" : text.of(e, "detail", "conflict: " + p.detail()),
                        ProofDossiers.shortCommit(p.commit()),
                        null);
            case BranchesMerged b ->
                delivered(
                        e,
                        "BRANCHES_MERGED",
                        text.of(e, "branches", String.join(", ", b.branches()) + (b.clean() ? "" : ": not clean")),
                        null,
                        null);
            default -> {}
        }
    }

    private void delivered(EventEnvelope e, String kind, String text, String commit, Integer files) {
        delivered(e, kind, text, commit, files, null);
    }

    private void delivered(
            EventEnvelope e, String kind, String text, String commit, Integer files, SignatureFact signature) {
        out.add(new DeliveryFact(e.occurredAt().toString(), kind, text, commit, files, signature, ref.apply(e)));
    }
}
