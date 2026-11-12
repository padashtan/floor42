package dev.floor42.server.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * How the director's browser signs in without a token at rest (ADR-0034). Anyone on the machine may ask for a sign-in
 * link ({@code POST /auth/open}), but the link never goes back to the caller: the daemon opens it in the default
 * browser, or prints it on its own standard output in Docker, where processes inside the container cannot read it.
 * A link is single use and short-lived; redeeming it gives the browser its own session, kept in memory. A link used
 * twice means someone else used it first: the session it opened is revoked.
 */
@ApplicationScoped
public class DirectorSignIn {

    private static final Logger LOG = Logger.getLogger(DirectorSignIn.class);
    private static final String LINK_FILE = "sign-in-link";
    private static final int MAX_PENDING = 32;
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(2);

    public enum Delivery {
        /** Open the default browser (the director's own machine). */
        BROWSER,
        /** Print the link on the daemon's standard output (Docker: read with {@code docker compose logs}). */
        STDOUT,
        /** Keep it for the test that plays the browser. */
        NONE
    }

    @ConfigProperty(name = "floor42.sign-in.delivery", defaultValue = "browser")
    String delivery;

    @ConfigProperty(name = "floor42.sign-in.ttl-seconds", defaultValue = "60")
    long ttlSeconds;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    @jakarta.inject.Inject
    SessionRegistry registry;

    /** Null in the unit tests that build this class by hand. */
    @jakarta.inject.Inject
    jakarta.enterprise.event.Event<SessionsEnded> ended;

    /** @param session nullable: the secret of the browser session the code opened, once redeemed */
    private record Pending(Instant issued, String session) {}

    private final Map<String, Pending> codes = new LinkedHashMap<>();
    private Instant lastIssued = Instant.EPOCH;
    /** Test seam: no pause between two links (until {@link #clear}). */
    private Duration minInterval = MIN_INTERVAL;

    synchronized void noPause() {
        minInterval = Duration.ZERO;
    }

    private volatile String lastLink;
    private volatile Instant linkAt;

    /** Issues a link to {@code base}/auth?code=... and delivers it; false when asked again too soon or undeliverable. */
    public synchronized boolean open(String base) {
        Instant now = Instant.now();
        if (Duration.between(lastIssued, now).compareTo(minInterval) < 0) return false;
        lastIssued = now;
        purge(now);
        while (codes.size() >= MAX_PENDING) {
            Iterator<String> oldest = codes.keySet().iterator();
            oldest.next();
            oldest.remove();
            LOG.warn("Too many pending sign-in links: the oldest was dropped");
        }
        String code = SessionToken.random(24);
        codes.put(code, new Pending(now, null));
        String link = base + "/auth?code=" + code;
        if (!deliver(link)) {
            codes.remove(code);
            return false;
        }
        return true;
    }

    /** The browser's new session for a valid, unused code; empty otherwise (and a reused code revokes its session). */
    public synchronized Optional<String> redeem(String code, String userAgent) {
        if (code == null) return Optional.empty();
        purge(Instant.now());
        String known = codes.keySet().stream()
                .filter(c -> SessionToken.sameSecret(c, code))
                .findFirst()
                .orElse(null);
        if (known == null) return Optional.empty();
        Pending pending = codes.get(known);
        if (pending.session() != null) {
            codes.remove(known);
            registry.revokeSecret(pending.session());
            if (ended != null) ended.fire(new SessionsEnded());
            LOG.warn("A sign-in link was used twice: the session opened with it is revoked. Ask for a new link.");
            return Optional.empty();
        }
        dropLinkFileOf(known);
        String session = registry.openBreakGlass(userAgent).secret();
        codes.put(known, new Pending(pending.issued(), session));
        return Optional.of(session);
    }

    /**
     * Test seam (package-private, {@code LocalAccessGuardTest}): the last link issued with delivery {@code none}, which
     * only the test profile uses; tests play the browser with it. Never served.
     */
    public String lastLink() {
        return lastLink;
    }

    /** Test seam: forgets every link and the pause between two. */
    synchronized void clear() {
        codes.clear();
        lastIssued = Instant.EPOCH;
        minInterval = MIN_INTERVAL;
    }

    private void purge(Instant now) {
        // A redeemed code stays known for its lifetime, so a second use is noticed.
        codes.values().removeIf(p -> p.issued().plusSeconds(ttlSeconds).isBefore(now));
        if (codes.isEmpty()) dropLinkFile();
    }

