package dev.floor42.server.app;

import dev.floor42.domain.DomainException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** What the workshop refuses to read as text or to accept: non-UTF-8 files and Claude configuration paths. */
final class WorkshopText {

    private WorkshopText() {}

    /** The text of these bytes; null for a binary file or a file that is not valid UTF-8 (saving it would destroy it). */
    static String text(byte[] bytes) {
        for (byte b : bytes) if (b == 0) return null;
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            // Handled: not valid UTF-8, so null tells the caller the file is not text
            return null;
        }
    }

    /** An existing file that is not UTF-8 text is never overwritten by the editor ({@code NOT_TEXT}). */
    static void refuseANonTextFile(Path file, String path) {
        try {
            if (Files.isRegularFile(file)
                    && Files.size(file) <= Workshop.MAX_FILE_BYTES
                    && text(Files.readAllBytes(file)) == null) {
                throw new DomainException("NOT_TEXT", path + " is not UTF-8 text: the editor would destroy it");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    static void refuseConfiguration(List<String> files) {
        dev.floor42.server.runtime.Confinement.configurationPath(files).ifPresent(p -> {
            throw new DomainException(
                    "PROTECTED_PATH",
                    p + " configures Claude Code or MCP: a shell may not have made it, so it is never accepted");
        });
    }
}
