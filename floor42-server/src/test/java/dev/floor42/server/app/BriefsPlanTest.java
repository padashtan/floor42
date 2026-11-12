package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ProposedTicket;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The planner's closing blocks, read back by Floor42 (ADR-0017). */
class BriefsPlanTest {

    @Test
    void readsEveryTicketBlockInOrder() {
        List<ProposedTicket> plan = Briefs.plan("""
                Analysis first. TICKET: is not at the start of a line here.

                **TICKET:** Pin the compiler plugin
                WHY: builds differ between machines
                DONE WHEN: plugin version pinned; build passes
                DEPENDS ON: none
                SIZE: S

                - TICKET: Add the wrapper
                DONE WHEN: ./mvnw -v works
                DEPENDS ON: ticket 1 and 3
                """);

        assertThat(plan).hasSize(2);
        assertThat(plan.get(0).title()).isEqualTo("Pin the compiler plugin");
        assertThat(plan.get(0).description()).isEqualTo("builds differ between machines (size S)");
        assertThat(plan.get(0).acceptanceCriteria()).containsExactly("plugin version pinned", "build passes");
        assertThat(plan.get(0).dependsOn()).isEmpty();
        assertThat(plan.get(1).dependsOn())
                .as("a later ticket is not a dependency")
                .containsExactly(0);
    }

    @Test
    void semicolonsInsideBracketsOrQuotesStayInTheirCriterion() {
        // As written by the planner on a real repository: a criterion listing items with semicolons.
        assertThat(BriefParsers.criteria("Each claim (keys W; U; C) is checked; \"a; b\" quoted; git status is clean"))
                .containsExactly("Each claim (keys W; U; C) is checked", "\"a; b\" quoted", "git status is clean");
    }

    @Test
    void noBlockNoTicket() {
        assertThat(Briefs.plan("I could not find anything to plan.")).isEmpty();
        assertThat(Briefs.plan(null)).isEmpty();
    }
}
