package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ProposedTicket;
import dev.floor42.domain.TicketId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What Floor42 reads of a graph planner's and an extension planner's reports (ADR-0062). */
class GraphBriefsTest {

    static final String REPORT = String.join(
            "\n",
            "TICKET: First",
            "WHY: w",
            "DONE WHEN: a; b",
            "DEPENDS ON: T-3, T-7, 1",
            "TEST: ./check.sh => all green",
            "RISK: high: public",
            "TIER: standard",
            "TICKET: Second",
            "DEPENDS ON: 1",
            "TEST: none",
            "RISK: low: reads",
            "TIER: light");

    @Test
    void anExtensionReadsTheTicketIdsItBuildsOnBesideTheNumbersOfItsOwnNodes() {
        List<ProposedTicket> nodes = Briefs.extension(REPORT);
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).existing()).containsExactly(new TicketId("T-3"), new TicketId("T-7"));
        assertThat(nodes.get(0).dependsOn())
                .as("no earlier node: the 1 is ignored")
                .isEmpty();
        assertThat(nodes.get(1).existing()).isEmpty();
        assertThat(nodes.get(1).dependsOn()).containsExactly(0);
        assertThat(nodes.get(0).acceptanceCriteria()).containsExactly("a", "b", "Test: ./check.sh => all green");
    }

    @Test
    void aFirstPlanReadsNumbersOnlyAndNeverATicketId() {
        List<ProposedTicket> nodes = Briefs.graph(REPORT);
        assertThat(nodes.get(0).existing()).isEmpty();
        assertThat(nodes.get(1).dependsOn()).containsExactly(0);
    }

    @Test
    void theTestOfANodeNeedsItsArrowToBeRun() {
        assertThat(dev.floor42.domain.NodePlan.proposed(
                                "", List.of("Test: ./check.sh => all green", "Test: it works", "x"))
                        .tests())
                .containsExactly("./check.sh");
    }

    @Test
    void theNotesComeOffADescriptionAndNothingElse() {
        String text = "Does the work.\nRisk: high: public\nTier: light\nMore text.";
        assertThat(dev.floor42.domain.NodePlan.stripNotes(text)).isEqualTo("Does the work.\nMore text.");
        assertThat(dev.floor42.domain.NodePlan.proposed(text, List.of()).high()).isTrue();
    }
}
