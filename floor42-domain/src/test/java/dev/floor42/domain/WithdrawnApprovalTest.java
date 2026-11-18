package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A withdrawn approval question must not leave its action waiting for ever. */
class WithdrawnApprovalTest {

    @Test
    void withdrawingTheApprovalQuestionDeniesItsPendingAction() {
        Fixture f = new Fixture();
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.WRITE, null, Decision.ASK)));
        f.record(f.company
                .actions()
                .request(f.agent(CHRIS), new ActionId("a1"), m, t, CHRIS, ActionCategory.WRITE, "Write", "notes.txt"));
        QuestionId q = f.company.desk().open().getFirst().id();

        f.record(f.company.desk().withdraw(f.director(), q, "not needed"));

        assertThat(f.company.actions().find(new ActionId("a1")).orElseThrow().stage())
                .isEqualTo(ActionStage.DENIED);
    }
}
