package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.MAC;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;

class SocketOwnersTest {

    @Test
    @EnabledOnOs({WINDOWS, LINUX})
    void ownerOfALoopbackConnectionIsThisProcess() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var client = new Socket()) {
            client.connect(server.getLocalSocketAddress());
            var accepted = server.accept();
            assertThat(SocketOwners.forPlatform()
                            .owner((InetSocketAddress) accepted.getRemoteSocketAddress(), (InetSocketAddress)
                                    accepted.getLocalSocketAddress()))
                    .hasValue(ProcessHandle.current().pid());
            accepted.close();
        }
        assertThat(SocketOwners.forPlatform().supported()).isTrue();
    }

    @Test
    @EnabledOnOs({WINDOWS, LINUX})
    void ownerOfAChildProcessConnectionIsTheChild() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String java = ProcessHandle.current().info().command().orElseThrow();
            Process child = new ProcessBuilder(
                            java,
                            "-cp",
                            System.getProperty("java.class.path"),
                            ChildConnect.class.getName(),
                            String.valueOf(server.getLocalPort()))
                    .start();
            try (var accepted = server.accept()) {
                assertThat(SocketOwners.forPlatform()
                                .owner((InetSocketAddress) accepted.getRemoteSocketAddress(), (InetSocketAddress)
                                        accepted.getLocalSocketAddress()))
                        .hasValue(child.pid());
                assertThat(child.pid()).isNotEqualTo(ProcessHandle.current().pid());
            } finally {
                child.getOutputStream().close();
                child.waitFor(10, TimeUnit.SECONDS);
                child.destroyForcibly();
            }
        }
    }

    @Test
    @EnabledOnOs({WINDOWS, LINUX})
    void aPortNobodyHoldsHasNoOwner() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertThat(SocketOwners.forPlatform()
                            .owner(loopback(1), (InetSocketAddress) server.getLocalSocketAddress()))
                    .isEmpty();
        }
    }

    @Test
    @EnabledOnOs(MAC)
    void unsupportedPlatformSaysSo() {
        assertThat(SocketOwners.forPlatform().supported()).isFalse();
    }

    @Test
    void anIpv4MappedAddressIsItsFourBytes() {
        byte[] mapped = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 127, 0, 0, 1};
        assertThat(SocketOwners.canonical(mapped)).containsExactly(127, 0, 0, 1);
        assertThat(SocketOwners.canonical(new byte[16])).hasSize(16);
    }

    /** The Linux table parser on planted rows: the client side row matches, the daemon's own row never does. */
    @Test
    void theLinuxTableGivesTheClientSideRowOnly() {
        List<String> tcp = List.of(
                "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
                "   0: 0100007F:1F90 0100007F:C350 01 00000000:00000000 00:00000000 00000000  1000        0 111",
                "   1: 0100007F:C350 0100007F:1F90 01 00000000:00000000 00:00000000 00000000  1000        0 222",
                "   2: 0100007F:C351 0100007F:1F90 06 00000000:00000000 00:00000000 00000000  1000        0 333",
                "   3: 0100007F:C352 0200007F:1F90 01 00000000:00000000 00:00000000 00000000  1000        0 444");
        var server = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0x1F90);
        assertThat(LinuxSocketOwners.inodes(tcp, loopback(0xC350), server)).containsExactly("222");
        assertThat(LinuxSocketOwners.inodes(tcp, loopback(0xC351), server)).isEmpty();
        assertThat(LinuxSocketOwners.inodes(tcp, loopback(0xC352), server))
                .as("same ports, another remote address: not a connection to the daemon")
                .isEmpty();
        assertThat(LinuxSocketOwners.address("0000000000000000FFFF00000100007F"))
                .containsExactly(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 127, 0, 0, 1);
    }

    @Test
    void aSocketHeldByTwoProcessesHasNoOwner() {
        assertThat(LinuxSocketOwners.only(List.of(10L, 11L))).isEmpty();
        assertThat(LinuxSocketOwners.only(List.of())).isEmpty();
        assertThat(LinuxSocketOwners.only(List.of(10L))).hasValue(10L);
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
