package com.hassankarkaba.mprp.operations;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Db {
  public final JdbcTemplate jdbc;

  public Db(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> query(String sql, Object... args) {
    return jdbc.queryForList(sql, args);
  }

  public Optional<Map<String, Object>> maybe(String sql, Object... args) {
    var rows = query(sql, args);
    return rows.stream().findFirst();
  }

  public Map<String, Object> one(String sql, Object... args) {
    return maybe(sql, args).orElseThrow(() -> new DomainException(404, "Record not found"));
  }

  public int exec(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  public <T> T scalar(String sql, Class<T> type, Object... args) {
    return jdbc.queryForObject(sql, type, args);
  }

  public void lockMerchant(UUID id) {
    one("select id from merchant where id=? for update", id);
  }

  public static UUID uuid(Map<String, Object> r, String key) {
    return UUID.fromString(r.get(key).toString());
  }

  public static long number(Map<String, Object> r, String key) {
    return ((Number) r.get(key)).longValue();
  }
}
