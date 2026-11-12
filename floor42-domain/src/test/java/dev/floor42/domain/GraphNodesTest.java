package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.TicketEvent.TicketEdited;
import dev.floor42.domain.event.WorkflowEvent.NodePlanned;
import dev.floor42.domain.workflow.CheckProgress;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.StepAt;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The nodes of a graph mission (ADR-0062, increment 2): what the plan's approval settles ({@code NodePlanned}), the
 * checks on high-risk nodes only, the rerun of the rejecting check, the node tests that count as rejections, the
 * extension's dependencies and the "to recheck" mark. Not a golden fixture.
 */
class GraphNodesTest {

    static final Step.Check SECURITY = new Step.Check("sec", "Security", "Look", "Why", "Findings", true);
    static final Step.Check WORDING = new Step.Check("wording", "Wording", "Read", "Why", "Passages", true);
    static final Workflow GRAPH = new Workflow(
                    "Graph",
                    List.of(
                            new Step.Plan(),
                            new Step.Approval(),
                            new Step.Produce(),
                            new Step.Review(),
                            SECURITY,
                            WORDING,
                            new Step.Accept()))
            .asGraph();

    static final String LOW = "Does the reading.\nRisk: low: it only reads\nTier: light";
    static final String HIGH = "Changes the gateway.\nRisk: high: public\nTier: standard";

    @AfterEach
    void forget() {
        Fixture.takeMade();
    }

    Fixture f = new Fixture(Origin.REAL);
    MissionInterpreter interpreter = new MissionInterpreter();

    MissionId mission(Workflow w) {
        MissionId m = ((MissionCreated) f.record(f.company.missions().create(f.director(), "G", "o", "C:/repo", w))
                        .getFirst()
                        .event())
                .mission();
        f.record(f.company.missions().enableTicketValidation(f.director(), m));
        return m;
    }

    TicketId proposed(MissionId m, String description, List<String> criteria, TicketId... after) {
        List<EventEnvelope> events = f.record(f.company
                .tickets()
                .create(f.agent(Fixture.GEOFFREY), m, "Node", description, Priority.NORMAL, List.of(after), criteria));
        return ((TicketCreated) events.getFirst().event()).ticket();
    }

    List<EventEnvelope> approve(MissionId m, Map<TicketId, String> risks, TicketId... nodes) {
        return f.record(f.company.tickets().approve(f.director(), m, List.of(nodes), risks));
    }

    void start(MissionId m, TicketId... nodes) {
        f.record(f.company.missions().submitPlan(f.agent(Fixture.GEOFFREY), m));
        f.record(f.company.verification().setBudget(f.director(), m, null));
        f.record(f.company.missions().approvePlan(f.director(), m));
        for (TicketId t : nodes) {
            f.record(f.company.tickets().assign(f.director(), t, Fixture.CHRIS));
            f.record(f.company.tickets().start(f.agent(Fixture.CHRIS), t));
        }
    }

    void review(TicketId t, ReviewVerdict v) {
        StepAt at = interpreter
                .progress(f.company, f.company.tickets().find(t).orElseThrow())
                .map(CheckProgress::at)
                .orElse(null);
        f.record(f.company.tickets().review(f.agent(Fixture.DEHREN), t, v, "f", List.of(), at));
    }

    void submit(TicketId t) {
        f.record(f.company.tickets().submitForReview(f.agent(Fixture.CHRIS), t, "patch"));
    }

    private static List<NodePlanned> planned(List<EventEnvelope> events) {
        return events.stream()
                .map(EventEnvelope::event)
                .filter(NodePlanned.class::isInstance)
                .map(NodePlanned.class::cast)
                .toList();
    }

    @Test
    void theApprovalPlansEachNodeAndTheNotesLeaveTheDescription() {
        MissionId m = mission(GRAPH);
        TicketId a = proposed(m, LOW, List.of("done", "Test: cat X.md => it prints X", "Test: none"));
        TicketId b = proposed(m, HIGH, List.of("done"), a);

        List<EventEnvelope> events = approve(m, Map.of(), a, b);

        assertThat(planned(events))
                .containsExactly(
                        new NodePlanned(a, "low", "light", List.of("cat X.md")),
                        new NodePlanned(b, "high", "standard", List.of()));
        assertThat(events.stream().map(EventEnvelope::event).filter(TicketEdited.class::isInstance))
                .hasSize(2);
        assertThat(f.company.tickets().find(a).orElseThrow().description())
                .isEqualTo("Does the reading.\n\nWhy this risk: it only reads");
        assertThat(f.company.workflows().nodeOf(a).orElseThrow().tests()).containsExactly("cat X.md");
        assertThat(f.company.tickets().find(a).orElseThrow().status()).isEqualTo(TicketStatus.TODO);
    }

