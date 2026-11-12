package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DARIUS;
import static dev.floor42.domain.Fixture.GALEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.MissionEvent;
import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Recent projects (pin, forget) and the director's request to continue an ended session, all journaled. */
@ExtendWith(GoldenFixtureExtension.class)
class RecentProjectsTest {

    final Fixture f = new Fixture(Origin.REAL);

    MissionId realMission(String repo) {
        var events = f.record(
                f.company.missions().create(f.director(), "Secure the APIs", "Add a gateway", repo, Workflow.BUILT_IN));
        return ((MissionEvent.MissionCreated) events.getFirst().event()).mission();
    }

    MissionId running(String repo) {
        MissionId m = realMission(repo);
        TicketId t = f.ticket(m, "Validate token audience");
        f.record(f.company.tickets().assign(f.director(), t, CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(GALEN), m));
        f.record(f.company.verification().setBudget(f.director(), m, null));
        f.record(f.company.missions().approvePlan(f.director(), m));
        f.record(f.company.tickets().start(f.agent(CHRIS), t));
        return m;
    }

    @Test
    void theDirectorPinsAndForgetsProjectsOfRealMissions() {
        MissionId m = realMission("C:/repo");

        f.record(f.company.projects().pin(f.director(), "C:/repo", true));
        assertThat(f.company.projects().mark("C:/repo").orElseThrow().pinned()).isTrue();
        assertThat(f.company.projects().pin(f.director(), "C:/repo", true))
                .as("pinned already")
                .isEmpty();
        assertThat(f.journal.getLast().mission()).as("a company-wide fact").isNull();

        f.record(f.company.projects().forget(f.director(), "C:/repo"));
        assertThat(f.company.projects().forgotten("C:/repo")).isTrue();
        assertThat(f.company.projects().mark("C:/repo").orElseThrow().pinned())
                .as("forgetting unpins")
                .isFalse();
        assertThat(f.company.projects().forget(f.director(), "C:/repo")).isEmpty();

        // Pinning brings it back, and so does a new mission on it.
        f.record(f.company.projects().pin(f.director(), "C:/repo", true));
        assertThat(f.company.projects().forgotten("C:/repo")).isFalse();
        f.record(f.company.projects().forget(f.director(), "C:/repo"));
        realMission("C:/repo");
        assertThat(f.company.projects().forgotten("C:/repo")).isFalse();

        assertThat(f.company.queries().lastActivity(m)).isPresent();
    }

    @Test
    void onlyTheDirectorMarksAProjectAndOnlyOneAMissionWorkedOn() {
        realMission("C:/repo");

        assertThatThrownBy(() -> f.company.projects().pin(f.agent(CHRIS), "C:/repo", true))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
        assertThatThrownBy(() -> f.company.projects().forget(f.system(), "C:/repo"))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
        assertThatThrownBy(() -> f.company.projects().pin(f.director(), "C:/elsewhere", true))
                .extracting("code")
                .isEqualTo("UNKNOWN_PROJECT");
        assertThatThrownBy(() -> f.company.projects().forget(f.director(), " "))
                .extracting("code")
                .isEqualTo("MISSING_PATH");
    }

    @Test
    void theDirectorAsksToContinueTheLatestEndedSessionOfTheWorkInProgress() {
        MissionId m = running("C:/repo");
        TicketId t = f.firstTicket(m);
        f.record(f.company.sessions().start(f.system(), "M-1:s1", t, CHRIS, "claude-sonnet-5-5", null));

        assertThatThrownBy(() -> f.company.sessions().requestContinuation(f.director(), "M-1:s1"))
                .extracting("code")
                .isEqualTo("SESSION_OPEN");
        f.record(
                f.company.sessions().end(f.system(), "M-1:s1", true, AgentSession.INTERRUPTED, null, null, null, null));
        assertThatThrownBy(() -> f.company.sessions().requestContinuation(f.agent(CHRIS), "M-1:s1"))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");

        f.record(f.company.sessions().requestContinuation(f.director(), "M-1:s1"));
        assertThat(f.company.sessions().continuationRequested(m, t, CHRIS)).contains("M-1:s1");
        assertThat(f.company.sessions().continuationRequested(m, t, DARIUS)).isEmpty();

        // The next session takes it up: the request is spent, and the journal says which session it continued.
        f.record(f.company.sessions().start(f.system(), "M-1:s2", t, CHRIS, "claude-sonnet-5-5", null));
        f.record(f.company.sessions().recordContinuation(f.system(), "M-1:s2", "M-1:s1"));
        assertThat(f.company.sessions().continuationRequested(m, t, CHRIS)).isEmpty();
        assertThat(f.company.sessions().find("M-1:s2").orElseThrow().continuedFrom())
                .isEqualTo("M-1:s1");
        assertThatThrownBy(() -> f.company.sessions().recordContinuation(f.director(), "M-1:s2", "M-1:s1"))
                .extracting("code")
                .isEqualTo("FORBIDDEN");

        // An earlier session is not the work in progress any more.
        f.record(f.company.sessions().end(f.system(), "M-1:s2", true, "failed", null, null, null, null));
        assertThatThrownBy(() -> f.company.sessions().requestContinuation(f.director(), "M-1:s1"))
                .extracting("code")
                .isEqualTo("NOT_LATEST_SESSION");
    }

    @Test
    void aReviewSessionIsNotContinued() {
        MissionId m = running("C:/repo");
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "report"));
        f.record(f.company.sessions().start(f.system(), "M-1:r1", t, DARIUS, "claude-haiku-5-5", null));
        f.record(f.company.sessions().end(f.system(), "M-1:r1", true, "failed", null, null, null, null));

        assertThatThrownBy(() -> f.company.sessions().requestContinuation(f.director(), "M-1:r1"))
                .extracting("code")
                .isEqualTo("NOT_CONTINUABLE");
        assertThat(List.of(f.company.sessions().of(m).getFirst().id())).containsExactly("M-1:r1");
    }
}
