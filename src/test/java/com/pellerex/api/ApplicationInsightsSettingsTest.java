package com.pellerex.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The Application Insights agent records every call the app serves as a request, and the portal's
 * request list shows them. The chart's Kubernetes checks call the health addresses every minute, so
 * the agent's settings leave those calls out, as the request line does ({@code CorrelationIdFilter}).
 */
class ApplicationInsightsSettingsTest {

    private static final Path SETTINGS = Path.of("applicationinsights.json");

    /**
     * Each setting drops the requests to one exact path. The path holds no query string (the agent
     * keeps that apart), so a call with one is left out too, and an address that only looks like a
     * health address, such as /health/readyz, is still recorded.
     */
    @Test
    void theAgentLeavesOutCallsToTheThreeHealthAddressesOnly() throws IOException {
        // The Dockerfile's unit-tests stage copies the settings in; a build with only pom.xml and src/ has none
        assumeTrue(Files.exists(SETTINGS), "the agent's settings are not in this build");
        JsonNode overrides = new ObjectMapper().readTree(SETTINGS.toFile()).at("/sampling/overrides");

        assertThat(overrides).allSatisfy(override -> {
            assertThat(override.path("telemetryType").asText()).isEqualTo("request");
            assertThat(override.path("percentage").asInt(-1)).isZero();
            assertThat(override.path("attributes")).hasSize(1);
            assertThat(override.at("/attributes/0/key").asText()).isEqualTo("url.path");
            assertThat(override.at("/attributes/0/matchType").asText()).isEqualTo("strict");
        });
        assertThat(overrides)
                .extracting(override -> override.at("/attributes/0/value").asText())
                .containsExactlyInAnyOrder("/health/startup", "/health/live", "/health/ready");
    }
}
