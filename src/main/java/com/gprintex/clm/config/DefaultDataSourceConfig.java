package com.gprintex.clm.config;

import com.gprintex.clm.config.SimpleJdbcCallFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * Default JDBC configuration when Oracle Wallet is not enabled.
 * Uses the Spring Boot auto-configured DataSource.
 */
@Configuration
@ConditionalOnProperty(name = "oracle.wallet.enabled", havingValue = "false", matchIfMissing = true)
public class DefaultDataSourceConfig {

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    public SimpleJdbcCallFactory simpleJdbcCallFactory(DataSource dataSource) {
        return new SimpleJdbcCallFactory(dataSource);
    }
}
