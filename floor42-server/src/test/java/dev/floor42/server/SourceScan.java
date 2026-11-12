package dev.floor42.server;

import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * The main sources of the domain and the server, parsed by the JDK's own compiler (syntax only, no attribution): what
 * the size gate and the catch gate read. A text scan would miscount braces in strings, text blocks and comments.
 * {@code main} prints the current offenders, for whoever pays the debt back.
 */
public final class SourceScan {

    /** The main source roots the gates read, from the server module's folder (Maven's working directory). */
    public static final List<Path> ROOTS =
            List.of(Path.of("..", "floor42-domain", "src", "main", "java"), Path.of("src", "main", "java"));

    /** A method or constructor body: its owner's simple name (nested names joined by a dot), and its lines. */
    public record Method(String file, String owner, String name, int line, int lines) {
        public String key() {
            return owner + "." + name;
        }
    }

    /** A catch clause: where it is, and whether it rethrows or says why it handles the failure. */
    public record Catch(String file, int line, boolean rethrows, boolean explained) {}

    /** A top-level type's file and its length in lines. */
    public record Source(String file, String type, int lines) {}

    /** The size rules: a source file and a method body (its braces included) at most this long. */
    public static final int CLASS_LINES = 400;
    /** The file size that fails the gate: the target plus 10 percent; between the two it only warns. */
    public static final int CLASS_LINES_FAIL = CLASS_LINES + CLASS_LINES / 10;

    public static final int METHOD_LINES = 30;

    private final List<Method> methods = new ArrayList<>();
    private final List<Catch> catches = new ArrayList<>();
    private final List<Source> sources = new ArrayList<>();

    private SourceScan() {}

    public static SourceScan of(List<Path> roots) {
        SourceScan scan = new SourceScan();
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) javac.getTask(null, fm, d -> {}, List.of("-proc:none"), null, units);
            Trees trees = Trees.instance(task);
            for (CompilationUnitTree unit : task.parse()) scan.read(unit, trees.getSourcePositions());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return scan;
    }

    public static SourceScan mainSources() {
        return of(ROOTS);
    }

    public List<Method> methods() {
        return methods;
    }

    public List<Catch> catches() {
        return catches;
    }

    public List<Source> sources() {
        return sources;
    }

    private void read(CompilationUnitTree unit, SourcePositions positions) {
        String text;
        try {
            text = unit.getSourceFile().getCharContent(true).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String file = Path.of(unit.getSourceFile().toUri()).getFileName().toString();
        var lines = unit.getLineMap();
        int total = (int) text.lines().count();
        for (Tree t : unit.getTypeDecls()) {
            if (t instanceof ClassTree c)
                sources.add(new Source(file, c.getSimpleName().toString(), total));
        }
        new TreePathScanner<Void, String>() {
            @Override
            public Void visitClass(ClassTree c, String owner) {
                String name = c.getSimpleName().toString();
                String inner = owner == null ? name : name.isEmpty() ? owner + ".$anon" : owner + "." + name;
                return super.visitClass(c, inner);
            }

            @Override
            public Void visitMethod(MethodTree m, String owner) {
                if (m.getBody() != null) {
                    long start = positions.getStartPosition(unit, m.getBody());
                    long end = positions.getEndPosition(unit, m.getBody());
                    int first = (int) lines.getLineNumber(start);
                    int last = (int) lines.getLineNumber(end);
                    methods.add(new Method(file, owner, m.getName().toString(), first, last - first + 1));
                }
                return super.visitMethod(m, owner);
            }

            @Override
            public Void visitCatch(CatchTree c, String owner) {
                long start = positions.getStartPosition(unit, c.getBlock());
                long end = positions.getEndPosition(unit, c.getBlock());
                boolean[] rethrows = {false};
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitThrow(ThrowTree t, Void v) {
                        rethrows[0] = true;
                        return null;
                    }

                    @Override
                    public Void visitClass(ClassTree k, Void v) {
                        return null; // a throw in a nested class or lambda is not this catch's
                    }

                    @Override
                    public Void visitLambdaExpression(LambdaExpressionTree l, Void v) {
                        return null;
                    }
                }.scan(c.getBlock(), null);
                boolean explained = text.substring((int) start, (int) end).contains("// Handled:");
                catches.add(new Catch(file, (int) lines.getLineNumber(start), rethrows[0], explained));
                return super.visitCatch(c, owner);
            }
        }.scan(unit, null);
    }

    /** Prints what the gates would refuse with empty allow-lists. */
    public static void main(String[] args) {
        SourceScan scan = mainSources();
        int limit = args.length > 0 ? Integer.parseInt(args[0]) : METHOD_LINES;
        scan.sources.stream()
                .filter(s -> s.lines() > CLASS_LINES)
                .forEach(s -> System.out.println("CLASS " + s.lines() + " " + s.file()));
        scan.methods.stream()
                .filter(m -> m.lines() > limit)
                .sorted((a, b) -> Integer.compare(b.lines(), a.lines()))
                .forEach(m ->
                        System.out.println("METHOD " + m.lines() + " " + m.key() + " " + m.file() + ":" + m.line()));
        scan.catches.stream()
                .filter(c -> !c.rethrows() && !c.explained())
                .forEach(c -> System.out.println("CATCH " + c.file() + ":" + c.line()));
    }
}
