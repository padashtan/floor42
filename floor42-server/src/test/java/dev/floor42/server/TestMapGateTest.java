package dev.floor42.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the test map honest (tools/test-map.conf, ADR-0068): every source file has a rule of its own, every selection
 * the map can give finds tests that exist, and the share of the server the quick loop targets does not slide back to
 * "everything". Part of the core set, so it runs in every quick loop.
 */
class TestMapGateTest {

    static final Path ROOT = Path.of("..");
    static final String SERVER_MAIN = "floor42-server/src/main/java";
    /** The server's main classes the map targets instead of running every Java test: 64 percent on 2026-11-13. */
    static final double TARGETED_FLOOR = 0.60;

    final TestMap map = TestMap.load(ROOT.resolve(TestMap.FILE));
    final List<String> tests = Stream.of("floor42-domain/src/test/java", "floor42-server/src/test/java")
            .flatMap(root -> files(root).stream().map(f -> f.substring(root.length() + 1)))
            .filter(f -> f.endsWith("Test.java"))
            .map(f -> f.substring(0, f.length() - ".java".length()))
            .toList();
    final List<String> sources = Stream.of(
                    "floor42-domain/src",
                    "floor42-server/src/main/java",
                    "floor42-server/src/test/java",
                    "floor42-web/src",
                    "floor42-web/e2e")
            .flatMap(root -> files(root).stream())
            .toList();

    static List<String> files(String root) {
        try (Stream<Path> walk = Files.walk(ROOT.resolve(root))) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> ROOT.relativize(p).toString().replace('\\', '/'))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean exists(String pattern) {
        return tests.stream().anyMatch(t -> TestMap.selects(pattern, t));
    }

    /** The domain tags of the browser tests, as floor42-web/e2e/fixtures.ts lists them. */
    static Set<String> domains() throws IOException {
        Matcher m = Pattern.compile("DOMAINS = \\[([^]]*)]")
                .matcher(Files.readString(ROOT.resolve("floor42-web/e2e/fixtures.ts")));
        assertThat(m.find()).as("DOMAINS in floor42-web/e2e/fixtures.ts").isTrue();
        Set<String> tags = new LinkedHashSet<>();
        for (String d : m.group(1).replace("'", "").split(",")) tags.add("@" + d.strip());
        return tags;
    }

    @Test
    void everySourceFileHasARuleOfItsOwn() {
        assertThat(sources.stream().filter(f -> map.ruleFor(f).isEmpty()))
                .as("source files no rule of %s takes (they would run everything): add a rule", TestMap.FILE)
                .isEmpty();
    }

    @Test
    void everySelectionFindsTestsThatExist() throws IOException {
        Set<String> domains = domains();
        Set<String> breaches = new LinkedHashSet<>();
        Set<Set<String>> seen = new LinkedHashSet<>();
        for (String file : sources) {
            TestMap.Selection s = map.select(List.of(file));
            s.problems.forEach(p -> breaches.add(file + ": " + p));
            if (!s.javaAll
                    && !s.java.isEmpty()
                    && seen.add(s.java)
                    && s.java.stream().noneMatch(this::exists)) breaches.add(file + ": no test class for " + s.java);
            for (String term : s.e2e)
                if (!domains.contains(term)
                        && !Files.exists(ROOT.resolve("floor42-web/e2e").resolve(term)))
                    breaches.add(file + ": " + term + " is neither a domain tag nor a journey file");
        }
        assertThat(breaches)
                .as("selections of %s that point at nothing", TestMap.FILE)
                .isEmpty();
    }

