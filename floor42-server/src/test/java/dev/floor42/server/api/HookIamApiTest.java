package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.DenialReason;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.SessionEvent.AgentTokenIssued;
import dev.floor42.server.app.MissionGovernor;
import dev.floor42.server.iam.AgentTokenVerifier.Ok;
import dev.floor42.server.iam.DirectorTokens;
import dev.floor42.server.iam.IamOnProfile;
import dev.floor42.server.iam.IamSessionProfile;
import dev.floor42.server.iam.IamSessionTestBase;
import dev.floor42.server.iam.IdentityRefusals;
import dev.floor42.server.iam.KeycloakClient;
import dev.floor42.server.iam.SessionClaims;
import dev.floor42.server.iam.SessionIdentity.Minted;
import dev.floor42.server.runtime.ClaudeCodeRuntime;
import dev.floor42.server.runtime.SessionReport;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/** Hook and MCP calls in IAM mode: every refusal is a 401 with a code, and only an attributable one is journaled. */
@QuarkusTest
@TestProfile(IamSessionProfile.class)
class HookIamApiTest extends IamSessionTestBase {

    private static final long ME = ProcessHandle.current().pid();

    @Inject
    IdentityRefusals refusals;

    @Inject
    ClaudeCodeRuntime runtime;

    @Inject
    dev.floor42.server.iam.Delegations delegations;

    @Inject
    DirectorTokens directorTokens;

    @Inject
    dev.floor42.server.iam.AgentClients clients;

    @Inject
    dev.floor42.server.security.SessionRegistry sessions;

    @Inject
    KeycloakClient keycloak;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    private io.restassured.response.ValidatableResponse hook(String token, String body) {
        return given().contentType("application/json")
                .header("Authorization", "Bearer " + token)
                .body(body)
                .post("/hooks/pre-tool-use")
                .then();
    }

    /** A session whose record says {@code s}, carrying a token minted for {@code claimed} (a hostile issuer's work). */
    private String sessionWithTokenOf(Setup s, Setup claimed) {
        String launch = UUID.randomUUID().toString();
        String jwt = tokenFor(claimed, launch);
        Ok ok = (Ok) verifier.verify(jwt);
        hooks.register(
                new MissionGovernor(office, spec(s)),
                new SessionClaims(launch, CHRIS, s.mission(), s.ticket()),
                new Minted(jwt, ok.jti(), ok.issuer(), ok.expiresAt()));
        hooks.bindProcess(jwt, ME);
        return jwt;
    }

    private boolean deniedAs(String code) {
        return journal.readAll().stream()
                .anyMatch(e -> e.event() instanceof ActionDenied d
                        && d.reason() == DenialReason.BLOCKED_UPSTREAM
                        && ("identity: " + code).equals(d.detail()));
    }

    @Inject
    dev.floor42.server.security.SessionToken directorToken;

