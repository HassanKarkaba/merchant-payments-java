package com.hassankarkaba.mprp.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtocolTest {
  @Test
  void serviceKeyValidityAndPreviousOverlapHaveExactExpiry() {
    Instant activation = Instant.parse("2026-11-02T00:00:00Z");
    var current =
        new ServiceKeys.Key("v2", "a".repeat(32), activation, activation.plusSeconds(86400 * 365));
    var previous =
        new ServiceKeys.Key(
            "v1", "b".repeat(32), activation.minusSeconds(86400), activation.plusSeconds(86400));
    var keys = new ServiceKeys(current, previous);
    assertTrue(keys.verificationSecret("v1", activation.plusSeconds(86399)).isPresent());
    assertTrue(keys.verificationSecret("v1", activation.plusSeconds(86400)).isEmpty());
    assertTrue(keys.verificationSecret("v2", activation.minusNanos(1)).isEmpty());
    assertTrue(keys.verificationSecret("v2", current.expiresAt()).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ServiceKeys(
                current,
                new ServiceKeys.Key(
                    "v1",
                    "b".repeat(32),
                    activation.minusSeconds(1),
                    activation.plusSeconds(86401))));
    assertThrows(
        IllegalStateException.class,
        () -> keys.signingHeaders("GET", "/v1/capabilities/", new byte[0], current.expiresAt()));
  }

  @Test
  void sharedGoldenBytesAndHashAreFrozen() throws Exception {
    byte[] raw = getClass().getResourceAsStream("/protocol/canonical-v1.json").readAllBytes();
    String hash =
        new String(
                getClass().getResourceAsStream("/protocol/canonical-v1.sha256").readAllBytes(),
                java.nio.charset.StandardCharsets.US_ASCII)
            .trim();
    assertArrayEquals(raw, CanonicalJson.bytes(CanonicalJson.parse(raw)));
    assertEquals(hash, CanonicalJson.digest(raw));
  }

  @Test
  void canonicalEncodingRejectsNonIntegerAndDuplicateKeys() {
    assertEquals("{\"a\":1,\"z\":\"é\"}", CanonicalJson.string(Map.of("z", "é", "a", 1)));
    assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("{\"a\":1,\"a\":2}"));
    assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("{\"a\":1.5}"));
    assertThrows(
        IllegalArgumentException.class, () -> CanonicalJson.parse(new byte[] {(byte) 0xff}));
    assertEquals(
        "2026-11-02T00:00:00.000000Z",
        CanonicalJson.instant(Instant.parse("2026-11-02T00:00:00Z")));
  }

  @Test
  void moneyAndFeeAreExact() {
    assertEquals(10000, Money.parse("100.00", 100, 100000000));
    assertEquals(320, Money.fee(10000));
    assertEquals(146, Money.fee(4000));
    for (String bad : new String[] {"+1", "1e2", "1.001", "١", "1,000", " 1"})
      assertThrows(IllegalArgumentException.class, () -> Money.parse(bad, 1, 100000000));
  }

  @Test
  void transportBindsMethodPathBodyAndTime() {
    String t = "1793577600", n = "a".repeat(32);
    byte[] b = CanonicalJson.bytes(Map.of("x", 1));
    String s = HmacAuth.sign("fictional-local-test-secret", "POST", "/v1/authorize/", t, n, b);
    Instant now = Instant.ofEpochSecond(Long.parseLong(t));
    assertTrue(
        HmacAuth.verify("fictional-local-test-secret", "POST", "/v1/authorize/", t, n, b, s, now));
    assertFalse(
        HmacAuth.verify("fictional-local-test-secret", "POST", "/v1/refund/", t, n, b, s, now));
    assertFalse(
        HmacAuth.verify(
            "fictional-local-test-secret",
            "POST",
            "/v1/authorize/",
            t,
            n,
            b,
            s,
            now.plusSeconds(301)));
  }
}
