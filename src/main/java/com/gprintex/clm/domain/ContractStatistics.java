package com.gprintex.clm.domain;

import java.math.BigDecimal;

/**
 * Statistics summary for contracts within a tenant and time period.
 */
public record ContractStatistics(
    long totalContracts,
    long activeContracts,
    long pendingContracts,
    long expiredContracts,
    long cancelledContracts,
    BigDecimal totalValue,
    BigDecimal averageValue,
    long expiringWithin30Days,
    long autoRenewEnabled
) {
    public static ContractStatistics empty() {
        return new ContractStatistics(0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
    }
}
