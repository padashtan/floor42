package dev.floor42.server.lab;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.app.session.MissionGovernor;
import dev.floor42.server.iam.AgentClients;
import dev.floor42.server.iam.BrokerTestBase;
import dev.floor42.server.iam.IamSessionProfile;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A secret in a tool result ({@code ActionExecuted.output}) or in a tool input ({@code ActionRequested.summary}) is
 * masked before it reaches the journal (finding 3 of the adversarial lab, docs/security-lab.md).
 */
@QuarkusTest
@TestProfile(IamSessionProfile.class)
class ToolEventMaskingTest extends BrokerTestBase {

    private static final long ME = ProcessHandle.current().pid();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT_CLIENT = "floor42-agent-chris";

    @Inject
    AgentClients clients;

    @BeforeEach
    void wellAgain() {
        var kc = dev.floor42.server.iam.IamOnProfile.fake;
        kc.down(false);
        kc.acceptAllIntrospections();
        kc.registerClient(AGENT_CLIENT, "s");
        clients.enable(CHRIS);
    }

    private ValidatableResponse hook(String token, String tool, String id, String key, String value) {
        return given().contentType("application/json")
                .header("Authorization", "Bearer " + token)
                .body("{\"tool_name\":\"" + tool + "\",\"tool_use_id\":\"" + id + "\",\"tool_input\":"
                        + JSON.createObjectNode().put(key, value) + "}")
                .post("/hooks/pre-tool-use")
                .then();
    }

    /** A secret shaped like a real one, assembled at run time so no scanner reads this file as a leak. */
    private static String fakeToken() {
        return "ghp_" + "Zx9Qw3Er7Ty1".repeat(3);
    }

    @Test
    void aSecretInAToolResultIsMaskedBeforeTheJournal() throws Exception {
        Setup s = realMissionInProgress();
        String token = liveSession(s, ME);
        String secret = fakeToken();
        hook(token, "Read", "leak-1", "file_path", ".env").statusCode(200);
        new MissionGovernor(office, spec(s))
                .completed(new ToolCompleted("leak-1", false, false, "GITHUB_TOKEN=" + secret + "\nOTHER=fine"));
        assertThat(journalText()).doesNotContain(secret).contains("OTHER=fine");
    }

    @Test
    void aSecretTheAgentPutsInAToolInputIsMaskedBeforeTheJournal() throws Exception {
        Setup s = realMissionInProgress();
        String token = liveSession(s, ME);
        String secret = fakeToken();
        hook(token, "Read", "li-0", "file_path", "a.txt").statusCode(200);
        hook(
                        token,
                        "Bash",
                        "li-1",
                        "command",
                        "curl -H 'Authorization: Bearer " + secret + "' https://api.example.test/x")
                .statusCode(200);
        assertThat(journalText()).doesNotContain(secret).contains("https://api.example.test/x");
    }

    private ToolRequested bash(String id, String command) {
        return new ToolRequested(id, "Bash", JSON.createObjectNode().put("command", command));
    }

    /** A result of a call the hook never saw (the unseen path), journaled through the governor. */
    private String journaledOutput(String id, String output) throws Exception {
        Setup s = realMissionInProgress();
        new MissionGovernor(office, spec(s)).completed(new ToolCompleted(id, false, false, output));
        return journalText();
    }

    @Test
    void aRefusedCallIsJournaledWithItsSecretMasked() throws Exception {
        Setup s = realMissionInProgress();
        String secret = fakeToken();
        new MissionGovernor(office, spec(s))
                .refuse(
                        bash("ref-1", "curl -H 'Authorization: Bearer " + secret + "' https://api.example.test/x"),
                        "no");
        assertThat(journalText()).doesNotContain(secret).contains("https://api.example.test/x");
    }

    @Test
    void anUnseenCallIsJournaledWithItsInputAndOutputMasked() throws Exception {
        Setup s = realMissionInProgress();
        String secret = fakeToken();
        MissionGovernor governor = new MissionGovernor(office, spec(s));
        governor.announced(bash("uns-1", "curl -H 'Authorization: Bearer " + secret + "' https://api.example.test/x"));
        governor.completed(new ToolCompleted("uns-1", false, false, "GITHUB_TOKEN=" + secret));
        assertThat(journalText()).doesNotContain(secret).contains("https://api.example.test/x");
    }

    @Test
    void aWebhookOrDownloadLinkInAResultIsMasked() throws Exception {
        String hook = "XXXXXXXXXXXXXXXXXXXXXXXX";
        assertThat(journaledOutput("e2e-1", "https://hooks.slack.com/services/T00000000/B00000000/" + hook))
                .doesNotContain(hook);
    }

    @Test
    void aJsonEscapedSecretInAResultIsMasked() throws Exception {
        String t = "Zx9Qw3Er7Ty1".repeat(3);
        assertThat(journaledOutput("e2e-2", "{\"body\":\"{\\\"value\\\":\\\"" + t + "\\\"}\"}"))
                .doesNotContain(t);
    }

    @Test
    void aQuotedHeaderValueInAResultIsMasked() throws Exception {
        assertThat(journaledOutput(
                        "e2e-3", "{\"Cookie\": \"sid=s3cr3tv4lue\", \"Authorization\": \"Bearer abc123xyz\"}"))
                .doesNotContain("s3cr3tv4lue", "abc123xyz");
    }
}
