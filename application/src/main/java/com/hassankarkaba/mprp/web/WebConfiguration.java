package com.hassankarkaba.mprp.web;

import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(
        new HandlerInterceptor() {
          @Override
          public void postHandle(
              HttpServletRequest request,
              HttpServletResponse response,
              Object handler,
              ModelAndView view) {
            if (view != null
                && view.getViewName() != null
                && view.getViewName().startsWith("redirect:")) {
              String path = view.getViewName().substring(9);
              if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\"))
                throw new com.hassankarkaba.mprp.operations.DomainException(
                    400, "Unsafe redirect target");
              view.setView(new RedirectView(path, false, false));
            }
          }
        });
  }
}
