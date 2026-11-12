package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The journals behind the domain's golden masters, for server tests: the scripted day of {@code GoldenScenarioTest} and
 * the fixtures of every test class that has a golden master under {@code floor42-domain/src/test/resources/golden}.
 * Lives in the domain's package to reach its package-private test helpers.
 */
public final class GoldenJournals {

    private static final Path FIXTURES =
            Path.of("..", "floor42-domain", "src", "test", "resources", "golden", "fixture");

    private GoldenJournals() {}

    /** Every journal, named: the scenario first, then each fixture as {@code Class.test#n}, in a stable order. */
    public static Map<String, List<EventEnvelope>> all() {
        Map<String, List<EventEnvelope>> all = new LinkedHashMap<>();
        all.put("scenario", scenario());
        for (String name : fixtureClasses()) all.putAll(fixtures(name));
        return all;
    }

    /** The scripted day: it emits every type of event. */
    public static List<EventEnvelope> scenario() {
        GoldenScenarioTest test = new GoldenScenarioTest();
        test.play();
        return List.copyOf(test.s.journal);
    }

    private static List<String> fixtureClasses() {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(f -> f.endsWith(".txt"))
                    .map(f -> f.substring(0, f.length() - 4))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs each test method of the class, in name order as its golden master does, and keeps what its fixtures recorded. */
    private static Map<String, List<EventEnvelope>> fixtures(String simpleName) {
        Map<String, List<EventEnvelope>> out = new LinkedHashMap<>();
        try {
            Class<?> type = Class.forName("dev.floor42.domain." + simpleName);
            List<Method> tests = new ArrayList<>();
            for (Method m : type.getDeclaredMethods()) if (m.isAnnotationPresent(Test.class)) tests.add(m);
            tests.sort(Comparator.comparing(Method::getName));
            for (Method m : tests) {
                Fixture.takeMade();
                var constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object instance = constructor.newInstance();
                m.setAccessible(true);
                try {
                    m.invoke(instance);
                } catch (InvocationTargetException failed) {
                    // The domain's own build reports a failing test; what its fixtures recorded is still a journal.
                }
                List<Fixture> made = Fixture.takeMade();
                for (int i = 0; i < made.size(); i++) {
                    out.put(simpleName + "." + m.getName() + "#" + (i + 1), List.copyOf(made.get(i).journal));
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot run the golden fixtures of " + simpleName, e);
        }
        return out;
    }
}
