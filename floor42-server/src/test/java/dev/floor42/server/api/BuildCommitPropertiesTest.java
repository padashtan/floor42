package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Counter-check of 1.8.0: the Docker image could not be tied to its commit (its build has no .git, so /api/status said
 * build.commit null). The Dockerfile now runs bin/build-commit-properties with the commit its builder passes.
 */
class BuildCommitPropertiesTest {

    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path dir;

    /** The POSIX shell the image's build uses; on Windows, Git's. */
    static Optional<String> shell() {
        return Stream.of("/bin/sh", "C:/Program Files/Git/bin/sh.exe")
                .filter(s -> Files.isExecutable(Path.of(s)))
                .findFirst();
    }

    Properties run(Map<String, String> env) throws Exception {
        String sh = shell().orElse(null);
        assumeTrue(sh != null, "no POSIX shell");
        ProcessBuilder pb =
                new ProcessBuilder(sh, Path.of("../bin/build-commit-properties").toString(), dir.toString());
        pb.environment().keySet().removeIf(k -> k.startsWith("FLOOR42_COMMIT"));
        pb.environment().putAll(env);
        Process p = pb.redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
        Properties props = new Properties();
        Path f = dir.resolve("build-commit.properties");
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
                props.load(r);
            }
        }
        return props;
    }

    @Test
    void theCommitTheBuilderPassesIsWrittenAsStatusReadsIt() throws Exception {
        Properties p = run(Map.of(
                "FLOOR42_COMMIT", COMMIT,
                "FLOOR42_COMMIT_TIME", "2026-11-01T15:00:00+01:00",
                "FLOOR42_COMMIT_DIRTY", "false"));

        assertThat(p.getProperty("commit")).isEqualTo("0123456");
        assertThat(p.getProperty("commitFull")).isEqualTo(COMMIT);
        assertThat(p.getProperty("commitTime")).isEqualTo("2026-11-01T15:00:00+01:00");
        assertThat(Files.readString(dir.resolve("build-changes.txt")))
                .as("clean")
                .isBlank();
    }

    @Test
    void aMissingOrMalformedCommitWritesNothingNeverAGuess() throws Exception {
        assertThat(run(Map.of())).isEmpty();
        assertThat(run(Map.of("FLOOR42_COMMIT", "HEAD"))).isEmpty();
        assertThat(run(Map.of("FLOOR42_COMMIT", "0123456"))).as("not a full id").isEmpty();
        assertThat(run(Map.of("FLOOR42_COMMIT", COMMIT + "\ncommit=forged"))).isEmpty();
        assertThat(dir.resolve("build-changes.txt")).doesNotExist();
    }

    @Test
    void changesLeftOutAreSaidAndAnUnknownStateStaysUnknown() throws Exception {
        run(Map.of("FLOOR42_COMMIT", COMMIT, "FLOOR42_COMMIT_DIRTY", "true"));
        assertThat(Files.readString(dir.resolve("build-changes.txt"))).isNotBlank();

        Files.delete(dir.resolve("build-changes.txt"));
        Properties p = run(Map.of("FLOOR42_COMMIT", COMMIT, "FLOOR42_COMMIT_TIME", "now; rm -rf /"));
        assertThat(p.getProperty("commitTime")).as("not an ISO-8601 time").isNull();
        assertThat(dir.resolve("build-changes.txt")).as("dirty unknown").doesNotExist();
    }
}
