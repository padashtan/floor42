package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.EnvironmentBlocker;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.Priority;
import dev.floor42.domain.Question;
import dev.floor42.domain.Setting;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.workflow.Workflow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Docker question's answers mean only what they say: "Go on without Docker" never reads as the dependency
 * question's "Go on without it" (which skips the other developers' branches), and the director's step names only the
 * tickets that NEED docker. The dispatcher is off: the state is what the test builds.
 */
@QuarkusTest
@TestProfile(StallTest.DispatcherOff.class)
class DockerAnswersTest {

    @Inject
    OfficeService office;

    @Inject
    DockerQuestion docker;

    CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    @AfterEach
    void sandboxOff() {
        office.execute(c -> c.preferences().changeSetting(director(), Setting.SHELL_SANDBOX, null));
    }

    @Test
    void goingOnWithoutDockerKeepsTheDependenciesAndOnlyTheDockerTicketIsTheDirectors() {
        office.execute(c -> c.preferences().changeSetting(director(), Setting.SHELL_SANDBOX, "wsl"));
        MissionId m = ((MissionCreated) office.execute(
                                c -> c.missions().create(director(), "Image", "Build", "C:/x", Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        List<EventEnvelope> first = office.execute(c ->
                c.tickets().create(director(), m, "Write the code", "", Priority.NORMAL, List.of(), List.of("code")));
        TicketId code = ((TicketCreated) first.getFirst().event()).ticket();
        List<EventEnvelope> second = office.execute(c -> c.tickets()
                .create(director(), m, "Build the image", "", Priority.NORMAL, List.of(code), List.of("image")));
        TicketId image = ((TicketCreated) second.getFirst().event()).ticket();
        List<EventEnvelope> planned = new ArrayList<>(first);
        planned.addAll(second);

        office.execute(c ->
                docker.askWithin(c, m, List.of("Dockerfile", "plan"), planned, List.of(List.of(), List.of("docker"))));

        Question q = office.query(c -> c.desk().of(m).getFirst());
        assertThat(q.options()).containsExactly("I do the step myself", "Go on without Docker");
        assertThat(q.phrase().params()).containsEntry("tickets", image.value());
        office.execute(c -> c.desk().answer(director(), q.id(), OptionCode.WITHOUT_DOCKER.label()));

        // The image ticket builds on another developer's code: going on without Docker says nothing of that branch.
        boolean without = office.query(
                c -> Dependencies.wentOnWithout(c, c.tickets().find(image).orElseThrow()));
        assertThat(without).isFalse();

        // Control: the dependency question's own answer does.
        office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.system(), Origin.REAL),
                        m,
                        image + " builds on work done on other branches",
                        "",
                        List.of(OptionCode.GO_ON_WITHOUT.label(), OptionCode.DROP_TICKET.label()),
                        null,
                        Criticality.MEDIUM,
                        false,
                        image,
                        Phrase.of(DependencyDesk.CODE, "ticket", image.value())));
        Question dependencies = office.query(c -> c.desk().of(m).getLast());
        office.execute(c -> c.desk().answer(director(), dependencies.id(), OptionCode.GO_ON_WITHOUT.label()));
        boolean withoutDependencies = office.query(
                c -> Dependencies.wentOnWithout(c, c.tickets().find(image).orElseThrow()));
        assertThat(withoutDependencies).isTrue();
    }

    @Test
    void withNoTicketNamingDockerTheDirectorsStepIsTheMissionsNotEveryTickets() {
        office.execute(c -> c.preferences().changeSetting(director(), Setting.SHELL_SANDBOX, "wsl"));
        MissionId m = ((MissionCreated) office.execute(
                                c -> c.missions().create(director(), "Image", "Build", "C:/x", Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        List<EventEnvelope> planned = office.execute(c ->
                c.tickets().create(director(), m, "Write the code", "", Priority.NORMAL, List.of(), List.of("code")));
        TicketId code = ((TicketCreated) planned.getFirst().event()).ticket();

        office.execute(c -> docker.askWithin(c, m, List.of("Dockerfile"), planned, List.of(List.of())));
        Question q = office.query(c -> c.desk().of(m).getFirst());
        assertThat(q.ticket()).isNull();
        assertThat(q.phrase().params()).containsEntry("tickets", "");
        office.execute(c -> c.desk().answer(director(), q.id(), OptionCode.DO_IT_MYSELF.label()));

        java.util.Optional<EnvironmentBlocker> ticketStep =
                office.query(c -> EnvironmentBlocker.directorStep(c.desk().of(m), code));
        java.util.Optional<EnvironmentBlocker> missionStep =
                office.query(c -> EnvironmentBlocker.missionStep(c.desk().of(m)));
        assertThat(ticketStep).isEmpty();
        assertThat(missionStep).map(EnvironmentBlocker::missing).contains("docker");
    }
}
