package dev.floor42.server.app;

import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.WorkflowInterpreter;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterEnd;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterRuling;
import dev.floor42.domain.workflow.WorkflowInterpreter.Position;
import dev.floor42.domain.workflow.WorkflowInterpreter.TicketStep;

/** What the equivalence bench asks a mission's workflow: the daemon's interpreter, or a mutant it must catch. */
public interface WorkflowDecider {

    Position position(Company c, MissionId m);

    TicketStep step(Company c, Ticket t, WorkRole role);

    AfterRuling afterRuling(Company c, CommitteeArbitrated ruling);

    AfterEnd afterEnd(Company c, MissionId m);

    /** As the daemon decides: {@code MissionInterpreter}, each mission's own workflow. */
    static WorkflowDecider daemon() {
        MissionInterpreter i = new MissionInterpreter();
        return new WorkflowDecider() {
            public Position position(Company c, MissionId m) {
                return i.position(c, m);
            }

            public TicketStep step(Company c, Ticket t, WorkRole role) {
                return i.step(c, t, role);
            }

            public AfterRuling afterRuling(Company c, CommitteeArbitrated ruling) {
                return i.afterRuling(c, ruling);
            }

            public AfterEnd afterEnd(Company c, MissionId m) {
                return i.afterEnd(c, m);
            }
        };
    }

    /** A mutant that ignores every pin and runs the built-in workflow: the bench must fail it. */
    static WorkflowDecider ignoringPins() {
        WorkflowInterpreter i = new WorkflowInterpreter(Workflow.BUILT_IN);
        return new WorkflowDecider() {
            public Position position(Company c, MissionId m) {
                return i.position(c, m);
            }

            public TicketStep step(Company c, Ticket t, WorkRole role) {
                return i.step(c, t, role);
            }

            public AfterRuling afterRuling(Company c, CommitteeArbitrated ruling) {
                return i.afterRuling(ruling);
            }

            public AfterEnd afterEnd(Company c, MissionId m) {
                return i.afterEnd(m);
            }
        };
    }
}
