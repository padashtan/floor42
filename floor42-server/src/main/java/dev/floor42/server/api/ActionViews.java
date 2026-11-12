package dev.floor42.server.api;

import dev.floor42.domain.Action;
import dev.floor42.domain.DecisionBasis;
import dev.floor42.domain.Incident;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.RecordedReason;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read models of what agents asked to do, and of the incidents Floor42 raised about it. */
public final class ActionViews {

    private ActionViews() {}

    /**
     * {@code ran} tells the UI whether a machine may be animated for this action (only real executions qualify).
     * {@code decisionBasisCode} and its params give the basis Floor42 wrote, so the screen writes it in its language;
     * null for a basis it did not write (a guard's finding, a tool's error), shown as recorded.
     */
    public record ActionView(
            String id,
            String ticket,
            String agent,
            String category,
            String tool,
            String summary,
            String stage,
            boolean ran,
            String decisionBasis,
            String denialReason,
            boolean authorizedByOffice,
            String result,
            String requestedAt,
            String updatedAt,
            Long runningSeconds,
            String decisionBasisCode,
            Map<String, String> decisionBasisParams) {
        public ActionView {
            decisionBasisParams = decisionBasisParams == null
                    ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(decisionBasisParams));
        }

        static ActionView of(Action a) {
            Phrase basis = DecisionBasis.read(a.decisionBasis()).orElse(null);
            return new ActionView(
                    a.id().value(),
                    a.ticket() == null ? null : a.ticket().value(),
                    a.agent().value(),
                    a.category().name(),
                    a.tool(),
                    a.summary(),
                    a.stage().name(),
                    a.stage().hasRun(),
                    a.decisionBasis(),
                    a.denialReason() == null ? null : a.denialReason().name(),
                    a.authorizedByOffice(),
                    a.result(),
                    a.requestedAt().toString(),
                    a.updatedAt().toString(),
                    a.runningSeconds(),
                    basis == null ? null : basis.code(),
                    basis == null ? null : basis.params());
        }
    }

    /** {@code descriptionCode} and its params: the description Floor42 wrote, for the screen's language; else null. */
    public record IncidentView(
            String id,
            String criticality,
            String description,
            String action,
            String raisedAt,
            String descriptionCode,
            Map<String, String> descriptionParams) {
        public IncidentView {
            descriptionParams = descriptionParams == null
                    ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(descriptionParams));
        }

        static IncidentView of(Incident i) {
            Phrase said = RecordedReason.read(i.description()).orElse(null);
            return new IncidentView(
                    i.id().value(),
                    i.criticality().name(),
                    i.description(),
                    i.action() == null ? null : i.action().value(),
                    i.raisedAt().toString(),
                    said == null ? null : said.code(),
                    said == null ? null : said.params());
        }
    }
}
