package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code floor42 doctor} without the daemon and without wsl.exe: the machine's checks and the sandbox probe get
 * recorded-format outputs through their seams, the real code path does the rest (scratch folder, sandbox script,
 * reading, printing). Exit code 0 only when every required item is present; nothing secret is printed.
 */
class DoctorTest {

    static final ClaudeCodeProbe.Availability CLAUDE = new ClaudeCodeProbe.Availability(true, "2.1.316", null);

    /** wsl.exe -l -v writes UTF-16: the bytes as SandboxProbe.start reads them. */
    static String wslList(String distros) {
        return new String(
                ("  NAME      STATE           VERSION\r\n" + distros).getBytes(StandardCharsets.UTF_16LE),
                StandardCharsets.UTF_8);
    }

    static HostChecks host(String wsl, List<List<String>> runs) {
        HostChecks h = new HostChecks();
        h.useForTests(
                (argv, limit) -> {
                    runs.add(argv);
                    return argv.getFirst().equals("git")
                            ? new SandboxProbe.RunResult("git version 2.51.0.windows.1\n", 0, false)
                            : new SandboxProbe.RunResult(wsl, 0, false);
                },
                () -> "Windows 11");
        return h;
    }

    record Run(int code, String out, List<List<String>> sandboxRuns) {}

    static Run doctor(String wsl) {
        List<List<String>> hostRuns = new ArrayList<>();
        List<List<String>> sandboxRuns = new ArrayList<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = Doctor.run(
                host(wsl, hostRuns),
                windows -> CLAUDE,
                (argv, limit) -> {
                    sandboxRuns.add(argv);
                    return new SandboxProbe.RunResult(SandboxProbeTest.DEBIAN, 0, false);
                },
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return new Run(code, bytes.toString(StandardCharsets.UTF_8), sandboxRuns);
    }

    @Test
    void everythingRequiredPresentExitsZero() {
        Run r = doctor(wslList("* Debian    Running         2\r\n"));

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("[ok]       Claude Code").contains("Every required item is present.");
        assertThat(r.sandboxRuns()).as("one run in the sandbox").hasSize(1);
        assertThat(r.sandboxRuns().getFirst()).startsWith(SandboxHost.linux(SandboxHost.current()) ? "sh" : "wsl.exe");
    }

    @Test
    void dataFolderWarningsArePrintedButNeverChangeTheExitCode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = Doctor.run(
                host(wslList("* Debian    Running         2\r\n"), new ArrayList<>()),
                windows -> CLAUDE,
                (argv, limit) -> new SandboxProbe.RunResult(SandboxProbeTest.DEBIAN, 0, false),
                List.of(new DataChecks.Item(DataChecks.State.WARN, "Port 7420", "used by another program")),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));

        assertThat(code).isZero();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("[WARN]    Port 7420: used by another program");
    }

    @Test
    void aRequiredItemMissingExitsOneWithTheCommandToCopy() {
        Run r = doctor(wslList("* Ubuntu    Running         2\r\n"));

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out())
                .contains("[MISSING]  WSL distribution Debian")
                .contains("to install, copy: wsl --install -d Debian")
                .contains("1 required item(s) missing or not confirmed.");
    }

    @Test
    void itPrintsNoTokenNorAnySecretOfTheEnvironment() {
        String out = doctor(wslList("* Debian    Running         2\r\n")).out();

        assertThat(out.toLowerCase(Locale.ROOT)).doesNotContain("token", "bearer", "password");
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String k = e.getKey().toUpperCase(Locale.ROOT);
            boolean secret = k.contains("TOKEN") || k.contains("SECRET") || k.contains("KEY") || k.contains("PASS");
            if (secret && e.getValue().length() >= 8) assertThat(out).doesNotContain(e.getValue());
        }
    }
}
