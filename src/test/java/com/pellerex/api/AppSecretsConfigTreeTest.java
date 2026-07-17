package com.pellerex.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.pellerex.api.config.AppSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * J4 / JV-D6 — proves the CSI tmpfs file-mount contract: a directory of key-per-file secrets is
 * read by {@code spring.config.import=configtree:} and bound straight into {@link AppSecrets} — no
 * env var, no Kubernetes Secret. The @TempDir stands in for the {@code /mnt/secrets-store/} mount.
 */
class AppSecretsConfigTreeTest {

    @Test
    void readsKeyVaultSecretFromConfigTreeFileMount(@TempDir Path secretsDir) throws IOException {
        Files.writeString(secretsDir.resolve("DbConnectionString"), "Server=db;Database=app;User Id=sa;");

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.import=configtree:" + secretsDir.toString() + "/")
                .withUserConfiguration(AppSecrets.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppSecrets secrets = context.getBean(AppSecrets.class);
                    assertThat(secrets.hasDbConnectionString()).isTrue();
                    assertThat(secrets.getDbConnectionString()).isEqualTo("Server=db;Database=app;User Id=sa;");
                });
    }
}
