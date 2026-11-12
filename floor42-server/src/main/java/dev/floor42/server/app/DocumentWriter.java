package dev.floor42.server.app;

import dev.floor42.domain.DeliveryCopy;
import dev.floor42.server.runtime.ConfinedPath;
import dev.floor42.server.runtime.Confinement;
import dev.floor42.server.runtime.OutsideWorkspace;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes a mission's documents into its folder of the destination (ADR-0051), never over a file: each one goes to a
 * temporary name created new, then is moved to a name that is free (moving never replaces). The same bytes already
 * there: nothing written. Other bytes: written beside them as {@code name (2).ext}. Every outcome is kept for the
 * journal.
 *
 * <p>Documents only: what an agent wrote that a tool could run or read as instructions (code, tests, configuration,
 * scripts, {@code CLAUDE.md}, anything under a dot folder) is left out, so a copy never adds to what runs in the
 * director's project.
 */
final class DocumentWriter {

    /**
     * What a person reads: prose, tables, images, PDFs and office documents without macros. Never SVG nor HTML: both can
     * carry scripts once opened from the project folder.
     */
    static final Set<String> DOCUMENTS = Set.of(
            "md",
            "markdown",
            "txt",
            "csv",
            "tsv",
            "pdf",
            "png",
            "jpg",
            "jpeg",
            "gif",
            "webp",
            "docx",
            "xlsx",
            "pptx",
            "odt",
            "ods",
            "odp",
            "rtf");

    /** Prose that coding agents read as their instructions when they work in a folder. */
    private static final Set<String> AGENT_INSTRUCTIONS = Set.of(
            "claude.md",
            "claude.local.md",
            "agent.md",
            "agents.md",
            "gemini.md",
            "copilot-instructions.md",
            ".cursorrules");

    /** Folders agents load commands, skills, sub-agents or hooks from, wherever they sit. */
    private static final Set<String> AGENT_FOLDERS = Set.of("agents", "commands", "skills", "hooks");

    /** Names Windows keeps for devices, with any extension: never a file. */
    private static final Pattern DEVICE = Pattern.compile("con|prn|aux|nul|com[0-9]|lpt[0-9]");

    private final Path folder;
    private final Path real;
    private final long maxFile;
    private final long maxTotal;
    private long total;
    private final List<DeliveryCopy> outcomes = new ArrayList<>();

    DocumentWriter(Path folder, long maxFile, long maxTotal) {
        this.folder = folder;
        this.maxFile = maxFile;
        this.maxTotal = maxTotal;
        try {
            this.real = folder.toRealPath();
        } catch (IOException e) {
            throw DeliveryDestination.refused("Cannot read " + folder + ": " + e.getMessage(), e);
        }
    }

