package com.mimococo.marketops.adminobservability.internal;

import com.mimococo.marketops.shared.CorrelationId;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Builds the metadata payload from the application's own build and data state.
 *
 * <p>Every dependency is optional. A missing build-info file, an absent Flyway
 * bean, or an unreachable database degrades one field rather than failing the
 * request, because this resource is the console's only way to report that
 * something is wrong.
 *
 * <p>The database field is produced by a direct connection probe rather than by
 * consuming an actuator health bean. Several health beans share one type, so
 * selecting the database one by type is ambiguous and selecting it by bean name
 * ties this class to a name the framework owns.
 */
@Component
public class MetaStatusAssembler {

    /** Value reported when the source commit was not supplied at build time. */
    public static final String UNKNOWN_COMMIT = "unknown";

    /** Value reported when a version cannot be determined. */
    public static final String UNKNOWN_VERSION = "UNKNOWN";

    /** Database is reachable and the connection answers a validation query. */
    public static final String STATUS_UP = "UP";

    /** Database is configured but did not answer. */
    public static final String STATUS_DOWN = "DOWN";

    /** No data source is configured, so no statement can be made. */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /**
     * Seconds allowed for the connection validation.
     *
     * <p>The metadata resource is polled by the console, so an unreachable
     * database has to be reported quickly rather than holding a request thread
     * for the full network timeout.
     */
    static final int PROBE_TIMEOUT_SECONDS = 2;

    private static final Logger log = LoggerFactory.getLogger(MetaStatusAssembler.class);

    /** A commit is either a hexadecimal object name or the literal unknown value. */
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");

    private final Clock clock;
    private final String product;
    private final String application;
    private final String environment;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<DataSource> dataSource;
    private final ObjectProvider<Flyway> flyway;

    MetaStatusAssembler(Clock clock,
                        Environment springEnvironment,
                        ObjectProvider<BuildProperties> buildProperties,
                        ObjectProvider<DataSource> dataSource,
                        ObjectProvider<Flyway> flyway) {
        this.clock = clock;
        this.product = springEnvironment.getRequiredProperty("marketops.product");
        this.application = springEnvironment.getRequiredProperty("spring.application.name");
        this.environment = springEnvironment.getRequiredProperty("marketops.environment");
        this.buildProperties = buildProperties;
        this.dataSource = dataSource;
        this.flyway = flyway;
    }

    /**
     * Assemble the current metadata.
     *
     * @return a fully populated payload; no field is ever {@code null}
     */
    public MetaStatusResponse assemble() {
        return new MetaStatusResponse(
                product,
                application,
                environment,
                resolveBuildVersion(),
                resolveGitCommit(),
                Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString(),
                new MetaStatusResponse.DatabaseStatus(resolveDatabaseStatus()),
                new MetaStatusResponse.MigrationStatus(resolveMigrationVersion()),
                CorrelationId.current());
    }

    private String resolveBuildVersion() {
        BuildProperties properties = buildProperties.getIfAvailable();
        return properties != null ? properties.getVersion() : UNKNOWN_VERSION;
    }

    /**
     * Return the commit the artifact was built from.
     *
     * <p>The value is validated rather than passed through: it originates from a
     * build parameter, and an unexpected string would be published verbatim by an
     * endpoint that is readable without authentication.
     */
    private String resolveGitCommit() {
        BuildProperties properties = buildProperties.getIfAvailable();
        if (properties == null) {
            return UNKNOWN_COMMIT;
        }
        String value = properties.get("gitCommit");
        if (value == null || !COMMIT.matcher(value).matches()) {
            return UNKNOWN_COMMIT;
        }
        return value;
    }

    private String resolveDatabaseStatus() {
        DataSource source = dataSource.getIfAvailable();
        if (source == null) {
            return STATUS_UNKNOWN;
        }
        try (Connection connection = source.getConnection()) {
            return connection.isValid(PROBE_TIMEOUT_SECONDS) ? STATUS_UP : STATUS_DOWN;
        } catch (SQLException | RuntimeException exception) {
            logSanitizedProbeFailure("database_probe_failed", exception);
            return STATUS_DOWN;
        }
    }

    private String resolveMigrationVersion() {
        Flyway instance = flyway.getIfAvailable();
        if (instance == null) {
            return UNKNOWN_VERSION;
        }
        try {
            MigrationInfo current = instance.info().current();
            if (current == null || current.getVersion() == null) {
                return UNKNOWN_VERSION;
            }
            // Only the version is exposed. The description and script name would
            // reveal the migration content to an unauthenticated caller.
            return current.getVersion().getVersion();
        } catch (RuntimeException exception) {
            logSanitizedProbeFailure("migration_version_probe_failed", exception);
            return UNKNOWN_VERSION;
        }
    }

    /**
     * Record only the category of a probe failure.
     *
     * <p>Driver and Flyway exception messages can contain credentials, hosts,
     * ports, role names and SQL. The exception object is therefore never handed
     * to the logger and no stack trace is attached to this unauthenticated path.
     */
    private void logSanitizedProbeFailure(String event, Exception exception) {
        log.atWarn()
                .addKeyValue("event", event)
                .addKeyValue("correlation_id", CorrelationId.current())
                .addKeyValue("exception_class", exception.getClass().getName())
                .log("Metadata dependency probe failed");
    }
}
