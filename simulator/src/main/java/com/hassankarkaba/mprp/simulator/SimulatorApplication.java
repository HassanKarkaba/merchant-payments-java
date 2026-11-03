package com.hassankarkaba.mprp.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SimulatorApplication {
  public static void main(String[] args) {
    String command = System.getenv().getOrDefault("MPRP_COMMAND", "serve");
    for (String arg : args)
      if (arg.startsWith("--mprp.command=")) command = arg.substring("--mprp.command=".length());
    SpringApplication app = new SpringApplication(SimulatorApplication.class);
    if (!command.equals("serve"))
      app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
    var context = app.run(args);
    if (!command.equals("serve")) context.close();
  }
}
