package dev.floor42.server.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.sqlite.SQLiteConfig;

/**
 * The data-folder half of {@code floor42 doctor}: is the folder writable and roomy, is the journal sound, is there a
 * recent backup, is the daemon's port free or Floor42's own. It reads and measures; it changes nothing (the journal is
 * opened read-only). A warning never fails the doctor's exit code: the machine checklist alone decides that.
 */
public final class DataChecks {

    /** Below this many free bytes the data folder warns: a journal, its backups and the worktrees need room. */
    public static final long MIN_FREE_BYTES = 1L << 30;

    /** A backup older than this warns: the daemon copies the journal at every start and once a day. */
    public static final Duration MAX_BACKUP_AGE = Duration.ofDays(2);

    public static final int PORT = 7420;

    public enum State {
        OK,
        WARN
    }

    public record Item(State state, String name, String detail) {}

    public enum PortState {
        FREE,
        FLOOR42,
        /** In use, and /api/status does not answer 200: a daemon still starting, or another program. */
        BUSY
    }

    /** The port seam: the real one binds, then asks /api/status; tests give a fixed answer. */
    public interface Port {
        PortState probe(int port);
    }

    private DataChecks() {}

    public static List<Item> check(Path data, Path backups, Instant now, long minFreeBytes, Port port) {
        List<Item> items = new ArrayList<>();
        items.add(folder(data, minFreeBytes));
        PortState state = port.probe(PORT);
        // A read lock on the live journal (rollback-journal mode) can hold up the daemon's commits past its busy
        // timeout: while anything answers on the port, only the newest backup copy is checked.
        if (state == PortState.FREE) items.add(journal(data.resolve("floor42.db"), "Journal"));
        else {
            Path copy = newestBackup(backups);
            items.add(
                    copy == null
                            ? new Item(State.OK, "Journal", "not checked while Floor42 may run (no backup copy yet)")
                            : journal(copy, "Journal (newest backup copy " + copy.getFileName() + ")"));
        }
        items.add(backup(backups, now));
        items.add(port(state));
        return items;
    }

    private static Path newestBackup(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".db"))
                    .max(java.util.Comparator.comparingLong(f -> f.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            // Handled: no copy to check; the backup line says why
            return null;
        }
    }

    public static String text(List<Item> items) {
        StringBuilder s = new StringBuilder("\nData folder and daemon (a warning does not change the exit code):\n");
        for (Item i : items) {
            s.append("  ")
                    .append(i.state() == State.OK ? "[ok]      " : "[WARN]    ")
                    .append(i.name())
                    .append(": ")
                    .append(i.detail())
                    .append('\n');
        }
        return s.toString();
    }

    private static Item folder(Path data, long minFree) {
        String name = "Data folder " + data;
        if (!Files.isDirectory(data))
            return new Item(State.OK, name, "not created yet (Floor42 makes it at its start)");
        try {
            Files.delete(Files.createTempFile(data, "doctor-", ".tmp"));
            long free = Files.getFileStore(data).getUsableSpace();
            String gib = String.format("%.1f GiB free", free / (double) (1L << 30));
            return free < minFree
                    ? new Item(
                            State.WARN, name, "writable, but only " + gib + ": the journal, backups and worktrees grow")
                    : new Item(State.OK, name, "writable, " + gib);
        } catch (IOException e) {
            // Handled: reported as a warning line of the doctor, never fatal.
            return new Item(State.WARN, name, "not writable: " + e.getMessage());
        }
    }

    /** {@code PRAGMA quick_check} on a read-only connection: the journal is not touched. */
    private static Item journal(Path db, String name) {
        if (!Files.isRegularFile(db)) return new Item(State.OK, name, "none yet");
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db, config.toProperties());
                Statement st = c.createStatement();
                ResultSet r = st.executeQuery("PRAGMA quick_check")) {
            String first = r.next() ? r.getString(1) : "no answer";
            return first.equals("ok")
                    ? new Item(State.OK, name, "quick_check ok")
                    : new Item(State.WARN, name, "quick_check says: " + first + " (restore a backup: docs/backups.md)");
        } catch (SQLException e) {
            // Handled: reported as a warning line of the doctor, never fatal.
            return new Item(State.WARN, name, "cannot be checked: " + e.getMessage());
        }
    }

    private static Item backup(Path dir, Instant now) {
        String name = "Last backup";
        Instant newest = null;
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                newest = files.filter(f -> f.getFileName().toString().endsWith(".db"))
                        .map(f -> f.toFile().lastModified())
                        .filter(t -> t > 0)
                        .max(Long::compare)
                        .map(Instant::ofEpochMilli)
                        .orElse(null);
            } catch (IOException e) {
                // Handled: reported as a warning line of the doctor, never fatal.
                return new Item(State.WARN, name, "backup folder unreadable: " + e.getMessage());
            }
        }
        if (newest == null) return new Item(State.WARN, name, "no backup in " + dir);
        Duration age = Duration.between(newest, now);
        String when = newest + " (" + age.toHours() + " h ago)";
        return age.compareTo(MAX_BACKUP_AGE) > 0
                ? new Item(State.WARN, name, when + ", older than 2 days: copy backups/ off the machine too")
                : new Item(State.OK, name, when);
    }

    private static Item port(PortState state) {
        String name = "Port " + PORT;
        return switch (state) {
            case FREE -> new Item(State.OK, name, "free");
            case FLOOR42 -> new Item(State.OK, name, "used by Floor42 itself");
            case BUSY ->
                new Item(
                        State.WARN,
                        name,
                        "port in use (Floor42 starting, or another program): Floor42 cannot start while it is");
        };
    }

    /** Binds the loopback port; when that fails, asks {@code /api/status} whether Floor42 is the one holding it. */
    public static PortState realPort(int port) {
        try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return PortState.FREE;
        } catch (IOException busy) {
            // Handled: busy is the answer; whose it is comes from the status request below
            try {
                HttpResponse<Void> r = HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                                        .timeout(Duration.ofSeconds(2))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
                return r.statusCode() == 200 ? PortState.FLOOR42 : PortState.BUSY;
            } catch (IOException e) {
                // Handled: no HTTP answer on a bound port: something else holds it, or Floor42 is starting.
                return PortState.BUSY;
            } catch (InterruptedException e) {
                // Handled: the doctor is being stopped; the flag is restored and the port counted busy.
                Thread.currentThread().interrupt();
                return PortState.BUSY;
            }
        }
    }
}
