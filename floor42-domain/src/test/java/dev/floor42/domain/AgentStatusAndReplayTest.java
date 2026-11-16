package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DEHREN;
import static dev.floor42.domain.Fixture.GEOFFREY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(GoldenFixtureExtension.class)
class AgentStatusAndReplayTest {

    final Fixture f = new Fixture();

    @Test
    void statusFollowsTheFacts() {
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.AVAILABLE);

        MissionId m = f.mission();
        TicketId t = f.ticket(m, "Validate audience");
        f.record(f.company.tickets().assign(f.director(), t, CHRIS));
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.ASSIGNED);

        f.record(f.company.missions().submitPlan(f.agent(GEOFFREY), m));
        f.record(f.company.missions().approvePlan(f.director(), m));
        f.record(f.company.tickets().start(f.agent(CHRIS), t));
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.WORKING);

        f.allow(m, ActionCategory.EXECUTE, null, null);
        f.record(f.company
                .actions()
                .request(f.agent(CHRIS), new ActionId("a1"), m, t, CHRIS, ActionCategory.EXECUTE, "Bash", "mvn test"));
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.EXECUTING);

        f.record(f.company.actions().recordOutcome(f.system(), new ActionId("a1"), true, "ok"));
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.WORKING);

        f.record(f.company.missions().suspend(f.director(), m, "pause"));
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.PAUSED);
    }

    @Test
    void anAgentIsNoLongerExecutingOnceItsSessionEndedWithoutTheOutcome() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", null));
        f.allow(m, ActionCategory.EXECUTE, null, null);
        f.record(f.company
                .actions()
                .request(
                        f.agent(CHRIS),
                        new ActionId("a1"),
                        m,
                        t,
                        CHRIS,
                        ActionCategory.EXECUTE,
                        "Bash",
                        "stop the daemon"));
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.EXECUTING);

        // The daemon died under the command: at the next start the session is closed, the outcome never came.
        f.record(f.company.sessions().end(f.system(), "s1", true, "interrupted", null, null, null, null));

        assertThat(f.company.queries().statusOf(CHRIS)).isNotEqualTo(AgentStatus.EXECUTING);
        Action a = f.company.actions().find(new ActionId("a1")).orElseThrow();
        assertThat(a.stage()).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(a.orphaned()).isTrue();
    }

    @Test
    void anAgentNeverAppearsBusyOnAClosedMission() {
        MissionId m = f.runningMission();
        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.WORKING);

        f.record(f.company.missions().cancel(f.director(), m, "dropped"));

        assertThat(f.company.queries().statusOf(CHRIS)).isEqualTo(AgentStatus.AVAILABLE);
    }

    @Test
    void replayingTheJournalRebuildsTheSameCompany() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.allow(m, ActionCategory.EXECUTE, "mvn", CHRIS);
        f.record(f.company
                .actions()
                .request(f.agent(CHRIS), new ActionId("a1"), m, t, CHRIS, ActionCategory.EXECUTE, "Bash", "mvn test"));
        f.record(f.company.actions().recordOutcome(f.system(), new ActionId("a1"), true, "BUILD SUCCESS"));
        f.record(f.company
                .desk()
                .ask(
                        f.agent(GEOFFREY),
                        m,
                        "Gateway or services?",
                        "",
                        List.of("gateway", "services"),
                        "gateway",
                        Criticality.HIGH,
                        false,
                        null));
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "patch"));
        f.record(f.company
                .tickets()
                .review(f.agent(DEHREN), t, ReviewVerdict.VALIDATED_WITH_RESERVATIONS, "ok, docs missing"));

        Company rebuilt = Company.replay(f.journal);

        assertThat(rebuilt.version()).isEqualTo(f.company.version()).isEqualTo(f.journal.size());
        assertThat(snapshot(rebuilt)).isEqualTo(snapshot(f.company));
    }

    /** Everything observable, flattened: two companies with the same snapshot are indistinguishable to clients. */
    static String snapshot(Company c) {
        StringBuilder s = new StringBuilder();
        c.staff()
                .all()
                .forEach(a -> s.append(a.id())
                        .append(a.suspended())
                        .append(c.queries().statusOf(a.id()))
                        .append('\n'));
        for (Mission m : c.missions().all()) {
            s.append(m.id())
                    .append(m.status())
                    .append(m.origin())
                    .append(m.policy().rules())
                    .append(m.outcome())
                    .append('\n');
            c.tickets()
                    .of(m.id())
                    .forEach(t -> s.append(t.id())
                            .append(t.status())
                            .append(t.assignee())
                            .append(t.reviews())
                            .append(t.deliverable())
                            .append('\n'));
            c.actions()
                    .of(m.id())
                    .forEach(a -> s.append(a.id())
                            .append(a.stage())
                            .append(a.decisionBasis())
                            .append(a.result())
                            .append(a.authorizedByOffice())
                            .append('\n'));
            c.desk()
                    .of(m.id())
                    .forEach(q -> s.append(q.id())
                            .append(q.status())
                            .append(q.answer())
                            .append('\n'));
        }
        s.append(c.actions().incidents().stream().map(Incident::description).collect(Collectors.joining()));
        return s.toString();
    }
}
