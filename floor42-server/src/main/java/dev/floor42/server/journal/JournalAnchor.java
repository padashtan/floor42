package dev.floor42.server.journal;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** The last signed checkpoint kept in a file beside the journal, outside the database (ADR-0079). */
final class JournalAnchor {

    static final String FILE = "journal-checkpoint.properties";

    record Anchor(long sequence, String hash, String keyId) {}

    private JournalAnchor() {}

    static boolean exists(Path dataDir) {
        return Files.exists(dataDir.resolve(FILE), LinkOption.NOFOLLOW_LINKS);
    }

    /** @return null when there is none; an IOException when it is there and cannot be read */
    static Anchor read(Path dataDir) throws IOException {
        Path f = dataDir.resolve(FILE);
        if (!exists(dataDir)) return null;
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) throw new IOException(FILE + " is not a plain file");
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
            return new Anchor(
                    Long.parseLong(p.getProperty("sequence")), p.getProperty("hash"), p.getProperty("key-id"));
        } catch (RuntimeException e) {
            throw new IOException(FILE + " is malformed", e);
        }
    }

    static void write(Path dataDir, long sequence, String hash, String keyId) throws IOException {
        Properties p = new Properties();
        p.setProperty("sequence", Long.toString(sequence));
        p.setProperty("hash", hash);
        p.setProperty("key-id", keyId);
        Path partial = dataDir.resolve(FILE + ".partial");
        try (Writer w = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
            p.store(w, "The last signed checkpoint of Floor42's journal, kept outside the database");
        }
        Files.move(partial, dataDir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
    }

    static void forget(Path dataDir) throws IOException {
        Files.deleteIfExists(dataDir.resolve(FILE));
    }
}
