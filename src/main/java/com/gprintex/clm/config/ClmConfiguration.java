package com.gprintex.clm.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    OracleWalletProperties.class,
    ClmProperties.class
})
public class ClmConfiguration {
}
