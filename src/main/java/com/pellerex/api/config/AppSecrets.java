package com.pellerex.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Key Vault secrets, read from the CSI driver's tmpfs <strong>file mount</strong> (JV-D6).
 *
 * <p>The Secrets Store CSI driver mounts each Key Vault secret as a file under
 * {@code /mnt/secrets-store/}; {@code spring.config.import=configtree:/mnt/secrets-store/} (see
 * application.yml) turns each file into a property keyed by its file name, so {@code DbConnectionString}
 * binds straight in. The value never touches an env var or a Kubernetes Secret in etcd — the tmpfs
 * mount is the security best practice (JV-R13). The {@code :} default keeps the app bootable when the
 * mount is absent (local dev, or {@code secret-provider-class-enabled=false}).
 */
@Component
public class AppSecrets {

    private final String dbConnectionString;

    public AppSecrets(@Value("${DbConnectionString:}") String dbConnectionString) {
        this.dbConnectionString = dbConnectionString;
    }

    public boolean hasDbConnectionString() {
        return dbConnectionString != null && !dbConnectionString.isBlank();
    }

    public String getDbConnectionString() {
        return dbConnectionString;
    }
}
