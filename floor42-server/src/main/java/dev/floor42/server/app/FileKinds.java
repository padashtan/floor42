package dev.floor42.server.app;

import java.util.Locale;
import java.util.Set;

/** What a delivered file is, from its name only: what the screen may try to show (the server checks the bytes again). */
public final class FileKinds {

    private FileKinds() {}

    private static final Set<String> TEXT = Set.of(
            "md",
            "markdown",
            "txt",
            "text",
            "log",
            "csv",
            "tsv",
            "json",
            "jsonl",
            "yaml",
            "yml",
            "toml",
            "ini",
            "cfg",
            "conf",
            "properties",
            "xml",
            "html",
            "htm",
            "css",
            "scss",
            "js",
            "mjs",
            "cjs",
            "ts",
            "tsx",
            "jsx",
            "vue",
            "java",
            "kt",
            "kts",
            "groovy",
            "gradle",
            "scala",
            "py",
            "rb",
            "go",
            "rs",
            "c",
            "h",
            "cpp",
            "hpp",
            "cs",
            "php",
            "sh",
            "bash",
            "ps1",
            "bat",
            "cmd",
            "sql",
            "tex",
            "bib",
            "adoc",
            "rst",
            "patch",
            "diff",
            "env",
            "gitignore",
            "editorconfig",
            "dockerfile",
            "mk",
            "lock");
    private static final Set<String> NAMED_TEXT =
            Set.of("readme", "license", "makefile", "dockerfile", "changelog", "notice", "authors", "contributing");
    private static final Set<String> IMAGE = Set.of("png", "jpg", "jpeg", "gif", "webp", "svg");

    /** TEXT, IMAGE, PDF or OTHER. An SVG is an image: shown only in an {@code <img>}, never as a page (ADR-0051). */
    public static String kind(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        if (dot < 0 && NAMED_TEXT.contains(lower)) return "TEXT";
        if (IMAGE.contains(ext)) return "IMAGE";
        if (ext.equals("pdf")) return "PDF";
        if (TEXT.contains(ext)) return "TEXT";
        return "OTHER";
    }
}
