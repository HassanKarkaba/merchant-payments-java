package com.hassankarkaba.mprp.web;

import com.hassankarkaba.mprp.operations.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice
public class Errors {
  @ExceptionHandler(DomainException.class)
  public Object domain(DomainException e, HttpServletRequest request, Model model) {
    String correlation = UUID.randomUUID().toString();
    if (request.getRequestURI().endsWith(".json")
        || request.getRequestURI().startsWith("/callbacks/"))
      return ResponseEntity.status(e.status())
          .body(Map.of("error", e.getMessage(), "correlation_id", correlation));
    request.setAttribute("jakarta.servlet.error.status_code", e.status());
    model.addAttribute("title", "Request could not be completed");
    model.addAttribute("message", e.getMessage());
    model.addAttribute("correlation", correlation);
    return new org.springframework.web.servlet.ModelAndView(
        "error", model.asMap(), HttpStatus.valueOf(e.status()));
  }

  @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
  public Object invalid(RuntimeException e, HttpServletRequest r, Model m) {
    return domain(
        new DomainException(400, "Input is invalid or exceeds its supported bounds"), r, m);
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public Object data(Exception e, HttpServletRequest r, Model m) {
    return domain(
        new DomainException(
            409,
            "A concurrent change or database control blocked the request. Refresh and inspect the"
                + " retained result."),
        r,
        m);
  }

  @ExceptionHandler(Exception.class)
  public Object unexpected(Exception e, HttpServletRequest request, Model model) {
    return domain(
        new DomainException(
            500,
            "The request could not be completed. Inspect the retained record before retrying."),
        request,
        model);
  }
}
