package com.gprintex.clm;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import oracle.jdbc.OracleConnection;

public class TestConnection {
    public static void main(String[] args) {
        String user = System.getenv("ORACLE_USERNAME");
        String password = System.getenv("ORACLE_PASSWORD");
        String walletLocation = System.getenv("ORACLE_WALLET_LOCATION");
        String tnsName = System.getenv("ORACLE_TNS_NAME");
        
        System.out.println("=== Oracle Connection Test ===");
        System.out.println("Username: " + user);
        System.out.println("Password length: " + (password != null ? password.length() : "null"));
        System.out.println("Wallet location: " + walletLocation);
        System.out.println("TNS name: " + tnsName);

        if (isBlank(user) || isBlank(password) || isBlank(walletLocation) || isBlank(tnsName)) {
            System.err.println("Missing required environment variables; set ORACLE_USERNAME, ORACLE_PASSWORD, ORACLE_WALLET_LOCATION, ORACLE_TNS_NAME");
            return;
        }
        
        // Use TNS alias to honor wallet tnsnames.ora configuration
        String url = "jdbc:oracle:thin:@" + tnsName;
        
        System.out.println("URL: " + url);
        
        Properties props = new Properties();
        props.setProperty(OracleConnection.CONNECTION_PROPERTY_USER_NAME, user);
        props.setProperty(OracleConnection.CONNECTION_PROPERTY_PASSWORD, password);
        props.setProperty("oracle.net.wallet_location", 
            "(SOURCE=(METHOD=FILE)(METHOD_DATA=(DIRECTORY=" + walletLocation + ")))");
        props.setProperty(OracleConnection.CONNECTION_PROPERTY_TNS_ADMIN, walletLocation);
        
        try {
            Class.forName("oracle.jdbc.OracleDriver");
            System.out.println("Driver loaded successfully");
            
            try (Connection conn = DriverManager.getConnection(url, props);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT USER, SYS_CONTEXT('USERENV','SERVICE_NAME') FROM DUAL")) {
                System.out.println("Connected successfully!");
                if (rs.next()) {
                    System.out.println("Connected as: " + rs.getString(1));
                    System.out.println("Service: " + rs.getString(2));
                }
            }
        } catch (Exception e) {
            System.err.println("Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
