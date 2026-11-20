package com.smartprint;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 foundation check: real PostgreSQL, real Flyway migration,
 * Hibernate schema validation, health endpoint and OpenAPI document.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmartPrintApplicationTests {

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        // Fail fast instead of retrying a storage service that is not part of this test.
        registry.add("smartprint.storage.endpoint", () -> "http://127.0.0.1:1");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate rest;

    @Test
    void flywayMigrationAppliesAndCreatesCoreTables() {
        Integer applied = jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertThat(applied).isNotNull().isGreaterThanOrEqualTo(1);

        List<String> tables = jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'public'
            """, String.class);

        assertThat(tables).contains(
            "shops", "users", "roles", "customer_sessions", "orders", "order_files",
            "print_configs", "payments", "queue_counters", "print_queue_entries",
            "print_agents", "printers", "printer_capabilities", "print_jobs");
    }

    @Test
    void shopScopedCompositeForeignKeyTargetExists() {
        // print_queue_entries references orders(id, shop_id); PostgreSQL requires this
        // unique constraint, and its presence proves the migration fix is in effect.
        Integer constraints = jdbc.queryForObject("""
            select count(*) from pg_constraint c
            join pg_class t on t.oid = c.conrelid
            where t.relname = 'orders' and c.contype = 'u'
              and pg_get_constraintdef(c.oid) = 'UNIQUE (id, shop_id)'
            """, Integer.class);
        assertThat(constraints).isEqualTo(1);
    }

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/health", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void openApiDocumentIsServed() {
        ResponseEntity<Map> response = rest.getForEntity("/v3/api-docs", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("openapi");
        assertThat(response.getBody()).containsKey("paths");
    }
}
