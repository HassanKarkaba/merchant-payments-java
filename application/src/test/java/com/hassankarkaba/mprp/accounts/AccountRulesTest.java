package com.hassankarkaba.mprp.accounts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class AccountRulesTest {
  @Test
  void fictionalEmailAndUnicodeBusinessBoundaries() {
    assertEquals("owner@example.test", AccountRules.email("  OWNER@EXAMPLE.TEST "));
    assertThrows(RuntimeException.class, () -> AccountRules.email("owner@gmail.com"));
    assertThrows(RuntimeException.class, () -> AccountRules.email("é@example.test"));
    assertEquals("Café", AccountRules.business("Cafe\u0301"));
    assertThrows(RuntimeException.class, () -> AccountRules.business("x".repeat(101)));
    assertEquals("America/Detroit", AccountRules.timezone("America/Detroit"));
    assertThrows(RuntimeException.class, () -> AccountRules.timezone("Mars/Olympus"));
  }

  @Test
  void passwordAndStrictFields() {
    assertThrows(
        RuntimeException.class,
        () -> AccountRules.password("123456789012345", "owner@example.test"));
    assertThrows(
        RuntimeException.class,
        () -> AccountRules.password("owner is my password", "owner@example.test"));
    assertThrows(
        RuntimeException.class, () -> AccountRules.password("short", "owner@example.test"));
    assertThrows(
        RuntimeException.class,
        () -> AccountRules.password("1q2w3e4r5t6y7u8i9o0p", "owner@example.test"));
    assertThrows(
        RuntimeException.class, () -> AccountRules.password("😀".repeat(8), "owner@example.test"));
    assertThrows(
        RuntimeException.class,
        () -> AccountRules.password("١٢٣٤٥٦٧٨٩٠١٢٣٤٥", "owner@example.test"));
    assertEquals(
        "A varied and private phrase!",
        AccountRules.password("A varied and private phrase!", "owner@example.test"));
    assertThrows(
        RuntimeException.class,
        () -> AccountRules.fields(java.util.Set.of("email", "admin"), "email"));
  }

  @Test
  void standardTotpVectorsAndAuthenticatedContext() {
    String seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    assertEquals("287082", SecretCodec.totp(seed, 1));
    assertEquals("081804", SecretCodec.totp(seed, 1111111109L / 30));
    var codec = new SecretCodec(Base64.getEncoder().encodeToString(new byte[32]));
    String ciphertext = codec.encrypt("secret", "totp:user-one");
    assertEquals("secret", codec.decrypt(ciphertext, "totp:user-one"));
    assertThrows(IllegalStateException.class, () -> codec.decrypt(ciphertext, "totp:user-two"));
    assertNotEquals(ciphertext, codec.encrypt("secret", "totp:user-one"));
  }

  @Test
  void rotationPreservesRetainedKeysAndAuthenticatesKeyIdentity() {
    String oldKey = Base64.getEncoder().encodeToString(new byte[32]);
    byte[] newBytes = new byte[32];
    java.util.Arrays.fill(newBytes, (byte) 7);
    String newKey = Base64.getEncoder().encodeToString(newBytes);
    var oldCodec = new SecretCodec(oldKey, "old-id", "");
    String old = oldCodec.encrypt("retained", "totp:actor");
    var rotated = new SecretCodec(newKey, "current-id", "old-id=" + oldKey);
    assertEquals("retained", rotated.decrypt(old, "totp:actor"));
    String current = rotated.encrypt(rotated.decrypt(old, "totp:actor"), "totp:actor");
    assertTrue(current.startsWith("aead2:current-id:"));
    assertEquals(
        "retained", new SecretCodec(newKey, "current-id", "").decrypt(current, "totp:actor"));
    assertThrows(
        IllegalStateException.class,
        () -> rotated.decrypt(current.replace("current-id", "old-id"), "totp:actor"));
    byte[] binary = oldCodec.encryptBytes(new byte[] {0, 1, -1}, "statement:id");
    assertArrayEquals(new byte[] {0, 1, -1}, rotated.decryptBytes(binary, "statement:id"));
    assertThrows(
        IllegalStateException.class, () -> rotated.decryptBytes(binary, "statement:other"));
  }

  @Test
  void legacyCiphertextRemainsReadableDuringRotation() throws Exception {
    byte[] oldKey = new byte[32], newKey = new byte[32], nonce = new byte[12];
    java.util.Arrays.fill(newKey, (byte) 9);
    var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        javax.crypto.Cipher.ENCRYPT_MODE,
        new javax.crypto.spec.SecretKeySpec(oldKey, "AES"),
        new javax.crypto.spec.GCMParameterSpec(128, nonce));
    cipher.updateAAD("statement:legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] body = cipher.doFinal(new byte[] {1, 0, -1});
    byte[] legacy = new byte[nonce.length + body.length];
    System.arraycopy(nonce, 0, legacy, 0, nonce.length);
    System.arraycopy(body, 0, legacy, nonce.length, body.length);
    var codec =
        new SecretCodec(
            Base64.getEncoder().encodeToString(newKey),
            "next",
            "v1=" + Base64.getEncoder().encodeToString(oldKey));
    assertArrayEquals(new byte[] {1, 0, -1}, codec.decryptBytes(legacy, "statement:legacy"));
    String legacyText =
        "v1:"
            + Base64.getEncoder().encodeToString(nonce)
            + ":"
            + Base64.getEncoder().encodeToString(body);
    assertEquals(
        new String(new byte[] {1, 0, -1}, java.nio.charset.StandardCharsets.UTF_8),
        codec.decrypt(legacyText, "statement:legacy"));
  }
}
