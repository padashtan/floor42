package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Swagger documents every route: a tag, a summary, a description, and an example for every request body. */
@QuarkusTest
class ApiDocsTest {

    /** The documentation needs the director, like the API it describes. */
    @jakarta.inject.Inject
    dev.floor42.server.security.SessionToken token;

    @Test
    void everyRouteIsDocumentedAndEveryEntryNamesARoute() throws Exception {
        JsonNode paths = new ObjectMapper()
                .readTree(given().header("Authorization", "Bearer " + token.value())
                        .get("/q/openapi?format=json")
                        .asString())
                .path("paths");
        JsonNode docs = ApiDocs.load().path("operations");
        List<String> served = new ArrayList<>();
        List<String> undocumented = new ArrayList<>();
        paths.properties()
                .forEach(p -> p.getValue().properties().forEach(o -> {
                    String key = o.getKey().toUpperCase(java.util.Locale.ROOT) + " " + p.getKey();
                    served.add(key);
                    JsonNode op = o.getValue();
                    boolean documented = op.path("tags").size() == 1
                            && !op.path("description").asText().isBlank()
                            && (!op.has("requestBody")
                                    || op.path("requestBody")
                                            .path("content")
                                            .path("application/json")
                                            .has("example"));
                    if (!documented) undocumented.add(key);
                }));
        List<String> stale = new ArrayList<>();
        docs.fieldNames().forEachRemaining(k -> {
            if (!served.contains(k)) stale.add(k);
        });

        assertThat(undocumented)
                .as("routes without docs or example in api-docs.json")
                .isEmpty();
        assertThat(stale).as("entries of api-docs.json naming no route").isEmpty();
    }

    @Test
    void everyQueryParameterIsDescribed() throws Exception {
        JsonNode paths = new ObjectMapper()
                .readTree(given().header("Authorization", "Bearer " + token.value())
                        .get("/q/openapi?format=json")
                        .asString())
                .path("paths");
        List<String> bare = new ArrayList<>();
        paths.properties()
                .forEach(p -> p.getValue()
                        .properties()
                        .forEach(o -> o.getValue().path("parameters").forEach(param -> {
                            if (param.path("in").asText().equals("query")
                                    && param.path("description").asText().isBlank()) {
                                bare.add(o.getKey().toUpperCase(java.util.Locale.ROOT) + " " + p.getKey() + " ?"
                                        + param.path("name").asText());
                            }
                        })));
        assertThat(bare).as("query parameters without a description").isEmpty();
    }
}
