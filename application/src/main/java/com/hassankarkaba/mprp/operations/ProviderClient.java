package com.hassankarkaba.mprp.operations;

import com.hassankarkaba.mprp.protocol.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ProviderClient {
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final URI origin;
  private final ServiceKeys keys;

  public ProviderClient(RuntimeProfile profile, ServiceKeys keys) {
    this.origin = profile.simulatorUrl();
    this.keys = keys;
  }

  public Map<String, Object> call(String path, Map<String, Object> payload) {
    return request("POST", path, CanonicalJson.bytes(payload));
  }

  public Map<String, Object> get(String path) {
    return request("GET", path, new byte[0]);
  }

  public byte[] getBytes(String path) {
    if (!path.startsWith("/v1/statements/") || path.contains(".."))
      throw new IllegalArgumentException("Invalid provider path");
    return exchange("GET", path, new byte[0], null, 20 * 1024 * 1024);
  }

  public Map<String, Object> callOperator(
      String path, Map<String, Object> payload, String operatorSecret) {
    if (operatorSecret == null || operatorSecret.length() < 32)
      throw new DomainException(403, "Operator credential required");
    return request(methodPost(), path, CanonicalJson.bytes(payload), operatorSecret);
  }

  private String methodPost() {
    return "POST";
  }

  private Map<String, Object> request(String method, String path, byte[] bytes) {
    return request(method, path, bytes, null);
  }

  private Map<String, Object> request(
      String method, String path, byte[] bytes, String operatorSecret) {
    int limit =
        (path.contains("statements") || path.contains("history") || path.contains("inventory"))
            ? 20 * 1024 * 1024
            : 1024 * 1024;
    return CanonicalJson.parse(exchange(method, path, bytes, operatorSecret, limit));
  }

  private byte[] exchange(
      String method, String path, byte[] bytes, String operatorSecret, int limit) {
    if (!path.startsWith("/v1/") || path.contains("..") || path.contains("#"))
      throw new IllegalArgumentException("Invalid provider path");
    java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      var request =
          HttpRequest.newBuilder(origin.resolve(path))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json");
      keys.signingHeaders(method, path, bytes).forEach(request::header);
      if (operatorSecret != null) request.header("X-MPRP-Purge-Secret", operatorSecret);
      request.method(
          method,
          bytes.length == 0
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(bytes));
      pending = http.sendAsync(request.build(), info -> new BoundedBody(limit));
      var response = pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new ProviderUnavailable();
      return response.body();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ProviderUnavailable();
    } catch (Exception ex) {
      throw new ProviderUnavailable();
    } finally {
      if (pending != null && !pending.isDone()) pending.cancel(true);
    }
  }

  public static class ProviderUnavailable extends RuntimeException {
    public ProviderUnavailable() {
      super("Provider response unavailable; inspect retained operation");
    }
  }
}
