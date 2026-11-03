package com.hassankarkaba.mprp.web;

import com.hassankarkaba.mprp.accounts.Authz;
import com.hassankarkaba.mprp.operations.*;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class HomeController {
  private final Db db;
  private final Authz auth;

  public HomeController(Db db, Authz auth) {
    this.db = db;
    this.auth = auth;
  }

  @GetMapping("/")
  String home(Model m) {
    var a =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (a != null && a.isAuthenticated() && !a.getName().equals("anonymousUser")) {
      UUID actor = auth.current();
      m.addAttribute(
          "merchants",
          db.query(
              "select t.id,t.name,t.status,x.role from merchant t join membership x on"
                  + " x.merchant_id=t.id where x.user_id=? and x.active order by t.name limit 100",
              actor));
    }
    return "home";
  }
}
