package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.adminobservability.internal.MetaStatusAssembler;
import com.mimococo.marketops.adminobservability.internal.MetaStatusResponse;
import com.mimococo.marketops.shared.internal.correlation.CorrelationIdFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Starts the application against a real server and asserts that the pieces are
 * wired to each other, not merely present.
 *
 * <p>The unit tests establish each behaviour in isolation; this one establishes
 * that the migration runs under the owning role, that the application pool
 * connects under its own, and that the metadata resource can therefore report a
 * schema version and a reachable database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("ci")
class ApplicationSmokeIT {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        var container = TestDatabase.container();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", () -> TestDatabase.applicationRole());
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", () -> TestDatabase.migrationRole());
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @Autowired
    private MetaStatusAssembler assembler;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @LocalServerPort
    private int serverPort;

    @Test
    @DisplayName("the application starts and reports a migrated, reachable database")
    void applicationReportsItsOwnState() {
        MetaStatusResponse response = assembler.assemble();

        assertThat(response.application()).isEqualTo("marketops-server");
        assertThat(response.environment()).isEqualTo("ci");
        assertThat(response.database().status()).isEqualTo(MetaStatusAssembler.STATUS_UP);
        assertThat(response.migration().currentVersion()).isNotEqualTo(MetaStatusAssembler.UNKNOWN_VERSION);
        assertThat(response.correlationId()).isNotBlank();
    }

    @Test
    @DisplayName("the application pool connects as the unprivileged role")
    void applicationPoolUsesTheApplicationRole() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT current_user")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(TestDatabase.applicationRole());
        }
    }

    @Test
    @DisplayName("the correlation filter is part of the running application")
    void correlationFilterIsRegistered() {
        assertThat(correlationIdFilter).isNotNull();
    }

    @Test
    @DisplayName("health names components and status without operational detail")
    void healthResponseNamesComponentsButWithholdsDetails() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + serverPort + "/actuator/health"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"status\":\"UP\"")
                .contains("\"components\"")
                .contains("\"db\"")
                .doesNotContain("\"details\"", "jdbc:", "marketops_app", "marketops_migration",
                        "password", "SELECT");
    }
}
