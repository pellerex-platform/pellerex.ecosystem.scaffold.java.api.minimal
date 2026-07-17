package com.pellerex.api.controller;

import com.pellerex.api.config.AppSecrets;
import com.pellerex.api.model.EchoRequest;
import com.pellerex.api.model.SampleResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sample versioned API surface (mirrors the .NET reference {@code /v1/hello}).
 *
 * <p>It proves the Key Vault secret is readable from the CSI tmpfs mount (via {@link AppSecrets},
 * bound from {@code configtree:}) without ever returning the secret value, and exercises
 * {@code @Valid} request validation (JV-D21) which the {@code @RestControllerAdvice} turns into a
 * clean 400.
 */
@RestController
@RequestMapping("/v1")
public class SampleController {

    private final AppSecrets appSecrets;

    public SampleController(AppSecrets appSecrets) {
        this.appSecrets = appSecrets;
    }

    @GetMapping("/hello")
    public SampleResponse hello() {
        // Report only whether the secret was read — never echo the secret value back.
        return new SampleResponse(110, appSecrets.hasDbConnectionString());
    }

    @PostMapping("/echo")
    public SampleResponse echo(@Valid @RequestBody EchoRequest request) {
        return new SampleResponse(request.message().length(), appSecrets.hasDbConnectionString());
    }
}
