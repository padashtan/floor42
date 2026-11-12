package dev.floor42.server.app;

import dev.floor42.server.app.Delivered.Blob;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bytes of a {@link Blob}, read from the one channel opened when the file was checked: its size and its first
 * bytes are measured on that channel, and what is sent stops at that size. A file swapped for another, or grown, after
 * the check is not what goes out: the open channel still names the file that was checked.
 */
public final class BlobBytes {

    /** How many first bytes tell what a file really is (magic bytes, {@code %PDF-}). */
    static final int HEAD = 16;

    /** Files opened and not released yet: a seam for the tests that prove a route leaves no handle open. */
    private static final AtomicInteger OPEN = new AtomicInteger();

    private BlobBytes() {}

    /** How many checked files are open now. */
    public static int openNow() {
        return OPEN.get();
    }

    /** Called by {@link Blob#release()} for an open channel, once. */
    static void closed() {
        OPEN.decrementAndGet();
    }

    /** Opens {@code file} without following a link, and measures its size and head on the open channel. */
    static Blob open(String name, Path file, boolean temporary) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        OPEN.incrementAndGet();
        try {
            long size = channel.size();
            Blob measured = new Blob(name, file, temporary, size, new byte[0], channel);
            return new Blob(name, file, temporary, size, first(measured, HEAD), channel);
        } catch (IOException | RuntimeException e) {
            channel.close();
            closed();
            throw e;
        }
    }

    /** At most {@code max} of its first bytes, never past the size measured at the check. */
    static byte[] first(Blob blob, int max) throws IOException {
        int n = (int) Math.min(max, Math.max(0, blob.size()));
        if (blob.channel() == null) {
            try (InputStream in = Files.newInputStream(blob.file(), LinkOption.NOFOLLOW_LINKS)) {
                return in.readNBytes(n);
            }
        }
        ByteBuffer buffer = ByteBuffer.allocate(n);
        while (buffer.hasRemaining()) {
            if (blob.channel().read(buffer, buffer.position()) < 0) break;
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    /** Writes its bytes to {@code out}, from the start, cut at the size measured at the check. */
    static void copy(Blob blob, OutputStream out) throws IOException {
        if (blob.channel() == null) {
            try (InputStream in = Files.newInputStream(blob.file(), LinkOption.NOFOLLOW_LINKS)) {
                long left = blob.size();
                byte[] buffer = new byte[64 * 1024];
                int n;
                while (left > 0 && (n = in.read(buffer, 0, (int) Math.min(buffer.length, left))) > 0) {
                    out.write(buffer, 0, n);
                    left -= n;
                }
            }
            return;
        }
        WritableByteChannel target = Channels.newChannel(out);
        long position = 0;
        while (position < blob.size()) {
            long sent = blob.channel().transferTo(position, blob.size() - position, target);
            if (sent <= 0) break;
            position += sent;
        }
    }
}
