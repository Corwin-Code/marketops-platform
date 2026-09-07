package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.internal.application.UserAdministrationService;
import com.mimococo.marketops.identityaccess.internal.domain.UserScopeGrantRecord;
import com.mimococo.marketops.identityaccess.internal.web.BrowserSigningFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Signed loopback HTTP exercises the real principal resolver and live PostgreSQL grants. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "marketops.identity.oidc.issuer-uri=https://id.example.test/browser",
        "marketops.identity.oidc.jwk-set-uri=https://127.0.0.1/unused-secondary-decoder",
        "marketops.identity.oidc.audience=marketops"})
@Import(BrowserSigningFixture.class)
@ActiveProfiles("ci")
class AdvertisingOperationsPrincipalIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE =
            TestDatabase.isolatedContainer();
    @LocalServerPort private int port;
    @Autowired private UserAdministrationService users;
    private JdbcClient seed;
    private AdvertisingR1Fixture.Graph graph;
    private UUID commandId;
    private String ownerToken;
    private String makerToken;
    private UserScopeGrantRecord viewGrant;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @BeforeEach
    void fixture() throws Exception {
        var migration = new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword());
        seed = JdbcClient.create(migration);
        graph = AdvertisingR1Fixture.seed(migration);
        var administrator = new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                DATABASE.getUsername(), DATABASE.getPassword());
        var application = new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                TestDatabase.applicationRole(), TestDatabase.applicationPassword());
        try (var connection = application.getConnection()) {
            connection.setAutoCommit(false);
            String proof = AdvertisingR1Fixture.proof(administrator, connection, graph,
                    graph.id("ownerUser"), null, graph.id("recommendation"), graph.id("approval"));
            AdvertisingR1Fixture.seal(connection, graph, proof);
            commandId = AdvertisingR1Fixture.createCommand(connection, graph);
            connection.commit();
        }
        // R1 supplies the actual Owner and Maker roles. Only this read grant is added.
        viewGrant = users.grantScope("principal-fixture", graph.id("ownerUser"),
                ActionScopeCode.ADVERTISING_VIEW, ResourceScopeType.ORGANIZATION,
                graph.id("organization"), null);
        ownerToken = token(graph.id("ownerUser"));
        makerToken = token(graph.id("executorUser"));
    }

    @Test
    void eachOperationsReadUsesTheSignedPrincipalAndRefusesAnonymousIdentityFields() throws Exception {
        for (String path : paths()) {
            assertThat(http(ownerToken, path).statusCode()).as("accepted principal: %s", path).isEqualTo(200);
            assertThat(http(null, path + spoofedIdentity()).statusCode())
                    .as("anonymous query cannot create a principal: %s", path).isEqualTo(401);
        }
        assertThat(http(ownerToken, "/commands/" + commandId).body()).contains(commandId.toString());
        assertThat(http(ownerToken, "/reservations").body()).contains(graph.id("reservation").toString());
    }

    @Test
    void requestIdentityAndRoleFieldsCannotBorrowTheOwnersReadGrant() throws Exception {
        for (String path : paths()) {
            assertThat(http(ownerToken, path).statusCode()).as("positive grant control: %s", path).isEqualTo(200);
            var denied = http(makerToken, path + spoofedIdentity());
            assertThat(denied.statusCode()).as("signed Maker cannot become Owner: %s", path).isEqualTo(403);
            assertThat(denied.body()).doesNotContain(graph.id("reservation").toString());
        }
    }

    @Test
    void theSameSignedPrincipalCannotReuseAnAdvertisingGrantAfterRevocation() throws Exception {
        for (String path : paths()) {
            assertThat(http(ownerToken, path).statusCode()).as("live grant: %s", path).isEqualTo(200);
        }
        users.revokeScope("principal-fixture", viewGrant.id(), "Read scope withdrawn", viewGrant.version());
        for (String path : paths()) {
            assertThat(http(ownerToken, path + spoofedIdentity()).statusCode())
                    .as("same token and spoofed fields cannot revive the grant: %s", path).isEqualTo(403);
        }
    }

    private List<String> paths() {
        return List.of("/orchestration", "/reservations", "/exposure", "/containments",
                "/objects/" + graph.id("object") + "/manual-packets",
                "/commands/" + commandId, "/commands/" + commandId + "/outcomes");
    }

    private String spoofedIdentity() {
        return "?userId=" + graph.id("ownerUser") + "&organizationId=" + graph.id("organization")
                + "&identityProviderId=" + graph.id("provider") + "&roles=OWNER&multiFactorPresent=true"
                + "&actor.userId=" + graph.id("ownerUser") + "&actor.organizationId=" + graph.id("organization")
                + "&authentication.name=" + graph.id("ownerUser") + "&authentication.authenticated=true"
                + "&authentication.principal.userId=" + graph.id("ownerUser");
    }

    private String token(UUID user) throws Exception {
        UUID provider = seed.sql("SELECT id FROM iam.identity_provider WHERE issuer=:issuer")
                .param("issuer", BrowserSigningFixture.ISSUER).query(UUID.class).optional().orElseGet(() -> {
                    seed.sql("UPDATE iam.identity_provider SET issuer=:issuer WHERE id=:id")
                            .param("issuer", BrowserSigningFixture.ISSUER).param("id", graph.id("provider")).update();
                    return graph.id("provider");
                });
        String subject = "principal-fixture-" + user;
        seed.sql("""
                UPDATE iam.user_account SET identity_provider_id=:provider, external_subject=:subject,
                  credentials_valid_from=now()-interval '1 day' WHERE id=:id
                """).param("provider", provider).param("subject", subject).param("id", user).update();
        return BrowserSigningFixture.token(subject);
    }

    private HttpResponse<String> http(String token, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/api/v1/console/advertising" + path)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
