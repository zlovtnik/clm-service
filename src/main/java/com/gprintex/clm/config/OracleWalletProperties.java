package com.gprintex.clm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Oracle Wallet connection.
 */
@ConfigurationProperties(prefix = "oracle.wallet")
public record OracleWalletProperties(
    String walletLocation,
    String tnsName
) {
    public OracleWalletProperties {
        if (walletLocation == null || walletLocation.isBlank()) {
            walletLocation = System.getenv().getOrDefault("ORACLE_WALLET_LOCATION", "/opt/oracle/wallet");
        }
        if (tnsName == null || tnsName.isBlank()) {
            tnsName = System.getenv().getOrDefault("ORACLE_TNS_NAME", "clm_db");
        }
    }
}
