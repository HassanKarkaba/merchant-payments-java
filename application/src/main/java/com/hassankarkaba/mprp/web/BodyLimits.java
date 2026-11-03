package com.hassankarkaba.mprp.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
public class BodyLimits extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if (!Set.of("POST", "PUT", "PATCH").contains(req.getMethod())) {
      chain.doFilter(req, res);
      return;
    }
    boolean multipart = Objects.toString(req.getContentType(), "").startsWith("multipart/");
    int max =
        multipart
            ? 6 * 1024 * 1024
            : req.getRequestURI().startsWith("/callbacks/") ? 262144 : 65536;
    if (req.getContentLengthLong() > max) {
      res.sendError(413, "Request too large");
      return;
    }
    if (multipart) {
      chain.doFilter(req, res);
      return;
    }
    byte[] body = req.getInputStream().readNBytes(max + 1);
    if (body.length > max) {
      res.sendError(413, "Request too large");
      return;
    }
    Map<String, String[]> parameters = new LinkedHashMap<>();
    try {
      parse(req.getQueryString(), parameters);
      if (Objects.toString(req.getContentType(), "")
          .startsWith("application/x-www-form-urlencoded")) parse(strictUtf8(body), parameters);
    } catch (IllegalArgumentException invalid) {
      res.sendError(400, "Invalid or duplicate form field");
      return;
    }
    HttpServletRequestWrapper wrapped =
        new HttpServletRequestWrapper(req) {
          @Override
          public ServletInputStream getInputStream() {
            var in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              public int read() {
                return in.read();
              }

              public boolean isFinished() {
                return in.available() == 0;
              }

              public boolean isReady() {
                return true;
              }

              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Synchronous requests only");
              }
            };
          }

          @Override
          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
          }

          @Override
          public Map<String, String[]> getParameterMap() {
            return Collections.unmodifiableMap(parameters);
          }

          @Override
          public String getParameter(String key) {
            String[] v = parameters.get(key);
            return v == null ? null : v[0];
          }

          @Override
          public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
          }

          @Override
          public String[] getParameterValues(String key) {
            return parameters.get(key);
          }
        };
    chain.doFilter(wrapped, res);
  }

  private void parse(String encoded, Map<String, String[]> out) {
    if (encoded == null || encoded.isEmpty()) return;
    for (String pair : encoded.split("&", -1)) {
      if (pair.isEmpty()) continue;
      String[] parts = pair.split("=", 2);
      String key = formDecode(parts[0]), value = formDecode(parts.length == 2 ? parts[1] : "");
      String[] previous = out.putIfAbsent(key, new String[] {value});
      if (previous != null && !key.equals("_csrf"))
        throw new IllegalArgumentException("Duplicate field");
      if (out.size() > 100) throw new IllegalArgumentException("Too many fields");
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException invalid) {
      throw new IllegalArgumentException("Invalid UTF-8", invalid);
    }
  }

  private static String formDecode(String encoded) {
    var bytes = new ByteArrayOutputStream();
    for (int i = 0; i < encoded.length(); ) {
      int c = encoded.codePointAt(i);
      i += Character.charCount(c);
      if (c == '%') {
        if (i + 2 > encoded.length()) throw new IllegalArgumentException("Invalid percent escape");
        int hi = Character.digit(encoded.charAt(i), 16),
            lo = Character.digit(encoded.charAt(i + 1), 16);
        if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid percent escape");
        bytes.write((hi << 4) | lo);
        i += 2;
      } else if (c == '+') bytes.write(' ');
      else bytes.writeBytes(new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8));
    }
    return strictUtf8(bytes.toByteArray());
  }
}
