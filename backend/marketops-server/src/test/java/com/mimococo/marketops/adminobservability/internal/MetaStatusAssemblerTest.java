package com.mimococo.marketops.adminobservability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.shared.CorrelationId;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Establishes what the metadata resource reports when its sources are degraded.
 *
 * <p>Each dependency is optional at runtime, so the cases below describe the
 * value an operator sees when one of them is missing or unreachable. A field
 * that silently disappeared would leave the console unable to distinguish an
 * unreachable backend from a reachable one with a broken data layer.
 */
class MetaStatusAssemblerTest {

    private static final Instant FIXED = Instant.parse("2026-08-14T10:15:30.987654Z");
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

    @AfterEach
    void clearLoggingContext() {
        MDC.remove(CorrelationId.LOG_CONTEXT_KEY);
    }

    @Test
    @DisplayName("configured names are reported verbatim")
    void configuredNamesAreReported() {
        MetaStatusResponse response = assembler(null, null, null).assemble();

        assertThat(response.product()).isEqualTo("MarketOps Russia");
        assertThat(response.application()).isEqualTo("marketops-server");
        assertThat(response.environment()).isEqualTo("test");
    }

    @Test
    @DisplayName("the reported time comes from the injected clock and drops sub-second detail")
    void serverTimeUsesTheInjectedClock() {
        MetaStatusResponse response = assembler(null, null, null).assemble();

        assertThat(response.serverTimeUtc()).isEqualTo("2026-08-14T10:15:30Z");
    }

    @Test
    @DisplayName("a build without metadata reports unknown rather than failing")
    void missingBuildPropertiesDegradeToUnknown() {
        MetaStatusResponse response = assembler(null, null, null).assemble();

        assertThat(response.buildVersion()).isEqualTo(MetaStatusAssembler.UNKNOWN_VERSION);
        assertThat(response.gitCommit()).isEqualTo(MetaStatusAssembler.UNKNOWN_COMMIT);
    }

