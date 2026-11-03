package com.hassankarkaba.mprp.web;

import java.util.*;
import org.springframework.ui.Model;

public final class Web {
  private Web() {}

  public static String page(Model model, String title, List<Map<String, Object>> rows) {
    model.addAttribute("title", title);
    model.addAttribute("rows", rows);
    return "page";
  }

  public static String form(Model model, String title, String action, Map<String, String> fields) {
    model.addAttribute("title", title);
    model.addAttribute("action", action);
    model.addAttribute("fields", fields);
    return "form";
  }

  public static String redirect(String url) {
    return "redirect:" + url;
  }

  public static void fields(Map<String, ?> values, String... allowed) {
    var set = new HashSet<>(Arrays.asList(allowed));
    set.add("_csrf");
    for (String k : values.keySet())
      if (!set.contains(k))
        throw new com.hassankarkaba.mprp.operations.DomainException(400, "Unexpected field: " + k);
  }
}
