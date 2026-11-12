package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.app.ClaudeAssets.Asset;
import dev.floor42.server.app.ClaudeAssets.Kind;
import dev.floor42.server.app.ClaudeAssets.Level;
import dev.floor42.server.app.SessionReach.Init;
import dev.floor42.server.app.SessionReach.State;
import dev.floor42.server.app.SessionReach.Verdict;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Whether an item reaches Floor42's sessions: from a real session's init line, never guessed (W3). */
class SessionReachTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    static final Instant SESSION = Instant.parse("2026-10-30T10:00:00Z");
    static final Instant BEFORE = SESSION.minusSeconds(3600);
    static final Instant AFTER = SESSION.plusSeconds(3600);

    /** As recorded from Claude Code 2.1.316 under --restricted: built-in skills and agents only. */
    static final String INIT =
            "{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\"C:\\\\data\\\\workspaces\\\\M-1-bob\","
                    + "\"claude_code_version\":\"2.1.316\",\"tools\":[\"Task\",\"Read\",\"Skill\"],"
                    + "\"skills\":[\"simplify\",\"review\",\"tidy:format\"],\"agents\":[\"Explore\",\"Plan\"],"
                    + "\"plugins\":[{\"name\":\"cc-plugin-telemetry\",\"path\":\"builtin\"},{\"name\":\"tidy\",\"path\":\"x\"}]}";

    static Init init() {
        return SessionReach.parse(JSON, INIT, SESSION);
    }

    static Asset asset(Kind kind, Level level, String plugin, String name, Instant modified) {
        return new Asset(kind, level, plugin, name, null, List.of(), null, null, "p", modified, null);
    }

    @Test
    void readsTheInitLineOnly() {
        Init i = init();
        assertThat(i.version()).isEqualTo("2.1.316");
        assertThat(i.tools()).containsExactly("Task", "Read", "Skill");
        assertThat(i.plugins()).containsExactly("cc-plugin-telemetry", "tidy");
        assertThat(SessionReach.parse(JSON, "{\"type\":\"assistant\"}", SESSION))
                .isNull();
        assertThat(SessionReach.parse(JSON, "not json", SESSION)).isNull();
        Init bare = SessionReach.parse(JSON, "{\"type\":\"system\",\"subtype\":\"init\"}", SESSION);
        assertThat(bare.skills()).isNull();
    }

    @Test
    void listedAndOfferedIsAvailable() {
        Verdict v =
                SessionReach.verdict(asset(Kind.SKILL, Level.USER, null, "review", BEFORE), init(), true, true, null);
        assertThat(v).isEqualTo(new Verdict(State.AVAILABLE, "listed"));
    }

    @Test
    void listedButTheToolIsForbiddenIsNotAvailable() {
        Verdict v =
                SessionReach.verdict(asset(Kind.SKILL, Level.USER, null, "review", BEFORE), init(), true, false, null);
        assertThat(v).isEqualTo(new Verdict(State.NOT_AVAILABLE, "toolDenied"));
        Verdict unknown =
                SessionReach.verdict(asset(Kind.SKILL, Level.USER, null, "review", BEFORE), init(), true, null, null);
        assertThat(unknown.state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void presentBeforeTheSessionAndNotListedWasNotLoaded() {
        Verdict v = SessionReach.verdict(
                asset(Kind.SKILL, Level.USER, null, "db-migration", BEFORE), init(), false, true, null);
        assertThat(v).isEqualTo(new Verdict(State.NOT_AVAILABLE, "notLoaded"));
        Verdict agent =
                SessionReach.verdict(asset(Kind.AGENT, Level.USER, null, "divy", BEFORE), init(), false, true, null);
        assertThat(agent.reason()).isEqualTo("notLoaded");
    }

    @Test
    void changedAfterTheSessionIsUnknown() {
        Verdict v = SessionReach.verdict(
                asset(Kind.SKILL, Level.USER, null, "db-migration", AFTER), init(), false, true, null);
        assertThat(v).isEqualTo(new Verdict(State.UNKNOWN, "changedSince"));
    }

    @Test
    void noSessionIsUnknownNeverAvailable() {
        Verdict v =
                SessionReach.verdict(asset(Kind.SKILL, Level.USER, null, "review", BEFORE), null, false, true, null);
        assertThat(v).isEqualTo(new Verdict(State.UNKNOWN, "noSession"));
        Init bare = SessionReach.parse(JSON, "{\"type\":\"system\",\"subtype\":\"init\"}", SESSION);
        assertThat(SessionReach.verdict(asset(Kind.SKILL, Level.USER, null, "review", BEFORE), bare, false, true, null))
                .isEqualTo(new Verdict(State.UNKNOWN, "notReported"));
    }

    @Test
    void projectItemsNeedASessionOfTheMission() {
        Asset project = asset(Kind.SKILL, Level.PROJECT, null, "review", BEFORE);
        assertThat(SessionReach.verdict(project, init(), false, true, null))
                .isEqualTo(new Verdict(State.UNKNOWN, "noMissionSession"));
        assertThat(SessionReach.verdict(project, init(), false, true, true).state())
                .isEqualTo(State.UNKNOWN);
        assertThat(SessionReach.verdict(project, init(), false, true, false))
                .isEqualTo(new Verdict(State.NOT_AVAILABLE, "notInWorktree"));
        assertThat(SessionReach.verdict(project, init(), true, true, true))
                .isEqualTo(new Verdict(State.AVAILABLE, "listed"));
    }

    @Test
    void aPluginsItemsAreMatchedQualified() {
        Init i = init();
        assertThat(SessionReach.verdict(asset(Kind.PLUGIN, Level.PLUGIN, "tidy", "tidy", BEFORE), i, false, true, null))
                .isEqualTo(new Verdict(State.AVAILABLE, "listed"));
        assertThat(SessionReach.verdict(asset(Kind.SKILL, Level.PLUGIN, "tidy", "format", BEFORE), i, false, true, null)
                        .state())
                .isEqualTo(State.AVAILABLE);
        // A plugin's skill named like a built-in one is not taken for it.
        assertThat(SessionReach.verdict(
                                asset(Kind.SKILL, Level.PLUGIN, "other", "review", BEFORE), i, false, true, null)
                        .reason())
                .isEqualTo("notLoaded");
    }

    @Test
    void isolationIsMeasuredNotAssumed() {
        Verdict loaded = new Verdict(State.AVAILABLE, "listed");
        Verdict refused = new Verdict(State.NOT_AVAILABLE, "notLoaded");
        Verdict unknown = new Verdict(State.UNKNOWN, "noSession");
        assertThat(SessionReach.isolation(List.of(refused, refused))).isEqualTo("isolated");
        assertThat(SessionReach.isolation(List.of(refused, loaded))).isEqualTo("notIsolated");
        assertThat(SessionReach.isolation(List.of(unknown))).isEqualTo("unknown");
        assertThat(SessionReach.isolation(List.of())).isEqualTo("unknown");
    }

    @Test
    void namesTheSessionListsWithoutAFileAreShownApart() {
        List<Asset> onDisk = List.of(
                asset(Kind.SKILL, Level.USER, null, "review", BEFORE),
                asset(Kind.SKILL, Level.PLUGIN, "tidy", "format", BEFORE));
        assertThat(SessionReach.unmatched(Kind.SKILL, init(), onDisk)).containsExactly("simplify");
        assertThat(SessionReach.unmatched(Kind.AGENT, init(), onDisk)).containsExactly("Explore", "Plan");
        assertThat(SessionReach.unmatched(Kind.SKILL, null, onDisk)).isEmpty();
    }

    @Test
    void readsTheNewestSessionsFirst(@TempDir Path tmp) throws Exception {
        Path sessions = tmp.resolve("sessions");
        Path old = Files.createDirectories(sessions.resolve("a"));
        Path recent = Files.createDirectories(sessions.resolve("b"));
        Files.createDirectories(sessions.resolve("c")); // no transcript
        Path noInit = Files.createDirectories(sessions.resolve("d"));
        Files.writeString(old.resolve("transcript.jsonl"), INIT.replace("2.1.316", "2.1.300") + "\n{}\n");
        Files.writeString(recent.resolve("transcript.jsonl"), INIT + "\n");
        Files.writeString(noInit.resolve("transcript.jsonl"), "{\"type\":\"assistant\"}\n");
        Files.setLastModifiedTime(old.resolve("transcript.jsonl"), FileTime.from(BEFORE));

        List<Init> inits = SessionReach.inits(JSON, sessions);

        assertThat(inits).extracting(Init::version).containsExactly("2.1.316", "2.1.300");
        assertThat(SessionReach.latest(inits, i -> "2.1.300".equals(i.version()))
                        .version())
                .isEqualTo("2.1.300");
        assertThat(SessionReach.inits(JSON, tmp.resolve("none"))).isEmpty();
    }
}
