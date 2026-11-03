package com.hassankarkaba.mprp.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/** Java schema 1: sorted UTF-8 JSON with integers only and explicit nulls. */
public final class CanonicalJson {
  private static final ObjectMapper MAPPER =
      new ObjectMapper(
          JsonFactory.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxNestingDepth(32)
                      .maxStringLength(1048576)
                      .maxNumberLength(32)
                      .build())
              .build());
  private static final DateTimeFormatter INSTANT =
      new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

  private CanonicalJson() {}

  public static String instant(Instant value) {
    return INSTANT.format(value);
  }

  public static byte[] bytes(Object value) {
    return string(value).getBytes(StandardCharsets.UTF_8);
  }

  public static String string(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value, 0);
    return out.toString();
  }

  public static String sha256(Object value) {
    return digest(bytes(value));
  }

  public static String digest(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> parse(byte[] raw) {
    Object value = parseAny(raw);
    if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
    return (Map<String, Object>) value;
  }

  public static String sha256Bytes(byte[] raw) {
    return digest(raw);
  }

  public static Object parseAny(byte[] raw) {
    if (raw.length > 20 * 1024 * 1024)
      throw new IllegalArgumentException("JSON body exceeds limit");
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(raw))
              .toString();
      Object value = MAPPER.readValue(text, Object.class);
      string(value); // validates allowed canonical domain
      return value;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid bounded integer JSON", e);
    }
  }

  public static Map<String, Object> parse(String raw) {
    return parse(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static void append(StringBuilder out, Object value, int depth) {
    if (depth > 32) throw new IllegalArgumentException("JSON depth exceeds limit");
    if (value == null) {
      out.append("null");
      return;
    }
    if (value instanceof String s) {
      quote(out, s);
      return;
    }
    if (value instanceof UUID u) {
      quote(out, u.toString());
      return;
    }
    if (value instanceof Instant i) {
      quote(out, instant(i));
      return;
    }
    if (value instanceof Boolean
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger) {
      out.append(value);
      return;
    }
    if (value instanceof Map<?, ?> map) {
      if (map.size() > 10000) throw new IllegalArgumentException("Too many fields");
      TreeMap<String, Object> sorted = new TreeMap<>();
      map.forEach(
          (k, v) -> {
            if (!(k instanceof String s))
              throw new IllegalArgumentException("Object key is not text");
            sorted.put((String) k, v);
          });
      out.append('{');
      boolean first = true;
      for (var entry : sorted.entrySet()) {
        if (!first) out.append(',');
        first = false;
        quote(out, entry.getKey());
        out.append(':');
        append(out, entry.getValue(), depth + 1);
      }
      out.append('}');
      return;
    }
    if (value instanceof Collection<?> list) {
      if (list.size() > 10000) throw new IllegalArgumentException("Too many items");
      out.append('[');
      boolean first = true;
      for (Object item : list) {
        if (!first) out.append(',');
        first = false;
        append(out, item, depth + 1);
      }
      out.append(']');
      return;
    }
    throw new IllegalArgumentException(
        "Unsupported JSON value " + value.getClass().getSimpleName());
  }

  private static void quote(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
          throw new IllegalArgumentException("Invalid surrogate");
        out.append(c).append(value.charAt(++i));
        continue;
      }
      if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid surrogate");
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 32) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }
}
