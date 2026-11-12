package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.ConsultationKind;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.server.runtime.SessionReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Answers the reviewer's pending request (a colleague, or web research), then asks for the review again. */
@ApplicationScoped
public class ConsultRunner implements TaskRunner {

    private final OfficeService office;
    private final HelperSessions helpers;
    private final SessionGate gate;
    private final MissionPermissions permissions;
    private final ReviewRunner reviews;

    @Inject
    ConsultRunner(
            OfficeService office,
            HelperSessions helpers,
            SessionGate gate,
            MissionPermissions permissions,
            ReviewRunner reviews) {
        this.office = office;
        this.helpers = helpers;
        this.gate = gate;
        this.permissions = permissions;
        this.reviews = reviews;
    }

    @Override
    public Work work() {
        return Work.CONSULT;
    }

    @Override
    public void run(MissionId m, Task task) {
        TicketId t = task.ticket();
        AgentId reviewer = task.agent();
        HelperSessions.Request request = office.query(c -> {
            Ticket x = c.tickets().find(t).orElseThrow();
            return new HelperSessions.Request(
                    m, t, x.assignee(), x.consultations().getLast());
        });
        List<PermissionRule> temporary = new ArrayList<>();
        try {
            switch (helpers.prepare(request, task.helper())) {
                case HelperSessions.Answer a -> {
                    answerByOffice(m, t, reviewer, a.text());
                    return;
                }
                case HelperSessions.Session s -> {
                    temporary.addAll(s.temporary());
                    if (!answered(request, reviewer, s)) return;
                }
            }
        } finally {
            for (PermissionRule rule : temporary) permissions.remove(m, rule);
        }
        reviews.review(m, t, reviewer);
    }

    /**
     * Runs the helper's session. True when its answer is recorded and the review follows; false when the session was
     * held, or failed (Floor42 then answers, and the review goes on without it).
     */
    private boolean answered(HelperSessions.Request request, AgentId reviewer, HelperSessions.Session s) {
        Optional<SessionReport> run = gate.run(s.spec());
        if (run.isEmpty()) return false;
        SessionReport report = run.get();
        if (report.failed() || report.finalText() == null || report.finalText().isBlank()) {
            answerByOffice(request.mission(), request.ticket(), reviewer, failure(request.consultation(), report));
            return false;
        }
        AgentId by = s.spec().agent();
        office.execute(c -> c.tickets()
                .answerConsultation(
                        office.context(Actor.agent(by), Origin.REAL), request.ticket(), report.finalText()));
        return true;
    }

    private static String failure(Ticket.Consultation h, SessionReport report) {
        return "The " + (h.kind() == ConsultationKind.CONSULT ? "consultation" : "research") + " session failed"
                + (report.notes().isEmpty() ? "" : ": " + String.join("; ", report.notes()))
                + ". Conclude without it.";
    }

    private void answerByOffice(MissionId m, TicketId t, AgentId reviewer, String answer) {
        office.execute(c -> c.tickets().answerConsultation(office.context(Actor.system(), Origin.REAL), t, answer));
        reviews.review(m, t, reviewer);
    }
}
