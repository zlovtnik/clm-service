package com.gprintex.clm.camel;

import com.gprintex.clm.service.EtlService;
import org.apache.camel.CamelContext;
import org.apache.camel.EndpointInject;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.apache.camel.test.spring.junit5.UseAdviceWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration tests for Camel routes.
 * 
 * NOTE: These tests require an Oracle database with stored procedures.
 * Run with -Dspring.profiles.active=oracle when Oracle is available.
 */
@CamelSpringBootTest
@SpringBootTest
@UseAdviceWith
@ActiveProfiles("test")
@Disabled("Requires Oracle database - run with Oracle profile when available")
class CamelRouteTests {

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private ProducerTemplate producerTemplate;

    @MockBean
    private EtlService etlService;

    @EndpointInject("mock:contract-stage")
    private MockEndpoint mockContractStage;

    @BeforeEach
    void setUp() throws Exception {
        // Mock the EtlService to return a session ID
        when(etlService.createSession(anyString(), anyString())).thenReturn("TEST-SESSION-001");
    }

    private void setupAdviceWith() throws Exception {
        // Replace real endpoints with mocks for testing
        AdviceWith.adviceWith(camelContext, "contract-ingest", a -> {
            a.weaveByToUri("direct:contract-stage").replace().to("mock:contract-stage");
        });
        
        camelContext.start();
    }

    @Test
    void contractIngest_shouldRouteToStaging() throws Exception {
        setupAdviceWith();
        
        mockContractStage.expectedMessageCount(1);

        var contracts = List.of(
            Map.of("contractNumber", "CNT-001", "customerId", 100)
        );

        producerTemplate.sendBodyAndHeader(
            "direct:contract-ingest",
            contracts,
            "sourceSystem", "TEST"
        );

        mockContractStage.assertIsSatisfied();
    }
}
