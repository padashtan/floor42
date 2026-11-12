package dev.floor42.server.runtime;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * The sandbox for a sandbox-gated test, on Windows through WSL and on Linux natively. Skips only when the platform is
 * absent (no WSL distribution, or a Linux kernel that refuses unprivileged user namespaces, or another system); a
 * platform that exists but whose probe fails is a broken sandbox: the test fails with the probe's reason.
 */
public final class WslSandbox {

    private WslSandbox() {}

    static Sandbox ready(Path data) {
        Sandbox s = new ProofSandboxIsolationTest().sandbox(data);
        // These tests run commands in it, none needs the network; on native Linux network on needs pasta
        // (SandboxNative).
        s.policy = new SandboxPolicy() {
            @Override
            public boolean sandboxEnabled() {
                return true;
            }

            @Override
            public boolean sandboxNetwork() {
                return false;
            }
        };
        return ready(s);
    }

    static Sandbox ready(Sandbox s) {
        return ready(s, () -> SandboxHost.linux(SandboxHost.current()) ? userNamespaces() : distroExists(s.distro));
    }

    /** Skips the test unless a sandbox backend exists on this machine (WSL's Debian, or a Linux with user namespaces). */
    static void platformOrSkip() {
        String os = SandboxHost.current();
        assumeTrue(SandboxHost.supported(os), "no sandbox backend on " + os + " (Windows with WSL, or Linux)");
        assumeTrue(
                SandboxHost.linux(os) ? userNamespaces() : distroExists("Debian"),
                SandboxHost.linux(os) ? "no user namespaces here" : "no WSL distribution Debian");
    }

    static Sandbox ready(Sandbox s, BooleanSupplier distroExists) {
        String os = SandboxHost.current();
        assumeTrue(SandboxHost.supported(os), "no sandbox backend on " + os + " (Windows with WSL, or Linux)");
        assumeTrue(
                distroExists.getAsBoolean(),
                SandboxHost.linux(os) ? "no user namespaces here" : "no WSL distribution " + s.distro);
        if (!s.available()) throw new AssertionError("the WSL sandbox is broken: " + s.probeFailure());
        return s;
    }

    /** The install command the director copies for these apt packages, as this machine words it (sudo or wsl). */
    public static String installHint(String packages) {
        String run = "sh -c \"apt-get update && apt-get install -y " + packages + "\"";
        return SandboxHost.linux(SandboxHost.current()) ? "sudo " + run : "wsl -d Debian -u root -- " + run;
    }

    /** A command run in the Linux the sandbox uses: as it is on Linux, through wsl.exe on Windows. */
    static java.util.List<String> host(String... command) {
        java.util.List<String> argv = new java.util.ArrayList<>();
        if (!SandboxHost.linux(SandboxHost.current()))
            argv.addAll(java.util.List.of("wsl.exe", "-d", "Debian", "--exec"));
        argv.addAll(java.util.List.of(command));
        return argv;
    }

    /** Whether this Linux lets an unprivileged user make the namespaces the sandbox needs (what its script starts with). */
    private static boolean userNamespaces() {
        try {
            Process p = new ProcessBuilder("unshare", "-Urm", "true")
                    .redirectErrorStream(true)
                    .start();
            p.getOutputStream().close();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (java.io.IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean distroExists(String distro) {
        try {
            Process p = new ProcessBuilder("wsl.exe", "-l", "-q")
                    .redirectErrorStream(true)
                    .start();
            p.getOutputStream().close();
            byte[] raw = p.getInputStream().readAllBytes();
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            // wsl.exe writes UTF-16LE unless WSL_UTF8=1 is set: a NUL byte tells which
            boolean wide = new String(raw, StandardCharsets.ISO_8859_1).indexOf('\0') >= 0;
            String out = new String(raw, wide ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
            return out.replace("\uFEFF", "").lines().anyMatch(l -> l.strip().equalsIgnoreCase(distro));
        } catch (java.io.IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
