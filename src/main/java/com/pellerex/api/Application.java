package com.pellerex.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Pellerex Managed API Service — Spring Boot REST API scaffold (v1).
 *
 * <p>Embedded Tomcat binds the hard-coded platform port 8890 (see application.yml). Telemetry is
 * codeless via the Application Insights Java agent ({@code -javaagent}); secrets are read from the
 * CSI tmpfs file mount via {@code spring.config.import=configtree:}. No engine code is Java-aware —
 * this scaffold is the only Java-specific artifact.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
