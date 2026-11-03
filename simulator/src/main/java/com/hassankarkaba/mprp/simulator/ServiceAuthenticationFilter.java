package com.hassankarkaba.mprp.simulator;

import com.hassankarkaba.mprp.protocol.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ServiceAuthenticationFilter extends OncePerRequestFilter {
  private final JdbcTemplate db;
  private final ServiceKeys keys;

  public ServiceAuthenticationFilter(JdbcTemplate db, ServiceKeys keys) {
    this.db = db;
    this.keys = keys;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    byte[] bytes = request.getInputStream().readNBytes(1048577);
    if (bytes.length > 1048576) {
      error(response, 413, "body_too_large");
      return;
    }
    String
        path =
            request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString()),
        timestamp = request.getHeader("X-MPRP-Timestamp"),
        nonce = request.getHeader("X-MPRP-Nonce"),
        signature = request.getHeader("X-MPRP-Signature");
    Instant now = Instant.now();
    var selected = keys.verificationSecret(request.getHeader("X-MPRP-Key-Id"), now);
    if (selected.isEmpty()
        || !HmacAuth.verify(
            selected.get(), request.getMethod(), path, timestamp, nonce, bytes, signature, now)) {
      error(response, 401, "invalid_service_auth");
      return;
    }
    try {
      db.update(
          "DELETE FROM sim_nonces WHERE nonce IN (SELECT nonce FROM sim_nonces WHERE"
              + " expires_at<=now() LIMIT 5000)");
      db.update(
          "INSERT INTO sim_nonces(nonce,expires_at) VALUES(?,now()+interval '10 minutes')", nonce);
    } catch (DuplicateKeyException e) {
      error(response, 401, "replayed_transport");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public ServletInputStream getInputStream() {
            ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
              public boolean isFinished() {
                return stream.available() == 0;
              }

              public boolean isReady() {
                return true;
              }

              public void setReadListener(ReadListener l) {}

              public int read() {
                return stream.read();
              }
            };
          }

          @Override
          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        },
        response);
  }

  private void error(HttpServletResponse response, int status, String code) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response
        .getWriter()
        .write(CanonicalJson.string(Map.of("code", code, "message", "Service request rejected")));
  }
}
