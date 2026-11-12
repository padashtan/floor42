package dev.floor42.server.runtime.pty;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the program to start the way a shell would, in the PATH of the environment given to it (with PATHEXT on
 * Windows): a pseudo-terminal starts a file, not a name.
 */
final class Executables {

    private Executables() {}

    /** The command with its first element made an absolute path to an existing file. */
    static List<String> resolve(List<String> command, Map<String, String> environment) throws IOException {
        if (command.isEmpty()) throw new IOException("No command to start");
        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, find(command.getFirst(), environment).toString());
        // A later element naming an existing file (a script given to an interpreter) is made absolute too: the program
        // runs in another directory.
        for (int i = 1; i < resolved.size(); i++) {
            String arg = resolved.get(i);
            if (!arg.startsWith("-") && Files.isRegularFile(Path.of(arg))) {
                resolved.set(i, Path.of(arg).toAbsolutePath().toString());
            }
        }
        return resolved;
    }

    static Path find(String name, Map<String, String> environment) throws IOException {
        boolean windows = Pty.windows();
        List<String> extensions = windows ? extensions(value(environment, "PATHEXT")) : List.of("");
        Path given = Path.of(name);
        if (given.isAbsolute() || name.contains("/") || name.contains(File.separator)) {
            for (String ext : withPlain(extensions, name)) {
                Path p = Path.of(name + ext).toAbsolutePath();
                if (runnable(p)) return p;
            }
            throw new IOException("No program at " + name);
        }
        String path = value(environment, "PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                for (String ext : withPlain(extensions, name)) {
                    Path p;
                    try {
                        p = Path.of(dir.strip().replace("\"", ""), name + ext);
                    } catch (RuntimeException e) {
                        // Handled: an entry of PATH that is no path is skipped
                        continue;
                    }
                    if (runnable(p)) return p.toAbsolutePath();
                }
            }
        }
        throw new IOException(name + " is not on the PATH");
    }

    /** On Windows a name already ending with a known extension is tried as is, otherwise each extension in turn. */
    private static List<String> withPlain(List<String> extensions, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (extensions.stream().anyMatch(e -> !e.isEmpty() && lower.endsWith(e))) return List.of("");
        return extensions;
    }

    private static List<String> extensions(String pathext) {
        String value = pathext == null || pathext.isBlank() ? ".COM;.EXE;.BAT;.CMD" : pathext;
        List<String> list = new ArrayList<>();
        for (String e : value.split(";")) {
            if (!e.isBlank()) list.add(e.strip().toLowerCase(Locale.ROOT));
        }
        return list;
    }

    private static boolean runnable(Path p) {
        return Files.isRegularFile(p) && (Pty.windows() || Files.isExecutable(p));
    }

    /** Environment names are case-insensitive on Windows. */
    static String value(Map<String, String> environment, String name) {
        for (var e : environment.entrySet()) {
            if (Pty.windows() ? e.getKey().equalsIgnoreCase(name) : e.getKey().equals(name)) return e.getValue();
        }
        return null;
    }
}