    /** Confinement is checked at the request, not only there: a link that appears while the director decides counts. */
    @Test
    void anApprovalNeverAllowsAPathThatBecameALinkOutsideMeanwhile() throws Exception {
        Setup s = realMissionInProgress();
        office.execute(c -> c.missions()
                .setToolPermission(
                        director(),
                        s.mission(),
                        "Write",
                        dev.floor42.domain.ActionCategory.WRITE,
                        dev.floor42.domain.Decision.ASK_ALWAYS));
        String launch = UUID.randomUUID().toString();
        String token = tokenFor(s, launch);
        Ok ok = (Ok) verifier.verify(token);
        // As in a real session: the governor is wrapped in the tally, which counts and records the last check.
        var tally = dev.floor42.server.runtime.TestTallies.wrap(
                new MissionGovernor(office, spec(s)), Path.of(s.workdir()), sandbox);
        hooks.register(
                tally,
                new SessionClaims(launch, CHRIS, s.mission(), s.ticket()),
                new Minted(token, ok.jti(), ok.issuer(), ok.expiresAt()));
        hooks.bindProcess(token, ME);
        Path outside = Files.createTempDirectory("outside");
        var answered = java.util.concurrent.CompletableFuture.supplyAsync(() -> hook(
                        token,
                        "{\"tool_name\":\"Write\",\"tool_use_id\":\"swap-1\",\"tool_input\":"
                                + "{\"file_path\":\"x/new.txt\",\"content\":\"c\"}}")
                .statusCode(200)
                .extract()
                .path("hookSpecificOutput.permissionDecision")
                .toString());
        String question = null;
        for (int i = 0; i < 100 && question == null; i++) {
            question = office.query(c -> c.desk().of(s.mission()).stream()
                    .filter(q -> q.status() == dev.floor42.domain.QuestionStatus.OPEN
                            && q.kind() == dev.floor42.domain.QuestionKind.ACTION_APPROVAL)
                    .map(q -> q.id().value())
                    .findFirst()
                    .orElse(null));
            if (question == null) Thread.sleep(100);
        }
        assertThat(question).as("the director is asked").isNotNull();
        Path link = Path.of(s.workdir(), "x");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // Windows without the symlink privilege: a junction needs none and leads outside just the same.
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString())
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    p.waitFor() == 0 && Files.exists(link), "neither a symbolic link nor a junction could be created");
        }
        given().header("Authorization", "Bearer " + directorToken.value())
                .contentType("application/json")
                .body("{\"answer\":\"ALLOW\"}")
                .post("/api/questions/" + question + "/answer")
                .then()
                .statusCode(200);
        assertThat(answered.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("deny");
        var action = office.query(c -> c.actions().of(s.mission()).stream()
                .filter(x -> x.tool().equals("Write"))
                .findFirst()
                .orElseThrow());
        assertThat(action.stage()).isEqualTo(dev.floor42.domain.ActionStage.FAILED);
        assertThat(action.result()).contains("Refused at the last check").contains("through a link");
    }

    @Test
    void sessionEndedTokenIsRefusedAtOnce() throws Exception {
        String token = liveSession(realMissionInProgress(), ME);
        hook(token, preToolUse("Read", "live-1")).statusCode(200);
        hooks.unregister(token);
        hook(token, preToolUse("Read", "ended-1"))
                .statusCode(401)
                .body("code", equalTo("TOKEN_REFUSED"))
                .body("reason", equalTo("SESSION_ENDED"));
        assertThat(deniedAs("SESSION_ENDED"))
                .as("not attributable to a live session: not journaled")
                .isFalse();
    }

    @Test
    void wrongMissionClaimIsRefused() throws Exception {
        Setup s = realMissionInProgress();
        String token = sessionWithTokenOf(s, new Setup(new MissionId("M-9999"), s.ticket(), s.workdir()));
        hook(token, preToolUse("Read", "wm-1")).statusCode(401).body("reason", equalTo("WRONG_MISSION"));
        assertThat(deniedAs("WRONG_MISSION")).isTrue();
    }

    @Test
    void forgedTokenGoesToTheRingNotTheJournal() throws Exception {
        String token = liveSession(realMissionInProgress(), ME);
        String forged = token.substring(0, token.length() - 6) + "AAAAAA";
        int before = refusals.recent().size();
        hook(forged, preToolUse("Read", "forged-1")).statusCode(401).body("code", equalTo("TOKEN_REFUSED"));
        assertThat(refusals.recent()).hasSizeGreaterThan(before);
        assertThat(refusals.recent().toString()).doesNotContain("eyJ");
        assertThat(journalText()).doesNotContain("forged-1");
    }

    @Test
    void anUnauthenticatedCallWithAnEmptyBodyIsA401NeverA500() {
        for (String path : new String[] {"/hooks/pre-tool-use", "/mcp"})
            given().contentType("application/json")
                    .post(path)
                    .then()
                    .statusCode(401)
                    .body("code", equalTo("TOKEN_REFUSED"));
    }

    @Test
    void mcpToolsCallChecksTheSameWay() throws Exception {
        Setup s = realMissionInProgress();
        String token = liveSession(s, ME);
        given().contentType("application/json")
                .header("Authorization", "Bearer " + token)
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .post("/mcp")
                .then()
                .statusCode(200);
        String elsewhere = liveSession(s, ME + 1); // bound to another process: this one is a stranger to it
        given().contentType("application/json")
                .header("Authorization", "Bearer " + elsewhere)
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ask_director\","
                        + "\"arguments\":{\"question\":\"q\"},\"_meta\":{\"claudecode/toolUseId\":\"mcp-1\"}}}")
                .post("/mcp")
                .then()
                .statusCode(401)
                .body("reason", equalTo("WRONG_PROCESS"));
        assertThat(deniedAs("WRONG_PROCESS")).isTrue();
    }

    @Test
    void askDirectorWithoutTheHooksAuthorizationOpensNoQuestion() throws Exception {
        Setup s = realMissionInProgress();
        String token = liveSession(s, ME);
        for (String meta : new String[] {",\"_meta\":{\"claudecode/toolUseId\":\"never-hooked\"}", ""}) {
            String answer = given().contentType("application/json")
                    .header("Authorization", "Bearer " + token)
                    .body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":"
                            + "\"ask_director\",\"arguments\":{\"question\":\"Blue or red?\"}" + meta + "}}")
                    .post("/mcp")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();
            assertThat(answer).contains("NOT_AUTHORIZED");
        }
        int questions = office.query(c -> c.desk().of(s.mission()).size());
        assertThat(questions).as("no question reached the desk").isZero();
    }

    @Test
    void liveFakeClaudeSessionRunsEndToEndInIamMode() throws Exception {
        Setup s = realMissionInProgress();
        var fake = IamOnProfile.fake;
        keycloak.ensureAgentClient(CHRIS, 28800);
        delegations.grant(s.mission(), offlineTokens(), List.of());
        try {
            SessionReport report = runtime.run(spec(s), new MissionGovernor(office, spec(s)));
            assertThat(report.failed()).as(String.valueOf(report.notes())).isFalse();
            assertThat(report.allowed() + report.denied()).isPositive();
        } finally {
            delegations.revoke(s.mission());
        }
        AgentTokenIssued issued = journal.readAll().stream()
                .map(e -> e.event())
                .filter(AgentTokenIssued.class::isInstance)
                .map(AgentTokenIssued.class::cast)
                .filter(e -> e.mission().equals(s.mission()))
                .findFirst()
                .orElseThrow();
        assertThat(issued.tokenId()).isNotBlank();
        assertThat(journalText()).doesNotContain("eyJ");
        assertThat(deniedAs("WRONG_PROCESS")).isFalse();
        try (Stream<Path> folders = Files.list(dataDir.resolve("sessions"))) {
            for (Path folder : folders.toList()) {
                for (String name : List.of("settings.json", "mcp.json", "launch.json")) {
                    Path file = folder.resolve(name);
                    if (Files.exists(file)) assertThat(Files.readString(file)).doesNotContain("eyJ");
                }
            }
        }
    }

    @Test
    void anApprovedMissionWithoutDelegationNeverFallsBackToTheDirectorsSignIn() throws Exception {
        Setup s = realMissionInProgress();
        keycloak.ensureAgentClient(CHRIS, 28800);
        String subject = keycloak.refresh(IamOnProfile.fake.refreshTokenFor("director", "floor42-desk"), "floor42-desk")
                .accessToken();
        String browser = sessions.openBrowser("iam-nodeleg").session().id();
        directorTokens.put(
                browser,
                new KeycloakClient.Tokens(subject, null, null, Instant.now().plusSeconds(3600)));
        try {
            SessionReport report = runtime.run(spec(s), new MissionGovernor(office, spec(s)));
            assertThat(report.failed()).isTrue();
            assertThat(String.join(" ", report.notes()))
                    .contains("no identity token")
                    .contains("no delegation");
        } finally {
            directorTokens.remove(browser);
        }
    }

    @Test
    void aRevokedAgentGetsNoNewSessionToken() throws Exception {
        Setup s = realMissionInProgress();
        keycloak.ensureAgentClient(CHRIS, 28800);
        clients.revokeLocally(spec(s).agent());
        try {
            SessionReport report = runtime.run(spec(s), new MissionGovernor(office, spec(s)));
            assertThat(report.failed()).isTrue();
            assertThat(String.join(" ", report.notes()))
                    .contains("no identity token")
                    .contains("revoked");
        } finally {
            clients.enable(spec(s).agent());
        }
    }

    @Test
    void aSlowKeycloakDoesNotSlowTheHookPath() throws Exception {
        String token = liveSession(realMissionInProgress(), ME);
        IamOnProfile.fake.delay(Duration.ofSeconds(3));
        try {
            long begin = System.nanoTime();
            hook(token, preToolUse("Read", "slow-1")).statusCode(200);
            assertThat((System.nanoTime() - begin) / 1_000_000).isLessThan(500);
        } finally {
            IamOnProfile.fake.delay(Duration.ZERO);
        }
    }

    /** A step-up sign-in as the director's browser would do it: the offline tokens a delegation is made of. */
    private KeycloakClient.Tokens offlineTokens() throws Exception {
        String verifier = "v".repeat(43);
        String challenge = dev.floor42.server.iam.Jwts.base64url(java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        String redirect = "http://127.0.0.1:7420/auth/callback";
        String query = "response_type=code&client_id=floor42-desk&redirect_uri="
                + java.net.URLEncoder.encode(redirect, java.nio.charset.StandardCharsets.UTF_8)
                + "&scope=openid+offline_access&state=s&nonce=n&code_challenge=" + challenge
                + "&code_challenge_method=S256&prompt=login&max_age=0";
        var r = java.net.http.HttpClient.newHttpClient()
                .send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                        IamOnProfile.fake.issuer() + "/protocol/openid-connect/auth?" + query))
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
        String location = r.headers().firstValue("Location").orElseThrow();
        String code = location.replaceAll(".*[?&]code=([^&]+).*", "$1");
        return keycloak.redeemCode(code, verifier, java.net.URI.create(redirect));
    }
}
