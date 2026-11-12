package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.app.ClaudeAssets.Asset;
import dev.floor42.server.app.ClaudeAssets.Kind;
import dev.floor42.server.app.ClaudeAssets.Level;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The catalogue's reading of a fake {@code ~/.claude} (W3): only the frontmatter, capped, links out of a root never
 * followed, malformed files listed with their problem rather than failing the whole reading.
 */
class ClaudeAssetsTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @TempDir
    Path tmp;

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    static Asset named(List<Asset> assets, String name) {
        return assets.stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " in " + assets));
    }

    /**
     * A link to a folder. Symbolic links need a privilege on Windows (developer mode): without it, a junction, which
     * any user may make and which resolves the same way; failing both, the link tests are skipped.
     */
    static void link(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            if (!System.getProperty("os.name")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("win")) {
                Assumptions.abort("symbolic links cannot be created here: " + e.getMessage());
            }
        }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() != 0 || !Files.isDirectory(link)) Assumptions.abort("no junction either");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assumptions.abort("interrupted");
        }
    }

    @Test
    void readsTheFrontmatterOfSkillsAndAgents() throws Exception {
        Path home = tmp.resolve("claude");
        write(
                home.resolve("skills/db-migration/SKILL.md"),
                "---\r\nname: db-migration\r\ndescription: \"Write migrations: versioned, \\\"reversible\\\"\"\r\n---\r\nbody\r\n");
        write(
                home.resolve("skills/folded/SKILL.md"),
                "﻿---\nname: folded\ndescription: >-\n  First line\n  second line\n# a comment\n---\n");
        write(
                home.resolve("agents/reviewer.md"),
                "---\nname: reviewer\ndescription: Reads diffs\ntools: Read, Grep, Glob\nmodel: haiku\n---\nYou review.\n");
        write(home.resolve("agents/listed.md"), "---\nname: listed\ntools:\n  - Read\n  - 'Bash'\n---\n");
        write(home.resolve("agents/notes.txt"), "not an agent");

        List<Asset> user = ClaudeAssets.user(home, List.of());

        assertThat(user).hasSize(4);
        Asset skill = named(user, "db-migration");
        assertThat(skill.kind()).isEqualTo(Kind.SKILL);
        assertThat(skill.level()).isEqualTo(Level.USER);
        assertThat(skill.description()).isEqualTo("Write migrations: versioned, \"reversible\"");
        assertThat(skill.path()).isEqualTo("skills/db-migration/SKILL.md");
        assertThat(skill.problem()).isNull();
        assertThat(named(user, "folded").description()).isEqualTo("First line second line");
        Asset agent = named(user, "reviewer");
        assertThat(agent.tools()).containsExactly("Read", "Grep", "Glob");
        assertThat(agent.model()).isEqualTo("haiku");
        assertThat(agent.path()).isEqualTo("agents/reviewer.md");
        assertThat(named(user, "listed").tools()).containsExactly("Read", "Bash");
    }

    @Test
    void aHugeFileIsReadOnlyUpToTheCap() throws Exception {
        Path home = tmp.resolve("claude");
        Path big = home.resolve("skills/big/SKILL.md");
        Path open = home.resolve("skills/endless/SKILL.md");
        Files.createDirectories(big.getParent());
        Files.createDirectories(open.getParent());
        byte[] line = "lorem ipsum dolor sit amet\n".repeat(1000).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = Files.newOutputStream(big)) {
            out.write("---\nname: big\ndescription: a big one\n---\n".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 300; i++) out.write(line); // about 8 MB
        }
        try (OutputStream out = Files.newOutputStream(open)) {
            out.write("---\nname: endless\n".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 300; i++) out.write(line);
        }

        List<Asset> user = ClaudeAssets.user(home, List.of());

        assertThat(named(user, "big").description()).isEqualTo("a big one");
        assertThat(named(user, "big").problem()).isNull();
        // Never closed within the cap: nothing of it is trusted, the folder's name stands.
        assertThat(named(user, "endless").problem()).isEqualTo("tooLarge");
        assertThat(named(user, "endless").description()).isNull();
    }

    @Test
    void malformedFrontmattersAreListedWithTheirProblem() throws Exception {
        Path home = tmp.resolve("claude");
        write(home.resolve("skills/plain/SKILL.md"), "# Just markdown\nno frontmatter\n");
        write(home.resolve("skills/unclosed/SKILL.md"), "---\nname: unclosed\ndescription: never ends\n");
        write(home.resolve("skills/odd/SKILL.md"), "---\nname: odd\nthis is not yaml at all\n---\n");
        write(home.resolve("skills/empty/SKILL.md"), "");
        write(home.resolve("agents/binary.md"), "\u0000\u0001---\nname: x\n---");
        Files.createDirectories(home.resolve("skills/no-file"));

        List<Asset> user = ClaudeAssets.user(home, List.of());

        assertThat(named(user, "plain").problem()).isEqualTo("noFrontmatter");
        assertThat(named(user, "unclosed").problem()).isEqualTo("malformed");
        assertThat(named(user, "unclosed").description()).isNull();
        assertThat(named(user, "odd").problem()).isEqualTo("malformed");
        assertThat(named(user, "empty").problem()).isEqualTo("noFrontmatter");
        assertThat(named(user, "binary").problem()).isEqualTo("noFrontmatter");
        assertThat(user).extracting(Asset::name).doesNotContain("no-file");
    }

    @Test
    void controlCharactersAndLongValuesAreCut() {
        String fm = "---\nname: a\u0007b\ndescription: " + "x".repeat(5000) + "\n---\n";
        var parsed = ClaudeAssets.frontmatter(fm.getBytes(StandardCharsets.UTF_8), false);
        assertThat(ClaudeAssets.clean(parsed.fields().get("name"), 120)).isEqualTo("a b");
        assertThat(ClaudeAssets.clean(parsed.fields().get("description"), 600)).hasSize(600);
    }

    @Test
    void aLinkOutOfItsRootIsListedNotRead() throws Exception {
        Path home = tmp.resolve("claude");
        Path toolkit = tmp.resolve("toolkit");
        write(toolkit.resolve("secret/SKILL.md"), "---\nname: secret\ndescription: outside the root\n---\n");
        write(home.resolve("skills/inside/SKILL.md"), "---\nname: inside\ndescription: in\n---\n");
        link(home.resolve("skills/secret"), toolkit.resolve("secret"));

        Asset out = named(ClaudeAssets.user(home, List.of()), "secret");
        assertThat(out.problem()).isEqualTo("linkOut");
        assertThat(out.description()).isNull();
        assertThat(out.path()).isEqualTo("skills/secret/SKILL.md");

        // A root the director trusts is read through the link.
        Asset trusted = named(ClaudeAssets.user(home, List.of(toolkit)), "secret");
        assertThat(trusted.problem()).isNull();
        assertThat(trusted.description()).isEqualTo("outside the root");
    }

    @Test
    void aRootThatIsItselfALinkIsReadWhereItLeads() throws Exception {
        Path home = tmp.resolve("claude");
        Path toolkit = tmp.resolve("toolkit");
        write(toolkit.resolve("agents/helper.md"), "---\nname: helper\ndescription: helps\n---\n");
        Files.createDirectories(home);
        link(home.resolve("agents"), toolkit.resolve("agents"));

        Asset helper = named(ClaudeAssets.user(home, List.of()), "helper");
        assertThat(helper.problem()).isNull();
        assertThat(helper.description()).isEqualTo("helps");
    }

    @Test
    void projectLevelPathsAreRelativeToTheRepository() throws Exception {
        Path repo = tmp.resolve("repo");
        write(repo.resolve(".claude/skills/release/SKILL.md"), "---\nname: release\ndescription: ship it\n---\n");
        write(repo.resolve(".claude/agents/qa.md"), "---\nname: qa\n---\n");

        List<Asset> project = ClaudeAssets.project(repo, List.of());

        assertThat(project).extracting(Asset::level).containsOnly(Level.PROJECT);
        assertThat(named(project, "release").path()).isEqualTo(".claude/skills/release/SKILL.md");
        assertThat(named(project, "qa").path()).isEqualTo(".claude/agents/qa.md");
        assertThat(ClaudeAssets.project(tmp.resolve("nothing"), List.of())).isEmpty();
    }

    @Test
    void pluginsAreReadFromTheirManifests() throws Exception {
        Path home = tmp.resolve("claude");
        Path plugin = home.resolve("plugins/cache/market/tidy/1.2.0");
        write(
                plugin.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"tidy\",\"description\":\"Keeps code tidy\",\"version\":\"1.2.0\"}");
        write(plugin.resolve("skills/format/SKILL.md"), "---\nname: format\ndescription: formats\n---\n");
        write(plugin.resolve("agents/linter.md"), "---\nname: linter\ntools: Read\n---\n");
        Path elsewhere = tmp.resolve("elsewhere/rogue");
        write(
                elsewhere.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"rogue\",\"description\":\"should not show\"}");
        write(
                home.resolve("plugins/installed_plugins.json"),
                "{\"version\":2,\"plugins\":{"
                        + "\"tidy@market\":[{\"scope\":\"user\",\"installPath\":"
                        + json(plugin.toString()) + ",\"version\":\"1.2.0\"}],"
                        + "\"rogue@market\":[{\"scope\":\"user\",\"installPath\":"
                        + json(elsewhere.toString()) + "}],"
                        + "\"gone@market\":[{\"scope\":\"user\",\"installPath\":\"cache/market/gone/0.1\"}]}}");

        List<Asset> plugins = ClaudeAssets.plugins(JSON, home, List.of());

        Asset tidy = plugins.stream()
                .filter(a -> a.kind() == Kind.PLUGIN && a.name().equals("tidy"))
                .findFirst()
                .orElseThrow();
        assertThat(tidy.description()).isEqualTo("Keeps code tidy");
        assertThat(tidy.version()).isEqualTo("1.2.0");
        assertThat(tidy.path()).isEqualTo("cache/market/tidy/1.2.0");
        Asset format = named(plugins, "format");
        assertThat(format.plugin()).isEqualTo("tidy");
        assertThat(format.level()).isEqualTo(Level.PLUGIN);
        assertThat(format.path()).isEqualTo("skills/format/SKILL.md");
        assertThat(named(plugins, "linter").tools()).containsExactly("Read");
        Asset rogue = named(plugins, "rogue");
        assertThat(rogue.problem()).isEqualTo("outsidePlugins");
        assertThat(rogue.description()).isNull();
        assertThat(named(plugins, "gone").problem()).isEqualTo("unreadable");
    }

    @Test
    void noPluginsFileMeansNoPlugins() throws Exception {
        Path home = tmp.resolve("claude");
        Files.createDirectories(home.resolve("plugins"));
        write(home.resolve("plugins/installed_plugins.json"), "{ not json");
        assertThat(ClaudeAssets.plugins(JSON, home, List.of())).isEmpty();
        assertThat(ClaudeAssets.plugins(JSON, tmp.resolve("absent"), List.of())).isEmpty();
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\") + "\"";
    }
}
