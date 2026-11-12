package dev.floor42.server.iam;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/** The table scan: a table that cannot be read is told apart from a table with no matching row. */
class WindowsSocketOwnersScanTest {

    static final int ESTABLISHED = WindowsSocketOwners.ESTABLISHED;

    /** A one-row IPv4 table: state, local address and port, remote address and port (network order), pid. */
    private static MemorySegment table(
            Arena arena, int count, int rowsWritten, int localPort, int remotePort, int pid) {
        MemorySegment t = arena.allocate(4L + (long) rowsWritten * WindowsSocketOwners.ROW4);
        t.set(JAVA_INT, 0, count);
        if (rowsWritten > 0) {
            t.set(JAVA_INT, 4, ESTABLISHED);
            t.set(JAVA_INT, 8, 0x0100007F);
            t.set(JAVA_INT, 12, WindowsSocketOwners.port(localPort));
            t.set(JAVA_INT, 16, 0x0100007F);
            t.set(JAVA_INT, 20, WindowsSocketOwners.port(remotePort));
            t.set(JAVA_INT, 24, pid);
        }
        return t;
    }

    @Test
    void aMatchingRowGivesItsPidAndNoRowGivesAnEmptyArrayNotAnUnreadableTable() throws Exception {
        InetSocketAddress client = new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 50000);
        InetSocketAddress server = new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 7420);
        try (Arena arena = Arena.ofConfined()) {
            var hit = WindowsSocketOwners.scan(table(arena, 1, 1, 50000, 7420, 4242), false, client, server);
            assertThat(hit).isPresent();
            assertThat(hit.get()).containsExactly(4242L);
            var miss = WindowsSocketOwners.scan(table(arena, 1, 1, 50001, 7420, 4242), false, client, server);
            assertThat(miss).isPresent();
            assertThat(miss.get()).isEmpty();
        }
    }

    @Test
    void aTableShorterThanItsCountCannotBeReadAndSaysSo() throws Exception {
        InetSocketAddress any = new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 1);
        try (Arena arena = Arena.ofConfined()) {
            assertThat(WindowsSocketOwners.scan(table(arena, 3, 1, 1, 1, 7), false, any, any))
                    .isEmpty();
        }
    }
}
