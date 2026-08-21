package com.example.platform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

@Service
public class DatabaseProvisionerService {

    @Value("${spring.datasource.url}")
    private String adminJdbcUrl;

    @Value("${spring.datasource.username}")
    private String adminUsername;

    @Value("${spring.datasource.password}")
    private String adminPassword;

    private static final String PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private final SecureRandom random = new SecureRandom();

    public record DbCredentials(String dbName, String username, String password) {}

    public DbCredentials provisionDatabase(UUID projectId) {
        // Sanitize the UUID-derived identifiers so they're safe as SQL identifiers —
        // UUIDs contain hyphens, which aren't valid in unquoted Postgres identifiers.
        String suffix = projectId.toString().replace("-", "");
        String dbName = "project_" + suffix;
        String username = "user_" + suffix;
        String password = generatePassword();

        try (Connection conn = DriverManager.getConnection(adminJdbcUrl, adminUsername, adminPassword);
             Statement stmt = conn.createStatement()) {

            // Identifiers (db/user names) cannot be parameterized in JDBC — but since we generated
            // them ourselves from a UUID (never from user input), there's no injection risk here.

            boolean userExists;
            try (ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + username + "'")) {
                userExists = rs.next();
            }

            if (!userExists) {
                stmt.execute("CREATE USER " + username + " WITH PASSWORD '" + password + "'");
            } else {
                stmt.execute("ALTER USER " + username + " WITH PASSWORD '" + password + "'");
            }

            // Postgres 16+ requires the creating role to have explicit membership in a role
            // before it can be set as a database OWNER — CREATEROLE alone isn't sufficient.
            stmt.execute("GRANT " + username + " TO " + adminUsername);

            boolean dbExists;
            try (ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'")) {
                dbExists = rs.next();
            }

            if (!dbExists) {
                stmt.execute("CREATE DATABASE " + dbName + " OWNER " + username);
            }

            stmt.execute("GRANT ALL PRIVILEGES ON DATABASE " + dbName + " TO " + username);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to provision database for project " + projectId, e);
        }

        return new DbCredentials(dbName, username, password);
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    public void deprovisionDatabase(String dbName, String username) {
        if (dbName == null || username == null) return;

        try (Connection conn = DriverManager.getConnection(adminJdbcUrl, adminUsername, adminPassword);
             Statement stmt = conn.createStatement()) {

            // Forcibly disconnect any lingering connections before dropping —
            // Postgres refuses DROP DATABASE while any session is still attached.
            stmt.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                            "WHERE datname = '" + dbName + "' AND pid <> pg_backend_pid()"
            );

            stmt.execute("DROP DATABASE IF EXISTS " + dbName);
            stmt.execute("DROP USER IF EXISTS " + username);

        } catch (Exception e) {
            // Best-effort cleanup — log but don't block project deletion if this fails.
            System.err.println("Warning: failed to deprovision database " + dbName + ": " + e.getMessage());
        }
    }
}