    @Test
    void floor42StripsTheNotesNotTheDirector() {
        MissionId m = mission(GRAPH);
        TicketId a = proposed(m, LOW, List.of("done"));
        List<EventEnvelope> events = approve(m, Map.of(), a);
        EventEnvelope edit = events.stream()
                .filter(e -> e.event() instanceof TicketEdited)
                .findFirst()
                .orElseThrow();
        assertThat(edit.actor()).isEqualTo(Actor.system());
        assertThat(((TicketEdited) edit.event()).description()).contains("it only reads");
    }

    @Test
    void aTestCommandWithABidiControlRefusesTheApproval() {
        MissionId m = mission(GRAPH);
        TicketId a = proposed(m, LOW, List.of("Test: cat X.md\u202e => it prints X"));
        assertThatThrownBy(() -> approve(m, Map.of(), a))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("STEP_TEXT_HIDDEN_CHARACTER");
        assertThat(f.company.tickets().find(a).orElseThrow().status()).isEqualTo(TicketStatus.PROPOSED);
    }

    @Test
    void theDirectorsRiskIsTheFinalOne() {
        MissionId m = mission(GRAPH);
        TicketId a = proposed(m, LOW, List.of("done"));
        TicketId b = proposed(m, HIGH, List.of("done"));
        assertThat(planned(approve(m, Map.of(a, "high", b, "low"), a, b)))
                .extracting(NodePlanned::risk)
                .containsExactly("high", "low");
        assertThatThrownBy(() -> approve(m, Map.of(a, "medium"), a)).isInstanceOf(DomainException.class);
    }

    @Test
    void aLoopMissionPlansNothing() {
        MissionId m = mission(Workflow.BUILT_IN);
        TicketId a = proposed(m, LOW, List.of("done"));
        assertThat(planned(approve(m, Map.of(), a))).isEmpty();
        assertThat(f.company.tickets().find(a).orElseThrow().description()).contains("Risk: low");
    }

