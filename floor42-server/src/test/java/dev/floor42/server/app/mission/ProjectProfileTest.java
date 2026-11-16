package dev.floor42.server.app.mission;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.app.mission.ProjectProfile.Profile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The profile is read from files only and proposes a checks command from a table (ADR-0070). */
class ProjectProfileTest {

    @TempDir
    Path dir;

    private void file(String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void mavenWrapperGivesMvnwVerify() throws Exception {
        file("pom.xml", "<project/>");
        file("mvnw", "#!/bin/sh");
        Profile p = ProjectProfile.read(dir);
        assertThat(p.buildTool()).isEqualTo("Maven");
        assertThat(p.files()).containsExactly("pom.xml", "mvnw");
        assertThat(p.suggestedChecks()).isEqualTo("./mvnw -q verify");
        assertThat(p.downloads()).containsExactly("mvnw");
    }

    @Test
    void plainPomGivesMvnVerify() throws Exception {
        file("pom.xml", "<project/>");
        assertThat(ProjectProfile.read(dir).suggestedChecks()).isEqualTo("mvn -q verify");
    }

    @Test
    void gradleWrapperGivesGradlewCheck() throws Exception {
        file("gradlew", "#!/bin/sh");
        file("build.gradle", "");
        Profile p = ProjectProfile.read(dir);
        assertThat(p.buildTool()).isEqualTo("Gradle");
        assertThat(p.suggestedChecks()).isEqualTo("./gradlew check");
    }

    @Test
    void packageJsonWithTestScriptGivesNpmTest() throws Exception {
        file("package.json", "{\"scripts\":{\"build\":\"x\",\"test\":\"vitest\"}}");
        Profile p = ProjectProfile.read(dir);
        assertThat(p.buildTool()).isEqualTo("Node");
        assertThat(p.suggestedChecks()).isEqualTo("npm test");
        assertThat(p.downloads()).containsExactly("package.json");
    }

    @Test
    void packageJsonWithoutTestScriptGivesNoCommand() throws Exception {
        file("package.json", "{\"scripts\":{\"build\":\"x\"}}");
        assertThat(ProjectProfile.read(dir).suggestedChecks()).isNull();
    }

    @Test
    void nodeModulesPresentMeansNoDownloadSignal() throws Exception {
        file("package.json", "{}");
        Files.createDirectory(dir.resolve("node_modules"));
        assertThat(ProjectProfile.read(dir).downloads()).isEmpty();
    }

    @Test
    void makefileWithTestTargetGivesMakeTest() throws Exception {
        file("Makefile", "build:\n\tx\ntest:\n\ty\n");
        Profile p = ProjectProfile.read(dir);
        assertThat(p.buildTool()).isEqualTo("Make");
        assertThat(p.suggestedChecks()).isEqualTo("make test");
    }

    @Test
    void unknownFolderGivesNoToolAndNoCommand() {
        Profile p = ProjectProfile.read(dir);
        assertThat(p.buildTool()).isNull();
        assertThat(p.files()).isEmpty();
        assertThat(p.suggestedChecks()).isNull();
    }

    @Test
    void fileAboveMaxSizeCountsAsAbsent() throws Exception {
        file("pom.xml", "x".repeat((int) ProjectToolchain.MAX_FILE + 1));
        assertThat(ProjectProfile.read(dir).buildTool()).isNull();
    }

    @Test
    void unreadableFileCountsAsAbsent() throws Exception {
        // A directory named like a build file is not a regular file: it cannot be read as one.
        Files.createDirectory(dir.resolve("pom.xml"));
        assertThat(ProjectProfile.read(dir).buildTool()).isNull();
    }
}