    /** The mission's folder in the destination, created if needed: {@code <home>/<mission>}. */
    static Path prepare(Path home, String mission) {
        Path folder = home.resolve(mission);
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw DeliveryDestination.refused("Cannot create " + folder + ": " + e.getMessage(), e);
        }
        Path expected = DeliveryDestination.realOfNearest(home).resolve(mission);
        if (Files.isSymbolicLink(folder)
                || !DeliveryDestination.realOfNearest(folder).equals(expected)) {
            throw DeliveryDestination.refused(folder + " leads elsewhere through a link");
        }
        return folder;
    }

    Path folder() {
        return folder;
    }

    List<DeliveryCopy> outcomes() {
        return List.copyOf(outcomes);
    }

    /** Why a path is not a document to copy, or empty when it is one. */
    static Optional<String> notADocument(String path) {
        String p = path.replace('\\', '/');
        String[] segments = p.split("/");
        for (int i = 0; i < segments.length; i++) {
            String s = segments[i];
            String lower = s.toLowerCase(Locale.ROOT);
            if (s.startsWith(".")) return Optional.of("in a dot folder or a dot file: tools read those");
            if (device(lower) || s.endsWith(" ") || s.endsWith("."))
                return Optional.of("a name Windows does not allow");
            if (i < segments.length - 1 && AGENT_FOLDERS.contains(lower)) {
                return Optional.of("in a folder agents load commands, skills or hooks from");
            }
        }
        String name = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        if (AGENT_INSTRUCTIONS.contains(name)) return Optional.of("instructions an agent would follow");
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        if (!DOCUMENTS.contains(ext)) return Optional.of("not a document: code, configuration and scripts stay out");
        return Optional.empty();
    }

    /** {@code CON}, {@code nul.txt}, {@code CON .md}: Windows reads the part before the first dot, trailing spaces and dots dropped. */
    static boolean device(String lowerName) {
        String base = lowerName.split("\\.", 2)[0].replaceAll("[ .]+$", "");
        return DEVICE.matcher(base).matches();
    }

    /** Why the bytes are not what the extension says, or empty: a PDF, an image or a zip office file by its magic, text by its lack of NUL. */
    static Optional<String> notWhatItSays(String path, Path bytes) throws IOException {
        String name = path.toLowerCase(Locale.ROOT);
        String ext = name.substring(name.lastIndexOf('.') + 1);
        byte[] h;
        try (InputStream in = Files.newInputStream(bytes, LinkOption.NOFOLLOW_LINKS)) {
            h = in.readNBytes(8192);
        }
        boolean ok = switch (ext) {
            case "pdf" -> starts(h, "%PDF-");
            case "png" -> h.length >= 4 && (h[0] & 0xff) == 0x89 && starts(h, 1, "PNG");
            case "jpg", "jpeg" ->
                h.length >= 3 && (h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8 && (h[2] & 0xff) == 0xff;
            case "gif" -> starts(h, "GIF8");
            case "webp" -> starts(h, "RIFF") && starts(h, 8, "WEBP");
            case "docx", "xlsx", "pptx", "odt", "ods", "odp" -> starts(h, "PK\u0003\u0004");
            case "rtf" -> starts(h, "{\\rtf");
            default -> text(h);
        };
        return ok ? Optional.empty() : Optional.of("its content is not a ." + ext + " file");
    }

    private static boolean starts(byte[] h, String prefix) {
        return starts(h, 0, prefix);
    }

    private static boolean starts(byte[] h, int at, String prefix) {
        if (h.length < at + prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (h[at + i] != (byte) prefix.charAt(i)) return false;
        return true;
    }

    private static boolean text(byte[] h) {
        for (byte b : h) if (b == 0) return false;
        return true;
    }

    /** Whether a file of this size still fits the caps (an unknown size is checked once read). */
    boolean fits(Long size) {
        return size == null || (size <= maxFile && total + size <= maxTotal);
    }

    void leftOut(String agent, String path, String why) {
        outcomes.add(new DeliveryCopy(agent, path, null, DeliveryCopy.LEFT_OUT, why));
    }

    /** Copies {@code bytes} (a file Floor42 read itself) to {@code path} under the mission's folder. */
    void write(String agent, String path, Path bytes, long size) {
        if (!fits(size)) {
            leftOut(agent, path, "past the size caps (" + maxFile + " bytes a file)");
            return;
        }
        Path temp = null;
        try {
            ConfinedPath target = Confinement.relative(folder, path, ConfinedPath.Mode.WRITE);
            Path file = target.file();
            Optional<String> why = refusal(path, bytes, file);
            if (why.isPresent()) {
                leftOut(agent, path, why.get());
                return;
            }
            temp = file.resolveSibling(".floor42-" + UUID.randomUUID() + ".part");
            Files.copy(bytes, temp, LinkOption.NOFOLLOW_LINKS);
            outcomes.add(place(agent, path, temp, file));
            total += size;
        } catch (OutsideWorkspace e) {
            // Handled: a path the confinement refuses is never written; the reason is journaled
            leftOut(agent, path, e.getMessage());
        } catch (IOException e) {
            // Handled: a file that cannot be written is left out with the reason; the others are still copied
            leftOut(agent, path, "cannot be written: " + e.getMessage());
        } finally {
            discard(temp);
        }
    }

    /**
     * Why the file may not be written there: its bytes are not what its extension says, or a link or junction appeared
     * between the mission's folder and the file's folder once that folder was created.
     */
    private Optional<String> refusal(String path, Path bytes, Path file) throws IOException {
        Optional<String> wrong = notWhatItSays(path, bytes);
        if (wrong.isPresent()) return wrong;
        Files.createDirectories(file.getParent());
        if (!file.getParent().toRealPath().equals(real.resolve(folder.relativize(file.getParent())))) {
            return Optional.of("its folder leads elsewhere through a link");
        }
        return Optional.empty();
    }

    /** Moves the written bytes to a free name: never over a file, never in silence. */
    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop") // retried only through the continue above
    private DeliveryCopy place(String agent, String path, Path temp, Path file) throws IOException {
        for (int n = 1; n < 1000; n++) {
            Path candidate = n == 1
                    ? file
                    : file.resolveSibling(numbered(file.getFileName().toString(), n));
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        && Files.mismatch(candidate, temp) == -1) {
                    return new DeliveryCopy(agent, path, relative(candidate), DeliveryCopy.SAME, null);
                }
                continue;
            }
            try {
                // No REPLACE_EXISTING and no ATOMIC_MOVE (which replaces on Windows): a name taken meanwhile fails.
                Files.move(temp, candidate);
            } catch (FileAlreadyExistsException e) {
                // Handled: the name was taken between the check and the move: the next free one is tried
                continue;
            }
            String outcome = n == 1 ? DeliveryCopy.WRITTEN : DeliveryCopy.KEPT_BOTH;
            return new DeliveryCopy(agent, path, relative(candidate), outcome, null);
        }
        return new DeliveryCopy(agent, path, null, DeliveryCopy.LEFT_OUT, "no free name beside the existing files");
    }

    /** {@code report.pdf} becomes {@code report (2).pdf}. */
    static String numbered(String name, int n) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name + " (" + n + ")" : name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
    }

    private String relative(Path file) {
        return folder.relativize(file).toString().replace('\\', '/');
    }

    private static void discard(Path temp) {
        if (temp == null) return;
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            // Handled: a temporary file left behind holds a copy of a document only; it is named .floor42-*.part
        }
    }
}
