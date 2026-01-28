package com.gprintex.clm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CLM application configuration properties.
 */
@ConfigurationProperties(prefix = "clm")
public record ClmProperties(
    TenantProperties tenant,
    EtlProperties etl,
    IntegrationProperties integration
) {
    public ClmProperties {
        tenant = tenant != null ? tenant : new TenantProperties(null);
        etl = etl != null ? etl : new EtlProperties(0, 0, 0);
        integration = integration != null ? integration : new IntegrationProperties(0, 0, 0);
    }

    public record TenantProperties(String defaultId) {
        public TenantProperties {
            if (defaultId == null || defaultId.isBlank()) {
                defaultId = "DEFAULT";
            }
        }
    }

    public record EtlProperties(
        int batchSize,
        int parallelConsumers,
        int stagingRetentionDays
    ) {
        public EtlProperties {
            if (batchSize <= 0) batchSize = 1000;
            if (parallelConsumers <= 0) parallelConsumers = 4;
            if (stagingRetentionDays <= 0) stagingRetentionDays = 30;
        }
    }

    public record IntegrationProperties(
        int dedupWindowHours,
        int maxRetries,
        int aggregationTimeoutSeconds
    ) {
        public IntegrationProperties {
            if (dedupWindowHours <= 0) dedupWindowHours = 24;
            if (maxRetries <= 0) maxRetries = 3;
            if (aggregationTimeoutSeconds <= 0) aggregationTimeoutSeconds = 300;
        }
    }
}
