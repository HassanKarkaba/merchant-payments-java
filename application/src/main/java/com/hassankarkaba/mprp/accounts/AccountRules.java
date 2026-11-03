package com.hassankarkaba.mprp.accounts;

import java.text.Normalizer;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AccountRules {
  private static final Set<String> COMMON = loadCommonPasswords();

  private AccountRules() {}

  public static String email(String input) {
    String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    if (value.length() > 254
        || !value.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@(example\\.test|demo\\.invalid)"))
      throw bad("Use a fictional example.test or demo.invalid email address.");
    return value;
  }

  public static String business(String input) {
    String value = Normalizer.normalize(input == null ? "" : input.trim(), Normalizer.Form.NFC);
    if (value.isBlank()
        || value.codePointCount(0, value.length()) > 100
        || value.chars().anyMatch(Character::isISOControl))
      throw bad("Fictional business name must contain 1–100 characters.");
    return value;
  }

  public static String timezone(String input) {
    try {
      if (!ZoneId.getAvailableZoneIds().contains(input)) throw new IllegalArgumentException();
      return ZoneId.of(input).getId();
    } catch (Exception e) {
      throw bad("Choose a valid IANA timezone, such as America/Detroit.");
    }
  }

  public static String password(String input, String email) {
    if (input == null
        || input.codePointCount(0, input.length()) < 15
        || input.codePointCount(0, input.length()) > 128
        || input
            .codePoints()
            .allMatch(
                c ->
                    Character.isDigit(c)
                        || Character.getType(c) == Character.LETTER_NUMBER
                        || Character.getType(c) == Character.OTHER_NUMBER))
      throw bad("Use a password of 15–128 characters that is not entirely numeric.");
    String lower = input.toLowerCase(Locale.ROOT);
    String local = email == null ? "" : email.split("@", 2)[0];
    if (COMMON.contains(lower.strip())
        || Set.of(
                "passwordpassword",
                "password123456789",
                "correcthorsebatterystaple",
                "letmeinletmeinletmein")
            .contains(lower)
        || (local.length() >= 3 && lower.contains(local)))
      throw bad("Choose an uncommon password unrelated to your email.");
    return input;
  }

  public static void fields(Set<String> actual, String... allowed) {
    var permitted = new java.util.HashSet<>(java.util.List.of(allowed));
    permitted.add("_csrf");
    if (!permitted.containsAll(actual))
      throw bad("Unsupported form fields. Reload the page and try again.");
  }

  public static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static Set<String> loadCommonPasswords() {
    try (var stream = AccountRules.class.getResourceAsStream("/common-passwords.txt.gz")) {
      if (stream == null)
        throw new IllegalStateException("Required common-password corpus is missing");
      try (var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  new java.util.zip.GZIPInputStream(stream),
                  java.nio.charset.StandardCharsets.UTF_8))) {
        return reader
            .lines()
            .map(String::strip)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Could not load common-password corpus", e);
    }
  }
}
