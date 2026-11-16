package dev.floor42.server.app.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.Decision;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.server.runtime.ToolCatalog;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the agent's brief says about the external MCP tool: its name and its decision, nothing of any server. */
class GovernedMcpBriefTest {

    private static final AgentId CHRIS = new AgentId("chris");

    private final Company c = Company.replay(List.of());
    private Instant now = Instant.parse("2026-11-16T09:00:00Z");

    private CommandContext ctx(Actor actor) {
        now = now.plusSeconds(1);
        return new CommandContext(actor, Origin.SIMULATED, now, null);
    }

    private String brief(Decision line) {
        c.staff()
                .hire(
                        ctx(Actor.director()),
                        CHRIS,
                        "Chris",
                        "developer",
                        "anthropic",
                        "claude-sonnet-5-5",
                        "medium",
                        List.of());
        MissionId m = ((MissionCreated) c.missions()
                        .create(ctx(Actor.director()), "Gateway", "Reach the remote servers")
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((TicketCreated) c.tickets()
                        .create(ctx(Actor.system()), m, "Implement", "", Priority.NORMAL, List.of(), List.of("done"))
                        .getFirst()
                        .event())
                .ticket();
        c.missions().setToolPermission(ctx(Actor.system()), m, ToolCatalog.CALL_MCP, ActionCategory.NETWORK, line);
        return BriefPermissions.permissions(
                c, c.missions().find(m).orElseThrow(), c.staff().find(CHRIS).orElseThrow(), t);
    }

    @Test
    void theBriefNamesTheToolWithItsDecisionAndNoAddress() {
        String brief = brief(Decision.ASK);

        assertThat(brief).contains("- mcp__floor42__call_mcp: asks the director the first time");
        assertThat(brief).contains("list_mcp_tools");
        assertThat(brief).doesNotContain("http", "127.0.0.1", "audience", "target-api");
    }

    @Test
    void aForbiddenLineSaysSoAndOffersNothing() {
        assertThat(brief(Decision.DENY))
                .contains("- mcp__floor42__call_mcp: forbidden")
                .doesNotContain("list_mcp_tools");
    }
}
