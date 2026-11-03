package com.hassankarkaba.mprp.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacAuth {
  private HmacAuth() {}

  public static String sign(
      String secret, String method, String path, String timestamp, String nonce, byte[] body) {
    try {
      String content =
          method.toUpperCase(Locale.ROOT)
              + "\n"
              + path
              + "\n"
              + timestamp
              + "\n"
              + nonce
              + "\n"
              + CanonicalJson.digest(body);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC unavailable", e);
    }
  }

  public static boolean verify(
      String secret,
      String method,
      String path,
      String timestamp,
      String nonce,
      byte[] body,
      String signature,
      Instant now) {
    try {
      if (timestamp == null
          || !timestamp.matches("[0-9]{1,12}")
          || nonce == null
          || !nonce.matches("[A-Za-z0-9_-]{32,128}")
          || signature == null
          || !signature.matches("[a-f0-9]{64}")) return false;
      long seconds = Long.parseLong(timestamp);
      if (Math.abs(Math.subtractExact(now.getEpochSecond(), seconds)) > 300) return false;
      return MessageDigest.isEqual(
          sign(secret, method, path, timestamp, nonce, body).getBytes(StandardCharsets.US_ASCII),
          signature.getBytes(StandardCharsets.US_ASCII));
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static Map<String, String> headers(
      String secret, String keyId, String method, String path, byte[] body) {
    byte[] entropy = new byte[24];
    new SecureRandom().nextBytes(entropy);
    String nonce = HexFormat.of().formatHex(entropy),
        time = Long.toString(Instant.now().getEpochSecond());
    return Map.of(
        "X-MPRP-Key-Id",
        keyId,
        "X-MPRP-Timestamp",
        time,
        "X-MPRP-Nonce",
        nonce,
        "X-MPRP-Signature",
        sign(secret, method, path, time, nonce, body));
  }
}