    @Test
    void aNodePlannedForAnUnknownTicketOrTwiceStopsTheReplay() {
        MissionId m = mission(GRAPH);
        TicketId a = proposed(m, LOW, List.of("done"));
        approve(m, Map.of(), a);
        EventEnvelope again = envelope(new NodePlanned(a, "low", "light", List.of()));
        assertThatThrownBy(() -> f.company.apply(again))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planned twice");
        EventEnvelope unknown = envelope(new NodePlanned(new TicketId("T-99"), "low", "light", List.of()));
        assertThatThrownBy(() -> f.company.apply(unknown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void aNodePlannedOnAMissionNotPinnedInGraphModeStopsTheReplay() {
        MissionId m = mission(Workflow.BUILT_IN);
        TicketId a = proposed(m, LOW, List.of("done"));
        EventEnvelope planned = envelope(new NodePlanned(a, "low", "light", List.of()));
        assertThatThrownBy(() -> f.company.apply(planned))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("graph mode");
    }

    private EventEnvelope envelope(NodePlanned e) {
        return new EventEnvelope(
                "e-" + e.ticket(),
                Instant.parse("2026-11-04T12:00:00Z"),
                Origin.REAL,
                Actor.director(),
                null,
                null,
                null,
                e);
    }

    @Test
    void theChecksRunOnHighRiskNodesOnly() {
        MissionId m = mission(GRAPH);
        TicketId low = proposed(m, LOW, List.of("done"));
        TicketId high = proposed(m, HIGH, List.of("done"));
        approve(m, Map.of(), low, high);
        start(m, low, high);
        submit(low);
        submit(high);

        review(low, ReviewVerdict.VALIDATED);
        review(high, ReviewVerdict.VALIDATED);

        assertThat(f.company.tickets().find(low).orElseThrow().status())
                .as("the review alone completes a low-risk node")
                .isEqualTo(TicketStatus.DONE);
        assertThat(f.company.tickets().find(high).orElseThrow().status())
                .as("a high-risk node still waits for its checks")
                .isEqualTo(TicketStatus.IN_REVIEW);
        assertThat(interpreter.progress(
                        f.company, f.company.tickets().find(high).orElseThrow()))
                .hasValueSatisfying(p -> assertThat(p.check()).contains(SECURITY));
    }

    @Test
    void aResubmissionRerunsOnlyTheCheckThatRejected() {
        MissionId m = mission(GRAPH);
        TicketId high = proposed(m, HIGH, List.of("done"));
        approve(m, Map.of(), high);
        start(m, high);
        submit(high);
        review(high, ReviewVerdict.VALIDATED); // the review
        review(high, ReviewVerdict.VALIDATED); // security
        review(high, ReviewVerdict.REJECTED); // wording rejects: reopened
        assertThat(f.company.tickets().find(high).orElseThrow().status()).isEqualTo(TicketStatus.IN_PROGRESS);

        submit(high);
        CheckProgress p = interpreter
                .progress(f.company, f.company.tickets().find(high).orElseThrow())
                .orElseThrow();
        assertThat(p.at().position()).as("the wording check, not the review").isEqualTo(2);
        assertThat(p.check()).contains(WORDING);
        review(high, ReviewVerdict.VALIDATED);
        assertThat(f.company.tickets().find(high).orElseThrow().status()).isEqualTo(TicketStatus.DONE);
        assertThat(CheckProgress.positions(
                        GRAPH,
                        f.company.tickets().find(high).orElseThrow().reviews(),
                        f.company.tickets().reviewRounds(high)))
                .containsExactly(0, 1, 2, 2);
    }

    @Test
    void aFailedNodeTestIsARejectionOfTheSingleRework() {
        MissionId m = mission(GRAPH);
        TicketId high = proposed(m, HIGH, List.of("done", "Test: ./t.sh => ok"));
        approve(m, Map.of(), high);
        start(m, high);
        f.record(f.company.workshop().prepareWorkspace(f.system(), m, Fixture.CHRIS, "C:/ws/c", "floor42/c", "abc"));
        f.record(f.company
                .verification()
                .recordChecks(
                        f.system(),
                        m,
                        Fixture.CHRIS,
                        "./t.sh",
                        1,
                        false,
                        5,
                        "boom",
                        "abc",
                        CheckRun.nodeTestTrigger(high)));
        f.record(f.company
                .verification()
                .recordChecks(
                        f.system(),
                        m,
                        Fixture.CHRIS,
                        "./t.sh",
                        null,
                        false,
                        0,
                        "not run",
                        "abc",
                        CheckRun.nodeTestTrigger(high)));
        assertThat(f.company.verification().nodeTestFailures(high))
                .as("a run that never started did not fail")
                .isEqualTo(1);

        submit(high);
        review(high, ReviewVerdict.VALIDATED); // review
        review(high, ReviewVerdict.REJECTED); // security rejects: with the failed test, the second rejection
        assertThat(f.company.tickets().find(high).orElseThrow().status())
                .as("asked, not reopened")
                .isEqualTo(TicketStatus.IN_REVIEW);
        assertThat(f.company.desk().of(m))
                .anyMatch(q -> q.kind() == QuestionKind.REVIEW_ESCALATION && high.equals(q.ticket()));
    }

    @Test
    void anExtensionBuildsOnExistingNodesAndOnlyOnThose() {
        MissionId m = mission(GRAPH);
        TicketId a = proposed(m, LOW, List.of("done"));
        approve(m, Map.of(), a);
        start(m);
        f.record(f.company
                .tickets()
                .create(
                        f.director(),
                        m,
                        "Plan more",
                        "",
                        Priority.NORMAL,
                        List.of(),
                        List.of("c"),
                        TicketKind.PLANNING));
        TicketId planning = f.company.tickets().of(m).getLast().id();
        f.record(f.company.tickets().assign(f.system(), planning, Fixture.GEOFFREY));
        f.record(f.company.tickets().start(f.agent(Fixture.GEOFFREY), planning));

        List<EventEnvelope> out = f.record(f.company
                .tickets()
                .propose(
                        f.agent(Fixture.GEOFFREY),
                        planning,
                        Fixture.CHRIS,
                        List.of(new ProposedTicket("More", "d", List.of("c"), List.of(), List.of(a)))));

        TicketCreated created = (TicketCreated) out.getFirst().event();
        assertThat(created.dependsOn()).containsExactly(a);
        assertThat(f.company.tickets().find(created.ticket()).orElseThrow().status())
                .isEqualTo(TicketStatus.PROPOSED);
        TicketId planning2 = planning;
        assertThatThrownBy(() -> f.company
                        .tickets()
                        .propose(
                                f.agent(Fixture.GEOFFREY),
                                planning2,
                                Fixture.CHRIS,
                                List.of(new ProposedTicket(
                                        "Bad", "d", List.of("c"), List.of(), List.of(new TicketId("T-77"))))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void aDoneNodeIsToRecheckOnceADependencyIsReopenedAfterIt() {
        MissionId m = mission(GRAPH);
        TicketId a = proposed(m, LOW, List.of("done"));
        TicketId b = proposed(m, LOW, List.of("done"), a);
        approve(m, Map.of(), a, b);
        start(m, a);
        submit(a);
        review(a, ReviewVerdict.VALIDATED);
        f.record(f.company.tickets().assign(f.director(), b, Fixture.CHRIS));
        f.record(f.company.tickets().start(f.agent(Fixture.CHRIS), b));
        submit(b);
        review(b, ReviewVerdict.VALIDATED);
        assertThat(f.company.tickets().toRecheck(b)).isFalse();

        f.record(f.company.workshop().giveDirective(f.director(), a, "the interface changed"));

        assertThat(f.company.tickets().toRecheck(b))
                .as("b was done on the old a")
                .isTrue();
        assertThat(f.company.tickets().toRecheck(a)).isFalse();
        assertThat(f.company.tickets().find(b).orElseThrow().status())
                .as("never reopened by itself")
                .isEqualTo(TicketStatus.DONE);
    }
}
