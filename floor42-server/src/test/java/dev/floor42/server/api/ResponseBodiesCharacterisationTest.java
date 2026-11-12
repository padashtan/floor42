package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.domain.GoldenJournals;
import dev.floor42.domain.golden.GoldenFile;
import dev.floor42.server.journal.TestJournals;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The bodies of the main GET routes, on the domain's golden scenario served by a real daemon: the daemon starts on a
 * journal holding the scripted day of {@code GoldenScenarioTest}, and each body is compared with what it answered when
 * the master was recorded ({@code src/test/resources/golden/api-bodies.txt}; {@code -Dfloor42.golden.record=true}
 * records it again). A view refactored keeps its values, not only its schema ({@code OpenApiContractTest}). Fields that
 * depend on the clock or the machine are masked, each named in {@link #MASKED}.
 */
@QuarkusTest
@TestProfile(ResponseBodiesCharacterisationTest.GoldenDay.class)
class ResponseBodiesCharacterisationTest {

    /** A daemon of its own, on a journal that holds the golden scenario before it starts. */
    public static class GoldenDay implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floor42.live-missions.enabled", "false");
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(GoldenJournal.class));
        }
    }

    /** Writes the golden scenario's journal into a data folder of its own, before the daemon replays it. */
    public static class GoldenJournal implements QuarkusTestResourceLifecycleManager {
        @Override
        public Map<String, String> start() {
            try {
                Path dir = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "golden-day-");
                var journal = TestJournals.open(dir.resolve("floor42.db"));
                GoldenJournals.scenario().forEach(e -> journal.append(List.of(e)));
                return Map.of("floor42.data-dir", dir.toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void stop() {
            // The folder stays under target/ for a look after a failure; the next clean removes it. The property it
            // set must not outlive this daemon: the next one in this JVM (any test class) would replay the golden day.
            System.clearProperty("floor42.data-dir");
        }
    }

    /** The pom's version, as the app reports it: a bump of {@code versions:set} never touches the golden. */
    static final String APP_VERSION = org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getValue("quarkus.application.version", String.class);

    /** Field names whose value depends on the machine (its folders), never on the journal: shown as "<masked>". */
    static final Set<String> MASKED = Set.of("dataDir", "path", "worktree", "folder");

    /**
     * A time the daemon read from its clock while answering (when a listing was read, when costs were summed), not from
     * the journal: the golden day happened on 2026-10-20, so any instant within a day of now is the clock's.
     */
    static boolean fromTheClock(JsonNode value) {
        if (!value.isTextual()) return false;
        try {
            Instant at = Instant.parse(value.asText());
            return Duration.between(at, Instant.now()).abs().compareTo(Duration.ofDays(1)) < 0;
        } catch (DateTimeParseException e) {
            return false; // Handled: not a time at all, kept as it is
        }
    }

    static final List<String> ROUTES = List.of(
            "/api/missions",
            "/api/missions/M-1",
            "/api/missions/M-2",
            "/api/agents",
            "/api/agents/ana/profile",
            "/api/agents/chris/profile",
            "/api/agents/darius/profile",
            "/api/agents/galen/profile",
            "/api/agents/rita/profile",
            "/api/questions",
            "/api/progress",
            "/api/missions/M-1/report",
            "/api/missions/M-1/deliverables",
            "/api/costs",
            "/api/missions/M-1/costs",
            "/api/usage",
            "/api/resume",
            "/api/missions/M-1/replay-export/preview",
            "/api/missions/M-1/archive",
            "/api/lessons",
            "/api/notes");

    @Inject
    SessionToken token;

    final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void theMainRoutesAnswerTheGoldenDayAsRecorded() throws Exception {
        StringBuilder out = new StringBuilder();
        for (String route : ROUTES) {
            Response r =
                    given().header("Authorization", "Bearer " + token.value()).get(route);
            out.append("== GET ")
                    .append(route)
                    .append(" -> ")
                    .append(r.statusCode())
                    .append('\n');
            out.append(canonical(r.asString())).append('\n');
        }
        assertThat(GoldenFile.check("api-bodies.txt", out.toString().replace("\r\n", "\n")))
                .isNull();
    }

    /** Keys sorted, masked fields replaced: the same journal always prints the same text. */
    String canonical(String body) throws IOException {
        if (body.isBlank()) return "(empty)";
        JsonNode node = json.readTree(body);
        return json.writeValueAsString(sorted(node));
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                var f = it.next();
                boolean masked = (MASKED.contains(f.getKey()) && !f.getValue().isNull()) || fromTheClock(f.getValue());
                boolean release =
                        f.getKey().equals("version") && f.getValue().asText().equals(APP_VERSION);
                fields.put(
                        f.getKey(),
                        release
                                ? json.getNodeFactory().textNode("<app-version>")
                                : masked ? json.getNodeFactory().textNode("<masked>") : sorted(f.getValue()));
            }
            ObjectNode o = json.createObjectNode();
            fields.forEach(o::set);
            return o;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(n -> items.add(sorted(n)));
            return json.createArrayNode().addAll(items);
        }
        return node;
    }
}
