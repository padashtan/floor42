package dev.floor42.server;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Compiles the fake Claude Code and the fake gh once per test run, before any test, into
 * {@code target/fakes/*.jar}. Launched as {@code java FakeClaude.java}, the fake was compiled again by the source
 * launcher at every session (about a second each on Windows, for hundreds of sessions). A jar newer than its source is
 * kept. Compiled for release 21: the {@code java} found on the PATH runs it, and may be older than the build's JDK.
 */
public final class FakeTools implements LauncherSessionListener {

    /** The fake Claude Code, run as {@code java -cp <jar> FakeClaude}. */
    public static final Path CLAUDE_JAR = Path.of("target/fakes/fake-claude.jar");

    /** The fake gh, run as {@code java -cp <jar> FakeGh}. */
    public static final Path GH_JAR = Path.of("target/fakes/fake-gh.jar");

    private static final String RELEASE = "21";

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            build(Path.of("src/test/resources/fake-claude/FakeClaude.java"), CLAUDE_JAR);
            build(Path.of("src/test/resources/fake-gh/FakeGh.java"), GH_JAR);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build the fake tools: " + e.getMessage(), e);
        }
    }

    /** The command that runs the fake gh with the test JVM's own java. */
    public static List<String> ghCommand() {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                GH_JAR.toAbsolutePath().toString(),
                "FakeGh");
    }

    static synchronized void build(Path source, Path jar) throws IOException {
        // Outside the server module (another working directory), there is nothing to build.
        if (!Files.isRegularFile(source)) return;
        if (Files.isRegularFile(jar) && Files.getLastModifiedTime(jar).compareTo(Files.getLastModifiedTime(source)) > 0)
            return;
        Files.createDirectories(jar.getParent());
        Path classes = Files.createTempDirectory(jar.getParent(), "classes-");
        try {
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac == null) throw new IOException("the tests need a JDK, not a JRE");
            StringWriter errors = new StringWriter();
            boolean ok = javac.getTask(
                            errors,
                            null,
                            null,
                            List.of("--release", RELEASE, "-proc:none", "-d", classes.toString()),
                            null,
                            javac.getStandardFileManager(null, null, null).getJavaFileObjects(source))
                    .call();
            if (!ok) throw new IOException(source + " does not compile:\n" + errors);
            Path partial = Files.createTempFile(jar.getParent(), "jar-", ".tmp");
            try (OutputStream file = Files.newOutputStream(partial);
                    JarOutputStream out = new JarOutputStream(file);
                    Stream<Path> files = Files.walk(classes)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    out.putNextEntry(
                            new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
                    Files.copy(f, out);
                    out.closeEntry();
                }
            }
            Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path f : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
            }
        }
    }
}
