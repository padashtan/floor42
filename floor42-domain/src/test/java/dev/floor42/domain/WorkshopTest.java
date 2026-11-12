package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DEHREN;
import static dev.floor42.domain.Fixture.GEOFFREY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The director's workshop (spec section 11): every intervention traced, human edits protected, work resumed. */
@ExtendWith(GoldenFixtureExtension.class)
class WorkshopTest {

    final Fixture f = new Fixture(Origin.REAL);
    final MissionId m = realMission();
    final TicketId t = f.firstTicket(m);

    MissionId realMission() {
        MissionId id = ((MissionCreated) f.record(f.company
                                .missions()
                                .create(
                                        f.director(),
                                        "Contributor guide",
                                        "Add a CONTRIBUTING.md",
                                        "C:/work/repo",
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId ticket = f.ticket(id, "Deliver: Contributor guide");
        f.record(f.company.tickets().assign(f.director(), ticket, CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(GEOFFREY), id));
        f.record(f.company.verification().setBudget(f.director(), id, null));
        f.record(f.company.missions().approvePlan(f.director(), id));
        f.record(f.company.tickets().start(f.agent(CHRIS), ticket));
        f.record(f.company
                .workshop()
                .prepareWorkspace(
                        f.system(), id, CHRIS, "C:/office/workspaces/M-1-chris", "floor42/m-1-chris", "abc123"));
        return id;
    }

    Action write(String id, String path) {
        f.record(f.company
                .actions()
                .request(f.agent(CHRIS), new ActionId(id), m, t, CHRIS, ActionCategory.WRITE, "Write", path));
        return f.company.actions().find(new ActionId(id)).orElseThrow();
    }

    @Test
    void theWorkspaceIsRecordedWithItsBase() {
        Workspace w = f.company.workshop().workspace(m, CHRIS).orElseThrow();
        assertThat(w.branch()).isEqualTo("floor42/m-1-chris");
        assertThat(w.baseCommit()).isEqualTo("abc123");
        assertThatThrownBy(() -> f.company.workshop().prepareWorkspace(f.system(), m, CHRIS, "x", "y", "z"))
                .hasMessageContaining("already has a workspace");
        assertThatThrownBy(() -> f.company.workshop().prepareWorkspace(f.director(), m, DEHREN, "x", "y", "z"))
                .hasMessageContaining("Only Floor42");
    }

    @Test
    void anAgentNeverOverwritesTheDirectorsEditSilently() {
        f.allow(m, ActionCategory.WRITE, null, null);
        f.record(f.company
                .workshop()
                .recordDirectorEdit(
                        f.director(), m, CHRIS, "docs\\CONTRIBUTING.md", "fix the run step", "@@ -1 +1 @@"));

        assertThat(write("a1", "docs/CONTRIBUTING.md").stage()).isEqualTo(ActionStage.REQUESTED);
        Question asked = f.company.desk().open().getFirst();
        assertThat(asked.context()).contains("you edited docs/CONTRIBUTING.md");
        assertThat(write("a2", "README.md").stage()).isEqualTo(ActionStage.AUTHORIZED);

        Intervention edit = f.company.workshop().interventions(m).getFirst();
        assertThat(edit.kind()).isEqualTo(Intervention.Kind.DIRECTOR_EDIT);
        assertThat(edit.author().isDirector()).isTrue();
        assertThat(edit.files()).containsExactly("docs/CONTRIBUTING.md");
        assertThat(edit.detail()).isEqualTo("@@ -1 +1 @@");
    }

    @Test
    void aForbiddenWriteStaysForbiddenOnAnEditedFile() {
        f.record(f.company.workshop().recordDirectorEdit(f.director(), m, CHRIS, "README.md", null, "diff"));

        assertThat(write("a1", "README.md").denialReason()).isEqualTo(DenialReason.POLICY);
    }

    @Test
    void aDirectiveReopensADoneTicketAndIsKeptForEveryLaterBrief() {
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "Report"));
        f.record(f.company.tickets().review(f.agent(DEHREN), t, ReviewVerdict.VALIDATED, "fine"));
        assertThat(f.company.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.DONE);

        f.record(f.company.workshop().giveDirective(f.director(), t, "Mention that mvn compile must run first"));

        Ticket ticket = f.company.tickets().find(t).orElseThrow();
        assertThat(ticket.status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket.directives()).containsExactly("Mention that mvn compile must run first");
        assertThat(f.company.workshop().interventions(m)).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(Intervention.Kind.DIRECTIVE);
            assertThat(i.workspace()).isEqualTo(CHRIS);
        });
        assertThatThrownBy(() -> f.company.workshop().giveDirective(f.agent(CHRIS), t, "self-serve"))
                .hasMessageContaining("Only the director");
    }

    @Test
    void nothingHappensUnderARunningAgentsFeet() {
        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", "2.1.304"));

        assertThatThrownBy(() -> f.company.workshop().giveDirective(f.director(), t, "stop"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("session is running");
        assertThatThrownBy(() -> f.company.workshop().acceptPatch(f.director(), m, CHRIS, "c1", "msg", List.of("a")))
                .hasMessageContaining("session is running");
        assertThatThrownBy(() -> f.company.workshop().checkAccess(f.director(), m, CHRIS))
                .hasMessageContaining("session is running");
    }

    @Test
    void patchesAreTracedWithTheirFiles() {
        f.record(f.company
                .workshop()
                .acceptPatch(f.director(), m, CHRIS, "c0ffee", "Add CONTRIBUTING.md", List.of("CONTRIBUTING.md")));
        f.record(f.company
                .workshop()
                .rejectPatch(
                        f.director(), m, CHRIS, "wrong approach", "patches/M-1-chris-2.patch", List.of("pom.xml")));

        assertThat(f.company.workshop().interventions(m))
                .extracting(Intervention::kind, Intervention::detail)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Intervention.Kind.PATCH_ACCEPTED, "c0ffee"),
                        org.assertj.core.groups.Tuple.tuple(
                                Intervention.Kind.PATCH_REJECTED, "patches/M-1-chris-2.patch"));
        assertThatThrownBy(() -> f.company.workshop().acceptPatch(f.director(), m, CHRIS, "c1", "msg", List.of()))
                .hasMessageContaining("Nothing to accept");
        assertThatThrownBy(() -> f.company.workshop().acceptPatch(f.director(), m, DEHREN, "c1", "msg", List.of("a")))
                .hasMessageContaining("no workspace");
    }

    @Test
    void theWorkshopSurvivesAReplay() {
        f.record(f.company.workshop().recordDirectorEdit(f.director(), m, CHRIS, "README.md", null, "diff"));
        f.record(f.company.workshop().giveDirective(f.director(), t, "Keep it short"));

        Company replayed = Company.replay(f.journal);

        assertThat(replayed.workshop().workspace(m, CHRIS).orElseThrow().editedByDirector())
                .containsExactly("README.md");
        assertThat(replayed.workshop().interventions(m)).hasSize(2);
        assertThat(replayed.tickets().find(t).orElseThrow().directives()).containsExactly("Keep it short");
    }
}
