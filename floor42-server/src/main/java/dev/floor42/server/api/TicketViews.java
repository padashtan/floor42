package dev.floor42.server.api;

import dev.floor42.domain.Company;
import dev.floor42.domain.EnvironmentBlocker;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.server.app.Delivered;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read models of a mission's tickets, with their reviews and the help reviewers asked for. */
public final class TicketViews {

    private TicketViews() {}

    /** @param items its reservations and notes (ADR-0053); null for a review journaled before 1.13.0 */
    public record ReviewView(String reviewer, String verdict, String findings, Delivered.ReviewItemsView items) {
        static ReviewView of(Ticket.Review r) {
            Delivered.DeliveredReview d = Delivered.DeliveredReview.of(r);
            return new ReviewView(d.reviewer(), d.verdict(), d.findings(), d.items());
        }
    }

    /** Help a reviewer asked for (ADR-0018); answeredBy null when Floor42 answered itself. */
    public record ConsultationView(
            int round, String reviewer, String kind, String role, String question, String answer, String answeredBy) {
        static ConsultationView of(Ticket.Consultation c) {
            return new ConsultationView(
                    c.round(),
                    c.reviewer().value(),
                    c.kind().name(),
                    c.role(),
                    c.question(),
                    c.answer(),
                    c.answeredBy() == null ? null : c.answeredBy().value());
        }
    }

    /** One run of a node's test by Floor42 (ADR-0062); {@code outcome} is passed, failed or not run. */
    public record NodeTestView(String command, String outcome, Integer exitCode, long durationMs, String at) {
        static NodeTestView of(dev.floor42.domain.CheckRun r) {
            return new NodeTestView(
                    r.command(),
                    r.passed() ? "passed" : r.failed() ? "failed" : "not run",
                    r.exitCode(),
                    r.durationMs(),
                    r.at().toString());
        }
    }

    /** What the plan settled for a node of a graph mission, and what its tests showed. */
    public record NodeView(String risk, String tier, List<String> tests, List<NodeTestView> runs) {
        public NodeView {
            runs = runs == null ? null : Collections.unmodifiableList(new ArrayList<>(runs));
            tests = tests == null ? null : Collections.unmodifiableList(new ArrayList<>(tests));
        }

        static NodeView of(Company c, Ticket t) {
            if (!c.workflows().of(t.mission()).runnable().map(w -> w.graph()).orElse(false)) return null;
            return c.workflows()
                    .nodeOf(t.id())
                    .map(n -> new NodeView(
                            n.risk().wire(),
                            n.tier().wire(),
                            n.tests(),
                            c.verification().checkRuns(t.mission()).stream()
                                    .filter(r ->
                                            r.nodeTest().filter(t.id()::equals).isPresent())
                                    .map(NodeTestView::of)
                                    .toList()))
                    .orElse(null);
        }
    }

    public record TicketView(
            String id,
            String title,
            String description,
            String priority,
            String status,
            String assignee,
            List<String> dependsOn,
            List<String> acceptanceCriteria,
            String deliverable,
            List<ReviewView> reviews,
            List<String> directives,
            List<ConsultationView> consultations,
            String createdAt,
            String startedAt,
            String endedAt,
            String kind,
            /**
             * The open question of the director's that holds this ticket (rejected too often, for instance): no work
             * starts on it until it is answered, and the answer sends it back to work. Null when none holds it.
             */
            String waitingFor,
            /**
             * What its last report says the environment lacks: a directive alone will not provide it, so the screen
             * warns before sending the ticket back to work. Null when the report says none.
             */
            EnvironmentBlocker environmentBlocker,
            /** Graph mode (ADR-0062): the node's risk, tier, tests and their runs; null for any other ticket. */
            NodeView node,
            /** Graph mode: a done node one of whose dependencies was reopened since; derived, never acted on. */
            boolean toRecheck) {
        public TicketView {
            acceptanceCriteria = acceptanceCriteria == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<>(acceptanceCriteria));
            consultations = consultations == null ? null : Collections.unmodifiableList(new ArrayList<>(consultations));
            dependsOn = dependsOn == null ? null : Collections.unmodifiableList(new ArrayList<>(dependsOn));
            directives = directives == null ? null : Collections.unmodifiableList(new ArrayList<>(directives));
            reviews = reviews == null ? null : Collections.unmodifiableList(new ArrayList<>(reviews));
        }

        static TicketView of(Company c, Ticket t) {
            return new TicketView(
                    t.id().value(),
                    t.title(),
                    t.description(),
                    t.priority().name(),
                    t.status().name(),
                    t.assignee() == null ? null : t.assignee().value(),
                    t.dependsOn().stream().map(TicketId::value).toList(),
                    t.acceptanceCriteria(),
                    t.deliverable(),
                    t.reviews().stream().map(ReviewView::of).toList(),
                    t.directives(),
                    t.consultations().stream().map(ConsultationView::of).toList(),
                    iso(t.createdAt()),
                    iso(t.startedAt()),
                    iso(t.endedAt()),
                    t.kind().name(),
                    t.status().isTerminal() ? null : waitingFor(c, t),
                    t.environmentBlocker().orElse(null),
                    NodeView.of(c, t),
                    c.tickets().toRecheck(t.id()));
        }

        /** The mission's tickets, in their order. */
        static List<TicketView> all(Company c, dev.floor42.domain.MissionId m) {
            return c.tickets().of(m).stream().map(t -> of(c, t)).toList();
        }

        /** As the dispatcher reads it (TaskSelection): an open question about the ticket holds it. */
        private static String waitingFor(Company c, Ticket t) {
            return c.desk().of(t.mission()).stream()
                    .filter(q -> q.status() == QuestionStatus.OPEN && t.id().equals(q.ticket()))
                    .map(q -> q.id().value())
                    .findFirst()
                    .orElse(null);
        }

        private static String iso(Instant at) {
            return at == null ? null : at.toString();
        }
    }
}
