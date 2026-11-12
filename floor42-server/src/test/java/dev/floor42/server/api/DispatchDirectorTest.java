package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.StaffingReview;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.LiveMissions;
import dev.floor42.server.app.SchedulerQuiet;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * What the dispatcher does with the director's decisions (ADR-0019): nothing starts while a review escalation waits,
 * only the director's yes hires, a note made into a ticket goes to work, and a suspended mission moves only once
 * resumed.
 */
@QuarkusTest
class DispatchDirectorTest extends DispatchFixture {

    @Inject
    LiveMissions liveMissions;

    @Test
    void answeringAnEscalatedReviewLetsTheNextTicketStart() throws Exception {
        MissionId m = mission("Write notes [fake:unverifiable]");
        approve(m);
        Supplier<Question> escalation = () -> office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN && q.kind() == QuestionKind.REVIEW_ESCALATION)
                .findFirst()
                .orElse(null));
        await("the review escalates", Duration.ofSeconds(60), () -> {
            allowActions(m);
            return escalation.get() != null;
        });
        // Added while the mission waits for the director, then the dispatcher is asked to look again: it still starts
        // nothing. The same ticket, with the same agent, starts once the director answers (the control below), so
        // what holds it here is the open escalation, not a ticket that could never run.
        TicketId next = ticket(m, "Deliver: more notes", "Write notes", List.of());
        assign(next, "chris");
        liveMissions.nudge(m);
        quiet();
        assertThat(status(next))
                .as("nothing starts while the director is asked")
                .isEqualTo(TicketStatus.TODO);

        api().body("{\"answer\":\"Accept as is\"}")
                .post("/api/questions/" + escalation.get().id().value() + "/answer")
                .then()
                .statusCode(200);
        await("the next ticket starts", Duration.ofSeconds(30), () -> status(next) != TicketStatus.TODO);
        cancel(m);
    }

    /**
     * Rejected twice, the ticket waits for the director, said on the ticket itself rather than a silent IN_PROGRESS;
     * the answer alone sends the developer back to work, without a nudge.
     */
    @Test
    void answeringTheRejectedTwiceQuestionSendsTheDeveloperBackToWork() throws Exception {
        MissionId m = mission("Write notes [fake:rejected]");
        approve(m);
        Supplier<Question> rejected = () -> office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN
                        && q.phrase() != null
                        && q.phrase().code().equals("rejectedTimes"))
                .findFirst()
                .orElse(null));
        await("the ticket is rejected twice", Duration.ofSeconds(90), () -> {
            allowActions(m);
            return rejected.get() != null;
        });
        Question q = rejected.get();
        TicketId t = q.ticket();
        quiet();
        assertThat(status(t)).isEqualTo(TicketStatus.IN_PROGRESS);
        api().get("/api/missions/" + m.value())
                .then()
                .statusCode(200)
                .body("tickets.find { it.id == '" + t.value() + "' }.waitingFor", Matchers.equalTo(q.id().value()));
        Supplier<Long> produced = () -> office.query(c -> c.sessions().of(m).stream()
                .filter(s -> s.ticket().equals(t)
                        && s.agent().equals(c.tickets().find(t).orElseThrow().assignee()))
                .count());
        long before = produced.get();

        api().body("{\"answer\":\"Try once more with the notes\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);
        await("the developer is back at work, with no nudge", Duration.ofSeconds(30), () -> {
            allowActions(m);
            return produced.get() > before;
        });
        cancel(m);
    }

    /**
     * A report naming a step the sandbox prevents, rejected each time: the ticket waits in review on ONE question that
     * names what is missing, never on a loop of sessions; the director's answer sends it back, and the next rejection
     * asks the same question again, not "rejected N times" on top.
     */
    @Test
    void aStepTheEnvironmentPreventsStopsTheReviewLoopOnOneQuestion() throws Exception {
        MissionId m = mission("Write notes [fake:env-blocked] [fake:env-only]");
        approve(m);
        Supplier<List<Question>> open = () -> office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN && q.phrase() != null)
                .toList());
        await("the environment question is asked", Duration.ofSeconds(90), () -> {
            allowActions(m);
            return !open.get().isEmpty();
        });
        quiet();
        Question q = open.get().getFirst();
        assertThat(q.phrase().code()).isEqualTo("environmentBlocked");
        assertThat(q.phrase().params()).containsEntry("missing", "a desktop browser");
        assertThat(status(q.ticket())).isEqualTo(TicketStatus.IN_REVIEW);
        // The screens read the blocker, to warn before a directive that would only repeat the step.
        String blocker = "tickets.find { it.id == '" + q.ticket().value() + "' }.environmentBlocker.missing";
        api().get("/api/missions/" + m.value())
                .then()
                .statusCode(200)
                .body(blocker, Matchers.equalTo("a desktop browser"));
        api().get("/api/missions/" + m.value() + "/report")
                .then()
                .statusCode(200)
                .body(blocker, Matchers.equalTo("a desktop browser"));

        api().body("{\"answer\":\"I install what is missing\"}")
                .post("/api/questions/" + q.id().value() + "/answer")
                .then()
                .statusCode(200);
        await("the second round asks again", Duration.ofSeconds(90), () -> {
            allowActions(m);
            return open.get().stream().anyMatch(x -> !x.id().equals(q.id()));
        });
        quiet();
        assertThat(open.get())
                .extracting(x -> x.phrase().code())
                .as("one question, not the rejected-twice one on top")
                .containsExactly("environmentBlocked");
        cancel(m);
    }

    /** The real missions in progress: those the dispatcher's staffChanged looks at. */
    List<MissionId> realInProgress() {
        return office.query(c -> c.missions().all().stream()
                .filter(x -> x.origin() == Origin.REAL && x.status() == MissionStatus.IN_PROGRESS)
                .map(x -> x.id())
                .toList());
    }

    /** The ids of the agents named Ida, dismissed ones included: other tests hire and dismiss theirs. */
    Set<AgentId> idas() {
        return office.query(c -> c.staff().all().stream()
                .filter(a -> a.displayName().equals("Ida"))
                .map(Agent::id)
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void thePlannerAdvisesOnStaffingAndOnlyTheDirectorsYesHires() throws Exception {
        Set<AgentId> before = idas();
        MissionId m = mission("Write notes [fake:slow=3]");
        approve(m);
        api().post("/api/missions/" + m.value() + "/staffing").then().statusCode(200);

        Supplier<Question> asked = () -> office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.kind() == QuestionKind.STAFFING && q.status() == QuestionStatus.OPEN)
                .findFirst()
                .orElse(null));
        await("the planner's advice", Duration.ofSeconds(60), () -> {
            answerAll(m, null);
            return asked.get() != null;
        });
        Question q = asked.get();
        assertThat(q.options()).containsExactly("Hire them", "Not now");
        assertThat(q.blocking()).isFalse();
        StaffingReview review = office.query(c -> c.staff().staffingReview(m).orElseThrow());
        assertThat(status(review.ticket())).isEqualTo(TicketStatus.DONE);
        assertThat(idas()).as("nothing is hired before the director's yes").isEqualTo(before);

        // The hire runs on a raw thread (Staffing) the scheduler does not track; its last step makes every mission in
        // progress look again. Seeing those forks come from it is its observable end: nothing of it can then leak
        // into the next test after the fixture's quiet.
        String hireThread = "hire-" + m;
        Set<String> forkedByHire = ConcurrentHashMap.newKeySet();
        SchedulerQuiet.probe(scheduler, (thread, task) -> {
            if (thread.equals(hireThread)) forkedByHire.add(task);
        });
        try {
            api().body("{\"answer\":\"Hire them\"}")
                    .post("/api/questions/" + q.id().value() + "/answer")
                    .then()
                    .statusCode(200);

            await("Ida is hired", Duration.ofSeconds(10), () -> idas().size() == before.size() + 1);
            await(
                    "the parallel sessions are raised to 3",
                    Duration.ofSeconds(10),
                    () -> "3"
                            .equals(api().get("/api/settings")
                                    .then()
                                    .extract()
                                    .path("find { it.key == 'PARALLEL_SESSIONS' }.value")));
            // Every real mission in progress, this one included (it is cancelled only after).
            await(
                    "the hire to make the missions in progress look again",
                    Duration.ofSeconds(10),
                    () -> forkedByHire.contains("staff-" + m)
                            && realInProgress().stream().allMatch(x -> forkedByHire.contains("staff-" + x)));
            cancel(m);
        } finally {
            SchedulerQuiet.probe(scheduler, null);
            // The setting is the company's: put back the default this test changed.
            api().body("{\"value\":null}")
                    .put("/api/settings/PARALLEL_SESSIONS")
                    .then()
                    .statusCode(200);
        }
        AgentId ida =
                idas().stream().filter(id -> !before.contains(id)).findFirst().orElseThrow();
        dismissWhenFree(ida.value());
    }

    @Test
    void aNoteTheDirectorMakesIntoATicketGoesToWorkWithoutApproval() throws Exception {
        MissionId m = mission("Write notes");
        approve(m);
        String note = api().body("{\"text\":\"Show the payload of a request\"}")
                .post("/api/notes")
                .then()
                .statusCode(200)
                .extract()
                .path("[-1].id");

        String ticket = api().body("{\"mission\":\"" + m.value() + "\"}")
                .post("/api/notes/" + note + "/ticket")
                .then()
                .statusCode(200)
                .extract()
                .path("find { it.id == '" + note + "' }.ticket");

        TicketId made = new TicketId(ticket);
        await("the note's ticket is done", Duration.ofSeconds(90), () -> {
            answerAll(m, null);
            return status(made) == TicketStatus.DONE;
        });
        await(
                "the note is closed into the archive",
                Duration.ofSeconds(10),
                () -> office.query(c -> c.notebook().notes().stream().anyMatch(n -> n.id().equals(note) && n.done())));
        cancel(m);
    }

    @Test
    void theDirectorResumesASuspendedMission() throws Exception {
        MissionId m = mission("Describe this repository [fake:slow=2]");
        approve(m);
        office.execute(
                c -> c.missions().suspend(office.context(Actor.director(), Origin.REAL), m, "incident under review"));
        await(
                "the running session to end",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.sessions().of(m).stream().noneMatch(AgentSession::isOpen)));
        // Even when the dispatcher looks again, nothing moves while suspended.
        liveMissions.nudge(m);
        quiet();
        MissionStatus status =
                office.query(c -> c.missions().find(m).orElseThrow().status());
        assertThat(status).isEqualTo(MissionStatus.SUSPENDED);
        assertThat(done(m)).as("nothing moves while suspended").isFalse();

        api().post("/api/missions/" + m.value() + "/resume")
                .then()
                .statusCode(200)
                .body("status", Matchers.equalTo("IN_PROGRESS"));
        await("the work to finish after the resume", Duration.ofSeconds(60), () -> {
            answerAll(m, null);
            return done(m);
        });
        cancel(m);
    }
}