    private boolean deliver(String link) {
        Delivery mode = Delivery.valueOf(delivery.toUpperCase(Locale.ROOT));
        switch (mode) {
            case NONE -> lastLink = link;
            case STDOUT -> {
                // Not through the logger: log files and the API console must never hold a live link.
                System.out.println("Floor42 sign-in link (single use, " + ttlSeconds + " s): " + link);
                System.out.flush();
            }
            case BROWSER -> {
                return openBrowser(link);
            }
        }
        return true;
    }

    /** Opens the link in the default browser; kept in a private file when none can be started. */
    private boolean openBrowser(String link) {
        if (noDesktop(System.getenv())) return keepInFile(link);
        try {
            Process opener = new ProcessBuilder(browserCommand(link))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // An opener that fails at once (xdg-open with no browser installed) has opened nothing.
            if (opener.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && opener.exitValue() != 0) {
                return keepInFile(link);
            }
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the link is kept in the file instead
            Thread.currentThread().interrupt();
            return keepInFile(link);
        } catch (IOException e) {
            // Handled: logged; the caller answers that no link could be opened
            LOG.warnf("Cannot open the browser for the sign-in link: %s", e.getMessage());
            return keepInFile(link);
        }
        return true;
    }

    /** Linux with no graphical session and no BROWSER (a headless server, WSL without a desktop): nothing can open it. */
    static boolean noDesktop(Map<String, String> env) {
        boolean linux =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
        return linux
                && List.of("DISPLAY", "WAYLAND_DISPLAY", "BROWSER").stream().allMatch(k -> blank(env.get(k)));
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    /**
     * No browser can be started: the link goes to a file only the daemon's user can read (mode 600), and the daemon
     * prints that file's path, never the link (it would reach {@code daemon.log}). The file is removed when the link is
     * redeemed or expires. False when the data folder is unknown or the file cannot be written.
     */
    boolean keepInFile(String link) {
        if (dataDir == null) return false;
        try {
            Path file = dataDir.resolve(LINK_FILE);
            Files.deleteIfExists(file);
            Files.createDirectories(dataDir);
            Files.createFile(
                    file,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, link + System.lineSeparator());
            // Gone when the link expires, whether or not anything asks again.
            linkAt = Instant.now();
            EXPIRY.schedule(
                    () -> {
                        // A newer link in the file has its own timer.
                        if (!linkAt.plusSeconds(ttlSeconds).isAfter(Instant.now())) dropLinkFile();
                    },
                    ttlSeconds + 1,
                    java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("Floor42 sign-in link (single use, " + ttlSeconds + " s) is in " + file);
            System.out.flush();
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            // Handled: logged; the caller answers that no link could be delivered
            LOG.warnf("Cannot keep the sign-in link in %s: %s", dataDir, e.getMessage());
            return false;
        }
    }

    private static final java.util.concurrent.ScheduledExecutorService EXPIRY =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sign-in-link-expiry");
                t.setDaemon(true);
                return t;
            });

    /** The file goes when it holds the redeemed link: a newer link in it is still valid. */
    void dropLinkFileOf(String code) {
        try {
            Path file = dataDir == null ? null : dataDir.resolve(LINK_FILE);
            if (file != null
                    && Files.isRegularFile(file)
                    && Files.readString(file).contains("code=" + code)) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            // Handled: the file holds a link that is spent or expires anyway
            LOG.debugf("Cannot read the sign-in link file: %s", e.getMessage());
        }
    }

    /** A stale file of a daemon that did not stop cleanly, and the file of this one on its way out. */
    @jakarta.annotation.PostConstruct
    @jakarta.annotation.PreDestroy
    void dropLinkFile() {
        try {
            if (dataDir != null) Files.deleteIfExists(dataDir.resolve(LINK_FILE));
        } catch (IOException e) {
            // Handled: the file holds a link that is spent or expired anyway
            LOG.debugf("Cannot remove the sign-in link file: %s", e.getMessage());
        }
    }

    static List<String> browserCommand(String link) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // rundll32 rather than "cmd /c start": no shell parses the link.
        if (os.startsWith("windows")) return List.of("rundll32", "url.dll,FileProtocolHandler", link);
        if (os.contains("mac")) return List.of("open", link);
        return List.of("xdg-open", link);
    }
}
