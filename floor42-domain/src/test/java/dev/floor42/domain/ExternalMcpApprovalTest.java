package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An external MCP tool in the table (ADR-0072): its approval is per server and remote tool, its refusals are Floor42's. */
class ExternalMcpApprovalTest {

    final Fixture f = new Fixture();
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    List<EventEnvelope> request(String id, ActionCategory category, String tool, String summary) {
        return f.record(
                f.company.actions().request(f.agent(CHRIS), new ActionId(id), m, t, CHRIS, category, tool, summary));
    }

    Action action(String id) {
        return f.company.actions().find(new ActionId(id)).orElseThrow();
    }

    private static final String CALL_MCP = "mcp__floor42__call_mcp";

    private int openQuestions() {
        return f.company.desk().open().size();
    }

    @Test
    void anApprovalOfAnExternalMcpToolCoversThatServerAndToolOnly() {
        f.record(f.company
                .missions()
                .addPermission(
                        f.director(), m, PermissionRule.forTool(CALL_MCP, ActionCategory.NETWORK, Decision.ASK)));

        request("a1", ActionCategory.NETWORK, CALL_MCP, "srv-a search {\"q\":\"x\"}");
        assertThat(openQuestions()).isEqualTo(1);
        f.record(f.company
                .desk()
                .answer(f.director(), f.company.desk().open().getFirst().id(), "ALLOW"));
        assertThat(action("a1").stage()).isEqualTo(ActionStage.AUTHORIZED);

        // the same server and tool, other arguments: remembered
        request("a2", ActionCategory.NETWORK, CALL_MCP, "srv-a search {\"q\":\"y\"}");
        assertThat(action("a2").stage()).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(openQuestions()).isZero();

        // another tool of the same server, and the same tool name on another server: asked again, twice
        request("a3", ActionCategory.NETWORK, CALL_MCP, "srv-a delete {}");
        assertThat(action("a3").stage()).isEqualTo(ActionStage.REQUESTED);
        request("a4", ActionCategory.NETWORK, CALL_MCP, "srv-b search {}");
        assertThat(action("a4").stage()).isEqualTo(ActionStage.REQUESTED);
        assertThat(openQuestions()).isEqualTo(2);
    }

    @Test
    void aRefusalOfAnExternalMcpToolIsRememberedPerServerAndTool() {
        f.record(f.company
                .missions()
                .addPermission(
                        f.director(), m, PermissionRule.forTool(CALL_MCP, ActionCategory.NETWORK, Decision.ASK)));
        request("a1", ActionCategory.NETWORK, CALL_MCP, "srv-a search {}");
        f.record(f.company
                .desk()
                .answer(f.director(), f.company.desk().open().getFirst().id(), "DENY"));

        request("a2", ActionCategory.NETWORK, CALL_MCP, "srv-a search {\"q\":1}");
        assertThat(action("a2").denialReason()).isEqualTo(DenialReason.DIRECTOR);
        request("a3", ActionCategory.NETWORK, CALL_MCP, "srv-a other {}");
        assertThat(action("a3").stage()).isEqualTo(ActionStage.REQUESTED);
    }

    @Test
    void aMissionTableThatPredatesExternalMcpDeniesIt() {
        // no line for the tool: the NETWORK default is deny
        request("a1", ActionCategory.NETWORK, CALL_MCP, "srv-a search {}");

        assertThat(action("a1").stage()).isEqualTo(ActionStage.DENIED);
        assertThat(action("a1").denialReason()).isEqualTo(DenialReason.POLICY);
    }

    @Test
    void anExternalMcpRefusalIsFloor42sAndAProtocolErrorIsAFailure() {
        f.record(f.company
                .missions()
                .addPermission(
                        f.director(), m, PermissionRule.forTool(CALL_MCP, ActionCategory.NETWORK, Decision.ALLOW)));
        String[] codes = {
            "MCP_SERVER_UNKNOWN",
            "MCP_TOOL_NOT_ALLOWED",
            "INVALID_ARGUMENTS",
            "NOT_AUTHORIZED",
            "RESOURCE_NOT_GRANTED",
            "MCP_PROTOCOL"
        };
        for (int i = 0; i < codes.length; i++) {
            request("r" + i, ActionCategory.NETWORK, CALL_MCP, "srv-a search {}");
            f.record(f.company
                    .actions()
                    .recordOutcome(f.system(), new ActionId("r" + i), false, codes[i] + ": said no"));
            assertThat(action("r" + i).refused()).as(codes[i]).isEqualTo(!codes[i].equals("MCP_PROTOCOL"));
        }
    }
}
