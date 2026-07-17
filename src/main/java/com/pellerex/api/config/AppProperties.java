package com.pellerex.api.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed, boot-validated application configuration (JV-D21).
 *
 * <p>{@code @Validated} + Bean Validation constraints make startup <strong>fail fast</strong>: if a
 * required value is missing or invalid the context refuses to start, so a misconfigured pod never
 * reports healthy. Values come from {@code application-{profile}.yml} (profile selected by
 * {@code SPRING_PROFILES_ACTIVE} per environment).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Comma-separated CORS origins. Required — boot aborts if absent. */
    @NotBlank
    private String allowedOrigins;

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
