package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A daemon started with a {@code restore-pending.properties} that something other than the director wrote in its data
 * folder (an agent of the same OS user, ADR-0044 amended): the start restores nothing and says so, in the banner's
 * alerts and in Settings.
 */
@QuarkusTest
@TestProfile(PlantedRestoreMarkerTest.Planted.class)
class PlantedRestoreMarkerTest {

    /** Plants the marker in a data folder of its own before the daemon starts. */
    public static class Planted implements QuarkusTestProfile {
        static final Path DATA = Path.of("target/test-data/planted-" + UUID.randomUUID());

        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                Files.createDirectories(DATA);
                Files.writeString(
                        DATA.resolve("restore-pending.properties"), "backup=startup-20261031T080000000Z.db\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return Map.of("floor42.data-dir", DATA.toAbsolutePath().toString().replace('\\', '/'));
        }
    }

    @Inject
    SessionToken token;

    @Test
    void aPlantedMarkerIsRefusedAndShownInTheBanner() {
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/journal/alerts")
                .then()
                .statusCode(200)
                .body("refusedRestore.backup", equalTo("startup-20261031T080000000Z.db"))
                .body("refusedRestore.keptAs", equalTo("restore-pending.refused.properties"))
                .body("refusedRestore.modifiedAt", notNullValue())
                .body("locked", nullValue());
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/backups")
                .then()
                .statusCode(200)
                .body("pendingRestore", nullValue())
                .body("lastRestore", nullValue())
                .body("refusedRestore.backup", equalTo("startup-20261031T080000000Z.db"));
    }
}
