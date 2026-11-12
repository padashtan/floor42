package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.TicketId;
import dev.floor42.server.runtime.SessionReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Asks the director whether to run a failed step again. While "Retry" is open the mission waits: the dispatcher never
 * starts the same task again on its own.
 */
@ApplicationScoped
public class RetryDesk {

    static final String LEAVE_IT = "Leave it";

    private final OfficeService office;

    @Inject
    RetryDesk(OfficeService office) {
        this.office = office;
    }

    void ask(MissionId m, TicketId t, AgentId agent, SessionReport report) {
        ask(
                m,
                t,
                agent,
                report.notes().isEmpty() ? "the session ended without a report" : String.join("; ", report.notes()));
    }

    void ask(MissionId m, TicketId t, AgentId agent, String why) {
        String retry = OptionCode.RETRY.label();
        office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.system(), Origin.REAL),
                        m,
                        "The session of " + agent + " on " + t + " failed",
                        why,
                        List.of(retry, LEAVE_IT),
                        retry,
                        Criticality.HIGH,
                        false,
                        t,
                        Phrase.of("sessionFailed", "agent", agent.value(), "ticket", t.value())));
    }
}
