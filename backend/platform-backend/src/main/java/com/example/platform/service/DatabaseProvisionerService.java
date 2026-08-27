package com.example.platform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

@Service
public class DatabaseProvisionerService {

    @Value("${spring.datasource.url}")
    private String pgAdminJdbcUrl;

    @Value("${spring.datasource.username}")
    private String pgAdminUsername;

    @Value("${spring.datasource.password}")
    private String pgAdminPassword;

    // Only needed if you plan to run MySQL-based projects — see application.properties for the
    // (commented-out, opt-in) template. Blank by default so absence doesn't break startup for
    // people who only ever use Postgres; provisioning a MySQL project without these configured
    // fails loudly with a real connection error, not a silent no-op.
    @Value("${spring.datasource.mysql.url:}")
    private String mysqlAdminJdbcUrl;

    @Value("${spring.datasource.mysql.username:}")
    private String mysqlAdminUsername;

    @Value("${spring.datasource.mysql.password:}")
    private String mysqlAdminPassword;

    private static final String PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private final SecureRandom random = new SecureRandom();

    /**
     * @param type "PostgreSQL" or "MySQL" — matches AnalyzerService's detectedDatabaseDriver
     *             values exactly, so callers can pass that field straight through.
     * @param port the port RunService should connect to on host.docker.internal (5432/3306).
     */
    public record DbCredentials(String dbName, String username, String password, String type, int port) {}

    public DbCredentials provisionPostgresDatabase(UUID projectId) {
        String suffix = projectId.toString().replace("-", "");
        String dbName = "project_" + suffix;
        String username = "user_" + suffix;
        String password = generatePassword();

        try (Connection conn = DriverManager.getConnection(pgAdminJdbcUrl, pgAdminUsername, pgAdminPassword);
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
            stmt.execute("GRANT " + username + " TO " + pgAdminUsername);

            boolean dbExists;
            try (ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'")) {
                dbExists = rs.next();
            }

            if (!dbExists) {
                stmt.execute("CREATE DATABASE " + dbName + " OWNER " + username);
            }

            stmt.execute("GRANT ALL PRIVILEGES ON DATABASE " + dbName + " TO " + username);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to provision Postgres database for project " + projectId, e);
        }

        return new DbCredentials(dbName, username, password, "PostgreSQL", 5432);
    }

    /**
     * MySQL counterpart of {@link #provisionPostgresDatabase}. Requires
     * spring.datasource.mysql.url/username/password to be configured with an admin account that
     * can create databases and users — see application.properties. The user is created with host
     * '%' (not 'localhost') since connections arrive from inside a Docker container, not from the
     * MySQL server's own host.
     */
    public DbCredentials provisionMySqlDatabase(UUID projectId) {
        if (mysqlAdminJdbcUrl == null || mysqlAdminJdbcUrl.isBlank()) {
            throw new IllegalStateException(
                    "MySQL provisioning was requested but spring.datasource.mysql.url is not configured. " +
                            "Add spring.datasource.mysql.url/username/password to application-local.properties " +
                            "(see application.properties for the template).");
        }

        String suffix = projectId.toString().replace("-", "");
        String dbName = "project_" + suffix;
        // MySQL usernames are capped at 32 characters (a MySQL-specific limit — Postgres allows
        // up to 63, which is why this only surfaced here). "user_" (5 chars) + the full 32-char
        // UUID suffix is 37, over the limit — truncate the suffix to 27 chars so the full
        // username is exactly 32. Collision risk from truncating a UUID to 27 hex chars is
        // astronomically low (still ~2^108 possibilities), and even a collision would just mean
        // reusing the same DB user across two projects, not a crash — worth knowing about, not
        // worth guarding against further.
        String username = "user_" + suffix.substring(0, 27);
        String password = generatePassword();

        try (Connection conn = DriverManager.getConnection(mysqlAdminJdbcUrl, mysqlAdminUsername, mysqlAdminPassword);
             Statement stmt = conn.createStatement()) {

            boolean userExists;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM mysql.user WHERE user = '" + username + "' AND host = '%'")) {
                userExists = rs.next();
            }

            if (!userExists) {
                stmt.execute("CREATE USER '" + username + "'@'%' IDENTIFIED BY '" + password + "'");
            } else {
                stmt.execute("ALTER USER '" + username + "'@'%' IDENTIFIED BY '" + password + "'");
            }

            stmt.execute("CREATE DATABASE IF NOT EXISTS " + dbName);
            stmt.execute("GRANT ALL PRIVILEGES ON " + dbName + ".* TO '" + username + "'@'%'");
            stmt.execute("FLUSH PRIVILEGES");

        } catch (Exception e) {
            throw new IllegalStateException("Failed to provision MySQL database for project " + projectId, e);
        }

