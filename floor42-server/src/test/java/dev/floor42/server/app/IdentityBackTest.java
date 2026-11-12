package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.iam.IamRecovered;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** D32: Keycloak reachable again makes the dispatcher look at the real missions in PLANNING too (live missions on). */
@QuarkusTest
class IdentityBackTest {

    @Inject
    OfficeService office;

    @Inject
    MissionScheduler scheduler;

    @Inject
    Event<IamRecovered> recovered;

    @Test
    void aMissionInPlanningIsNudgedWhenKeycloakIsReachableAgain() throws Exception {
        CommandContext director = office.context(Actor.director(), Origin.REAL);
        MissionId planning = ((MissionCreated) office.execute(c -> c.missions()
                                .create(director, "Waits", "Plan me", "C:/not-a-repository", Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        MissionStatus status =
                office.query(c -> c.missions().find(planning).orElseThrow().status());
        assertThat(status).isEqualTo(MissionStatus.PLANNING);
        List<String> forked = new CopyOnWriteArrayList<>();
        SchedulerQuiet.probe(scheduler, (thread, task) -> forked.add(task));
        try {
            recovered.fire(new IamRecovered());
            SchedulerQuiet.await(scheduler);
        } finally {
            SchedulerQuiet.probe(scheduler, null);
        }

        assertThat(forked).contains("iam-" + planning);
    }
}
