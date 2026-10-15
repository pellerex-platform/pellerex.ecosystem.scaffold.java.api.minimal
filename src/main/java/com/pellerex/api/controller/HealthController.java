package com.pellerex.api.controller;

import com.pellerex.api.health.HealthTester;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform health endpoints (JV-D12).
 *
 * <p>A plain {@code @RestController} serving {@code /health/startup}, {@code /health/live} and
 * {@code /health/ready} at the <strong>root</strong> (no {@code /api}, no version prefix). These
 * exact three paths are the ones ProxyApi bypasses for auth + metering, so they must not be
 * versioned or remapped. This is deliberately <strong>not</strong> Spring Boot Actuator (whose base
 * path is {@code /actuator} and which has no native {@code startup} group).
 *
 * <p>Each answers one word from the last result of the {@link HealthTester}: {@code Healthy} (200)
 * or {@code Unhealthy} (503). Anyone can call them, so what failed and why goes to the health
 * test's log line only. The words are ASP.NET Core's own, which the .NET scaffold answers with, so
 * an app answers the same in every language. The chart's Kubernetes checks call them.
 */
@RestController
public class HealthController {

    private static final String HEALTHY = "Healthy";
    private static final String UNHEALTHY = "Unhealthy";
    private static final MediaType PLAIN_TEXT =
            new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    private final HealthTester healthTester;

    public HealthController(HealthTester healthTester) {
        this.healthTester = healthTester;
    }

    /** Healthy once the first health test has finished, whatever it found. */
    @GetMapping("/health/startup")
    public ResponseEntity<String> startup() {
        return answer(this.healthTester.started());
    }

    /** Healthy when the last health test passed. */
    @GetMapping("/health/live")
    public ResponseEntity<String> live() {
        return answer(this.healthTester.healthy());
    }

    /** Healthy when the last health test passed. */
    @GetMapping("/health/ready")
    public ResponseEntity<String> ready() {
        return answer(this.healthTester.healthy());
    }

    private static ResponseEntity<String> answer(boolean healthy) {
        return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(PLAIN_TEXT)
                .body(healthy ? HEALTHY : UNHEALTHY);
    }
}
