package dev.floor42.server.api.journal;

import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.api.journal.ProofDossier.Section;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.delivery.MissionReports;
import dev.floor42.server.app.delivery.MissionReports.MissionReport;
import dev.floor42.server.iam.Delegations;
import dev.floor42.server.iam.MissionDelegation;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.security.ReplaySanitizer;
import dev.floor42.server.security.SessionToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Builds the mission proof dossier (ADR-0071): the journal's facts about identities, refusals, approvals, costs, checks
 * and deliverables, each with its source and its event numbers. Reading journals nothing; every text is cleaned by the
 * replay export's {@link ReplaySanitizer}.
 */
@ApplicationScoped
public class ProofDossiers {

    /** Lines per section; the count stays the full one. */
    static final int CAP = 200;

    static final int FORMAT = 1;

    static final String STATEMENT = "facts from the journal; any other source is named on its line";

    @Inject
    OfficeService office;

    @Inject
    Journal journal;

    @Inject
    SessionToken token;

    @Inject
    ReplayExports exports;

    @Inject
    MissionReports reports;

    @Inject
    Delegations delegations;

    ProofDossier build(String id) {
        MissionId mission;
        try {
            mission = new MissionId(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Unknown mission " + id, e);
        }
        Mission m = office.query(c -> c.missions().find(mission))
                .orElseThrow(() -> new NotFoundException("Unknown mission " + id));
        List<EventEnvelope> events = journal.readAll().stream()
                .filter(e -> mission.equals(e.mission()))
                .toList();
        ReplaySanitizer clean = new ReplaySanitizer(token.value(), exports.roots(m, events));
        MissionReport report = reports.report(mission);
        return compose(m, events, report, delegations.find(mission), clean, exports.version(), Instant.now());
    }

    /** Pure: no I/O. Event numbers are the position among the mission's events from 1, as in the replay export. */
    static ProofDossier compose(
            Mission m,
            List<EventEnvelope> events,
            MissionReport report,
            Optional<MissionDelegation> delegation,
            ReplaySanitizer clean,
            String version,
            Instant now) {
        return new ProofDossierComposer(m, events, report, clean).compose(delegation, version, now);
    }

    static <T> Section<T> section(List<T> all) {
        return new Section<>(all.size(), all.stream().limit(CAP).toList());
    }

    static String shortCommit(String commit) {
        return commit == null ? null : commit.substring(0, Math.min(12, commit.length()));
    }
}
