package com.hassankarkaba.mprp.protocol;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Completes only after bounded bytes, so the caller's deadline covers the entire body. */
public final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final int limit;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> result = new CompletableFuture<>();
  private Flow.Subscription subscription;

  public BoundedBody(int limit) {
    this.limit = limit;
  }

  public CompletionStage<byte[]> getBody() {
    return result;
  }

  public void onSubscribe(Flow.Subscription s) {
    subscription = s;
    s.request(1);
  }

  public void onNext(List<ByteBuffer> chunks) {
    try {
      for (var b : chunks) {
        if ((long) out.size() + b.remaining() > limit)
          throw new IllegalArgumentException("Provider body limit exceeded");
        byte[] bytes = new byte[b.remaining()];
        b.get(bytes);
        out.writeBytes(bytes);
      }
      subscription.request(1);
    } catch (Exception e) {
      subscription.cancel();
      result.completeExceptionally(e);
    }
  }

  public void onError(Throwable error) {
    result.completeExceptionally(error);
  }

  public void onComplete() {
    result.complete(out.toByteArray());
  }
}
