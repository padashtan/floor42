package dev.floor42.server.app.mission;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.app.mission.Preflight.Facts;
import dev.floor42.server.app.mission.Preflight.Level;
import dev.floor42.server.app.mission.Preflight.Line;
import dev.floor42.server.app.mission.Preflight.Tool;
import dev.floor42.server.app.mission.Preflight.ToolState;
import dev.floor42.server.app.mission.Preflight.Verdict;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One verdict from facts handed in as values: blockers first, nothing measured by reading (ADR-0070). */
class PreflightTest {

    private static final Tool GIT_UNMEASURED =
            new Tool("git", null, List.of("git repository"), ToolState.UNKNOWN, null);

    /** A calm start: Claude Code there, a clean repository, a checks command, the sandbox not in use. */
    private static Facts calm() {
        return new Facts(true, null, null, false, true, true, true, 0, false, List.of(), List.of(), true);
    }

    private static Facts claude(boolean available, String reason) {
        return new Facts(available, reason, "notFound", false, true, true, true, 0, false, List.of(), List.of(), true);
    }

    private static Facts repo(boolean hasCommit, int uncommitted, boolean hasChecks) {
        return new Facts(
                true, null, null, false, true, true, hasCommit, uncommitted, false, List.of(), List.of(), hasChecks);
    }

    /** The sandbox in use: working or not, with or without network, the downloads and the tools given. */
    private static Facts sandboxed(boolean working, boolean network, List<String> downloads, List<Tool> tools) {
        return new Facts(true, null, null, true, working, true, true, 0, network, downloads, tools, true);
    }

    private static List<String> codes(Preflight.Result r) {
        return r.lines().stream().map(Line::code).toList();
    }

    @Test
    void runtimeUnavailableBlocks() {
        Preflight.Result r = Preflight.of(claude(false, "not found"));
        assertThat(r.verdict()).isEqualTo(Verdict.BLOCKED);
        assertThat(r.lines().getFirst().code()).isEqualTo("claude.notFound");
        assertThat(r.lines().getFirst().params()).containsEntry("reason", "not found");
    }

    @Test
    void sandboxMandatoryAndBrokenBlocks() {
        Preflight.Result r = Preflight.of(sandboxed(false, true, List.of(), List.of()));
        assertThat(r.verdict()).isEqualTo(Verdict.BLOCKED);
        assertThat(codes(r)).contains("sandbox.required");
    }

    @Test
    void unsafeModeTurnsSandboxBlockIntoNothing() {
        // Unsafe mode makes the sandbox not required: the server then passes sandboxRequired=false.
        Preflight.Result r = Preflight.of(
                new Facts(true, null, null, false, false, true, true, 0, false, List.of(), List.of(), true));
        assertThat(codes(r)).doesNotContain("sandbox.required");
        assertThat(r.verdict()).isEqualTo(Verdict.READY);
    }

    @Test
    void dirtyTreeWarnsWithCount() {
        Preflight.Result r = Preflight.of(repo(true, 3, true));
        assertThat(r.verdict()).isEqualTo(Verdict.WARNINGS);
        assertThat(r.lines().getFirst()).isEqualTo(new Line("repo.dirty", Level.WARN, Map.of("count", "3")));
    }

    @Test
    void folderWithoutCommitWarns() {
        assertThat(codes(Preflight.of(repo(false, 0, true)))).contains("repo.noCommit");
    }

    @Test
    void missingToolWarnsAndNamesTheFile() {
        Tool mvn = new Tool("mvn", null, List.of("pom.xml"), ToolState.MISSING, null);
        Line line = Preflight.of(sandboxed(true, true, List.of(), List.of(mvn)))
                .lines()
                .getFirst();
        assertThat(line.code()).isEqualTo("tool.missing");
        assertThat(line.level()).isEqualTo(Level.WARN);
        assertThat(line.params()).containsEntry("tool", "mvn").containsEntry("because", "pom.xml");
    }

    @Test
    void olderToolWarnsWithBothVersions() {
        Tool java = new Tool("java", "21", List.of("pom.xml"), ToolState.OLDER, "17.0.2");
        Line line = Preflight.of(sandboxed(true, true, List.of(), List.of(java)))
                .lines()
                .getFirst();
        assertThat(line.code()).isEqualTo("tool.older");
        assertThat(line.params()).containsEntry("version", "17.0.2").containsEntry("wanted", "21");
    }

    @Test
    void unmeasuredToolIsNeverCalledMissing() {
        Preflight.Result r = Preflight.of(sandboxed(true, true, List.of(), List.of(GIT_UNMEASURED)));
        assertThat(codes(r)).contains("tool.unmeasured").doesNotContain("tool.missing");
    }

    @Test
    void networkOffWithMvnwWarnsOfDownload() {
        Preflight.Result r = Preflight.of(sandboxed(true, false, List.of("mvnw"), List.of()));
        assertThat(r.lines().getFirst()).isEqualTo(new Line("offline.download", Level.WARN, Map.of("file", "mvnw")));
    }

    @Test
    void networkOffWithoutSignalSaysUnknownNotFine() {
        Preflight.Result r = Preflight.of(sandboxed(true, false, List.of(), List.of()));
        Line unknown = r.lines().stream()
                .filter(l -> l.code().equals("offline.unknown"))
                .findFirst()
                .orElseThrow();
        assertThat(unknown.level()).isEqualTo(Level.INFO);
    }

    @Test
    void networkOnSaysNothingAboutOffline() {
        Preflight.Result r = Preflight.of(sandboxed(true, true, List.of("mvnw"), List.of()));
        assertThat(codes(r)).noneMatch(c -> c.startsWith("offline."));
    }

    @Test
    void noChecksAndNoSuggestionWarns() {
        assertThat(codes(Preflight.of(repo(true, 0, false)))).contains("checks.none");
    }

    @Test
    void linesAreOrderedBlockWarnInfoOk() {
        Facts f = new Facts(
                false, "x", "notFound", true, false, true, true, 2, false, List.of(), List.of(GIT_UNMEASURED), true);
        List<Level> levels = Preflight.of(f).lines().stream().map(Line::level).toList();
        assertThat(levels).isSorted();
        assertThat(levels.getFirst()).isEqualTo(Level.BLOCK);
    }

    @Test
    void verdictIsReadyOnlyWithoutBlockAndWarn() {
        Preflight.Result ready = Preflight.of(calm());
        assertThat(ready.verdict()).isEqualTo(Verdict.READY);
        assertThat(codes(ready)).containsExactly("ready");
        // An INFO line alone does not hold the verdict back.
        assertThat(Preflight.of(sandboxed(true, false, List.of(), List.of())).verdict())
                .isEqualTo(Verdict.READY);
        assertThat(Preflight.of(repo(true, 1, true)).verdict()).isEqualTo(Verdict.WARNINGS);
    }
}
