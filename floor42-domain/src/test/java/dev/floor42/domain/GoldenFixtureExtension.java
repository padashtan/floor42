package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.golden.CompanySnapshot;
import dev.floor42.domain.golden.GoldenFile;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The golden master of every domain test that uses a {@link Fixture}: after each test, what each of its fixtures recorded, command by command, the state
 * of its company, and the state its journal replays to. A class's file is compared once all its tests ran.
 *
 * <p>Commands a test runs without {@link Fixture#record} still show in the state and in the count of events left out.
 */
final class GoldenFixtureExtension implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(GoldenFixtureExtension.class);

    /**
     * Fixtures that classes without this extension left in the thread's list are not this class's: which class ran
     * just before depends on the file system's order of the class files, so they are dropped here.
     */
    @Override
    public void beforeAll(ExtensionContext context) {
        Fixture.takeMade();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        List<Fixture> made = Fixture.takeMade();
        if (made.isEmpty()) return;
        sections(context.getParent().orElseThrow())
                .put(context.getRequiredTestMethod().getName(), describe(made));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Map<String, String> sections = sections(context);
        if (sections.isEmpty()) return;
        StringBuilder text = new StringBuilder();
        sections.forEach(
                (test, section) -> text.append("=== ").append(test).append('\n').append(section));
        String why =
                GoldenFile.check("fixture/" + context.getRequiredTestClass().getSimpleName() + ".txt", text.toString());
        if (why != null) throw new AssertionError(why);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> sections(ExtensionContext classContext) {
        return classContext.getStore(NAMESPACE).getOrComputeIfAbsent("sections", k -> new TreeMap<>(), Map.class);
    }

    static String describe(List<Fixture> made) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < made.size(); i++) {
            Fixture f = made.get(i);
            out.append("## fixture ").append(i + 1).append(' ').append(f.origin).append('\n');
            int start = 0;
            for (int c = 0; c < f.commandEnds.size(); c++) {
                int end = f.commandEnds.get(c);
                out.append("command ").append(c + 1).append('\n');
                out.append(GoldenFile.events(f.journal.subList(start, end), start));
                start = end;
            }
            out.append("events not recorded by the test: ")
                    .append(f.company.version() - f.journal.size())
                    .append('\n');
            String live = CompanySnapshot.of(f.company);
            out.append("### live\n").append(live);
            out.append("### replay: ").append(replayed(f.journal, live));
        }
        return out.toString();
    }

    private static String replayed(List<EventEnvelope> journal, String live) {
        try {
            String replay = CompanySnapshot.of(Company.replay(journal));
            return replay.equals(live) ? "same as live\n" : "\n" + replay;
        } catch (RuntimeException e) {
            return "threw " + e.getClass().getName() + "\n";
        }
    }
}
