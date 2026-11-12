package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Retro;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.Retrospectives;
import dev.floor42.server.app.SchedulerQuiet;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The running app's retrospective meets an archive (ADR-0054), with the fake Claude Code: a mission the director cancels
 * and archives in one go gets no retrospective, and the refusal Floor42 meets is logged as expected (INFO, no stack);
 * restoring the mission starts nothing; the director's click convenes the retrospective it never had, once.
 */
@QuarkusTest
@TestProfile(RetroApiTest.RetroOn.class)
class MissionArchiveRetroTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    dev.floor42.server.journal.Journal journal;

    @Inject
    dev.floor42.server.app.MissionScheduler scheduler;

    /** What the retrospectives logged while the test ran. */
    static final class Captured extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord r) {
            records.add(r);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    @Test
    void archivedBeforeItsRetrospectiveThenRestoredTheTeamMeetsThen() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m =
                new MissionId(api().body("{\"title\":\"Archived early\",\"objective\":\"Describe this repository\","
                                + "\"plan\":true,\"repository\":\""
                                + repo.toString().replace("\\", "\\\\") + "\"}")
                        .post("/api/missions")
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("id"));
        await(
                "the planning plan",
                Duration.ofSeconds(60),
                () -> office.query(c -> c.missions().find(m).orElseThrow().status())
                        == MissionStatus.AWAITING_APPROVAL);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        // The planner's session: the one who worked on it, so a retrospective has someone to convene. Its tickets
        // then wait for the director's approval: nothing else runs.
        await(
                "the planner's session over",
                Duration.ofSeconds(60),
                () -> office.query(c -> !c.sessions().of(m).isEmpty()
                        && c.sessions().of(m).stream().noneMatch(AgentSession::isOpen)));
        // The session's end is not the plan task's end: it then records the proposed tickets, assigns them and closes
        // the planning ticket. A cancel before that leaves those events to land after the snapshot below.
        SchedulerQuiet.await(scheduler);

        Logger log = Logger.getLogger(Retrospectives.class.getName());
        Captured captured = new Captured();
        log.addHandler(captured);
        try {
            // Completed and archived in one command (a cancelled mission convenes none): Floor42 convenes the
            // retrospective after, and meets the archive.
            CommandContext director = office.context(Actor.director(), Origin.REAL);
            office.execute(c -> {
                List<EventEnvelope> events = new ArrayList<>();
                for (var t : c.tickets().of(m)) {
                    if (!t.status().isTerminal()) events.addAll(c.tickets().cancel(director, t.id(), "not needed"));
                }
                events.addAll(c.missions().complete(director, m, "put away at once"));
                events.addAll(c.missions().archive(director, m));
                return events;
            });
            await(
                    "the refusal logged",
                    Duration.ofSeconds(30),
                    () -> captured.records.stream()
                            .anyMatch(r -> r.getMessage() != null
                                    && String.valueOf(r.getMessage())
                                            .contains("was archived before its retrospective")));
        } finally {
            log.removeHandler(captured);
        }
        LogRecord refusal = captured.records.stream()
                .filter(r -> String.valueOf(r.getMessage()).contains("was archived before its retrospective"))
                .findFirst()
                .orElseThrow();
        assertThat(refusal.getLevel().intValue())
                .as("an expected outcome, not an error")
                .isEqualTo(Level.INFO.intValue());
        assertThat(refusal.getThrown()).as("no stack trace").isNull();
        assertThat(captured.records).noneMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue());
        boolean convened = office.query(c -> c.retros().find(m).isPresent());
        assertThat(convened).isFalse();

        // Its planner's session stays in the usage, marked archived: money spent stays visible.
        List<java.util.Map<String, Object>> usage =
                api().get("/api/usage").then().statusCode(200).extract().path("missions");
        assertThat(usage)
                .filteredOn(x -> m.value().equals(x.get("key")))
                .singleElement()
                .satisfies(x -> assertThat(x).containsEntry("archived", true));

        // A restore starts nothing: no retrospective, no session, no event but the restore (paid calls are opt-in).
        // The cancel's own reaction (stopping the mission's sessions) runs on the scheduler: done before the count.
        SchedulerQuiet.await(scheduler);
        long eventsBefore =
                journal.readAll().stream().filter(e -> m.equals(e.mission())).count();
        int sessionsBefore = office.query(c -> c.sessions().of(m).size());
        api().delete("/api/missions/" + m.value() + "/archived").then().statusCode(200);
        SchedulerQuiet.await(scheduler);
        List<EventEnvelope> after = journal.readAll().stream()
                .filter(e -> m.equals(e.mission()))
                .skip(eventsBefore)
                .toList();
        assertThat(after).extracting(e -> e.event().getClass().getSimpleName()).containsExactly("MissionUnarchived");
        int sessionsAfter = office.query(c -> c.sessions().of(m).size());
        assertThat(sessionsAfter).isEqualTo(sessionsBefore);
        boolean convenedOnRestore = office.query(c -> c.retros().find(m).isPresent());
        assertThat(convenedOnRestore).isFalse();

        // The director's click convenes it, once.
        api().get("/api/missions/" + m.value() + "/retro/offer")
                .then()
                .statusCode(200)
                .body("available", org.hamcrest.Matchers.equalTo(true));
        api().post("/api/missions/" + m.value() + "/retro/convene").then().statusCode(200);
        await(
                "the retrospective the director convened, then closed",
                Duration.ofSeconds(60),
                () -> office.query(c -> c.retros().find(m).map(Retro::concluded).orElse(false)));

        Logger root = Logger.getLogger("dev.floor42");
        Captured second = new Captured();
        root.addHandler(second);
        try {
            api().post("/api/missions/" + m.value() + "/retro/convene")
                    .then()
                    .statusCode(409)
                    .body("code", org.hamcrest.Matchers.equalTo("DUPLICATE_RETRO"));
            api().get("/api/missions/" + m.value() + "/retro/offer")
                    .then()
                    .body("available", org.hamcrest.Matchers.equalTo(false))
                    .body("code", org.hamcrest.Matchers.equalTo("DUPLICATE_RETRO"));
        } finally {
            root.removeHandler(second);
        }
        assertThat(second.records)
                .as("a refusal shown, never an error logged")
                .noneMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue());
        long opened = journal.readAll().stream()
                .filter(e ->
                        m.equals(e.mission()) && e.event() instanceof dev.floor42.domain.event.RetroEvent.RetroOpened)
                .count();
        assertThat(opened).isEqualTo(1);
    }
}
