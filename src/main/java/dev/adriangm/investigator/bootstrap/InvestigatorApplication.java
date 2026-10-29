package dev.adriangm.investigator.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot entry point for the investigation service and its scheduled worker. */
@SpringBootApplication(scanBasePackages = "dev.adriangm.investigator")
@EnableScheduling
public class InvestigatorApplication {
    /** Starts the configured application and its framework-managed lifecycle. */
    public static void main(String[] args)
    {
        SpringApplication.run(InvestigatorApplication.class, args);
    }
}
