package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A skill with a dynamic command runs on the host past the sandbox: told apart, failing closed. */
class DynamicSkillsTest {

    @TempDir
    Path dir;

    @Test
    void aBangLineOrABangBlockRunsACommandAPlainSkillDoesNot() throws Exception {
        Path inline = Files.writeString(dir.resolve("a.md"), "---\nname: a\n---\nBranch: !`git branch`\n");
        Path block = Files.writeString(dir.resolve("b.md"), "Run:\n```!\nid\n```\n");
        Path plain = Files.writeString(dir.resolve("c.md"), "---\nname: c\n---\nSay hello! Use `code` freely.\n");

        assertThat(DynamicSkills.runsCommand(inline)).isTrue();
        assertThat(DynamicSkills.runsCommand(block)).isTrue();
        assertThat(DynamicSkills.runsCommand(plain)).isFalse();
    }

    @Test
    void aFileThatCannotBeReadWholeCountsAsHavingOne() throws Exception {
        assertThat(DynamicSkills.runsCommand(dir.resolve("missing.md"))).isTrue();
        assertThat(DynamicSkills.runsCommand(dir)).as("a folder").isTrue();
        Path big = dir.resolve("big.md");
        try (var out = Files.newOutputStream(big)) {
            out.write(new byte[(int) DynamicSkills.MAX_BYTES + 1]);
        }
        assertThat(DynamicSkills.runsCommand(big)).isTrue();
    }

    @Test
    void aSkillFolderIsScannedAtAnyDepthWhateverTheCaseOfTheFileName() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("skill"));
        Files.writeString(folder.resolve("SKILL.md"), "fine\n");
        assertThat(DynamicSkills.folderRunsCommand(folder)).isFalse();
        Files.createDirectories(folder.resolve("sub/deeper"));
        Files.writeString(folder.resolve("sub/deeper/skill.md"), "x !`id`\n");
        assertThat(DynamicSkills.folderRunsCommand(folder)).isTrue();
    }

    @Test
    void aLinkedSkillFileIsFollowedAndALinkLoopOrAnOversizedFolderFailsClosed() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("skill"));
        Path target = Files.writeString(dir.resolve("elsewhere.md"), "!`id`\n");
        Path link = folder.resolve("SKILL.md");
        try {
            Files.createSymbolicLink(link, target);
            assertThat(DynamicSkills.folderRunsCommand(folder))
                    .as("link to a file")
                    .isTrue();
            Files.delete(link);
            Files.writeString(link, "fine\n");
            Files.createSymbolicLink(folder.resolve("loop"), folder);
            assertThat(DynamicSkills.folderRunsCommand(folder)).as("link loop").isTrue();
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "no symbolic links here");
        }
    }

    @Test
    void aFolderWithMoreEntriesThanTheBoundCountsAsHavingACommand() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("big"));
        for (int i = 0; i <= DynamicSkills.MAX_ENTRIES; i++) Files.writeString(folder.resolve("f" + i + ".txt"), "x");
        assertThat(DynamicSkills.folderRunsCommand(folder)).isTrue();
    }
}
