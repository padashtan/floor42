package dev.floor42.server.iam;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.OptionalLong;

/**
 * The process that owns the client end of a loopback TCP connection to the daemon: the proof that a hook or MCP call
 * comes from the session's own Claude Code process, not from a child that read its token. Empty means unknown, and
 * unknown is refused (fail closed).
 */
public interface SocketOwners {

    /**
     * @param client the connection's remote address as the daemon sees it (the caller's end)
     * @param server the connection's local address as the daemon sees it (the address and port the caller reached)
     * @return the pid owning the caller's end, or empty when it cannot be told for certain
     */
    OptionalLong owner(InetSocketAddress client, InetSocketAddress server);

    /**
     * True when a loopback connection opened by this very process is found to be owned by it. Both ends are ours, so
     * this self-test cannot detect a server row taken for the client row: the child process test covers that mix-up.
     */
    default boolean supported() {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var client = new Socket()) {
            client.connect(server.getLocalSocketAddress(), 2000);
            try (var accepted = server.accept()) {
                OptionalLong pid = owner((InetSocketAddress) accepted.getRemoteSocketAddress(), (InetSocketAddress)
                        accepted.getLocalSocketAddress());
                return pid.isPresent()
                        && pid.getAsLong() == ProcessHandle.current().pid();
            }
        } catch (IOException | RuntimeException e) {
            // Handled: a lookup that cannot even find its own socket is unsupported, and IAM mode refuses to start
            return false;
        }
    }

    /** The lookup of this operating system: Windows (TCP table) and Linux ({@code /proc}); none elsewhere. */
    static SocketOwners forPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.startsWith("windows")) return new WindowsSocketOwners();
        if (os.startsWith("linux")) return new LinuxSocketOwners();
        return new SocketOwners() {
            @Override
            public OptionalLong owner(InetSocketAddress client, InetSocketAddress server) {
                return OptionalLong.empty();
            }

            @Override
            public boolean supported() {
                return false;
            }
        };
    }

    /** An address as raw bytes, an IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) reduced to its 4 bytes. */
    static byte[] canonical(byte[] address) {
        if (address.length != 16) return address;
        for (int i = 0; i < 10; i++) if (address[i] != 0) return address;
        if (address[10] != (byte) 0xFF || address[11] != (byte) 0xFF) return address;
        return Arrays.copyOfRange(address, 12, 16);
    }
}
