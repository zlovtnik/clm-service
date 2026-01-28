package com.gprintex.clm.domain;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Integration message matching Oracle integration_messages table.
 */
public record IntegrationMessage(
    String messageId,
    Optional<String> correlationId,
    String messageType,
    Optional<String> sourceSystem,
    Optional<String> routingKey,
    Optional<String> destination,
    String payload,
    String status,
    LocalDateTime createdAt,
    Optional<LocalDateTime> processedAt,
    int retryCount,
    int maxRetries,
    Optional<LocalDateTime> nextRetryAt,
    Optional<String> errorMessage
) {
    public static IntegrationMessage create(
        String messageId,
        String messageType,
        String payload
    ) {
        return new IntegrationMessage(
            messageId,
            Optional.empty(),
            messageType,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            payload,
            MessageStatus.PENDING.name(),
            LocalDateTime.now(),
            Optional.empty(),
            0,
            3,
            Optional.empty(),
            Optional.empty()
        );
    }

    public IntegrationMessage withCorrelation(String correlationId) {
        return new IntegrationMessage(
            this.messageId, Optional.ofNullable(correlationId), messageType, sourceSystem,
            routingKey, destination, payload, status, createdAt, processedAt,
            retryCount, maxRetries, nextRetryAt, errorMessage
        );
    }

    /**
     * Create a copy with destination set. Does NOT change status.
     */
    public IntegrationMessage withDestination(String dest) {
        return new IntegrationMessage(
            messageId, correlationId, messageType, sourceSystem,
            routingKey, Optional.ofNullable(dest), payload, status,
            createdAt, processedAt, retryCount, maxRetries, nextRetryAt, errorMessage
        );
    }

    /**
     * Create a copy with destination set and status changed to PROCESSING.
     * @param dest the destination - must not be null
     * @throws IllegalArgumentException if dest is null
     */
    public IntegrationMessage withDestinationAndStartProcessing(String dest) {
        if (dest == null) {
            throw new IllegalArgumentException("dest must not be null - messages cannot enter PROCESSING without a destination");
        }
        return new IntegrationMessage(
            messageId, correlationId, messageType, sourceSystem,
            routingKey, Optional.of(dest), payload, MessageStatus.PROCESSING.name(),
            createdAt, processedAt, retryCount, maxRetries, nextRetryAt, errorMessage
        );
    }

    public IntegrationMessage completed() {
        return new IntegrationMessage(
            messageId, correlationId, messageType, sourceSystem,
            routingKey, destination, payload, MessageStatus.COMPLETED.name(),
            createdAt, Optional.of(LocalDateTime.now()), retryCount, maxRetries,
            Optional.empty(), Optional.empty()
        );
    }

    public IntegrationMessage failed(String error) {
        var newRetryCount = retryCount + 1;
        var newStatus = newRetryCount >= maxRetries 
            ? MessageStatus.DEAD_LETTER.name() 
            : MessageStatus.PENDING.name();
        var nextRetry = newRetryCount < maxRetries
            ? Optional.of(LocalDateTime.now().plusMinutes((long) Math.pow(2, newRetryCount)))
            : Optional.<LocalDateTime>empty();
            
        return new IntegrationMessage(
            messageId, correlationId, messageType, sourceSystem,
            routingKey, destination, payload, newStatus,
            createdAt, Optional.empty(), newRetryCount, maxRetries,
            nextRetry, Optional.of(error)
        );
    }

    public boolean canRetry() {
        return retryCount < maxRetries && !MessageStatus.DEAD_LETTER.name().equals(status);
    }
}
