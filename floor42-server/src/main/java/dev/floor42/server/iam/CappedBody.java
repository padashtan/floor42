package dev.floor42.server.iam;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** A response body read up to {@code limit} bytes, then the transfer is cancelled: no thread, no unbounded memory. */
final class CappedBody implements BodySubscriber<byte[]> {

    private final int limit;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    CappedBody(int limit) {
        this.limit = limit;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer b : buffers) {
            int take = Math.min(b.remaining(), limit - out.size());
            byte[] chunk = new byte[take];
            b.get(chunk);
            out.write(chunk, 0, take);
        }
        if (out.size() >= limit) {
            subscription.cancel();
            body.complete(out.toByteArray());
        }
    }

    @Override
    public void onError(Throwable t) {
        body.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
        body.complete(out.toByteArray());
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }
}
