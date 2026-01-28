package com.gprintex.clm.config;

import org.springframework.jdbc.core.simple.SimpleJdbcCall;

import javax.sql.DataSource;
import java.util.function.Function;

/**
 * Factory for creating and configuring SimpleJdbcCall instances.
 * Provides a functional interface for stored procedure calls.
 */
public class SimpleJdbcCallFactory {

    private final DataSource dataSource;

    public SimpleJdbcCallFactory(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Create a SimpleJdbcCall for a stored procedure.
     */
    public SimpleJdbcCall forProcedure(String packageName, String procedureName) {
        return new SimpleJdbcCall(dataSource)
            .withCatalogName(packageName)
            .withProcedureName(procedureName);
    }

    /**
     * Create a SimpleJdbcCall for a stored function.
     */
    public SimpleJdbcCall forFunction(String packageName, String functionName) {
        return new SimpleJdbcCall(dataSource)
            .withCatalogName(packageName)
            .withFunctionName(functionName);
    }

    /**
     * Create and configure a SimpleJdbcCall with custom configuration.
     */
    public SimpleJdbcCall create(Function<SimpleJdbcCall, SimpleJdbcCall> configurator) {
        return configurator.apply(new SimpleJdbcCall(dataSource));
    }

    /**
     * Get the underlying data source.
     */
    public DataSource getDataSource() {
        return dataSource;
    }
}
