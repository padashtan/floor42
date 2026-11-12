package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Decision;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.DeliveryEvent.MissionAssetsSelected;
import dev.floor42.domain.event.DeliveryEvent.PluginCommandRan;
import dev.floor42.domain.event.MissionEvent;
import dev.floor42.domain.event.TicketEvent;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.app.ClaudeCli;
import dev.floor42.server.app.MissionAssets;
import dev.floor42.server.app.MissionGovernor;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.Plugins;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.ClaudeCodeRuntime;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Installing plugins on the director's click and choosing what a mission's sessions receive (ADR-0040), end to end with
 * a fake {@code claude} command line and a local marketplace folder. No test runs the real Claude Code, reaches the
 * network, or touches the machine's own Claude Code folder.
 */
@QuarkusTest
@TestProfile(PluginsApiTest.OwnClaudeHome.class)
class PluginsApiTest extends ApiTestBase {

    /** A director's Claude Code folder of this test only: other tests read the shared one. */
    public static class OwnClaudeHome implements QuarkusTestProfile {
        static final Path HOME = Path.of("target", "test-plugins-home").toAbsolutePath();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floor42.catalogue.claude-home", HOME.toString());
        }
    }

    static final String MARKET = "acme-market";
    static final AgentId PLUGIA = new AgentId("plugia");

    @Inject
    OfficeService office;

    @Inject
    Plugins plugins;

    @Inject
    MissionAssets assets;

    @Inject
    ClaudeCodeRuntime runtime;

    @Inject
    Journal journal;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    Path state;
    Path market;
    final List<java.net.URI> fetched = new ArrayList<>();

    @BeforeEach
    void fakes() throws Exception {
        delete(OwnClaudeHome.HOME);
        delete(plugins.assetsDir());
        market = Files.createTempDirectory("floor42-market");
        write(market.resolve(".claude-plugin/marketplace.json"), """
                {"name":"acme-market","owner":{"name":"Acme"},"plugins":[
                 {"name":"helper","source":"./plugins/helper","description":"Tidies code","author":{"name":"Acme Tools"},"license":"MIT"},
                 {"name":"hooky","source":"./plugins/hooky","description":"Runs things"},
                 {"name":"remote","source":{"source":"github","repo":"acme/remote"},"description":"Elsewhere"}]}
                """);
        write(
                market.resolve("plugins/helper/.claude-plugin/plugin.json"),
                "{\"name\":\"helper\",\"version\":\"2.0.0\"}");
        write(
                market.resolve("plugins/helper/skills/tidy/SKILL.md"),
                "---\nname: tidy\ndescription: Tidy code\n---\nTidy.\n");
        write(market.resolve("plugins/helper/skills/tidy/notes.md"), "More.\n");
        write(
                market.resolve("plugins/helper/agents/checker.md"),
                "---\nname: checker\ndescription: Checks\n---\nCheck.\n");
        write(
                market.resolve("plugins/helper/agents/sneaky.md"),
                "---\nname: sneaky\ndescription: Hooks\nhooks:\n  PreToolUse: x\n---\nSneak.\n");
        write(market.resolve("plugins/helper/hooks/hooks.json"), "{\"hooks\":{}}");
        write(market.resolve("plugins/hooky/.claude-plugin/plugin.json"), "{\"name\":\"hooky\"}");
        write(market.resolve("plugins/hooky/hooks/hooks.json"), "{\"hooks\":{\"PreToolUse\":[]}}");
        write(market.resolve("plugins/hooky/.mcp.json"), "{\"mcpServers\":{}}");
        // The director added the marketplace in their own Claude Code.
        String path = market.toString().replace("\\", "\\\\");
        write(
                OwnClaudeHome.HOME.resolve("plugins/known_marketplaces.json"),
                "{\"acme-market\":{\"source\":{\"source\":\"directory\",\"path\":\"" + path + "\"},"
                        + "\"installLocation\":\"" + path + "\"}}");
        write(OwnClaudeHome.HOME.resolve("plugins/known.tsv"), MARKET + "\t" + market + "\n");
        state = Files.createTempDirectory("floor42-fake-claude-cli");
        scenario("");
        plugins.useForTests(
                new ClaudeCli.Process(List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        Path.of("src/test/resources/fake-claude-cli/FakeClaudeCli.java")
                                .toAbsolutePath()
                                .toString(),
                        "--fake-state",
                        state.toString())),
                (uri, max) -> {
                    fetched.add(uri);
                    return "{\"name\":\"claude-plugins-official\",\"plugins\":[{\"name\":\"far\",\"source\":\"./p\"}]}"
                            .getBytes(StandardCharsets.UTF_8);
                },
                60L);
    }

    @AfterEach
    void realOnesBack() {
        plugins.useForTests(null, null, null);
    }

    void scenario(String text) throws Exception {
        Files.writeString(
                state.resolve("scenario"),
                "home=" + OwnClaudeHome.HOME.toString().replace("\\", "/") + "\n" + text);
    }

    JsonPath preview(String target, String action, String plugin) {
        return api().body(Map.of("target", target, "action", action, "plugin", plugin, "marketplace", MARKET))
                .post("/api/plugins/preview")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    JsonPath run(String target, String action, String plugin) {
        String digest = preview(target, action, plugin).getString("digest");
        return api().body(Map.of(
                        "target", target, "action", action, "plugin", plugin, "marketplace", MARKET, "digest", digest))
                .post("/api/plugins/run")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    @Test
    void aPreviewShowsSourcePublisherVersionLicenceContentsAndTheExactCommandsAndRunsNothing() throws Exception {
        JsonPath view =
                api().get("/api/plugins").then().statusCode(200).extract().jsonPath();
        assertThat(view.getBoolean("cli.available")).isTrue();
        assertThat(view.getList("marketplaces.name", String.class))
                .contains(MARKET, "claude-plugins-official", "anthropic-agent-skills");
        assertThat(view.getList("marketplaces.find { it.name == 'acme-market' }.knownIn", String.class))
                .containsExactly("CLAUDE");

        JsonPath p = preview("FLOOR42", "INSTALL", "helper");
        assertThat(p.getString("refused")).isNull();
        assertThat(p.getString("publisher")).isEqualTo("Acme Tools");
        assertThat(p.getString("version")).isEqualTo("2.0.0");
        assertThat(p.getString("license")).isEqualTo("MIT");
        assertThat(p.getList("contents.skills", String.class)).containsExactly("tidy");
        assertThat(p.getList("contents.agents", String.class)).containsExactly("checker", "sneaky");
        assertThat(p.getBoolean("contents.hooks")).isTrue();
        assertThat(p.getList("warnings", String.class))
                .contains("HOOKS", "NOT_UNTIL_SELECTED", "CODE_NEVER_TO_AGENTS")
                .doesNotContain("YOUR_CLAUDE");
        List<String> commands = p.getList("commands", String.class);
        assertThat(commands).hasSize(2);
        assertThat(commands.get(0)).startsWith("CLAUDE_CONFIG_DIR=").contains("claude plugin marketplace add");
        assertThat(commands.get(1)).endsWith("claude plugin install helper@acme-market --json");
        // Only the version was asked: nothing was installed anywhere.
        assertThat(Files.readString(state.resolve("calls.log"))).doesNotContain("plugin ");

        JsonPath remote = preview("FLOOR42", "INSTALL", "remote");
        assertThat(remote.getBoolean("contents.known")).isFalse();
        assertThat(remote.getList("warnings", String.class)).contains("CONTENTS_UNKNOWN", "NO_LICENSE");

        JsonPath hooky = preview("CLAUDE", "INSTALL", "hooky");
        assertThat(hooky.getList("warnings", String.class)).contains("HOOKS", "MCP", "YOUR_CLAUDE");
        assertThat(hooky.getList("commands", String.class)).hasSize(1);
    }

    @Test
    void floor42OnlyInstallsIntoItsOwnFolderAndAStaleOrFailedRunIsJudgedFromDisk() throws Exception {
        String digest = preview("FLOOR42", "INSTALL", "helper").getString("digest");
        api().body(Map.of(
                        "target",
                        "FLOOR42",
                        "action",
                        "INSTALL",
                        "plugin",
                        "helper",
                        "marketplace",
                        MARKET,
                        "digest",
                        digest + "x"))
                .post("/api/plugins/run")
                .then()
                .statusCode(409)
                .body("code", equalTo("PREVIEW_STALE"));

        JsonPath out = run("FLOOR42", "INSTALL", "helper");
        assertThat(out.getString("outcome")).isEqualTo("INSTALLED");
        assertThat(out.getString("version")).isEqualTo("1.0.0");
        assertThat(out.getList("steps.output", String.class)).isNotEmpty();
        String log = Files.readString(state.resolve("calls.log"));
        assertThat(log).contains("[" + plugins.floor42Config() + "] plugin install helper@acme-market --json");
        // The director's own Claude Code was not touched.
        assertThat(OwnClaudeHome.HOME.resolve("plugins/installed_plugins.json")).doesNotExist();
        assertThat(api().get("/api/plugins")
                        .then()
                        .extract()
                        .jsonPath()
                        .getList("installed.findAll { it.target == 'FLOOR42' }.plugin", String.class))
                .containsExactly("helper");

        // A failure that changed nothing, then one that installed all the same: the state read again decides.
        scenario("install=fail");
        JsonPath failed = run("CLAUDE", "INSTALL", "hooky");
        assertThat(failed.getString("outcome")).isEqualTo("NOT_INSTALLED");
        assertThat(failed.getBoolean("commandsOk")).isFalse();
        assertThat(failed.getString("steps[0].output")).contains("network unreachable");
        scenario("install=failAfter");
        JsonPath partly = run("CLAUDE", "INSTALL", "hooky");
        assertThat(partly.getString("outcome")).isEqualTo("INSTALLED");
        assertThat(partly.getBoolean("commandsOk")).isFalse();

        scenario("");
        assertThat(run("CLAUDE", "UNINSTALL", "hooky").getString("outcome")).isEqualTo("REMOVED");
        assertThat(preview("CLAUDE", "UNINSTALL", "hooky").getString("refused")).isEqualTo("NOT_INSTALLED");

        List<PluginCommandRan> ran = events(PluginCommandRan.class);
        assertThat(ran).extracting(PluginCommandRan::present).contains(true, false);
        assertThat(journal.readAll().stream()
                        .filter(e -> e.event() instanceof PluginCommandRan)
                        .allMatch(e -> e.origin() == Origin.REAL && e.actor().isDirector()))
                .isTrue();
    }

    @Test
    void refreshingAMarketplaceReadsGitHubOnlyOnTheDirectorsClick() {
        api().get("/api/plugins").then().statusCode(200);
        assertThat(fetched).isEmpty();
        JsonPath view = api().post("/api/plugins/marketplaces/claude-plugins-official/refresh")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(fetched)
                .singleElement()
                .hasToString(
                        "http://127.0.0.1:9/anthropics/claude-plugins-official/HEAD/.claude-plugin/marketplace.json");
        assertThat(view.getString("marketplaces.find { it.name == 'claude-plugins-official' }.listedFrom"))
                .isEqualTo("fetched");
        api().post("/api/plugins/marketplaces/acme-market/refresh")
                .then()
                .statusCode(409)
                .body("code", equalTo("NOT_GITHUB"));
    }

    @Test
    void aMissionsSessionsReceiveOnlyTheChosenSkillsAndAgentsNeverTheirHooks() throws Exception {
        run("FLOOR42", "INSTALL", "helper");
        Setup s = mission();
        JsonPath choices = api().get("/api/missions/" + s.mission().value() + "/assets")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        String tidy = "floor42:helper@acme-market/skill/tidy";
        String checker = "floor42:helper@acme-market/agent/checker";
        String sneaky = "floor42:helper@acme-market/agent/sneaky";
        assertThat(choices.getList("choices.id", String.class)).contains(tidy, checker, sneaky);
        assertThat(choices.getString("choices.find { it.id == '" + sneaky + "' }.reason"))
                .isEqualTo("declaresHooks");

        api().body(Map.of("items", List.of(sneaky)))
                .put("/api/missions/" + s.mission().value() + "/assets")
                .then()
                .statusCode(409)
                .body("code", equalTo("ASSET_NOT_GIVABLE"));
        api().body(Map.of("items", List.of(tidy, checker)))
                .put("/api/missions/" + s.mission().value() + "/assets")
                .then()
                .statusCode(200)
                .body("selected.size()", equalTo(2));
        assertThat(events(MissionAssetsSelected.class))
                .anyMatch(e -> e.mission().equals(s.mission()));

        // A real session of the mission: Floor42 lays out the choice and passes it with --plugin-dir.
        SessionSpec spec = new SessionSpec(
                s.mission(),
                s.ticket(),
                PLUGIA,
                Origin.REAL,
                s.workdir(),
                "Inspect the repository [fake:cap]",
                "claude-sonnet-5-5",
                "low",
                List.of("Read", "Skill", "Agent"));
        runtime.run(spec, new MissionGovernor(office, spec));
        Path session;
        try (Stream<Path> dirs = Files.list(dataDir.resolve(ClaudeCodeRuntime.SESSIONS_DIR))) {
            session = dirs.filter(d -> Files.exists(d.resolve("plugins/helper")))
                    .findFirst()
                    .orElseThrow();
        }
        Path given = session.resolve("plugins/helper");
        assertThat(given.resolve("skills/tidy/SKILL.md")).exists();
        assertThat(given.resolve("skills/tidy/notes.md")).exists();
        assertThat(given.resolve("agents/checker.md")).exists();
        assertThat(given.resolve("agents/sneaky.md")).doesNotExist();
        assertThat(given.resolve("hooks")).doesNotExist();
        assertThat(Files.readString(given.resolve(".claude-plugin/plugin.json")))
                .contains("\"helper\"")
                .doesNotContain("hooks");
        String init = Files.readAllLines(session.resolve("transcript.jsonl")).getFirst();
        assertThat(init).contains("helper:tidy").contains("helper:checker").doesNotContain("sneaky");

        // Uninstalled since: the next session is not given it, and says so.
        run("FLOOR42", "UNINSTALL", "helper");
        List<String> notes = new ArrayList<>();
        assertThat(assets.pluginDirs(spec, Files.createTempDirectory("floor42-session"), notes))
                .isEmpty();
        assertThat(notes).singleElement().asString().contains("no longer installed");
    }

    record Setup(MissionId mission, TicketId ticket, String workdir) {}

    Setup mission() throws Exception {
        Path workdir = Files.createTempDirectory("floor42-plugins-session");
        var director = office.context(Actor.director(), Origin.REAL);
        if (office.query(c -> c.staff().find(PLUGIA).isEmpty())) {
            office.execute(c -> c.staff()
                    .hire(
                            director,
                            PLUGIA,
                            "Plugia",
                            "Agent of the plugin tests",
                            "anthropic",
                            "claude-sonnet-5-5",
                            "low",
                            List.of()));
        }
        MissionId m = ((MissionEvent.MissionCreated) office.execute(c -> c.missions()
                                .create(
                                        office.context(Actor.director(), Origin.REAL),
                                        "Inspect",
                                        "Inspect the repository",
                                        workdir.toString(),
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((TicketEvent.TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        office.context(Actor.director(), Origin.REAL),
                                        m,
                                        "Inspect",
                                        "",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of("report")))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.tickets().assign(office.context(Actor.director(), Origin.REAL), t, PLUGIA));
        office.execute(c -> c.missions()
                .setToolPermission(
                        office.context(Actor.director(), Origin.REAL), m, "Read", ActionCategory.READ, Decision.ALLOW));
        office.execute(c -> c.missions().submitPlan(office.context(Actor.director(), Origin.REAL), m));
        office.execute(c -> c.verification().setBudget(office.context(Actor.director(), Origin.REAL), m, null));
        office.execute(c -> c.missions().approvePlan(office.context(Actor.director(), Origin.REAL), m));
        office.execute(c -> c.tickets().start(office.context(Actor.agent(PLUGIA), Origin.REAL), t));
        return new Setup(m, t, workdir.toString());
    }

    <T> List<T> events(Class<T> type) {
        return journal.readAll().stream()
                .map(e -> e.event())
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static void delete(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
