package dev.floor42.server.iam;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;

/**
 * The owner of a loopback connection on Linux: the client side row of {@code /proc/net/tcp} and {@code tcp6} gives the
 * socket's inode, and the process holding {@code socket:[inode]} among its descriptors is the owner. Any read failure,
 * no row, or several rows give empty.
 */
final class LinuxSocketOwners implements SocketOwners {

    private static final String ESTABLISHED = "01";

    private final Path proc;

    LinuxSocketOwners() {
        this(Path.of("/proc"));
    }

    LinuxSocketOwners(Path proc) {
        this.proc = proc;
    }

    @Override
    public OptionalLong owner(InetSocketAddress client, InetSocketAddress server) {
        if (client.getAddress() == null || server.getAddress() == null) return OptionalLong.empty();
        try {
            List<String> inodes = new ArrayList<>();
            for (String table : List.of("net/tcp", "net/tcp6"))
                inodes.addAll(inodes(Files.readAllLines(proc.resolve(table)), client, server));
            return inodes.size() == 1 ? holder("socket:[" + inodes.getFirst() + "]") : OptionalLong.empty();
        } catch (IOException | RuntimeException e) {
            // Handled: an unreadable table is an unknown owner, and an unknown owner is refused
            return OptionalLong.empty();
        }
    }

    /** The inodes of the client side rows: local end = the caller's address and port, remote end = the daemon's. */
    static List<String> inodes(List<String> lines, InetSocketAddress client, InetSocketAddress server) {
        byte[] clientAddress = SocketOwners.canonical(client.getAddress().getAddress());
        byte[] serverAddress = SocketOwners.canonical(server.getAddress().getAddress());
        List<String> found = new ArrayList<>();
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 10 || !f[3].equals(ESTABLISHED)) continue;
            String[] local = f[1].split(":"), remote = f[2].split(":");
            if (Integer.parseInt(local[1], 16) != client.getPort()
                    || Integer.parseInt(remote[1], 16) != server.getPort()) continue;
            if (!Arrays.equals(SocketOwners.canonical(address(local[0])), clientAddress)) continue;
            if (!Arrays.equals(SocketOwners.canonical(address(remote[0])), serverAddress)) continue;
            if (!f[9].equals("0")) found.add(f[9]);
        }
        return found;
    }

    /** A {@code /proc/net} address: 32-bit words, each in host (little-endian) order. */
    static byte[] address(String hex) {
        byte[] raw = HexFormat.of().parseHex(hex);
        for (int w = 0; w < raw.length; w += 4) {
            byte b0 = raw[w], b1 = raw[w + 1];
            raw[w] = raw[w + 3];
            raw[w + 1] = raw[w + 2];
            raw[w + 2] = b1;
            raw[w + 3] = b0;
        }
        return raw;
    }

    /**
     * The one process holding the socket: every pid is scanned, and a socket held by several (a child that inherited
     * it) has no single owner: empty.
     */
    OptionalLong holder(String link) throws IOException {
        List<Long> holders = new ArrayList<>();
        try (DirectoryStream<Path> pids = Files.newDirectoryStream(proc, "[0-9]*")) {
            for (Path pid : pids)
                if (holds(pid.resolve("fd"), link))
                    holders.add(Long.parseLong(pid.getFileName().toString()));
        }
        return only(holders);
    }

    static OptionalLong only(List<Long> holders) {
        return holders.size() == 1 ? OptionalLong.of(holders.getFirst()) : OptionalLong.empty();
    }

    private static boolean holds(Path fds, String link) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(fds)) {
            for (Path fd : entries) if (link.equals(readLink(fd))) return true;
            return false;
        } catch (IOException | SecurityException | DirectoryIteratorException | UncheckedIOException e) {
            // Handled: another user's process (or one that just ended) cannot be read: it is not the owner looked for
            return false;
        }
    }

    private static String readLink(Path fd) {
        try {
            return Files.readSymbolicLink(fd).toString();
        } catch (IOException e) {
            // Handled: a descriptor closed while scanned is not the socket looked for
            return "";
        }
    }
}
