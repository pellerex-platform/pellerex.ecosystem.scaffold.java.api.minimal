package com.pellerex.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The chart's Kubernetes checks act on the health test, which runs once a minute: out of traffic
 * after two failed tests, restarted after five, about 5 minutes, and an answer that takes over 5
 * seconds counts as failed. And the test's line is written in every environment.
 */
class HealthChartTest {

    private static final Path CHART = Path.of("infrastructure/Helm/templates/deployment.yaml");

    /**
     * Every "key: value" line under one Kubernetes check of the chart, with the nesting dropped
     * ("path", "periodSeconds"). The chart is a Helm template, not plain YAML, so it is read as text.
     */
    private static Map<String, String> probeSettings(List<String> chart, String probe) {
        Map<String, String> settings = new HashMap<>();
        int start = chart.stream().map(String::strip).toList().indexOf(probe + ":");
        assertThat(start).as("the chart has a %s", probe).isNotNegative();
        int indent = chart.get(start).indexOf(probe);
        for (String line : chart.subList(start + 1, chart.size())) {
            if (line.length() - line.stripLeading().length() <= indent) {
                break;
            }
            String[] pair = line.strip().split(":", 2);
            if (pair.length == 2 && !pair[1].isBlank()) {
                settings.put(pair[0], pair[1].strip());
            }
        }
        return settings;
    }

    @Test
    void chartChecksActOnTheHealthTestAtTheTwoSpeeds() throws IOException {
        // The Docker build compiles and tests without the chart (.dockerignore leaves infrastructure/ out)
        assumeTrue(Files.exists(CHART), "the chart is not in this build");
        List<String> chart = Files.readAllLines(CHART);

        assertThat(probeSettings(chart, "startupProbe"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "path", "/health/startup", "port", "http",
                        "periodSeconds", "5", "failureThreshold", "24"));
        assertThat(probeSettings(chart, "readinessProbe"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "path", "/health/ready", "port", "http",
                        "periodSeconds", "60", "timeoutSeconds", "5", "failureThreshold", "2"));
        assertThat(probeSettings(chart, "livenessProbe"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "path", "/health/live", "port", "http",
                        "periodSeconds", "60", "timeoutSeconds", "5", "failureThreshold", "5"));
    }

    /** Production logs the app at WARN; the health test's passed line is INFO and still written. */
    @Test
    void theHealthTestWritesItsLineAtEveryLevel() throws IOException {
        List<PropertySource<?>> settings =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));

        assertThat(settings.get(0).getProperty("logging.level.com.pellerex.api.health")).isEqualTo("INFO");
    }
}