    @Test
    @DisplayName("a hexadecimal commit is published")
    void hexadecimalCommitIsPublished() {
        BuildProperties build = buildProperties("0.1.0-SNAPSHOT", "3ecc72ae509664ff0550f80ece98d4f50dbb0bc0");

        MetaStatusResponse response = assembler(build, null, null).assemble();

        assertThat(response.buildVersion()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(response.gitCommit()).isEqualTo("3ecc72ae509664ff0550f80ece98d4f50dbb0bc0");
    }

    @Test
    @DisplayName("a commit value that is not an object name is not published")
    void unexpectedCommitValueIsNotPublished() {
        for (String supplied : new String[] {
                "not-a-commit",
                "../../etc/passwd",
                "<script>alert(1)</script>",
                "3ecc72",
                "3ECC72AE509664FF0550F80ECE98D4F50DBB0BC0"}) {
            BuildProperties build = buildProperties("0.1.0-SNAPSHOT", supplied);

            MetaStatusResponse response = assembler(build, null, null).assemble();

            assertThat(response.gitCommit())
                    .as("value %s must not reach the response", supplied)
                    .isEqualTo(MetaStatusAssembler.UNKNOWN_COMMIT);
        }
    }

    @Test
    @DisplayName("no configured data source reports unknown, not down")
    void absentDataSourceIsUnknown() {
        MetaStatusResponse response = assembler(null, null, null).assemble();

        assertThat(response.database().status()).isEqualTo(MetaStatusAssembler.STATUS_UNKNOWN);
    }

    @Test
    @DisplayName("a validating connection reports the database as up")
    void validConnectionIsUp() throws SQLException {
        MetaStatusResponse response = assembler(null, dataSourceAnswering(true), null).assemble();

        assertThat(response.database().status()).isEqualTo(MetaStatusAssembler.STATUS_UP);
    }

    @Test
    @DisplayName("a connection that fails validation reports the database as down")
    void invalidConnectionIsDown() throws SQLException {
        MetaStatusResponse response = assembler(null, dataSourceAnswering(false), null).assemble();

        assertThat(response.database().status()).isEqualTo(MetaStatusAssembler.STATUS_DOWN);
    }

    @Test
    @DisplayName("an unreachable database reports down and never surfaces the driver message")
    void unreachableDatabaseIsDown() throws SQLException {
        DataSource source = mock(DataSource.class);
        when(source.getConnection())
                .thenThrow(new SQLException("connection to 10.0.0.7:5432 refused for marketops_app"));

        MetaStatusResponse response = assembler(null, source, null).assemble();

        assertThat(response.database().status()).isEqualTo(MetaStatusAssembler.STATUS_DOWN);
        assertThat(response.toString()).doesNotContain("10.0.0.7").doesNotContain("refused");
    }

    @Test
    @DisplayName("no Flyway bean reports an unknown schema version")
    void absentFlywayIsUnknown() {
        MetaStatusResponse response = assembler(null, null, null).assemble();

        assertThat(response.migration().currentVersion())
                .isEqualTo(MetaStatusAssembler.UNKNOWN_VERSION);
    }

    @Test
    @DisplayName("the applied schema version is reported without the migration description")
    void appliedVersionIsReported() {
        MigrationVersion version = MigrationVersion.fromVersion("1");
        MigrationInfo info = mock(MigrationInfo.class);
        when(info.getVersion()).thenReturn(version);
        MigrationInfoService service = mock(MigrationInfoService.class);
        when(service.current()).thenReturn(info);
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenReturn(service);

        MetaStatusResponse response = assembler(null, null, flyway).assemble();

        assertThat(response.migration().currentVersion()).isEqualTo(version.getVersion());
    }

    @Test
    @DisplayName("an empty schema history reports unknown rather than failing")
    void emptyHistoryIsUnknown() {
        MigrationInfoService service = mock(MigrationInfoService.class);
        when(service.current()).thenReturn(null);
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenReturn(service);

        MetaStatusResponse response = assembler(null, null, flyway).assemble();

        assertThat(response.migration().currentVersion())
                .isEqualTo(MetaStatusAssembler.UNKNOWN_VERSION);
    }

    @Test
    @DisplayName("an unreadable schema history reports unknown rather than failing the request")
    void unreadableHistoryIsUnknown() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenThrow(new IllegalStateException("history table is unreadable"));

        MetaStatusResponse response = assembler(null, null, flyway).assemble();

        assertThat(response.migration().currentVersion())
                .isEqualTo(MetaStatusAssembler.UNKNOWN_VERSION);
    }

    @Test
    @DisplayName("the payload quotes the identifier of the request being handled")
    void correlationIdentifierIsCarried() {
        MDC.put(CorrelationId.LOG_CONTEXT_KEY, "established-value");

        MetaStatusResponse response = assembler(null, null, null).assemble();

        assertThat(response.correlationId()).isEqualTo("established-value");
    }

    @Test
    @DisplayName("every field is populated even when every source is degraded")
    void noFieldIsEverNull() {
        MetaStatusResponse response = assembler(null, null, null).assemble();

        assertThat(response.product()).isNotNull();
        assertThat(response.application()).isNotNull();
        assertThat(response.environment()).isNotNull();
        assertThat(response.buildVersion()).isNotNull();
        assertThat(response.gitCommit()).isNotNull();
        assertThat(response.serverTimeUtc()).isNotNull();
        assertThat(response.database()).isNotNull();
        assertThat(response.database().status()).isNotNull();
        assertThat(response.migration()).isNotNull();
        assertThat(response.migration().currentVersion()).isNotNull();
        assertThat(response.correlationId()).isNotNull();
    }

    private MetaStatusAssembler assembler(BuildProperties build, DataSource source, Flyway flyway) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("assembler-test", Map.of(
                "marketops.product", "MarketOps Russia",
                "spring.application.name", "marketops-server",
                "marketops.environment", "test")));
        return new MetaStatusAssembler(
                CLOCK, environment, provider(build), provider(source), provider(flyway));
    }

    private static DataSource dataSourceAnswering(boolean valid) throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(valid);
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        return source;
    }

    private static BuildProperties buildProperties(String version, String commit) {
        Properties properties = new Properties();
        properties.setProperty("group", "com.mimococo.marketops");
        properties.setProperty("artifact", "marketops-server");
        properties.setProperty("version", version);
        properties.setProperty("gitCommit", commit);
        return new BuildProperties(properties);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
