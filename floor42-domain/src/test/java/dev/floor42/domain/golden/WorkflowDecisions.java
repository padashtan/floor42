package dev.floor42.domain.golden;

import dev.floor42.domain.Agent;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCancelled;
import dev.floor42.domain.event.MissionEvent.MissionCompleted;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.WorkflowInterpreter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Plays a journal event by event and writes down what the built-in workflow's interpreter decides for each mission,
 * each time it changes, and after each ruling and each end (ADR-0046): a golden master of its decisions. It records;
 * it proves nothing about the former rules. The proof that the interpreter decides as c62612d did is the server's
 * equivalence bench ({@code WorkflowEquivalence}), against code copied from that commit.
 *
 * <p>Roles are the agents' own here ({@code Agent.workRole}); the daemon adds the fallback of its first staff.
 */
public final class WorkflowDecisions {

    private final WorkflowInterpreter interpreter = new WorkflowInterpreter(Workflow.BUILT_IN);
    private final Company company = new Company();
    private final Map<String, String> last = new HashMap<>();
    private final StringBuilder out = new StringBuilder();

    private WorkflowDecisions() {}

    /** The decisions, in text. */
    public static String of(Iterable<EventEnvelope> journal) {
        WorkflowDecisions d = new WorkflowDecisions();
        int n = 0;
        for (EventEnvelope e : journal) {
            d.company.apply(e);
            d.after(++n, e);
        }
        return d.out.toString();
    }

    private void after(int n, EventEnvelope e) {
        String at = "#" + n + " " + e.type();
        switch (e.event()) {
            case CommitteeArbitrated r ->
                line(at + " " + r.mission() + " after ruling " + Canonical.of(interpreter.afterRuling(r)));
            case MissionCompleted done -> line(at + " after end " + Canonical.of(interpreter.afterEnd(done.mission())));
            case MissionCancelled done -> line(at + " after end " + Canonical.of(interpreter.afterEnd(done.mission())));
            default -> {}
        }
        for (Mission m : company.missions().all()) {
            String now = decisions(m);
            if (!now.equals(last.put(m.id().value(), now))) line(at + " " + m.id() + " " + now);
        }
    }

    private String decisions(Mission m) {
        String tickets = company.tickets().of(m.id()).stream()
                .map(t -> t.id() + ":" + interpreter.step(company, t, roleOf(t)))
                .collect(Collectors.joining(" "));
        return Canonical.of(interpreter.position(company, m.id())) + " [" + tickets + "]";
    }

    private WorkRole roleOf(Ticket t) {
        return t.assignee() == null
                ? null
                : company.staff().find(t.assignee()).map(Agent::workRole).orElse(null);
    }

    private void line(String text) {
        out.append(text).append('\n');
    }
}
