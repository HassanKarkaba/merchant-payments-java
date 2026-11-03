package com.hassankarkaba.mprp.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class BoundedBodyTest {
  @Test
  void completionRequiresAllBoundedBodyBytes() {
    var body = new BoundedBody(4);
    body.onSubscribe(
        new Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {}
        });
    body.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
    assertFalse(body.getBody().toCompletableFuture().isDone());
    body.onNext(List.of(ByteBuffer.wrap(new byte[] {3, 4})));
    body.onComplete();
    assertArrayEquals(new byte[] {1, 2, 3, 4}, body.getBody().toCompletableFuture().join());
  }

  @Test
  void chunkedOverflowCancelsAndNeverReturnsPartialSuccess() {
    boolean[] cancelled = {false};
    var body = new BoundedBody(3);
    body.onSubscribe(
        new Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {
            cancelled[0] = true;
          }
        });
    body.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.wrap(new byte[] {3, 4})));
    assertTrue(cancelled[0]);
    assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
  }
}
