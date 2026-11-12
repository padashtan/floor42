package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A new folder from the mission form's browser: one plain name, created only in the folder browsed, whose real path is
 * judged (a junction leads where it leads), never in a protected folder. Each refusal has its code.
 */
class NewFolderTest {

    @TempDir
    Path dir;

    Path data() throws Exception {
        return Files.createDirectories(dir.resolve("floor42-data"));
    }

    static void refused(Runnable r, String code) {
        assertThatThrownBy(r::run)
                .isInstanceOf(RefusedException.class)
                .satisfies(e -> assertThat(((RefusedException) e).code()).isEqualTo(code));
    }

    @Test
    void aPlainNameIsCreatedInTheFolderBrowsedAndNothingElse() throws Exception {
        Path parent = Files.createDirectories(dir.resolve("projects"));

        Path made = NewFolder.create(parent.toString(), "garden-planner", data());

        assertThat(made).isEqualTo(parent.toRealPath().resolve("garden-planner"));
        assertThat(Files.isDirectory(made)).isTrue();
        try (var listed = Files.list(parent)) {
            assertThat(listed.toList()).containsExactly(parent.resolve("garden-planner"));
        }
        refused(() -> NewFolder.create(parent.toString(), "garden-planner", null), NewFolder.EXISTS);
    }

    @Test
    void eachNameThatIsNotOnePlainFolderNameIsRefused() throws Exception {
        Path parent = Files.createDirectories(dir.resolve("projects"));
        List<String> bad = List.of(
                "",
                "a/b",
                "a\\b",
                "..",
                "a..b",
                "c:x",
                "what?",
                "tab\there",
                " lead",
                "trail ",
                "dot.",
                ".hidden",
                "$recycle",
                "CON",
                "nul.txt",
                "com1",
                "LPT9",
                "x".repeat(NewFolder.MAX_NAME + 1));
        for (String name : bad) {
            refused(() -> NewFolder.create(parent.toString(), name, null), NewFolder.NAME_INVALID);
        }
        refused(() -> NewFolder.create(parent.toString(), null, null), NewFolder.NAME_INVALID);
        refused(() -> NewFolder.create(parent.toString(), "node_modules", null), NewFolder.PROTECTED);
        assertThat(NewFolder.create(parent.toString(), "x".repeat(NewFolder.MAX_NAME), null))
                .exists();
        try (var listed = Files.list(parent)) {
            assertThat(listed.count()).as("nothing but the valid one").isEqualTo(1);
        }
    }

    @Test
    void theParentMustBeALocalFolderThatExists() {
        refused(() -> NewFolder.create("\\\\server\\share\\projects", "a", null), NewFolder.REMOTE);
        refused(() -> NewFolder.create("//server/share", "a", null), NewFolder.REMOTE);
        refused(() -> NewFolder.create(dir.resolve("missing").toString(), "a", null), NewFolder.PARENT_MISSING);
        refused(() -> NewFolder.create("relative", "a", null), NewFolder.PARENT_MISSING);
        refused(() -> NewFolder.create(" ", "a", null), NewFolder.PARENT_MISSING);
    }

    @Test
    void protectedFoldersAreRefused() throws Exception {
        Path git = Files.createDirectories(dir.resolve("repo").resolve(".git").resolve("hooks"));
        Path claude = Files.createDirectories(dir.resolve("repo").resolve(".claude"));
        Path data = data();
        Path inData = Files.createDirectories(data.resolve("workspaces"));
        Path root = dir.toRealPath().getRoot();
        String home = System.getProperty("user.home");

        for (Path p : List.of(git, claude, data, inData, root, Path.of(home))) {
            refused(() -> NewFolder.create(p.toString(), "new", data), NewFolder.PROTECTED);
        }
        assertThat(Files.exists(git.resolve("new"))).isFalse();
        assertThat(Files.exists(inData.resolve("new"))).isFalse();
    }

    @Test
    void aJunctionLeadingIntoTheDataFolderIsJudgedByWhereItLeads() throws Exception {
        Path data = data();
        Path target = Files.createDirectories(data.resolve("journal-side"));
        Path projects = Files.createDirectories(dir.resolve("projects"));
        Path link = projects.resolve("looks-harmless");
        link(link, target);

        refused(() -> NewFolder.create(link.toString(), "new", data), NewFolder.PROTECTED);

        assertThat(Files.exists(target.resolve("new"))).isFalse();
        // The same junction to a plain folder is fine: the folder is made where the junction leads.
        Path plain = Files.createDirectories(dir.resolve("elsewhere"));
        Path other = projects.resolve("to-plain");
        link(other, plain);
        assertThat(NewFolder.create(other.toString(), "new", data))
                .isEqualTo(plain.toRealPath().resolve("new"));
    }

    @Test
    void otherHomesAndTheFoldersWindowsKeepAreRefused() throws Exception {
        Path home = Path.of(System.getProperty("user.home")).toRealPath();
        refused(() -> NewFolder.create(home.getParent().toString(), "new", null), NewFolder.PROTECTED);
        try (var siblings = Files.list(home.getParent())) {
            siblings.filter(p -> Files.isDirectory(p) && !p.equals(home))
                    .findFirst()
                    .ifPresent(other ->
                            refused(() -> NewFolder.create(other.toString(), "new", null), NewFolder.PROTECTED));
        }
        for (String kept : List.of("$Recycle.Bin", "System Volume Information")) {
            Path p = Files.createDirectories(dir.resolve(kept).resolve("inside"));
            refused(() -> NewFolder.create(p.toString(), "new", null), NewFolder.PROTECTED);
        }
        for (String reserved : List.of("CONIN$", "conout$")) {
            refused(() -> NewFolder.create(dir.toString(), reserved, null), NewFolder.NAME_INVALID);
        }
    }

    @Test
    void aFolderThatLandedOutsideTheParentIsRemovedAndRefused() throws Exception {
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Path parent = Files.createDirectories(dir.resolve("projects"));
        Path swapped = dir.resolve("swapped");
        link(swapped, elsewhere);
        // As if the parent had been swapped for a junction between the check and the creation.
        Files.createDirectory(swapped.resolve("new"));

        refused(
                () -> {
                    try {
                        NewFolder.confirm(swapped.resolve("new"), parent.toRealPath());
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                },
                NewFolder.PROTECTED);

        assertThat(Files.exists(elsewhere.resolve("new"))).as("removed").isFalse();
        Files.createDirectory(parent.resolve("kept"));
        assertThat(NewFolder.confirm(parent.resolve("kept"), parent.toRealPath()))
                .isEqualTo(parent.toRealPath().resolve("kept"));
    }

    /** A junction on Windows (no privilege needed), a symbolic link elsewhere. */
    static void link(Path link, Path target) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            assertThat(p.waitFor()).as("mklink /J").isZero();
        } else {
            Files.createSymbolicLink(link, target);
        }
    }
}
