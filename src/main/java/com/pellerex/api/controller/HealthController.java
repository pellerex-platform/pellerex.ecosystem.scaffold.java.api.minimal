package com.pellerex.api.controller;

import com.pellerex.api.config.AppProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform health endpoints (JV-D12).
 *
 * <p>A plain {@code @RestController} serving {@code /health/startup}, {@code /health/live} and
 * {@code /health/ready} at the <strong>root</strong> (no {@code /api}, no version prefix), each
 * returning 200. These exact three paths are the ones ProxyApi bypasses for auth + metering, so
 * they must not be versioned or remapped. This is deliberately <strong>not</strong> Spring Boot
 * Actuator (whose base path is {@code /actuator} and which has no native {@code startup} group).
 * The chart defines no Kubernetes probes — reachability is proven through ProxyApi.
 */
@RestController
public class HealthController {

    private final AppProperties appProperties;

    public HealthController(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @GetMapping("/health/startup")
    public ResponseEntity<Map<String, String>> startup() {
        return ResponseEntity.ok(status("startup"));
    }

    @GetMapping("/health/live")
    public ResponseEntity<Map<String, String>> live() {
        return ResponseEntity.ok(status("live"));
    }

    /**
     * Readiness — confirms required configuration has bound at boot (JV-D12). If the app reached
     * this point the {@code @Validated} config props already passed fail-fast validation, so the
     * service is ready to serve.
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        boolean configBound = appProperties != null && appProperties.getAllowedOrigins() != null;
        Map<String, String> body = status("ready");
        body.put("config", configBound ? "bound" : "missing");
        return configBound
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(503).body(body);
    }

    private Map<String, String> status(String probe) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("probe", probe);
        return body;
    }
}
