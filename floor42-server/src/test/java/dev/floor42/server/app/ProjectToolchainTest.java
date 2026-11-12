package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.app.ProjectToolchain.Need;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectToolchainTest {

    @TempDir
    Path root;

    private Need need(List<Need> needs, String tool) {
        return needs.stream().filter(n -> n.tool().equals(tool)).findFirst().orElse(null);
    }

    @Test
    void aMavenProjectNeedsJavaAtItsReleaseAndMaven() throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");

        List<Need> needs = ProjectToolchain.read(root, "mvn -q verify");

        assertThat(need(needs, "java").wanted()).isEqualTo("21");
        assertThat(need(needs, "mvn").because()).containsExactly("pom.xml", "check command");
        assertThat(need(needs, "git")).isNotNull();
    }

    @Test
    void theWrapperStandsForMavenAndTheCompilerPluginsReleaseIsRead() throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><build><plugin><configuration><release>17</release></configuration></plugin></build>"
                        + "</project>");
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh");

        List<Need> needs = ProjectToolchain.read(root, "./mvnw -q verify");

        assertThat(need(needs, "java").wanted()).isEqualTo("17");
        assertThat(need(needs, "java").because()).contains("pom.xml", "mvnw", "check command");
        assertThat(need(needs, "mvn")).isNull();
    }

    @Test
    void aReleaseLeftToAPropertyIsNotGuessed() throws Exception {
        Files.writeString(
                root.resolve("pom.xml"), "<project><build><release>${java.version}</release></build></project>");

        assertThat(need(ProjectToolchain.read(root, null), "java").wanted()).isNull();
    }

    @Test
    void nodeFromEnginesAndNvmrc() throws Exception {
        Files.writeString(
                root.resolve("package.json"),
                "{\"name\":\"x\",\"engines\":{\"npm\":\">=9\",\"node\":\">=20.11\"},\"scripts\":{}}");
        Files.writeString(root.resolve(".nvmrc"), "v22\n");

        List<Need> needs = ProjectToolchain.read(root, "npm test");

        assertThat(need(needs, "node").wanted()).isEqualTo(">=20.11");
        assertThat(need(needs, "node").because()).containsExactly("package.json", ".nvmrc");
        assertThat(need(needs, "npm").because()).containsExactly("package.json", "check command");
    }

    @Test
    void pythonGoRustMakeAndDocker() throws Exception {
        Files.writeString(root.resolve("pyproject.toml"), "[project]\nname = \"x\"\nrequires-python = \">=3.11\"\n");
        Files.writeString(root.resolve("requirements.txt"), "requests\n");
        Files.writeString(root.resolve("go.mod"), "module example.com/x\n\ngo 1.22\n");
        Files.writeString(root.resolve("Cargo.toml"), "[package]\nname = \"x\"\n");
        Files.writeString(root.resolve("rust-toolchain.toml"), "[toolchain]\nchannel = \"1.80\"\n");
        Files.writeString(root.resolve("Makefile"), "all:\n");
        Files.writeString(root.resolve("Dockerfile"), "FROM scratch\n");

        List<Need> needs = ProjectToolchain.read(root, "make test");

        assertThat(need(needs, "python3").wanted()).isEqualTo(">=3.11");
        assertThat(need(needs, "pip3")).isNotNull();
        assertThat(need(needs, "go").wanted()).isEqualTo("1.22");
        assertThat(need(needs, "cargo").wanted()).isEqualTo("1.80");
        assertThat(need(needs, "rustc").wanted()).isEqualTo("1.80");
        assertThat(need(needs, "make").because()).containsExactly("Makefile", "check command");
        assertThat(need(needs, "docker")).isNotNull();
    }

    @Test
    void aPlainRustToolchainFileGivesItsChannel() throws Exception {
        Files.writeString(root.resolve("Cargo.toml"), "[package]\n");
        Files.writeString(root.resolve("rust-toolchain"), "stable\n");

        assertThat(need(ProjectToolchain.read(root, null), "rustc").wanted()).isEqualTo("stable");
    }

    @Test
    void theCheckCommandsFirstWordWithoutPathOrAssignmentOrShell() {
        assertThat(ProjectToolchain.checkTool("FOO=1 ./gradlew test")).contains("java");
        assertThat(ProjectToolchain.checkTool("/usr/bin/python -m pytest")).contains("python3");
        assertThat(ProjectToolchain.checkTool("npx vitest")).contains("npm");
        assertThat(ProjectToolchain.checkTool("mvnw.cmd verify")).contains("java");
        assertThat(ProjectToolchain.checkTool("sh -c 'make'")).isEmpty();
        assertThat(ProjectToolchain.checkTool("  ")).isEmpty();
        assertThat(ProjectToolchain.checkTool(null)).isEmpty();
    }

    @Test
    void anEmptyFolderNeedsGitOnlyAndAMissingOneNothing() {
        assertThat(ProjectToolchain.read(root, null)).extracting(Need::tool).containsExactly("git");
        assertThat(ProjectToolchain.read(root.resolve("gone"), null)).isEmpty();
        assertThat(ProjectToolchain.read(null, "cargo test"))
                .extracting(Need::tool)
                .containsExactly("cargo");
    }
}
