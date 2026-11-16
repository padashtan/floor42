package dev.floor42.server.app.workspace.signing;

import dev.floor42.domain.AgentId;
import dev.floor42.server.runtime.Git;
import dev.floor42.server.runtime.GitProcess;
import dev.floor42.server.security.LogSafe;
import dev.floor42.server.security.OwnerOnly;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Commits signed by the agent's identity (ADR-0077): one Ed25519 key per agent, made by {@code ssh-keygen} under the
 * data folder, never in a worktree. Git is given the signing settings per command with {@code -c}, so nothing is
 * written to a repository's configuration. A key that cannot be made is a commit left unsigned, never a lost commit.
 */
@ApplicationScoped
public class AgentSigning {

    private static final Logger LOG = Logger.getLogger(AgentSigning.class);

    static final String DOMAIN = "@floor42.invalid";
    static final String RETIRED = "-retired" + DOMAIN;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40,64}");

    /** What git says of one commit's signature: a status code, the agent whose key signed it, whether that key is retired. */
    public record Verdict(String status, String agent, boolean retired) {}

    private final Path root;
    private final Git git;
    private String program;

    @Inject
    public AgentSigning(@ConfigProperty(name = "floor42.data-dir") Path dataDir, Git git) {
        this.root = dataDir.resolve("signing");
        this.git = git;
    }

    /** For CDI's client proxy only. */
    protected AgentSigning() {
        this.root = null;
        this.git = null;
    }

    /** The {@code git -c ...} arguments that sign a commit with the agent's key; empty when no key can be made. */
    public synchronized List<String> signArgs(AgentId agent) {
        try {
            Path key = ensureKey(agent);
            List<String> args = new ArrayList<>(verifyArgs());
            args.addAll(List.of("-c", "user.signingkey=" + key, "-c", "commit.gpgsign=true"));
            return args;
        } catch (IOException | RuntimeException e) {
            // Handled: the director's commit goes ahead, unsigned; the dossier says so
            LOG.warnf(
                    "No signing key for %s, the commit is left unsigned: %s",
                    LogSafe.of(agent), LogSafe.of(e.getMessage()));
            return List.of();
        }
    }

    /** The {@code git -c ...} arguments that check signatures against Floor42's allowed signers. */
    public List<String> verifyArgs() {
        return List.of(
                "-c",
                "gpg.format=ssh",
                "-c",
                "gpg.ssh.program=" + program(),
                "-c",
                "gpg.ssh.allowedSignersFile=" + root.resolve("allowed_signers"));
    }

    /** The agent is revoked or dismissed: its private key is deleted, its public key kept to verify old commits. */
    public synchronized void retire(AgentId agent) {
        Path current = root.resolve(agent.value()).resolve("current");
        if (!Files.isDirectory(current)) return;
        try {
            Files.deleteIfExists(current.resolve("key"));
            Files.move(current, current.resolveSibling("retired-" + System.currentTimeMillis()));
            writeAllowedSigners();
        } catch (IOException e) {
            // Handled: logged; the key stays where it was
            LOG.errorf("The signing key of %s could not be retired: %s", LogSafe.of(agent), LogSafe.of(e.getMessage()));
        }
    }

    /** What git says of {@code commit} (a full hash) in {@code dir}, read now. */
    public Verdict verdict(Path dir, String commit) {
        if (commit == null || !HASH.matcher(commit).matches()) return new Verdict("UNCHECKABLE", null, false);
        List<String> args = new ArrayList<>(verifyArgs());
        // --no-replace-objects: a refs/replace entry in the repository must not lend a signed commit to an unsigned one
        args.addAll(
                List.of("--no-replace-objects", "log", "-1", "--format=%G?%x1f%GS", "--end-of-options", commit, "--"));
        GitProcess.Result r;
        try {
            r = git.run(dir, args);
        } catch (RuntimeException e) {
            // Handled: a worktree git cannot read is a signature nobody can check
            return new Verdict("UNCHECKABLE", null, false);
        }
        if (r.timedOut() || r.code() != 0) return new Verdict("UNCHECKABLE", null, false);
        String[] p = r.out().strip().split("\u001f", 2);
        String signer = p.length > 1 ? p[1].strip() : "";
        boolean retired = signer.endsWith(RETIRED);
        String agent = signer.endsWith(DOMAIN)
                ? signer.substring(0, signer.length() - (retired ? RETIRED : DOMAIN).length())
                : null;
        return switch (p[0]) {
            case "G" -> new Verdict("VERIFIED", agent, retired);
            case "N" -> new Verdict("NOT_SIGNED", null, false);
            case "B" -> new Verdict("BAD_SIGNATURE", null, false);
            case "U", "X", "Y", "R" -> new Verdict("UNKNOWN_KEY", null, false);
            default -> new Verdict("UNCHECKABLE", null, false);
        };
    }

    private Path ensureKey(AgentId agent) throws IOException {
        Path dir = root.resolve(agent.value()).resolve("current");
        Path key = dir.resolve("key");
        Path pub = dir.resolve("key.pub");
        // Both parts or none: a private key without its public part signs commits that nothing can verify
        if (Files.isRegularFile(key) && Files.isRegularFile(pub)) return key;
        Files.deleteIfExists(key);
        Files.deleteIfExists(pub);
        Files.createDirectories(dir);
        OwnerOnly.restrict(root);
        OwnerOnly.restrict(dir);
        run(List.of(program(), "-q", "-t", "ed25519", "-N", "", "-C", "floor42-" + agent, "-f", key.toString()));
        writeAllowedSigners();
        return key;
    }

    private void writeAllowedSigners() throws IOException {
        List<String> lines = new ArrayList<>();
        try (Stream<Path> agents = Files.list(root)) {
            for (Path a : (Iterable<Path>) agents.filter(Files::isDirectory).sorted()::iterator) {
                try (Stream<Path> keys = Files.list(a)) {
                    for (Path k : (Iterable<Path>) keys.sorted()::iterator) {
                        Path pub = k.resolve("key.pub");
                        if (!Files.isRegularFile(pub)) continue;
                        String who =
                                a.getFileName() + (k.getFileName().toString().equals("current") ? DOMAIN : RETIRED);
                        lines.add(who + " namespaces=\"git\" "
                                + Files.readString(pub).strip());
                    }
                }
            }
        }
        // A temporary file moved over the name: git reading it meanwhile sees the old list or the new one, never half
        Path partial = root.resolve("allowed_signers.partial");
        Files.writeString(partial, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(partial, root.resolve("allowed_signers"), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Handled: a volume without atomic moves: the plain move replaces the file
            Files.move(partial, root.resolve("allowed_signers"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** At start the list is made again from the keys on disk: a file deleted or edited meanwhile is not trusted. */
    void rebuildAllowedSigners() {
        if (root == null || !Files.isDirectory(root)) return;
        try {
            writeAllowedSigners();
        } catch (IOException e) {
            // Handled: logged; the list on disk is used as it is
            LOG.errorf("The allowed signers could not be rebuilt: %s", LogSafe.of(e.getMessage()));
        }
    }

    void onStart(@Observes StartupEvent ignored) {
        rebuildAllowedSigners();
    }

    private static void run(List<String> cmd) throws IOException {
        Process p = new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ssh-keygen did not finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted", e);
        }
        if (p.exitValue() != 0) throw new IOException("ssh-keygen exited with " + p.exitValue());
    }

    /** {@code ssh-keygen} from the PATH, else the one Git for Windows keeps beside its own; else the bare name. */
    private synchronized String program() {
        if (program == null) program = found().orElse("ssh-keygen");
        return program;
    }

    private Optional<String> found() {
        String path = System.getenv("PATH");
        if (path != null)
            for (String dir : path.split(File.pathSeparator))
                for (String name : List.of("ssh-keygen", "ssh-keygen.exe")) {
                    File f = new File(dir, name);
                    if (f.isFile() && f.canExecute()) return Optional.of(f.getPath());
                }
        // shortcut: only Git for Windows' layout, upgrade when another git bundle is met
        try {
            return git.outIfOk(root.toAbsolutePath().getParent(), "--exec-path")
                    .map(e -> Path.of(e.strip())
                            .getParent()
                            .getParent()
                            .getParent()
                            .resolve("usr/bin/ssh-keygen.exe"))
                    .filter(Files::isRegularFile)
                    .map(Path::toString);
        } catch (RuntimeException e) {
            // Handled: no git to ask, or a git path of another shape: the bare name is tried
            return Optional.empty();
        }
    }
}