        return new DbCredentials(dbName, username, password, "MySQL", 3306);
    }

    // Conventional locations checked for a repo-provided schema script, in priority order. This
    // is deliberately a plain "does this file exist" convention rather than anything configurable
    // — an explicit schema.sql committed to the repo is a clear, intentional signal from whoever
    // wrote the project, not a guess on this platform's part.
    private static final List<String> SCHEMA_SCRIPT_CANDIDATES = List.of(
            "schema.sql", "db/schema.sql", "sql/schema.sql", "database/schema.sql"
    );

    /**
     * Runs a repo-provided schema.sql (if one exists, at any of {@link #SCHEMA_SCRIPT_CANDIDATES})
     * against the just-provisioned database, using the project's own credentials — not the admin
     * connection, since the schema only needs to exist inside that one database. Silently does
     * nothing if no schema script is found (most projects, especially Spring Boot ones using
     * Flyway/JPA, won't have one and don't need one).
     *
     * Caller's responsibility: only call this once, right after first provisioning a database for
     * a project — never on a subsequent /run against an already-provisioned database, since
     * re-running CREATE TABLE against existing tables will fail. See RunController.
     *
     * Statement splitting is a naive split on ';' — good enough for a straightforward DDL script,
     * but will break on semicolons inside string literals/complex statements. Not a full SQL
     * parser; keep schema.sql scripts simple (plain CREATE TABLE statements).
     */
    public void runSchemaScriptIfPresent(String clonePath, DbCredentials credentials) {
        Path schemaScript = findSchemaScript(clonePath);
        if (schemaScript == null) {
            return; // No schema.sql in the repo — nothing to do, not an error.
        }

        String sql;
        try {
            sql = Files.readString(schemaScript);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read schema script at " + schemaScript, e);
        }

        String subprotocol = "MySQL".equals(credentials.type()) ? "mysql" : "postgresql";
        // localhost, not host.docker.internal — this runs from the backend JVM itself against
        // the DB server directly, the same way DatabaseProvisionerService's admin connections do.
        String jdbcUrl = "jdbc:" + subprotocol + "://localhost:" + credentials.port() + "/" + credentials.dbName();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, credentials.username(), credentials.password());
             Statement stmt = conn.createStatement()) {

            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (trimmed.isEmpty()) continue;
                stmt.execute(trimmed);
            }

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to run schema script (" + schemaScript.getFileName() + ") against database "
                            + credentials.dbName(), e);
        }
    }

    private Path findSchemaScript(String clonePath) {
        Path root = Path.of(clonePath);
        for (String candidate : SCHEMA_SCRIPT_CANDIDATES) {
            Path candidatePath = root.resolve(candidate);
            if (Files.exists(candidatePath)) {
                return candidatePath;
            }
        }
        return null;
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    /** @param dbType "PostgreSQL" or "MySQL" — routes to the matching admin connection. */
    public void deprovisionDatabase(String dbName, String username, String dbType) {
        if (dbName == null || username == null) return;

        if ("MySQL".equals(dbType)) {
            deprovisionMySql(dbName, username);
        } else {
            // Default to Postgres for null/"PostgreSQL"/anything else — matches this method's
            // pre-MySQL-support behavior for every project that predates this field existing.
            deprovisionPostgres(dbName, username);
        }
    }

    private void deprovisionPostgres(String dbName, String username) {
        try (Connection conn = DriverManager.getConnection(pgAdminJdbcUrl, pgAdminUsername, pgAdminPassword);
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
            System.err.println("Warning: failed to deprovision Postgres database " + dbName + ": " + e.getMessage());
        }
    }

    private void deprovisionMySql(String dbName, String username) {
        if (mysqlAdminJdbcUrl == null || mysqlAdminJdbcUrl.isBlank()) {
            System.err.println("Warning: cannot deprovision MySQL database " + dbName +
                    " — spring.datasource.mysql.url is not configured.");
            return;
        }

        try (Connection conn = DriverManager.getConnection(mysqlAdminJdbcUrl, mysqlAdminUsername, mysqlAdminPassword);
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP DATABASE IF EXISTS " + dbName);
            stmt.execute("DROP USER IF EXISTS '" + username + "'@'%'");

        } catch (Exception e) {
            System.err.println("Warning: failed to deprovision MySQL database " + dbName + ": " + e.getMessage());
        }
    }
}
