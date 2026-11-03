package com.hassankarkaba.mprp.accounts;

import com.hassankarkaba.mprp.operations.DomainException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession(
    maxInactiveIntervalInSeconds = 1800)
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  org.springframework.security.core.userdetails.UserDetailsService noGeneratedUsers() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Use the sandbox account login");
    };
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
  SecurityFilterChain security(HttpSecurity http, Authz authz) throws Exception {
    return http.formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .logout(l -> l.disable())
        .requestCache(c -> c.disable())
        .csrf(c -> c.ignoringRequestMatchers("/callbacks/processor/v1/**"))
        .headers(
            h ->
                h.contentSecurityPolicy(
                        p ->
                            p.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'; img-src"
                                    + " 'self' data:; form-action 'self'; frame-ancestors 'none';"
                                    + " base-uri 'none'"))
                    .referrerPolicy(
                        r ->
                            r.policy(
                                org.springframework.security.web.header.writers
                                    .ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/",
                        "/accounts/**",
                        "/checkout/**",
                        "/receipts/**",
                        "/health/**",
                        "/callbacks/processor/v1/**",
                        "/css/**",
                        "/js/**",
                        "/static/**",
                        "/style.css",
                        "/favicon.ico",
                        "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e -> e.authenticationEntryPoint((req, res, exception) -> unauthorized(req, res)))
        .addFilterAfter(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                  throws ServletException, IOException {
                var previous =
                    org.springframework.web.context.request.RequestContextHolder
                        .getRequestAttributes();
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(req, res));
                try {
                  res.setHeader("Cache-Control", "no-store");
                  var a =
                      org.springframework.security.core.context.SecurityContextHolder.getContext()
                          .getAuthentication();
                  if (a != null && a.isAuthenticated() && !"anonymousUser".equals(a.getName())) {
                    try {
                      authz.identity();
                      String p = req.getRequestURI();
                      boolean narrow =
                          p.startsWith("/accounts/mfa/")
                              || p.startsWith("/accounts/verify/")
                              || p.equals("/accounts/result/")
                              || p.equals("/accounts/resend-verification/")
                              || p.equals("/accounts/logout/")
                              || p.startsWith("/css/")
                              || p.startsWith("/js/")
                              || p.equals("/error");
                      if (!narrow) {
                        try {
                          authz.current();
                        } catch (DomainException incomplete) {
                          if (p.endsWith(".json")) {
                            res.setStatus(403);
                            res.setContentType("application/json");
                            res.getWriter().write("{\"error\":\"mfa_required\"}");
                          } else {
                            res.sendRedirect("/accounts/mfa/");
                          }
                          return;
                        }
                      }
                    } catch (DomainException expired) {
                      unauthorized(req, res);
                      return;
                    }
                  }
                  chain.doFilter(req, res);
                } finally {
                  org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                      previous);
                }
              }
            },
            AnonymousAuthenticationFilter.class)
        .build();
  }

  private static void unauthorized(HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    if (req.getRequestURI().endsWith(".json") || req.getRequestURI().startsWith("/exports/")) {
      res.setStatus(401);
      res.setContentType("application/json");
      res.getWriter()
          .write(
              "{\"error\":\"authentication_expired\",\"correlation_id\":\""
                  + UUID.randomUUID()
                  + "\"}");
    } else res.sendRedirect("/accounts/login/");
  }
}
