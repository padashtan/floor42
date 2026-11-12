package dev.floor42.server.app;

import java.util.List;

/**
 * Build and cache artefacts no agent means to deliver: an acceptance in the workshop leaves them out (they stay in the
 * worktree, listed apart) unless the repository already tracks files of the kind. What the repository ignores never
 * shows. Matched in Java: Floor42's git takes every pathspec literally ({@code --literal-pathspecs}).
 */
final class BuildArtefacts {

    /**
     * Caches only: a kind that may be a deliverable ({@code .venv}, {@code *.class}, {@code dist/}) is left to the
     * repository's {@code .gitignore}. A trailing slash names a folder anywhere in the tree, a leading star a file extension, anything else a name.
     */
    static final List<String> KINDS = List.of(
            "__pycache__/",
            "*.pyc",
            "*.pyo",
            ".pytest_cache/",
            ".mypy_cache/",
            ".ruff_cache/",
            ".tox/",
            "node_modules/",
            ".gradle/",
            ".coverage",
            ".DS_Store",
            "Thumbs.db");

    private BuildArtefacts() {}

    /** The kinds of which none of these tracked paths is one: a kind the repository tracks is committed as any file. */
    static List<String> untrackedKinds(List<String> tracked) {
        return KINDS.stream()
                .filter(k -> tracked.stream().noneMatch(p -> isOne(List.of(k), p)))
                .toList();
    }

    /** Whether the path is of one of these kinds. */
    static boolean isOne(List<String> kinds, String path) {
        String p = "/" + path.replace('\\', '/');
        for (String k : kinds) {
            boolean match = k.endsWith("/")
                    ? p.contains("/" + k)
                    : k.startsWith("*") ? p.endsWith(k.substring(1)) : p.endsWith("/" + k);
            if (match) return true;
        }
        return false;
    }
}
