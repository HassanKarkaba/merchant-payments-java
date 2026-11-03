package com.hassankarkaba.mprp.simulator;

import com.hassankarkaba.mprp.protocol.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SimulatorWorker {
  private final ProviderStore provider;
  private final boolean enabled;
  private final String origin;
  private final ServiceKeys keys;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public SimulatorWorker(
      ProviderStore provider,
      @Value("${mprp.worker}") boolean enabled,
      @Value("${mprp.callback-origin}") String origin,
      ServiceKeys keys) {
    this.provider = provider;
    this.enabled = enabled;
    this.origin = origin;
    this.keys = keys;
    URI u = URI.create(origin);
    if (!"http".equals(u.getScheme())
        || !java.util.Set.of("web", "localhost", "127.0.0.1", "host.docker.internal")
            .contains(u.getHost())
        || u.getUserInfo() != null
        || u.getQuery() != null)
      throw new IllegalArgumentException("Callback origin must be local allowlisted service");
  }

  @Scheduled(fixedDelay = 60000)
  public void daily() {
    if (enabled) provider.dailySettlements();
  }

  @Scheduled(fixedDelay = 5000)
  public void tick() {
    if (!enabled) return;
    provider.progress();
    for (Map<String, Object> event :
        provider.rows(
            "SELECT * FROM sim_events WHERE delivered=false AND due_at<=now() ORDER BY sequence"
                + " LIMIT 3")) {
      provider.heartbeat();
      String path = "/callbacks/processor/v1/";
      byte[] body = event.get("body").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      HttpRequest.Builder req =
          HttpRequest.newBuilder(URI.create(origin + path))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(body));
      keys.signingHeaders("POST", path, body).forEach(req::header);
      boolean success = false;
      java.util.concurrent.CompletableFuture<HttpResponse<Void>> pending = null;
      try {
        pending = http.sendAsync(req.build(), HttpResponse.BodyHandlers.discarding());
        HttpResponse<Void> r = pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
        success = r.statusCode() == 202;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception unavailable) {
        // The original provider event remains retained for another bounded attempt.
      } finally {
        if (pending != null && !pending.isDone()) pending.cancel(true);
      }
      if (success)
        provider.exec(
            "UPDATE sim_events SET delivered=true,attempts=attempts+1 WHERE sequence=?",
            event.get("sequence"));
      else
        provider.exec(
            "UPDATE sim_events SET attempts=attempts+1,due_at=now()+interval '30 seconds' WHERE"
                + " sequence=?",
            event.get("sequence"));
      provider.heartbeat();
    }
  }
}
