package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TerminalEvent.TerminalClosed;
import dev.floor42.domain.event.TerminalEvent.TerminalOpened;
import dev.floor42.server.app.DirectorTerminals;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.runtime.AgentRuntime;
import dev.floor42.server.runtime.HookRegistry;
import dev.floor42.server.runtime.RuntimeEvent;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The director's own terminal (ADR-0041), driven with a fake interactive program (FakeTty.java), never Claude Code: a
 * real pseudo-terminal, the screen over the WebSocket, only "opened" and "closed" journaled, the process tree stopped.
 */
@QuarkusTest
class TerminalApiTest {

    @Inject
    SessionToken token;

    @Inject
    HookRegistry hooks;

    @Inject
    Journal journal;

    @Inject
    Vertx vertx;

    @Inject
    SessionRegistry sessions;

    @Inject
    OfficeService office;

    @TestHTTPResource("/")
    URI base;

    final ObjectMapper json = new ObjectMapper();

    String bearer() {
        return "Bearer " + token.value();
    }

    Path project(String name) throws Exception {
        Path dir = Files.createTempDirectory("floor42-terminal-" + name);
        dir.toFile().deleteOnExit();
        return dir.toRealPath();
    }

    JsonNode open(Path project) throws Exception {
        String body = given().header("Authorization", bearer())
                .contentType(ContentType.JSON)
                .body(Map.of("project", project.toString(), "columns", 100, "rows", 30))
                .post("/api/terminals")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        return json.readTree(body);
    }

    /** A browser attached to a terminal: what it printed, as text, and the JSON messages. */
    final class Screen {
        final StringBuffer text = new StringBuffer();
        final List<JsonNode> messages = new CopyOnWriteArrayList<>();
        final CompletableFuture<Short> closed = new CompletableFuture<>();
        WebSocket ws;

        void type(String s) {
            ws.writeTextMessage("{\"type\":\"input\",\"data\":" + quote(s) + "}");
        }

        boolean shows(String s) {
            return text.indexOf(s) >= 0;
        }
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r") + "\"";
    }

