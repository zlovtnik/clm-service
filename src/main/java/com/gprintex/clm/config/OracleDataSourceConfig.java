package com.gprintex.clm.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Oracle DataSource configuration with Wallet support.
 * 
 * Wallet files required in TNS_ADMIN directory:
 * - tnsnames.ora
 * - sqlnet.ora
 * - cwallet.sso (auto-login wallet)
 * - ewallet.p12 (optional, for manual login)
 * 
 * This configuration is only activated when oracle.wallet.enabled=true
 */
@Configuration
@ConditionalOnProperty(name = "oracle.wallet.enabled", havingValue = "true")
public class OracleDataSourceConfig {

    @Value("${spring.datasource.username}")
    private String username;
    
    @Value("${spring.datasource.password}")
    private String password;

    @Bean
    public HikariDataSource dataSource(OracleWalletProperties walletProps) {
        // Validate wallet properties up-front
        if (walletProps.tnsName() == null || walletProps.tnsName().isBlank()) {
            throw new IllegalArgumentException("oracle.wallet.tnsName must be configured");
        }
        if (walletProps.walletLocation() == null || walletProps.walletLocation().isBlank()) {
            throw new IllegalArgumentException("oracle.wallet.walletLocation must be configured");
        }
        
        var dataSource = new HikariDataSource();
        
        // JDBC URL with full connection descriptor for reliable connections
        var jdbcUrl = String.format(
            "jdbc:oracle:thin:@%s?TNS_ADMIN=%s",
            walletProps.tnsName(),
            walletProps.walletLocation()
        );
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setDriverClassName("oracle.jdbc.OracleDriver");
        
        // Set username and password for wallet connections
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        
        // Oracle-specific connection properties
        var props = new Properties();
        props.setProperty("oracle.net.wallet_location", 
            "(SOURCE=(METHOD=FILE)(METHOD_DATA=(DIRECTORY=" + walletProps.walletLocation() + ")))");
        props.setProperty("oracle.net.tns_admin", walletProps.walletLocation());
        props.setProperty("oracle.jdbc.timezoneAsRegion", "false");
        dataSource.setDataSourceProperties(props);
        
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Factory for creating SimpleJdbcCall instances for stored procedures.
     */
    @Bean
    public SimpleJdbcCallFactory simpleJdbcCallFactory(DataSource dataSource) {
        return new SimpleJdbcCallFactory(dataSource);
    }
}
