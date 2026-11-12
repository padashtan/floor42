package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.server.app.RefusedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** A body is read up to its limit and no further (the import's cap, enforced at the boundary). */
class BoundedBodyTest {

    /** An endless stream that counts what was taken from it. */
    private static final class Endless extends InputStream {
        final AtomicLong taken = new AtomicLong();

        @Override
        public int read() {
            taken.incrementAndGet();
            return 'x';
        }

        @Override
        public int read(byte[] b, int off, int len) {
            taken.addAndGet(len);
            java.util.Arrays.fill(b, off, off + len, (byte) 'x');
            return len;
        }
    }

    @Test
    void aBodyWithinTheLimitIsReadWhole() {
        assertThat(BoundedBody.read(new ByteArrayInputStream(new byte[100]), 100L, 100, "C"))
                .hasSize(100);
        assertThat(BoundedBody.read(new ByteArrayInputStream(new byte[100]), null, 100, "C"))
                .hasSize(100);
    }

    @Test
    void aBodyJustAboveTheLimitIsRefusedWithTheCodeAndStatus413() {
        assertThatThrownBy(
                        () -> BoundedBody.read(new ByteArrayInputStream(new byte[101]), null, 100, "IMPORT_TOO_LARGE"))
                .isInstanceOfSatisfying(RefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(413);
                    assertThat(e.code()).isEqualTo("IMPORT_TOO_LARGE");
                });
    }

    @Test
    void aDeclaredLengthAboveTheLimitIsRefusedBeforeAByteIsRead() {
        Endless in = new Endless();
        assertThatThrownBy(() -> BoundedBody.read(in, 10_000_000_000L, 100, "C"))
                .isInstanceOf(RefusedException.class);
        assertThat(in.taken.get()).isZero();
    }

    @Test
    void anUndeclaredEndlessBodyIsNotReadBeyondOneByteOverTheLimit() {
        Endless in = new Endless();
        assertThatThrownBy(() -> BoundedBody.read(in, null, 100, "C")).isInstanceOf(RefusedException.class);
        assertThat(in.taken.get()).isLessThanOrEqualTo(101L + 8192);
    }

    @Test
    void aBrokenStreamIsAnErrorNotAShortBody() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("reset");
            }
        };
        assertThatThrownBy(() -> BoundedBody.read(broken, null, 100, "C"))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }
}