    Screen attach(String id, String ticket, String origin) throws Exception {
        WebSocketConnectOptions o = new WebSocketConnectOptions()
                .setHost("127.0.0.1")
                .setPort(base.getPort())
                .setURI("/ws/terminal/" + id + (ticket == null ? "" : "?ticket=" + ticket))
                .addHeader("Authorization", bearer());
        // Vert.x would add an Origin of its own: the test says which one a client sends.
        // (and drops any Origin when told not to send one).
        if (origin == null) o.setAllowOriginHeader(false);
        else o.addHeader("Origin", origin);
        Screen s = new Screen();
        // The handlers are set in the connect's own callback, on the event loop, before any frame is read: the daemon
        // sends the screen it kept (or closes a refused attach) at once, and a handler set from this thread, after
        // the connect returned, can miss that first frame (the screen never shows) or find the socket already closed.
        s.ws = vertx.createWebSocketClient()
                .connect(o)
                .map(ws -> {
                    s.ws = ws;
                    ws.binaryMessageHandler(b -> s.text.append(b.toString(StandardCharsets.UTF_8)));
                    ws.textMessageHandler(m -> {
                        try {
                            s.messages.add(json.readTree(m));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    ws.closeHandler(v -> s.closed.complete(ws.closeStatusCode()));
                    return ws;
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        return s;
    }

    List<EventEnvelope> terminalEvents(String id) {
        return journal.readAll().stream()
                .filter(e -> e.event() instanceof TerminalOpened o
                                && o.terminal().equals(id)
                        || e.event() instanceof TerminalClosed c && c.terminal().equals(id))
                .toList();
    }

    @Test
    void theDirectorTypesInTheirOwnProgramAndOnlyOpenedAndClosedAreJournaled() throws Exception {
        Path project = project("own");
        JsonNode access = open(project);
        String id = access.path("terminal").path("id").asText();
        assertThat(id).startsWith("TERM-");
        assertThat(access.path("terminal").path("running").asBoolean()).isTrue();
        assertThat(access.path("terminal").path("costUsd").isNull())
                .as("nothing reported yet")
                .isTrue();

        Screen screen = attach(id, access.path("ticket").asText(), "http://127.0.0.1:" + base.getPort());
        await("the program's first line", Duration.ofSeconds(30), () -> screen.shows("FAKE-TTY ready"));
        String first = screen.text.toString();
        assertThat(first).as("it runs in a real terminal").contains("tty=true");
        assertThat(first).as("a terminal type xterm.js understands").contains("term=xterm-256color");
        assertThat(first)
                .as("no Floor42 variable, no Claude Code marker reaches it")
                .contains("floor42-env=[]");
        assertThat(first).as("in the chosen project").contains("cwd=" + project);
        // Floor42 adds no isolation flag and no hook: only the status line that copies the reported cost.
        Matcher args = Pattern.compile("args=\\[([^\\]]*)\\]").matcher(first);
        assertThat(args.find()).isTrue();
        assertThat(args.group(1)).startsWith("--settings").doesNotContain("--restricted", "--strict-mcp-config", "-p");

        screen.type("hello there\r");
        await("its echo", Duration.ofSeconds(10), () -> screen.shows("echo: hello there"));
        screen.type("/cost\r");
        await("its own /cost", Duration.ofSeconds(10), () -> screen.shows("Total cost:"));
        screen.ws.writeTextMessage("{\"type\":\"resize\",\"columns\":80,\"rows\":20}");

        // The settings Floor42 passed hold one thing: a status line writing to the terminal's folder.
        String settingsPath = args.group(1).replaceFirst("^--settings,\\s*", "").strip();
        JsonNode settings = json.readTree(Path.of(settingsPath).toFile());
        assertThat(settings.properties()).extracting(Map.Entry::getKey).containsExactly("statusLine");
        assertThat(settings.path("statusLine").path("command").asText()).startsWith("cat > '");
        assertThat(settings.toString()).doesNotContain("hooks", token.value());

        // Claude Code runs the status line with its figures: here the test writes them where that command would.
        Path status = Path.of(settingsPath).resolveSibling("status.json");
        Files.writeString(
                status,
                "{\"session_id\":\"s-1\",\"model\":{\"display_name\":\"Opus\"},\"cost\":{\"total_cost_usd\":0.0421}}");
        await(
                "the cost Claude Code reported",
                Duration.ofSeconds(10),
                () -> screen.messages.stream()
                        .anyMatch(m -> m.path("type").asText().equals("status")
                                && m.path("terminal").path("costUsd").asDouble() == 0.0421));

        // One at a time per project.
        given().header("Authorization", bearer())
                .contentType(ContentType.JSON)
                .body(Map.of("project", project.toString()))
                .post("/api/terminals")
                .then()
                .statusCode(409)
                .body("code", equalTo("TERMINAL_OPEN"));

        given().header("Authorization", bearer())
                .get("/api/terminals")
                .then()
                .statusCode(200)
                .body("available", equalTo(true))
                .body("terminals.find { it.id == '" + id + "' }.costUsd", equalTo(0.0421f));

        given().header("Authorization", bearer())
                .delete("/api/terminals/" + id)
                .then()
                .statusCode(200);
        await(
                "the end on the screen",
                Duration.ofSeconds(15),
                () -> screen.messages.stream()
                        .anyMatch(m -> m.path("type").asText().equals("ended")));

        List<EventEnvelope> events = terminalEvents(id);
        assertThat(events)
                .extracting(e -> e.event().getClass().getSimpleName())
                .containsExactly("TerminalOpened", "TerminalClosed");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.actor().isDirector()).isTrue();
            assertThat(e.origin().name()).isEqualTo("REAL");
        });
        assertThat(((TerminalClosed) events.getLast().event()).reason()).isEqualTo("closed");
        // Nothing typed or printed reached the journal.
        assertThat(journal.readAll().toString()).doesNotContain("hello there", "FAKE-TTY", "Total cost");
        screen.ws.close();
    }

    @Test
    void closingStopsTheWholeProcessTree() throws Exception {
        JsonNode access = open(project("tree"));
        String id = access.path("terminal").path("id").asText();
        Screen screen = attach(id, access.path("ticket").asText(), "http://127.0.0.1:" + base.getPort());
        await("the program's first line", Duration.ofSeconds(30), () -> screen.shows("FAKE-TTY ready"));
        screen.type("child\r");
        await(
                "its child",
                Duration.ofSeconds(15),
                () -> Pattern.compile("child \\d+").matcher(screen.text).find());
        Matcher m = Pattern.compile("child (\\d+)").matcher(screen.text);
        assertThat(m.find()).isTrue();
        ProcessHandle child = ProcessHandle.of(Long.parseLong(m.group(1))).orElseThrow();
        assertThat(child.isAlive()).isTrue();

        given().header("Authorization", bearer())
                .delete("/api/terminals/" + id)
                .then()
                .statusCode(200);
        await("the child stopped", Duration.ofSeconds(15), () -> !child.isAlive());
        screen.ws.close();
    }

    @Test
    void aProgramThatEndsByItselfIsRecordedByFloor42WithItsExitCode() throws Exception {
        JsonNode access = open(project("exit"));
        String id = access.path("terminal").path("id").asText();
        Screen screen = attach(id, access.path("ticket").asText(), "http://127.0.0.1:" + base.getPort());
        await("the program's first line", Duration.ofSeconds(30), () -> screen.shows("FAKE-TTY ready"));
        screen.type("exit\r");
        await(
                "its end recorded",
                Duration.ofSeconds(15),
                () -> terminalEvents(id).size() == 2);
        EventEnvelope closed = terminalEvents(id).getLast();
        assertThat(closed.event())
                .isEqualTo(new TerminalClosed(
                        id, ((TerminalOpened) terminalEvents(id).getFirst().event()).project(), "exited", 3));
        assertThat(closed.actor().kind().name()).isEqualTo("SYSTEM");
        given().header("Authorization", bearer())
                .delete("/api/terminals/" + id)
                .then()
                .statusCode(404);
        screen.ws.close();
    }

    /**
     * Holds the thread that journals the end of one terminal, right after the event is stored, until released: the
     * test then stands in the gap between the journal and the terminals in memory, without waiting for a clock.
     *
     * <p>Test only: an {@link OfficeService} subscriber must never block, since it runs inside the office's lock (every
     * command waits meanwhile). This one blocks on purpose, for a few milliseconds, to open that gap.
     */
    final class HeldEnd implements AutoCloseable {
        final CountDownLatch stored = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AutoCloseable subscription;

        HeldEnd(String id) {
            subscription = office.subscribe(events -> {
                if (!ends(events, id)) return;
                stored.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        void awaitStored() throws InterruptedException {
            assertThat(stored.await(15, TimeUnit.SECONDS)).as("its end stored").isTrue();
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            subscription.close();
        }
    }

    static boolean ends(List<EventEnvelope> events, String id) {
        return events.stream()
                .anyMatch(e ->
                        e.event() instanceof TerminalClosed c && c.terminal().equals(id));
    }

    long closings(String id) {
        return terminalEvents(id).stream()
                .filter(e -> e.event() instanceof TerminalClosed)
                .count();
    }

    /** A terminal opened with the director's token, its program up, a browser attached. */
    record Opened(String id, Screen screen) {}

    Opened started(String name) throws Exception {
        JsonNode access = open(project(name));
        String id = access.path("terminal").path("id").asText();
        Screen screen = attach(id, access.path("ticket").asText(), own());
        await("the program's first line", Duration.ofSeconds(30), () -> screen.shows("FAKE-TTY ready"));
        return new Opened(id, screen);
    }

    int delete(String id) {
        return given().header("Authorization", bearer())
                .delete("/api/terminals/" + id)
                .statusCode();
    }

    @Test
    void aProgramThatEndedIsGoneFromTheApiAsSoonAsItsEndIsJournaled() throws Exception {
        Opened t = started("ended");
        try (HeldEnd held = new HeldEnd(t.id())) {
            t.screen().type("exit\r");
            held.awaitStored();
            assertThat(terminalEvents(t.id())).hasSize(2);
            // What aProgramThatEndsByItself... asserts once it sees the end in the journal, here with no luck needed.
            assertThat(delete(t.id()))
                    .as("a close while its end is being recorded")
                    .isEqualTo(404);
            assertThat(openTerminals()).as("no longer listed").doesNotContain(t.id());
        }
        await(
                "the end on the screen",
                Duration.ofSeconds(15),
                () -> t.screen().messages.stream()
                        .anyMatch(m -> m.path("type").asText().equals("ended")));
        assertThat(closings(t.id())).as("one end only, the program's").isEqualTo(1);
        assertThat(((TerminalClosed) terminalEvents(t.id()).getLast().event()).reason())
                .isEqualTo("exited");
        t.screen().ws.close();
    }

    @Test
    void aSecondCloseWhileTheFirstIsBeingRecordedFindsNothingToClose() throws Exception {
        Opened t = started("closed-twice");
        CompletableFuture<Integer> first;
        try (HeldEnd held = new HeldEnd(t.id())) {
            first = CompletableFuture.supplyAsync(() -> delete(t.id()));
            held.awaitStored();
            assertThat(delete(t.id())).as("the second close").isEqualTo(404);
            assertThat(openTerminals()).doesNotContain(t.id());
        }
        assertThat(first.get(15, TimeUnit.SECONDS)).as("the first close").isEqualTo(200);
        assertThat(closings(t.id())).isEqualTo(1);
        assertThat(((TerminalClosed) terminalEvents(t.id()).getLast().event()).reason())
                .isEqualTo("closed");
        t.screen().ws.close();
    }

    @Test
    void aCloseWhoseRecordFailsStillStopsTheProgram() throws Exception {
        Opened t = started("record-fails");
        t.screen().type("child\r");
        await(
                "its child",
                Duration.ofSeconds(15),
                () -> Pattern.compile("child \\d+").matcher(t.screen().text).find());
        Matcher m = Pattern.compile("child (\\d+)").matcher(t.screen().text);
        assertThat(m.find()).isTrue();
        ProcessHandle child = ProcessHandle.of(Long.parseLong(m.group(1))).orElseThrow();
        // A fault injected where the end is recorded: an Error, which no catch of a RuntimeException stops.
        AutoCloseable fault = office.subscribe(events -> {
            if (ends(events, t.id())) throw new InjectedFault();
        });
        try {
            assertThat(delete(t.id())).as("the close fails").isEqualTo(500);
        } finally {
            fault.close();
        }
        await("the program stopped all the same", Duration.ofSeconds(15), () -> !child.isAlive());
        assertThat(openTerminals()).doesNotContain(t.id());
        t.screen().ws.close();
    }

    /** The fault the test above injects. */
    static final class InjectedFault extends Error {
        InjectedFault() {
            super("fault injected by TerminalApiTest");
        }
    }

    @Test
    void theScreenNeedsTheDirectorASingleUseTicketAndABrowserOrigin() throws Exception {
        JsonNode access = open(project("ticket"));
        String id = access.path("terminal").path("id").asText();
        String ticket = access.path("ticket").asText();
        String origin = "http://127.0.0.1:" + base.getPort();

        assertThat(attach(id, null, origin).closed.get(10, TimeUnit.SECONDS))
                .as("no ticket")
                .isEqualTo((short) 4403);
        assertThat(attach(id, ticket, null).closed.get(10, TimeUnit.SECONDS))
                .as("no Origin: not a browser of the director's")
                .isEqualTo((short) 4403);
        // The refusal above consumed the ticket: a ticket opens the screen once.
        assertThat(attach(id, ticket, origin).closed.get(10, TimeUnit.SECONDS))
                .as("a ticket used twice")
                .isEqualTo((short) 4403);

        String again = given().header("Authorization", bearer())
                .post("/api/terminals/" + id + "/attach")
                .then()
                .statusCode(200)
                .extract()
                .path("ticket");
        Screen screen = attach(id, again, origin);
        await("the screen kept in memory", Duration.ofSeconds(30), () -> screen.shows("FAKE-TTY ready"));
        screen.ws.close();
        given().header("Authorization", bearer())
                .delete("/api/terminals/" + id)
                .then()
                .statusCode(200);
    }

    @Test
    void closingTheScreenOnlyDetachesItAndARunningShellSurvives() throws Exception {
        // The panel unmounts (another panel replaces it): the socket closes, the shell keeps running.
        JsonNode access = open(project("detach"));
        String id = access.path("terminal").path("id").asText();
        String origin = "http://127.0.0.1:" + base.getPort();
        Screen first = attach(id, access.path("ticket").asText(), origin);
        await("the program's first line", Duration.ofSeconds(30), () -> first.shows("FAKE-TTY ready"));
        first.ws.close();
        first.closed.get(10, TimeUnit.SECONDS);

        given().header("Authorization", bearer())
                .get("/api/terminals")
                .then()
                .statusCode(200)
                .body("terminals.find { it.id == '" + id + "' }.running", equalTo(true));
        // The panel comes back: the same shell answers.
        String again = given().header("Authorization", bearer())
                .post("/api/terminals/" + id + "/attach")
                .then()
                .statusCode(200)
                .extract()
                .path("ticket");
        Screen second = attach(id, again, origin);
        await("the screen kept in memory", Duration.ofSeconds(30), () -> second.shows("FAKE-TTY ready"));
        second.type("still here\r");
        await("its echo", Duration.ofSeconds(10), () -> second.shows("echo: still here"));
        second.ws.close();
        given().header("Authorization", bearer())
                .delete("/api/terminals/" + id)
                .then()
                .statusCode(200);
    }

    String own() {
        return "http://127.0.0.1:" + base.getPort();
    }

    /** A browser's request: its cookie, the daemon's own Origin and Floor42's header. */
    io.restassured.specification.RequestSpecification browser(String cookie) {
        return given().cookie(LocalAccessGuard.COOKIE, cookie)
                .header("Origin", own())
                .header(LocalAccessGuard.REQUEST_HEADER, "1");
    }

    /** A terminal a signed-in browser opens, its program up. */
    String openFrom(String cookie, String name) throws Exception {
        String body = browser(cookie)
                .contentType(ContentType.JSON)
                .body(Map.of("project", project(name).toString(), "columns", 100, "rows", 30))
                .post("/api/terminals")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        JsonNode access = json.readTree(body);
        String id = access.path("terminal").path("id").asText();
        Screen screen = attach(id, access.path("ticket").asText(), own());
        await("the program's first line", Duration.ofSeconds(30), () -> screen.shows("FAKE-TTY ready"));
        return id;
    }

    List<String> openTerminals() {
        return given().header("Authorization", bearer())
                .get("/api/terminals")
                .jsonPath()
                .getList("terminals.id");
    }

    @Test
    void signingOneBrowserOutStopsItsShellsAndLeavesAnotherSessionsRunning() throws Exception {
        String first = sessions.openBrowser("first browser").secret();
        String second = sessions.openBrowser("second browser").secret();
        String mine = openFrom(first, "signout-mine");
        String theirs = openFrom(second, "signout-theirs");

        browser(first).post("/api/auth/sign-out").then().statusCode(204);

        await(
                "the signed-out browser's shell stopped",
                Duration.ofSeconds(30),
                () -> !openTerminals().contains(mine));
        assertThat(terminalEvents(mine).stream().map(e -> e.event()).toList())
                .as("closed by the director, as a close from the panel")
                .anyMatch(e -> e instanceof TerminalClosed);
        given().header("Authorization", bearer())
                .get("/api/terminals")
                .then()
                .statusCode(200)
                .body("terminals.find { it.id == '" + theirs + "' }.running", equalTo(true));
        assertThat(terminalEvents(theirs).stream().map(e -> e.event()).toList())
                .as("the other session's shell was not touched")
                .noneMatch(e -> e instanceof TerminalClosed);

        browser(second).delete("/api/terminals/" + theirs).then().statusCode(200);
    }

    @Inject
    SocketSessions socketSessions;

    /** A feed socket a signed-in browser opened: the future ends with the close code. */
    CompletableFuture<Short> feedOf(String cookie) throws Exception {
        CompletableFuture<Short> closed = new CompletableFuture<>();
        vertx.createWebSocketClient()
                .connect(new WebSocketConnectOptions()
                        .setHost("127.0.0.1")
                        .setPort(base.getPort())
                        .setURI("/ws/events")
                        .addHeader("Cookie", LocalAccessGuard.COOKIE + "=" + cookie)
                        .addHeader("Origin", own()))
                .map(ws -> {
                    ws.closeHandler(v -> closed.complete(ws.closeStatusCode()));
                    return ws;
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        return closed;
    }

    @Test
    void revokingABrowserSessionClosesItsShellAndItsSockets() throws Exception {
        String cookie = sessions.openBrowser("revoked browser").secret();
        String id = openFrom(cookie, "revoked");
        CompletableFuture<Short> feed = feedOf(cookie);
        String sessionId = sessions.list().stream()
                .filter(s -> s.userAgent().equals("revoked browser"))
                .findFirst()
                .orElseThrow()
                .id();

        given().header("Authorization", bearer())
                .delete("/api/auth/sessions/" + sessionId)
                .then()
                .statusCode(204);

        await("no shell left", Duration.ofSeconds(2), () -> !openTerminals().contains(id));
        feed.get(2, TimeUnit.SECONDS);
    }

    @Test
    void anExpiredBrowserSessionLosesItsShellAndItsSocketsAtTheNextSweep() throws Exception {
        String cookie = sessions.openBrowser("expired browser").secret();
        String id = openFrom(cookie, "expired");
        CompletableFuture<Short> feed = feedOf(cookie);

        java.time.Clock real = java.time.Clock.systemUTC();
        sessions.useClock(java.time.Clock.offset(real, Duration.ofHours(25)));
        try {
            // The server binds a socket just after the client sees it open: sweep until it is bound and closed.
            await("the socket closed", Duration.ofSeconds(2), () -> {
                socketSessions.sweep();
                return feed.isDone();
            });
            await("no shell left", Duration.ofSeconds(2), () -> !openTerminals().contains(id));
        } finally {
            sessions.useClock(real);
        }
    }

    @Test
    void signingOutEverywhereStopsEveryShell() throws Exception {
        String first = sessions.openBrowser("first browser").secret();
        String second = sessions.openBrowser("second browser").secret();
        String one = openFrom(first, "everywhere-one");
        String other = openFrom(second, "everywhere-other");
        String directors =
                open(project("everywhere-token")).path("terminal").path("id").asText();

        browser(first).post("/api/auth/sign-out-everywhere").then().statusCode(204);

        await("no shell left", Duration.ofSeconds(30), () -> {
            List<String> open = openTerminals();
            return !open.contains(one) && !open.contains(other) && !open.contains(directors);
        });
    }

    @Test
    void aScriptTokenCannotSignOutNorStopAShell() throws Exception {
        String browserCookie = sessions.openBrowser("browser").secret();
        String id = openFrom(browserCookie, "script-signout");
        String script = sessions.issueScript(SessionRegistry.Scope.ALL, 1, "script", "test")
                .secret();

        for (String route : List.of("/api/auth/sign-out", "/api/auth/sign-out-everywhere")) {
            given().header("Authorization", "Bearer " + script)
                    .cookie(LocalAccessGuard.COOKIE, browserCookie)
                    .post(route)
                    .then()
                    .statusCode(403)
                    .body("code", equalTo("SCOPE"));
        }
        assertThat(openTerminals()).contains(id);
        browser(browserCookie).get("/api/terminals").then().statusCode(200);
        given().header("Authorization", "Bearer " + script)
                .get("/api/missions")
                .then()
                .statusCode(200);

        browser(browserCookie).delete("/api/terminals/" + id).then().statusCode(200);
    }

    @Test
    void anAgentsCredentialIsRefusedEverywhereOnTheTerminal() throws Exception {
        String agent = hooks.register(new AgentRuntime.Governor() {
            @Override
            public AgentRuntime.Verdict decide(RuntimeEvent.ToolRequested call) {
                return null;
            }

            @Override
            public String violationNow(dev.floor42.server.runtime.RuntimeEvent.ToolRequested call) {
                return null;
            }

            @Override
            public void completed(RuntimeEvent.ToolCompleted result) {}
        });
        try {
            given().header("Authorization", "Bearer " + agent)
                    .get("/api/terminals")
                    .then()
                    .statusCode(403);
            given().header("Authorization", "Bearer " + agent)
                    .contentType(ContentType.JSON)
                    .body(Map.of("project", project("agent").toString()))
                    .post("/api/terminals")
                    .then()
                    .statusCode(403);
            given().header("Authorization", "Bearer " + agent)
                    .delete("/api/terminals/TERM-1")
                    .then()
                    .statusCode(403);
            var refused = vertx.createWebSocketClient()
                    .connect(new WebSocketConnectOptions()
                            .setHost("127.0.0.1")
                            .setPort(base.getPort())
                            .setURI("/ws/terminal/TERM-1?ticket=x")
                            .addHeader("Authorization", "Bearer " + agent))
                    .toCompletionStage()
                    .toCompletableFuture();
            assertThat(refused)
                    .failsWithin(Duration.ofSeconds(10))
                    .withThrowableThat()
                    .withMessageContaining("403");
            // Nobody but the director may open one: the request above started nothing.
            assertThat(journal.readAll().stream()
                            .filter(e -> e.event() instanceof TerminalOpened o
                                    && o.project().contains("floor42-terminal-agent")))
                    .isEmpty();
        } finally {
            hooks.unregister(agent);
        }
    }

    @Test
    void theEnvironmentHoldsNothingOfFloor42s() {
        Map<String, String> env = DirectorTerminalsAccess.environment(
                Map.of(
                        "PATH", "/usr/bin",
                        "FLOOR42_HOOK_TOKEN", "agent-secret",
                        "floor42_data_dir", "/x",
                        "OFFICE_TOKEN", "old",
                        "CLAUDECODE", "1",
                        "CLAUDE_CODE_ENTRYPOINT", "sdk-ts",
                        "CLAUDE_CONFIG_DIR", "/home/me/.claude",
                        "SOMETHING", "prefix-director-token-suffix"),
                "director-token");
        assertThat(env).containsKeys("PATH", "CLAUDE_CONFIG_DIR", "TERM", "COLORTERM");
        assertThat(env)
                .doesNotContainKeys(
                        "FLOOR42_HOOK_TOKEN",
                        "floor42_data_dir",
                        "OFFICE_TOKEN",
                        "CLAUDECODE",
                        "CLAUDE_CODE_ENTRYPOINT",
                        "SOMETHING");
    }

    @Test
    void theEnvironmentIsNoChildOfAnotherClaudeCodeSession() {
        Map<String, String> env = DirectorTerminalsAccess.environment(
                Map.of(
                        "CLAUDE_CODE_CHILD_SESSION", "1",
                        "claude_code_child_session", "1",
                        "CLAUDE_CODE_SSE_PORT", "51234",
                        "CLAUDE_CODE_PARENT_SESSION_ID", "abc",
                        "CLAUDE_CODE_SESSION_ID", "def",
                        "CLAUDE_CODE_USE_BEDROCK", "1",
                        "CLAUDE_CODE_GIT_BASH_PATH", "C:/Git/bin/bash.exe"),
                "director-token");
        assertThat(env).containsKeys("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_GIT_BASH_PATH");
        assertThat(env)
                .doesNotContainKeys(
                        "CLAUDE_CODE_CHILD_SESSION",
                        "claude_code_child_session",
                        "CLAUDE_CODE_SSE_PORT",
                        "CLAUDE_CODE_PARENT_SESSION_ID",
                        "CLAUDE_CODE_SESSION_ID");
    }

    /** Reaches the package-private environment rule from the api package. */
    static final class DirectorTerminalsAccess {
        static Map<String, String> environment(Map<String, String> daemon, String token) {
            return DirectorTerminals.environmentFor(daemon, token);
        }
    }
}
