package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** A response body is cut at the cap and the transfer cancelled: the rest is never held in memory. */
class CappedBodyTest {

    private static final class Sub implements Flow.Subscription {
        final AtomicBoolean cancelled = new AtomicBoolean();

        public void request(long n) {}

        public void cancel() {
            cancelled.set(true);
        }
    }

    private static ByteBuffer bytes(int n) {
        return ByteBuffer.wrap(new byte[n]);
    }

    @Test
    void aBodyOverTheCapIsCutAndTheTransferCancelled() {
        CappedBody b = new CappedBody(10);
        Sub s = new Sub();
        b.onSubscribe(s);
        b.onNext(List.of(bytes(6), bytes(100)));
        assertThat(s.cancelled).isTrue();
        assertThat(b.getBody().toCompletableFuture().join()).hasSize(10);
    }

    @Test
    void aBodyUnderTheCapIsKeptWhole() {
        CappedBody b = new CappedBody(10);
        Sub s = new Sub();
        b.onSubscribe(s);
        b.onNext(List.of(bytes(4)));
        b.onComplete();
        assertThat(s.cancelled).isFalse();
        assertThat(b.getBody().toCompletableFuture().join()).hasSize(4);
    }

    @Test
    void aBodyOfExactlyTheCapIsCutThere() {
        CappedBody b = new CappedBody(5);
        b.onSubscribe(new Sub());
        b.onNext(List.of(bytes(5)));
        assertThat(b.getBody().toCompletableFuture().join()).hasSize(5);
    }

    @Test
    void anErrorFailsTheBody() {
        CappedBody b = new CappedBody(5);
        b.onSubscribe(new Sub());
        b.onError(new IllegalStateException("boom"));
        assertThat(b.getBody().toCompletableFuture()).isCompletedExceptionally();
    }
}
