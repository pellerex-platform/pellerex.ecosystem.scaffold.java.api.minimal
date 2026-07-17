package com.pellerex.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.pellerex.api.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** J3 / JV-D21 — @Validated config binds from properties and fails fast when a required value is
 *  missing (so a misconfigured pod never starts). */
class AppPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    void bindsWhenRequiredConfigPresent() {
        runner.withPropertyValues("app.allowed-origins=https://www.pellerex.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AppProperties.class).getAllowedOrigins())
                            .isEqualTo("https://www.pellerex.com");
                });
    }

    @Test
    void failsFastWhenRequiredConfigMissing() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }
}
