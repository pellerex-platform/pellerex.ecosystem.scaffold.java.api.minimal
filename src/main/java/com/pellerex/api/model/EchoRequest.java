package com.pellerex.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sample request body. Bean Validation constraints (JV-D21) reject malformed input with a 400
 * (turned into a clean envelope by the {@code @RestControllerAdvice}).
 */
public record EchoRequest(

        @NotBlank
        @Size(max = 280)
        String message) {
}
