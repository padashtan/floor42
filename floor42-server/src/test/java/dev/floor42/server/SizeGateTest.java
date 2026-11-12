package dev.floor42.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Size rules for the main sources of the domain and the server: a source file at most {@value SourceScan#CLASS_LINES}
 * lines (10 percent tolerance: up to {@value SourceScan#CLASS_LINES_FAIL} passes with a warning in the test output),
 * a method or constructor body at most {@value SourceScan#METHOD_LINES} lines (its braces included, as the
 * JDK's parser reads it, comments counted). What breaches them today is listed below with its ceiling: an entry may
 * shrink, never grow, and goes once it fits; anything not listed fails at once.
 */
class SizeGateTest {

    /**
     * Source files allowed past the class limit, with their ceiling. None since D19 split {@code DomainEvent} into one
     * file per area (2026-11-01).
     */
    static final Map<String, Integer> CLASSES_ALLOWED = Map.of();

    /**
     * Methods allowed past the method limit, with their ceiling (lines measured on 2026-11-01), each to be split and
     * its line removed. The counter-check of v1.7.0 listed 58 over 30 lines, and the merges with integ/1.8.0 brought
     * more; the longest were split under characterisation (ResponseBodiesCharacterisationTest, the golden masters):
     * ClaudeCodeRuntime.run, CatalogueResource.catalogue, Staffing.facts, AgentProfileView.of and facts,
     * DirectorTerminals.open, WindowsPty.start, AwaySummary.since, Progress.build, StreamJsonParser.parse,
     * MissionGovernor.observed and askDirector, DemoScenario.play, ReplayRuntime.run, ResumeResource.mission,
     * Setting.normalize, SandboxResource.view, ProjectToolchain.read, LocalAccessGuard.guard, Checks.execute,
     * ClaudeAssets.frontmatter and plugins, Retrospectives.run and turn, ProjectsResource.list, SessionContinuity.assess,
     * SandboxProbe.read, ReplayExports.assemble and html, among others. What is left is 31 to 45 lines:
     * the journal's row codec (SqliteJournal, EventCodec), declaration
     * tables (Kernel32.load) and request handlers. This gate is their record (not docs/DEBT.md, which holds five open
     * items already): a ceiling only goes down, and a split removes its line.
     */
    static final Map<String, Integer> METHODS_ALLOWED = Map.ofEntries(
            Map.entry("ArchiveResource.archive", 45),
            Map.entry("Kernel32.load", 38),
            Map.entry("ResumeResource.sessions", 38),
            Map.entry("MissionAssets.pluginDirs", 37),
            Map.entry("AgentProfileView.reviews", 36),
            Map.entry("EventCodec.decode", 36),
            Map.entry("Retrospectives.step", 35),
            Map.entry("AuthResource.issue", 34),
            Map.entry("Checks.run", 34),
            Map.entry("ResumeResource.resumeSession", 34),
            Map.entry("SandboxResource.view", 34),
            Map.entry("WindowsPty.spawn", 34),
            Map.entry("AgentProfileView.conduct", 33),
            Map.entry("ClaudeAssets.plugin", 33),
            Map.entry("SqliteJournal.query", 33),
            Map.entry("MissionAssets.view", 32),
            // A record's constructor call, one field a line (closedAt made it 31, P1c 2026-11-05).
            Map.entry("MissionViews.MissionView.of", 31),
            Map.entry("ResumeResource.mission", 32),
            Map.entry("Setting.normalize", 32),
            Map.entry("MissionAssets.layOut", 31),
            Map.entry("ReplayExports.html", 31),
            Map.entry("ReplayExports.preview", 31),
            Map.entry("ReplayRuntime.Replay.on", 31));

    final SourceScan scan = SourceScan.mainSources();

    @Test
    void sourceFilesStayWithinTheLimit() {
        List<String> breaches = new ArrayList<>();
        for (SourceScan.Source s : scan.sources()) {
            Integer ceiling = CLASSES_ALLOWED.get(s.file());
            if (ceiling == null && s.lines() > SourceScan.CLASS_LINES && s.lines() <= SourceScan.CLASS_LINES_FAIL)
                System.out.println("SizeGate warning: " + s.file() + " " + s.lines() + " lines, target "
                        + SourceScan.CLASS_LINES + " (fails above " + SourceScan.CLASS_LINES_FAIL + ")");
            if (s.lines() > (ceiling == null ? SourceScan.CLASS_LINES_FAIL : ceiling))
                breaches.add(
                        s.file() + " " + s.lines() + " lines" + (ceiling == null ? "" : " (ceiling " + ceiling + ")"));
        }
        assertThat(breaches).as("source files over the limit: split them").isEmpty();
    }

    @Test
    void methodsStayWithinTheLimit() {
        List<String> breaches = new ArrayList<>();
        for (Map.Entry<String, Integer> m : longest().entrySet()) {
            Integer ceiling = METHODS_ALLOWED.get(m.getKey());
            if (m.getValue() > (ceiling == null ? SourceScan.METHOD_LINES : ceiling))
                breaches.add(m.getKey() + " " + m.getValue() + " lines"
                        + (ceiling == null ? "" : " (ceiling " + ceiling + ")"));
        }
        assertThat(breaches)
                .as("methods over the limit: split them (SourceScan.main lists them)")
                .isEmpty();
    }

    @Test
    void theAllowListsHoldOnlyWhatStillBreaches() {
        Map<String, Integer> longest = longest();
        List<String> stale = new ArrayList<>();
        METHODS_ALLOWED.forEach((k, v) -> {
            if (longest.getOrDefault(k, 0) <= SourceScan.METHOD_LINES) stale.add(k);
        });
        CLASSES_ALLOWED.forEach((k, v) -> {
            boolean still = scan.sources().stream()
                    .anyMatch(s -> s.file().equals(k) && s.lines() > SourceScan.CLASS_LINES_FAIL);
            if (!still) stale.add(k);
        });
        assertThat(stale)
                .as("entries that fit now: remove them from the allow-list")
                .isEmpty();
    }

    /** The longest body under each owner and name (overloads share a key). */
    private Map<String, Integer> longest() {
        Map<String, Integer> out = new TreeMap<>();
        for (SourceScan.Method m : scan.methods()) out.merge(m.key(), m.lines(), Math::max);
        return out;
    }
}
