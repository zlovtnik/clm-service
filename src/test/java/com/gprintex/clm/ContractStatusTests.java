package com.gprintex.clm;

import com.gprintex.clm.domain.ContractStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ContractStatus state machine.
 */
class ContractStatusTests {

    @ParameterizedTest
    @CsvSource({
        "DRAFT, PENDING, true",
        "DRAFT, CANCELLED, true",
        "DRAFT, ACTIVE, false",
        "PENDING, ACTIVE, true",
        "PENDING, CANCELLED, true",
        "PENDING, DRAFT, true",
        "PENDING, COMPLETED, false",
        "ACTIVE, SUSPENDED, true",
        "ACTIVE, CANCELLED, true",
        "ACTIVE, COMPLETED, true",
        "ACTIVE, DRAFT, false",
        "SUSPENDED, ACTIVE, true",
        "SUSPENDED, CANCELLED, true",
        "SUSPENDED, COMPLETED, false",
        "CANCELLED, ACTIVE, false",
        "CANCELLED, DRAFT, false",
        "COMPLETED, ACTIVE, false",
        "COMPLETED, CANCELLED, false"
    })
    void canTransitionTo_shouldValidateStateTransitions(
        String from, String to, boolean expected
    ) {
        var fromStatus = ContractStatus.valueOf(from);
        var toStatus = ContractStatus.valueOf(to);
        
        assertEquals(expected, fromStatus.canTransitionTo(toStatus));
    }

    @Test
    void draft_canOnlyTransitionToPendingOrCancelled() {
        var draft = ContractStatus.DRAFT;
        
        assertTrue(draft.canTransitionTo(ContractStatus.PENDING));
        assertTrue(draft.canTransitionTo(ContractStatus.CANCELLED));
        
        assertFalse(draft.canTransitionTo(ContractStatus.ACTIVE));
        assertFalse(draft.canTransitionTo(ContractStatus.SUSPENDED));
        assertFalse(draft.canTransitionTo(ContractStatus.COMPLETED));
    }

    @Test
    void terminalStates_cannotTransition() {
        assertFalse(ContractStatus.CANCELLED.canTransitionTo(ContractStatus.ACTIVE));
        assertFalse(ContractStatus.COMPLETED.canTransitionTo(ContractStatus.ACTIVE));
    }
}
