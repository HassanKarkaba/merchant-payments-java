package com.hassankarkaba.mprp.protocol;

import java.time.*;
import java.util.*;

/**
 * Explicit activation/expiry for local service credentials, including a bounded previous-key
 * overlap.
 */
public final class ServiceKeys {
  public record Key(String id, String secret, Instant activeFrom, Instant expiresAt) {
    public Key {
      if (id == null
          || !id.matches("[A-Za-z0-9_.-]{1,64}")
          || secret == null
          || secret.length() < 32
          || activeFrom == null
          || expiresAt == null
          || !activeFrom.isBefore(expiresAt))
        throw new IllegalArgumentException("Invalid service key configuration");
    }

    @Override
    public String toString() {
      return "ServiceKey[id="
          + id
          + ",activeFrom="
          + activeFrom
          + ",expiresAt="
          + expiresAt
          + ",secret=redacted]";
    }

    public boolean active(Instant now) {
      return !now.isBefore(activeFrom) && now.isBefore(expiresAt);
    }
  }

  private final Key current, previous;

  public ServiceKeys(Key current, Key previous) {
    this.current = Objects.requireNonNull(current);
    this.previous = previous;
    if (previous != null
        && (previous.id().equals(current.id())
            || previous.activeFrom().isAfter(current.activeFrom())
            || previous.expiresAt().isAfter(current.activeFrom().plus(Duration.ofHours(24)))))
      throw new IllegalArgumentException(
          "Previous service key overlap exceeds 24 hours or identity is reused");
  }

  public static ServiceKeys from(
      String currentId,
      String currentSecret,
      String activeFrom,
      String expiresAt,
      String previousId,
      String previousSecret,
      String previousActiveFrom,
      String previousExpiresAt) {
    Key current =
        new Key(currentId, currentSecret, Instant.parse(activeFrom), Instant.parse(expiresAt));
    String[] old = {previousId, previousSecret, previousActiveFrom, previousExpiresAt};
    boolean any = Arrays.stream(old).anyMatch(x -> x != null && !x.isBlank()),
        all = Arrays.stream(old).allMatch(x -> x != null && !x.isBlank());
    if (any && !all)
      throw new IllegalArgumentException("Previous service key configuration is incomplete");
    return new ServiceKeys(
        current,
        all
            ? new Key(
                previousId,
                previousSecret,
                Instant.parse(previousActiveFrom),
                Instant.parse(previousExpiresAt))
            : null);
  }

  public Optional<String> verificationSecret(String keyId, Instant now) {
    if (current.id().equals(keyId) && current.active(now)) return Optional.of(current.secret());
    if (previous != null
        && previous.id().equals(keyId)
        && previous.active(now)
        && now.isBefore(current.activeFrom().plus(Duration.ofHours(24))))
      return Optional.of(previous.secret());
    return Optional.empty();
  }

  public Map<String, String> signingHeaders(String method, String path, byte[] body) {
    return signingHeaders(method, path, body, Instant.now());
  }

  public Map<String, String> signingHeaders(String method, String path, byte[] body, Instant now) {
    if (!current.active(now))
      throw new IllegalStateException("Current service signing key is outside its validity window");
    return HmacAuth.headers(current.secret(), current.id(), method, path, body);
  }

  public Key current() {
    return current;
  }
}
