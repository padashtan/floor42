package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.domain.workflow.WorkflowDocument;
import dev.floor42.domain.workflow.WorkflowVersion;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Versions are derived from the saves, never stored (ADR-0052): the number of saves of a name since its latest removal,
 * and the version a mission's pin is a copy of is found by comparing documents. Nothing here is written to the journal.
 */
class WorkflowVersionsTest {

    final Fixture f = new Fixture(Origin.REAL);

    @AfterEach
    void forgetFixture() {
        Fixture.takeMade();
    }

    private static Workflow light(String name) {
        return new Workflow(
                name,
                List.of(
                        new Step.Plan(2),
                        new Step.Approval(),
                        new Step.Produce(),
                        new Step.Review(),
                        new Step.Accept()));
    }

    private static Workflow decide(String name) {
        return new Workflow(name, List.of(new Step.Approval(), new Step.Committee(), new Step.Accept()));
    }

    private void save(Workflow w, boolean replace) {
        f.record(f.company.workflows().save(f.director(), w, replace, null));
    }

    private MissionId pin(Workflow w) {
        return ((MissionCreated) f.record(f.company.missions().create(f.director(), "t", "o", "C:/repo", w))
                        .getFirst()
                        .event())
                .mission();
    }

    private List<Integer> numbers(String name) {
        return f.company.workflows().versions(name).stream()
                .map(WorkflowVersion::number)
                .toList();
    }

    @Test
    void aWorkflowsVersionIsTheNumberOfItsSavesSinceItsLatestRemoval() {
        assertThat(f.company.workflows().versions("Light")).isEmpty();
        save(light("Light"), false);
        save(decide("Light"), true);
        save(light("LIGHT"), true);
        assertThat(numbers("light")).containsExactly(1, 2, 3);
        assertThat(f.company.workflows().versions("Light"))
                .extracting(v ->
                        ((WorkflowDocument.Reading.Read) v.reading()).workflow().has(Step.Produce.class))
                .containsExactly(true, false, true);

        f.record(f.company.workflows().remove(f.director(), "Light"));
        assertThat(f.company.workflows().versions("Light")).isEmpty();
        save(decide("Light"), false);
        assertThat(numbers("Light")).containsExactly(1);
        // Another name keeps its own count.
        save(light("Other"), false);
        assertThat(numbers("Other")).containsExactly(1);
    }

    @Test
    void restoringASaveSavesItsDocumentAgainAsANewVersion() {
        save(light("Light"), false);
        save(decide("Light"), true);
        Workflow first = ((WorkflowDocument.Reading.Read)
                        f.company.workflows().versions("Light").getFirst().reading())
                .workflow();

        save(first, true);

        List<WorkflowVersion> all = f.company.workflows().versions("Light");
        assertThat(numbers("Light")).containsExactly(1, 2, 3);
        assertThat(all.get(2).reading()).isEqualTo(all.get(0).reading());
        assertThat(all.get(2).reading()).isNotEqualTo(all.get(1).reading());
    }

    @Test
    void theVersionAPinFollowsIsTheLatestSaveBeforeItWithTheSameWorkflow() {
        save(light("Light"), false);
        save(decide("Light"), true);
        MissionId m = pin(decide("Light"));
        assertThat(f.company.workflows().followed(m))
                .get()
                .extracting(f -> f.version().number())
                .isEqualTo(2);

        // Edited since: the mission still follows the version it copied, and a restore does not move it.
        save(light("Light"), true);
        assertThat(f.company.workflows().followed(m))
                .get()
                .extracting(f -> f.version().number())
                .isEqualTo(2);
        save(decide("Light"), true);
        assertThat(f.company.workflows().followed(m))
                .get()
                .extracting(f -> f.version().number())
                .isEqualTo(2);
        assertThat(numbers("Light")).containsExactly(1, 2, 3, 4);
    }

    @Test
    void aPinThatMatchesNoSaveBeforeItIsACopy() {
        // Never saved under this name.
        MissionId unsaved = pin(decide("Draft"));
        assertThat(f.company.workflows().followed(unsaved)).isEmpty();
        // A save of the same workflow made after the pin does not count.
        save(decide("Draft"), false);
        assertThat(f.company.workflows().followed(unsaved)).isEmpty();
        // Saved before, but a different workflow of that name: edited on its way, so a copy.
        save(light("Light"), false);
        MissionId edited = pin(decide("Light"));
        assertThat(f.company.workflows().followed(edited)).isEmpty();
        // The built-in workflow and a mission never pinned follow no company version.
        assertThat(f.company.workflows().followed(new MissionId("M-999"))).isEmpty();
    }

    @Test
    void removingTheNameAfterThePinKeepsTheVersionItPinnedAndSaysItIsGone() {
        save(light("Gone"), false);
        save(decide("Gone"), true);
        MissionId m = pin(decide("Gone"));
        assertThat(f.company.workflows().followed(m)).get().satisfies(f -> {
            assertThat(f.version().number()).isEqualTo(2);
            assertThat(f.current()).isTrue();
        });

        f.record(f.company.workflows().remove(f.director(), "Gone"));
        assertThat(f.company.workflows().followed(m)).get().satisfies(f -> {
            assertThat(f.version().number()).isEqualTo(2);
            assertThat(f.current()).isFalse();
        });

        // Created again under the same name, even with the same steps: another life, the mission still says version 2
        // of the one that was removed.
        save(decide("Gone"), false);
        assertThat(numbers("Gone")).containsExactly(1);
        assertThat(f.company.workflows().followed(m)).get().satisfies(f -> {
            assertThat(f.version().number()).isEqualTo(2);
            assertThat(f.current()).isFalse();
        });
        // A mission pinned in the new life follows that one.
        MissionId later = pin(decide("Gone"));
        assertThat(f.company.workflows().followed(later)).get().satisfies(f -> {
            assertThat(f.version().number()).isEqualTo(1);
            assertThat(f.current()).isTrue();
        });
    }

    @Test
    void versionsAreNotInTheJournal() {
        int before = f.journal.size();
        save(light("Light"), false);
        save(decide("Light"), true);
        // One event per save, as before this increment: a version is counted, not written.
        assertThat(f.journal.size() - before).isEqualTo(2);
        assertThat(f.journal.stream()
                        .map(e -> e.event().getClass().getSimpleName())
                        .skip(before))
                .containsOnly("CompanyWorkflowSaved");
    }
}
