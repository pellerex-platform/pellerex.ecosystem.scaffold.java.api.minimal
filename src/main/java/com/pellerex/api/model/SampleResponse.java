package com.pellerex.api.model;

/**
 * Sample response view model. {@code dbConnectionStringConfigured} is a boolean so the secret value
 * itself never leaves the process.
 */
public record SampleResponse(int score, boolean dbConnectionStringConfigured) {
}
