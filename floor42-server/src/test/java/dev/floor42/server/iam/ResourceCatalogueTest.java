package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The resources agents may reach: validated at entry, granted per agent AND per mission, fail closed when damaged. */
class ResourceCatalogueTest {

    private static final AgentId ADA = new AgentId("ada");
    private static final MissionId M1 = new MissionId("M-1");

    @TempDir
    Path dir;

    ResourceCatalogue catalogue;
    Delegations delegations;

    @BeforeEach
    void start() {
        var json = new ObjectMapper();
        var vault = new SecretVault(dir);
        delegations = new Delegations(
                dir.resolve("iam").resolve("delegations.json"), vault, null, () -> true, Optional::empty, null, json);
        catalogue = new ResourceCatalogue(dir, json, delegations);
    }

    private static Resource res(String name, String base, boolean local) {
        return new Resource(
                name,
                java.net.URI.create(base),
                " aud ",
                List.of("s"),
                List.of(" get ", "post"),
                List.of("/api/"),
                0,
                local);
    }

    private Resource valid() {
        return res("demo-api", "https://api.example.com", false);
    }

    @Test
    void aNullInAListIsRefusedNotAServerError() {
        var r = valid();
        var withNull = new Resource(
                r.name(),
                r.baseUrl(),
                r.audience(),
                java.util.Arrays.asList((String) null),
                r.methods(),
                r.pathPrefixes(),
                0,
                false);
        assertThatThrownBy(() -> catalogue.put(withNull)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStoredResourceIsCleanedAndFoundByName() {
        catalogue.put(valid());
        Resource r = catalogue.find("demo-api").orElseThrow();
        assertThat(r.audience()).isEqualTo("aud");
        assertThat(r.methods()).containsExactly("GET", "POST");
        assertThat(r.scopes()).containsExactly("s");
        assertThat(r.maxResponseBytes()).isEqualTo(ResourceCatalogue.MAX_RESPONSE);
        assertThat(r.local()).isFalse();
        assertThat(catalogue.find("other")).isEmpty();
        assertThat(catalogue.all()).hasSize(1);
    }

    @Test
    void theResponseCapIsKeptWhenSmallAndClampedWhenLarge() {
        catalogue.put(new Resource(
                "a", java.net.URI.create("https://a.example"), "x", null, List.of("GET"), List.of("/"), 500, false));
        assertThat(catalogue.find("a").orElseThrow().maxResponseBytes()).isEqualTo(500);
        assertThat(catalogue.find("a").orElseThrow().scopes()).isEmpty();
        catalogue.put(new Resource(
                "a",
                java.net.URI.create("https://a.example"),
                "x",
                List.of(),
                List.of("GET"),
                List.of("/"),
                Integer.MAX_VALUE,
                false));
        assertThat(catalogue.find("a").orElseThrow().maxResponseBytes()).isEqualTo(ResourceCatalogue.MAX_RESPONSE);
        catalogue.put(new Resource(
                "a",
                java.net.URI.create("https://a.example"),
                "x",
                List.of(),
                List.of("GET"),
                List.of("/"),
                -5,
                false));
        assertThat(catalogue.find("a").orElseThrow().maxResponseBytes()).isEqualTo(ResourceCatalogue.MAX_RESPONSE);
        catalogue.put(new Resource(
                "a",
                java.net.URI.create("https://a.example"),
                "x",
                List.of(),
                List.of("GET"),
                List.of("/"),
                ResourceCatalogue.MAX_RESPONSE,
                false));
        assertThat(catalogue.find("a").orElseThrow().maxResponseBytes()).isEqualTo(ResourceCatalogue.MAX_RESPONSE);
    }

    @Test
    void puttingTheSameNameReplacesIt() {
        catalogue.put(valid());
        catalogue.put(res("demo-api", "https://other.example.com", false));
        assertThat(catalogue.all()).hasSize(1);
        assertThat(catalogue.find("demo-api").orElseThrow().baseUrl().getHost()).isEqualTo("other.example.com");
    }

    @Test
    void badNamesAndAddressesAreRefusedAtEntry() {
        for (String name : new String[] {null, "", "Upper", "a b", "a_b", "x".repeat(41)})
            assertThatThrownBy(() -> catalogue.put(res(name, "https://a.example", false)))
                    .as("name %s", name)
                    .isInstanceOf(IllegalArgumentException.class);
        catalogue.put(res("x".repeat(40), "https://a.example", false));
        assertThatThrownBy(() -> catalogue.put(null)).isInstanceOf(IllegalArgumentException.class);
        for (String base : new String[] {
            "https://user:pw@a.example",
            "http://a.example",
            "ftp://a.example",
            "https://a.example/path",
            "https://a.example/?q=1",
            "/relative"
        })
            assertThatThrownBy(() -> catalogue.put(res("a", base, false)))
                    .as(base)
                    .isInstanceOf(IllegalArgumentException.class);
        catalogue.put(res("a", "https://a.example/", false));
        catalogue.put(res("b", "HTTPS://b.example:8443", false));
    }

    @Test
    void plainHttpIsForALocalResourceOnly() {
        assertThatThrownBy(() -> catalogue.put(res("a", "http://127.0.0.1:9", false)))
                .isInstanceOf(IllegalArgumentException.class);
        catalogue.put(res("a", "http://127.0.0.1:9", true));
        assertThat(catalogue.find("a").orElseThrow().local()).isTrue();
    }

    @Test
    void audienceMethodsAndPrefixesAreRequired() {
        var base = java.net.URI.create("https://a.example");
        for (Resource r : List.of(
                new Resource("a", base, null, List.of(), List.of("GET"), List.of("/"), 1, false),
                new Resource("a", base, "  ", List.of(), List.of("GET"), List.of("/"), 1, false),
                new Resource("a", base, "x", List.of(), null, List.of("/"), 1, false),
                new Resource("a", base, "x", List.of(), List.of(), List.of("/"), 1, false),
                new Resource("a", base, "x", List.of(), List.of("TRACE"), List.of("/"), 1, false),
                new Resource("a", base, "x", List.of(), List.of("GET", "CONNECT"), List.of("/"), 1, false),
                new Resource("a", base, "x", List.of(), List.of("GET"), null, 1, false),
                new Resource("a", base, "x", List.of(), List.of("GET"), List.of(), 1, false),
                new Resource("a", base, "x", List.of(), List.of("GET"), List.of("api/"), 1, false),
                new Resource("a", base, "x", List.of(), List.of("GET"), List.of("/ok", "bad"), 1, false)))
            assertThatThrownBy(() -> catalogue.put(r)).isInstanceOf(IllegalArgumentException.class);
        for (String verb : List.of("GET", "POST", "PUT", "PATCH", "DELETE"))
            catalogue.put(new Resource("a", base, "x", List.of(), List.of(verb), List.of("/"), 1, false));
    }

    @Test
    void anAgentNeedsItsGrantAndTheMissionsDelegationBoth() {
        catalogue.put(valid());
        assertThat(catalogue.allowed(ADA, M1, "demo-api")).isFalse();
        catalogue.grant(ADA, List.of("demo-api"));
        assertThat(catalogue.allowed(ADA, M1, "demo-api"))
                .as("grant without delegation")
                .isFalse();
        delegations.grant(M1, new Tokens("a", "r", null, Instant.now()), List.of("demo-api"));
        assertThat(catalogue.allowed(ADA, M1, "demo-api")).isTrue();
        assertThat(catalogue.allowed(new AgentId("bob"), M1, "demo-api")).isFalse();
        assertThat(catalogue.allowed(ADA, new MissionId("M-2"), "demo-api")).isFalse();
        assertThat(catalogue.allowed(ADA, M1, "other")).isFalse();
        delegations.revokeLocally(M1);
        assertThat(catalogue.allowed(ADA, M1, "demo-api"))
                .as("revoked delegation")
                .isFalse();
    }

    @Test
    void aGrantNamingNoResourceOfTheDelegationIsNotEnough() {
        catalogue.put(valid());
        catalogue.grant(ADA, List.of("demo-api"));
        delegations.grant(M1, new Tokens("a", "r", null, Instant.now()), List.of());
        assertThat(catalogue.allowed(ADA, M1, "demo-api")).isFalse();
        assertThat(catalogue.grantsOf()).containsEntry("ada", List.of("demo-api"));
    }

    @Test
    void removingAResourceRemovesItFromEveryGrant() {
        catalogue.put(valid());
        catalogue.put(res("wiki", "https://w.example", false));
        catalogue.grant(ADA, List.of("demo-api", "wiki"));
        catalogue.grant(new AgentId("bob"), List.of("demo-api"));
        catalogue.remove("demo-api");
        assertThat(catalogue.find("demo-api")).isEmpty();
        assertThat(catalogue.find("wiki")).isPresent();
        assertThat(catalogue.grantsOf()).containsEntry("ada", List.of("wiki")).containsEntry("bob", List.of());
    }

    @Test
    void grantingReplacesTheAgentsListAndLeavesOthers() {
        catalogue.grant(ADA, List.of("a", "b"));
        catalogue.grant(new AgentId("bob"), List.of("c"));
        catalogue.grant(ADA, List.of("z"));
        assertThat(catalogue.grantsOf()).isEqualTo(Map.of("ada", List.of("z"), "bob", List.of("c")));
    }

    @Test
    void aDamagedFileReadsAsEmptySoNothingIsCallable() throws Exception {
        catalogue.put(valid());
        catalogue.grant(ADA, List.of("demo-api"));
        Files.writeString(dir.resolve("iam").resolve("resources.json"), "{broken");
        Files.writeString(dir.resolve("iam").resolve("grants.json"), "[1,");
        assertThat(catalogue.all()).isEmpty();
        assertThat(catalogue.find("demo-api")).isEmpty();
        assertThat(catalogue.grantsOf()).isEmpty();
    }

    @Test
    void nothingIsStoredBeforeTheFirstWrite() {
        assertThat(catalogue.all()).isEmpty();
        assertThat(catalogue.grantsOf()).isEmpty();
        assertThat(Files.exists(dir.resolve("iam").resolve("resources.json"))).isFalse();
    }
}
