package com.hassankarkaba.mprp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@org.springframework.boot.context.properties.EnableConfigurationProperties(
    com.hassankarkaba.mprp.operations.RuntimeProfile.class)
public class MprpApplication {
  public static void main(String[] args) {
    String command = System.getenv().getOrDefault("MPRP_COMMAND", "serve");
    for (String arg : args)
      if (arg.startsWith("--mprp.command=")) command = arg.substring("--mprp.command=".length());
    var application = new SpringApplication(MprpApplication.class);
    if (!command.equals("serve"))
      application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
    var context = application.run(args);
    if (!context.getEnvironment().getProperty("mprp.command", "serve").equals("serve"))
      System.exit(SpringApplication.exit(context));
  }
}
