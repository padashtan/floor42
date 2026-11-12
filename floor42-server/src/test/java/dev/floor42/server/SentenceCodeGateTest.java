package dev.floor42.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * A view record that carries a human sentence built from fields (a String component named {@code *Reason},
 * {@code *Basis}, {@code *Detail} or {@code *Summary}) also carries the matching code, so the UI can translate instead
 * of showing the server's English (ADR-0021): {@code fooReason} needs {@code fooCode}, or a bare {@code code}. What
 * lacks one today is listed below, as {@code Record.component}: an entry goes once its code exists, and anything not
 * listed fails at once. Same discipline as {@link SizeGateTest}: the list only shrinks.
 */
class SentenceCodeGateTest {

    static final Pattern SENTENCE = Pattern.compile("([A-Za-z0-9]*?)(Reason|Basis|Detail|Summary)");

    /** Records and sentence components still without a code (measured 2026-11-02), each to be fixed and removed. */
    static final Set<String> ALLOWED =
            Set.of("ActionView.denialReason", "CostLine.costBasis", "CostLine.sessionsBasis", "CostLine.tokensBasis");

    @Test
    void everySentenceComponentHasItsCode() {
        List<String> missing = new ArrayList<>(violations(SourceScan.ROOTS));
        missing.removeAll(ALLOWED);
        assertThat(missing)
                .as("a sentence field without its *Code field: send a code, the UI translates")
                .isEmpty();
    }

    @Test
    void theAllowListHoldsOnlyWhatStillLacksACode() {
        Set<String> now = new TreeSet<>(violations(SourceScan.ROOTS));
        List<String> stale = new ArrayList<>(ALLOWED);
        stale.removeAll(now);
        assertThat(stale).as("entries that now have their code: remove them").isEmpty();
    }

    @Test
    void aPlantedRecordWithoutACodeIsCaught() {
        assertThat(violationsIn("record Bad(String id, String denialReason, int n) {}"))
                .containsExactly("Bad.denialReason");
        assertThat(violationsIn("record Good(String denialReason, String denialCode) {}"))
                .isEmpty();
        assertThat(violationsIn("record Bare(String basis, String code) {}")).isEmpty();
    }

    static Set<String> violations(List<Path> roots) {
        Set<String> out = new TreeSet<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> files =
                        walk.filter(p -> p.toString().endsWith(".java")).toList();
                out.addAll(parse(files));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    static Set<String> violationsIn(String source) {
        try {
            Path dir = Files.createTempDirectory("sentence-gate");
            Path file = dir.resolve("Planted.java");
            Files.writeString(file, source);
            return new TreeSet<>(parse(List.of(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> parse(List<Path> files) {
        Set<String> out = new TreeSet<>();
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) javac.getTask(
                    null, fm, d -> {}, List.of("-proc:none"), null, fm.getJavaFileObjectsFromPaths(files));
            for (CompilationUnitTree unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree c, Void v) {
                        if (c.getKind() == Tree.Kind.RECORD) check(c, out);
                        return super.visitClass(c, v);
                    }
                }.scan(unit, null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static void check(ClassTree record, Set<String> out) {
        List<String> names = new ArrayList<>();
        for (Tree m : record.getMembers())
            if (m instanceof VariableTree v && v.getType().toString().equals("String"))
                names.add(v.getName().toString());
        for (String n : names) {
            var m = SENTENCE.matcher(n);
            if (!m.matches()) continue;
            String stem = m.group(1);
            boolean coded = names.contains(stem + "Code") || names.contains(n + "Code") || names.contains("code");
            if (!coded) out.add(record.getSimpleName() + "." + n);
        }
    }
}