    @Test
    void everyNamedTargetExists() throws IOException {
        Set<String> domains = domains();
        List<String> breaches = new ArrayList<>();
        Stream.concat(
                        map.core().stream(),
                        map.rules().stream()
                                .flatMap(r -> r.targets().stream())
                                .filter(t -> t.startsWith("java:") && !t.equals("java:all") && !t.contains("{"))
                                .map(t -> t.substring("java:".length())))
                .filter(p -> !exists(p))
                .forEach(p -> breaches.add("no test class for " + p));
        map.journeys().forEach((context, terms) -> {
            if (Stream.of("app", "api")
                    .noneMatch(layer -> Files.isDirectory(ROOT.resolve(SERVER_MAIN)
                            .resolve("dev/floor42/server")
                            .resolve(layer)
                            .resolve(context)))) breaches.add("e2e-of " + context + ": no such context in app or api");
            terms.stream().filter(t -> !domains.contains(t)).forEach(t -> breaches.add("e2e-of " + context + ": " + t));
        });
        assertThat(breaches)
                .as("targets of %s that point at nothing", TestMap.FILE)
                .isEmpty();
    }

    @Test
    void theTargetedShareOfTheServerDoesNotSlideBack() {
        List<String> main =
                sources.stream().filter(f -> f.startsWith(SERVER_MAIN)).toList();
        long targeted =
                main.stream().filter(f -> !map.select(List.of(f)).javaAll).count();
        assertThat((double) targeted / main.size())
                .as(
                        "share of the server's main classes that do not call for every Java test (%d of %d)",
                        targeted, main.size())
                .isGreaterThanOrEqualTo(TARGETED_FLOOR);
    }

    @Test
    void aFeatureContextCallsForItsOwnTestsAndTheCoreSet() {
        TestMap.Selection s = map.select(List.of(SERVER_MAIN + "/dev/floor42/server/app/delivery/Proofs.java"));

        assertThat(s.javaAll).isFalse();
        assertThat(s.java).containsExactly("dev/floor42/server/api/delivery/**", "dev/floor42/server/app/delivery/**");
        assertThat(s.e2e).containsExactly("@delivery");
        assertThat(s.runs("dev/floor42/server/api/delivery/ProofApiTest")).isTrue();
        assertThat(s.runs("dev/floor42/server/DebtGateTest")).as("core set").isTrue();
        assertThat(s.runs("dev/floor42/server/api/mission/MissionsApiTest")).isFalse();
    }

    @Test
    void aTestClassRunsAloneNotesRunNothingAndAnUnknownPathRunsEverything() {
        TestMap.Selection test =
                map.select(List.of("floor42-server\\src\\test\\java\\dev\\floor42\\server\\runtime\\FooTest.java"));
        assertThat(test.java).containsExactly("FooTest");
        assertThat(test.runs("dev/floor42/server/runtime/FooTest")).isTrue();

        TestMap.Selection notes = map.select(List.of("docs/PROGRESS.md", "README.md"));
        assertThat(notes.javaAll || notes.webAll || notes.e2eAll).isFalse();
        assertThat(notes.javaPatterns()).isEmpty();

        TestMap.Selection unknown = map.select(List.of("Makefile"));
        assertThat(unknown.unmapped).containsExactly("Makefile");
        assertThat(unknown.javaAll && unknown.webAll && unknown.e2eAll).isTrue();
    }

    @Test
    void theSurveyNamesTheFailingClassesTheQuickLoopWouldNotHaveRun() {
        List<String> diff = List.of(SERVER_MAIN + "/dev/floor42/server/app/delivery/Proofs.java");

        assertThat(map.survey(
                        diff,
                        List.of(
                                "dev.floor42.server.api.mission.MissionsApiTest",
                                "dev.floor42.server.api.delivery.ProofApiTest")))
                .isEqualTo(
                        "survey: 1 paths, java targeted, 2 failing, 1 escaped: dev.floor42.server.api.mission.MissionsApiTest");
        assertThat(map.survey(List.of("pom.xml"), List.of("dev.floor42.server.api.mission.MissionsApiTest")))
                .isEqualTo("survey: 1 paths, java all, 1 failing, 0 escaped");
    }
}
