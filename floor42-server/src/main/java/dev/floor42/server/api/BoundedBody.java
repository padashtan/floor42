package dev.floor42.server.api;

import dev.floor42.server.app.RefusedException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * A request body read up to a limit and no further: a client that sends more is refused as soon as the limit is passed,
 * on its declared length before a byte is read, or on the first byte beyond the limit otherwise. Nothing past the limit
 * is buffered, so a huge body costs the daemon at most the limit.
 */
final class BoundedBody {

    private BoundedBody() {}

    /** The body's bytes; refused 413 with {@code code} when it is, or says it is, longer than {@code limit} bytes. */
    static byte[] read(InputStream in, Long declaredLength, int limit, String code) {
        if (declaredLength != null && declaredLength > limit) throw tooLarge(code, limit);
        try {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) throw tooLarge(code, limit);
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RefusedException tooLarge(String code, int limit) {
        return RefusedException.tooLarge(code, "The request body is larger than " + limit + " bytes");
    }
}
