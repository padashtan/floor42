package dev.floor42.server.iam;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The owner of a loopback connection on Windows, from the TCP tables with owning pids ({@code GetExtendedTcpTable} in
 * {@code iphlpapi.dll}). Both tables are read: a dual-stack Java or Node socket connecting to 127.0.0.1 sits in the
 * IPv6 table under {@code ::ffff:127.0.0.1}. Exactly one matching row gives the pid; none or several give empty.
 */
final class WindowsSocketOwners implements SocketOwners {

    static final int AF_INET = 2, AF_INET6 = 23, TCP_TABLE_OWNER_PID_ALL = 5, NO_ERROR = 0, INSUFFICIENT = 122;
    static final int ESTABLISHED = 5, ROW4 = 24, ROW6 = 56;

    private final MethodHandle getExtendedTcpTable = load();

    @Override
    public OptionalLong owner(InetSocketAddress client, InetSocketAddress server) {
        if (getExtendedTcpTable == null || client.getAddress() == null || server.getAddress() == null)
            return OptionalLong.empty();
        Optional<long[]> v4 = rows(false, client, server);
        Optional<long[]> v6 = rows(true, client, server);
        // One table that cannot be read leaves the owner unproven, even if the other names exactly one.
        if (v4.isEmpty() || v6.isEmpty()) return OptionalLong.empty();
        long[] all = new long[v4.get().length + v6.get().length];
        System.arraycopy(v4.get(), 0, all, 0, v4.get().length);
        System.arraycopy(v6.get(), 0, all, v4.get().length, v6.get().length);
        return all.length == 1 && all[0] > 0 ? OptionalLong.of(all[0]) : OptionalLong.empty();
    }

    /** The pids of the matching client-side rows of one table; empty when the table could not be read. */
    private Optional<long[]> rows(boolean v6, InetSocketAddress client, InetSocketAddress server) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment size = arena.allocate(JAVA_INT);
            int rc = call(MemorySegment.NULL, size, v6);
            for (int tries = 0; tries < 3 && (rc == INSUFFICIENT || rc == NO_ERROR); tries++) {
                MemorySegment table = arena.allocate(size.get(JAVA_INT, 0) + 4096L);
                size.set(JAVA_INT, 0, (int) table.byteSize());
                rc = call(table, size, v6);
                if (rc == NO_ERROR) return scan(table, v6, client, server);
            }
            return Optional.empty();
        }
    }

    private int call(MemorySegment table, MemorySegment size, boolean v6) {
        try {
            return (int) getExtendedTcpTable.invokeExact(
                    table, size, 0, v6 ? AF_INET6 : AF_INET, TCP_TABLE_OWNER_PID_ALL, 0);
        } catch (Throwable e) {
            // Handled: a failed call is an unreadable table: no owner, the caller refuses
            return -1;
        }
    }

    /**
     * The client side row only (local end = the caller's address and port, remote end = the daemon's), never the
     * daemon's own row. Ports are DWORDs whose low 16 bits are in network order.
     */
    static Optional<long[]> scan(MemorySegment table, boolean v6, InetSocketAddress client, InetSocketAddress server) {
        byte[] clientAddress = SocketOwners.canonical(client.getAddress().getAddress());
        byte[] serverAddress = SocketOwners.canonical(server.getAddress().getAddress());
        int count = table.get(JAVA_INT, 0);
        int rowSize = v6 ? ROW6 : ROW4;
        long[] found = new long[0];
        for (int i = 0; i < count; i++) {
            long row = 4L + (long) i * rowSize;
            if (row + rowSize > table.byteSize()) return Optional.empty();
            int state = table.get(JAVA_INT, row + (v6 ? 48 : 0));
            int localPort = port(table.get(JAVA_INT, row + (v6 ? 20 : 8)));
            int remotePort = port(table.get(JAVA_INT, row + (v6 ? 44 : 16)));
            if (state != ESTABLISHED || localPort != client.getPort() || remotePort != server.getPort()) continue;
            if (!Arrays.equals(address(table, row + (v6 ? 0 : 4), v6), clientAddress)) continue;
            if (!Arrays.equals(address(table, row + (v6 ? 24 : 12), v6), serverAddress)) continue;
            found = Arrays.copyOf(found, found.length + 1);
            found[found.length - 1] = Integer.toUnsignedLong(table.get(JAVA_INT, row + (v6 ? 52 : 20)));
        }
        return Optional.of(found);
    }

    private static byte[] address(MemorySegment table, long offset, boolean v6) {
        return SocketOwners.canonical(table.asSlice(offset, v6 ? 16 : 4).toArray(JAVA_BYTE));
    }

    static int port(int dword) {
        return ((dword & 0xFF) << 8) | ((dword >> 8) & 0xFF);
    }

    private static MethodHandle load() {
        try {
            SymbolLookup lib = SymbolLookup.libraryLookup("iphlpapi", Arena.global());
            return Linker.nativeLinker()
                    .downcallHandle(
                            lib.find("GetExtendedTcpTable").orElseThrow(),
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        } catch (RuntimeException e) {
            // Handled: no TCP table on this Windows: every lookup is empty and IAM mode refuses to start
            return null;
        }
    }
}
