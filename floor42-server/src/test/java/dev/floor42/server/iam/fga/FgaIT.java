package dev.floor42.server.iam.fga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The S1 shapes against the real OpenFGA pinned in iam/compose.yaml (in-memory datastore here, the policy is the same).
 * A plain {@code docker run}, skipped when Docker answers nothing (set {@code FLOOR42_DOCKER="wsl docker"} where Docker
 * lives in WSL). The shipped model is read from iam/openfga/model.json.
 */
class FgaIT {

    static final String IMAGE =
            "openfga/openfga@sha256:b852071314f1fda1194d341a6ebac0a4f619b3cd87a6626d2ccc7048af4ec671";
    static final String KEY = "it-preshared-key-0123456789abcdef";
    static final String[] DOCKER = Optional.ofNullable(System.getenv("FLOOR42_DOCKER"))
            .orElse("docker")
            .split(" ");

    static String container;
    static URI url;

    @TempDir
    Path dir;

    static String docker(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(Arrays.asList(DOCKER));
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0)
            throw new IOException("docker " + String.join(" ", args) + " failed: " + out);
        return out;
    }

    @BeforeAll
    static void start() throws Exception {
        try {
            docker("version", "--format", "{{.Server.Version}}");
        } catch (IOException | InterruptedException e) {
            Assumptions.abort("no Docker");
        }
        // A first run prints the image pull before the id: the id is the last line
        String started = docker(
                "run",
                "-d",
                "--rm",
                "-p",
                "127.0.0.1::8080",
                "-e",
                "OPENFGA_AUTHN_METHOD=preshared",
                "-e",
                "OPENFGA_AUTHN_PRESHARED_KEYS=" + KEY,
                IMAGE,
                "run");
        container = started.lines().reduce((a, b) -> b).orElseThrow();
        String mapped =
                docker("port", container, "8080/tcp").lines().findFirst().orElseThrow();
        url = URI.create("http://127.0.0.1:" + mapped.substring(mapped.lastIndexOf(':') + 1));
        var probe = new FgaHttp(() -> url, () -> Optional.of(KEY), FgaStub.JSON);
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            try {
                if (probe.call("GET", "/stores", null).ok()) break;
            } catch (FgaException e) {
                if (System.nanoTime() > end) throw e;
                Thread.sleep(500);
            }
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (container != null) docker("rm", "-f", container);
    }

    private FgaStore store(Optional<String> key) {
        var settings = new FgaSettings(Optional.of(url.toString()), key, true, null);
        return new FgaStore(dir.resolve("fga.json"), settings, FgaStub.JSON, () -> {
            try {
                return Files.readString(Path.of("../iam/openfga/model.json"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void theShippedModelDecidesGrantAndDelegationAndRevokesAtOnce() {
        FgaStore s = store(Optional.of(KEY));
        assertThat(s.ensureReady()).isEmpty();
        FgaClient fga = s.client();
        var granted = new FgaTuple("agent:ada", "granted", "resource:jira");
        var delegated = new FgaTuple("mission:m1", "delegated", "resource:jira");
        fga.write(List.of(granted, delegated), List.of());
        fga.write(List.of(granted), List.of()); // idempotent: no read before write, no error

        var runnerM1 = new FgaTuple("agent:ada", "runner", "mission:m1");
        var runnerM2 = new FgaTuple("agent:ada", "runner", "mission:m2");
        assertThat(fga.check("agent:ada", "can_call", "resource:jira", List.of(runnerM1)))
                .as("grant and the session's mission")
                .isTrue();
        assertThat(fga.check("agent:ada", "can_call", "resource:jira", List.of(runnerM2)))
                .as("a delegation of another mission does not count")
                .isFalse();
        assertThat(fga.check("agent:ada", "can_call", "resource:jira", List.of()))
                .as("no contextual runner, no call")
                .isFalse();

        fga.write(List.of(), List.of(delegated));
        fga.write(List.of(), List.of(delegated)); // on_missing ignore
        assertThat(fga.check("agent:ada", "can_call", "resource:jira", List.of(runnerM1)))
                .as("a deleted tuple refuses the next call")
                .isFalse();
        assertThat(fga.read("resource:jira")).containsExactly(granted);
    }

    @Test
    void aCallWithoutTheKeyOrWithAWrongOneIsRefused() {
        assertThat(store(Optional.of(KEY)).ensureReady()).isEmpty();
        Throwable none = catchThrowable(
                () -> new FgaHttp(() -> url, Optional::empty, FgaStub.JSON).call("GET", "/stores", null));
        assertThat(none).isInstanceOf(FgaException.class);
        assertThat(((FgaException) none).code()).isEqualTo(FgaException.UNAVAILABLE);
        var wrong = store(Optional.of("not-the-key")).ensureReady();
        assertThat(wrong).isPresent();
        assertThat(wrong.get().code()).isEqualTo(FgaException.UNAVAILABLE);
    }
}
