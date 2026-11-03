package com.hassankarkaba.mprp.accounts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** JCA AES-GCM authenticated storage, bound to a user or durable delivery identity. */
@Component
public class SecretCodec {
  private final java.util.Map<String, byte[]> keys;
  private final String activeKeyId;
  private static final SecureRandom RANDOM = new SecureRandom();

  public SecretCodec(String configured) {
    this(configured, "v1", "");
  }

  @org.springframework.beans.factory.annotation.Autowired
  public SecretCodec(
      @Value("${mprp.encryption-key}") String configured,
      @Value("${mprp.encryption-key-id:v1}") String activeKeyId,
      @Value("${mprp.encryption-previous-keys:}") String previous) {
    if (!activeKeyId.matches("[A-Za-z0-9_-]{1,32}"))
      throw new IllegalStateException("Encryption key ID is invalid");
    this.activeKeyId = activeKeyId;
    var retained = new java.util.LinkedHashMap<String, byte[]>();
    retained.put(activeKeyId, decodeKey(configured));
    if (!previous.isBlank())
      for (String item : previous.split(";")) {
        int split = item.indexOf('=');
        if (split < 1)
          throw new IllegalStateException("Previous encryption keys require id=base64 entries");
        String id = item.substring(0, split);
        if (!id.matches("[A-Za-z0-9_-]{1,32}") || retained.containsKey(id))
          throw new IllegalStateException("Duplicate or invalid encryption key ID");
        retained.put(id, decodeKey(item.substring(split + 1)));
      }
    if (retained.size() > 10)
      throw new IllegalStateException("At most ten encryption keys may be retained");
    keys = java.util.Map.copyOf(retained);
  }

  private static byte[] decodeKey(String configured) {
    try {
      byte[] key = Base64.getDecoder().decode(configured);
      if (key.length != 32) throw new IllegalArgumentException();
      return key;
    } catch (Exception e) {
      throw new IllegalStateException("Encryption keys must encode 32 random bytes");
    }
  }

  public String activeKeyId() {
    return activeKeyId;
  }

  public java.util.Map<String, String> keyFingerprints() {
    var result = new java.util.TreeMap<String, String>();
    keys.forEach(
        (id, key) -> {
          try {
            result.put(
                id, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key)));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    return java.util.Map.copyOf(result);
  }

  public static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String encrypt(String value, String context) {
    return encryptRaw(value.getBytes(StandardCharsets.UTF_8), context);
  }

  private String encryptRaw(byte[] value, String context) {
    try {
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(keys.get(activeKeyId), "AES"),
          new GCMParameterSpec(128, nonce));
      cipher.updateAAD(("aead2:" + activeKeyId + "\n" + context).getBytes(StandardCharsets.UTF_8));
      return "aead2:"
          + activeKeyId
          + ":"
          + Base64.getEncoder().encodeToString(nonce)
          + ":"
          + Base64.getEncoder().encodeToString(cipher.doFinal(value));
    } catch (Exception e) {
      throw new IllegalStateException("Could not protect secret", e);
    }
  }

  public String decrypt(String value, String context) {
    return new String(decryptRaw(value, context), StandardCharsets.UTF_8);
  }

  private byte[] decryptRaw(String value, String context) {
    try {
      String[] parts = value.split(":");
      boolean legacy = parts.length == 3 && parts[0].equals("v1");
      if (!legacy && (parts.length != 4 || !parts[0].equals("aead2")))
        throw new IllegalArgumentException();
      String id = legacy ? "v1" : parts[1];
      byte[] key = keys.get(id);
      if (key == null) throw new IllegalArgumentException();
      byte[] nonce = Base64.getDecoder().decode(parts[legacy ? 1 : 2]);
      if (nonce.length != 12) throw new IllegalArgumentException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(
          (legacy ? context : "aead2:" + id + "\n" + context).getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(Base64.getDecoder().decode(parts[legacy ? 2 : 3]));
    } catch (Exception e) {
      throw new IllegalStateException("Stored secret integrity check failed", e);
    }
  }

  public byte[] encryptBytes(byte[] plaintext, String context) {
    return encryptRaw(plaintext, context).getBytes(StandardCharsets.UTF_8);
  }

  public byte[] decryptBytes(byte[] ciphertext, String context) {
    if (ciphertext.length >= 6
        && new String(ciphertext, 0, 6, StandardCharsets.US_ASCII).equals("aead2:"))
      return decryptRaw(new String(ciphertext, StandardCharsets.UTF_8), context);
    // Only fresh Java v1 staging used the legacy 12-byte nonce binary representation.
    if (ciphertext.length < 28)
      throw new IllegalStateException("Stored secret integrity check failed");
    for (byte[] key : keys.values())
      try {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE,
            new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(128, java.util.Arrays.copyOf(ciphertext, 12)));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(java.util.Arrays.copyOfRange(ciphertext, 12, ciphertext.length));
      } catch (javax.crypto.AEADBadTagException mismatch) {
      } catch (Exception failure) {
        throw new IllegalStateException("Stored secret integrity check failed", failure);
      }
    throw new IllegalStateException("Stored secret integrity check failed");
  }

  public static String newTotpSeed() {
    byte[] b = new byte[20];
    RANDOM.nextBytes(b);
    return base32(b);
  }

  public static String totp(String seed, long counter) {
    try {
      var generator =
          new com.eatthepath.otp.TimeBasedOneTimePasswordGenerator(
              java.time.Duration.ofSeconds(30), 6, "HmacSHA1");
      return generator.generateOneTimePasswordString(
          new SecretKeySpec(unbase32(seed), "HmacSHA1"),
          java.time.Instant.ofEpochSecond(Math.multiplyExact(counter, 30)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String base32(byte[] input) {
    String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    StringBuilder out = new StringBuilder();
    int buffer = 0, bits = 0;
    for (byte b : input) {
      buffer = (buffer << 8) | (b & 255);
      bits += 8;
      while (bits >= 5) {
        out.append(chars.charAt((buffer >> (bits - 5)) & 31));
        bits -= 5;
      }
    }
    if (bits > 0) out.append(chars.charAt((buffer << (5 - bits)) & 31));
    return out.toString();
  }

  private static byte[] unbase32(String input) {
    var out = new java.io.ByteArrayOutputStream();
    int buffer = 0, bits = 0;
    for (char c : input.toCharArray()) {
      int i = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
      if (i < 0) throw new IllegalArgumentException();
      buffer = (buffer << 5) | i;
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 255);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }
}
