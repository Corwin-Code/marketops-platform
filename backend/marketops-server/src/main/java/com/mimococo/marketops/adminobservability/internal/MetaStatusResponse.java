package com.mimococo.marketops.adminobservability.internal;

/**
 * Metadata payload returned to the operations console.
 *
 * <p>The field set is an allowlist. A connection string, database user, password,
 * search path, filesystem path, dependency inventory, or migration description
 * must never be added: this resource is reachable without authentication in the
 * foundation, so every field is effectively public.
 *
 * <p>Build time is deliberately absent. A wall-clock stamp makes two builds of one
 * source tree differ, and the source commit already identifies the build input.
 *
 * @param product product display name
 * @param application deployable application name
 * @param environment active environment name
 * @param buildVersion version of the built artifact
 * @param gitCommit commit the artifact was built from, or {@code unknown} locally
 * @param serverTimeUtc server clock at the time of the request
 * @param database database availability
 * @param migration applied schema version
 * @param correlationId identifier of the current request
 */
public record MetaStatusResponse(
        String product,
        String application,
        String environment,
        String buildVersion,
        String gitCommit,
        String serverTimeUtc,
        DatabaseStatus database,
        MigrationStatus migration,
        String correlationId) {

    /**
     * Database availability as observed by a connection probe.
     *
     * @param status {@code UP}, {@code DOWN}, or {@code UNKNOWN} when no data
     *               source is configured
     */
    public record DatabaseStatus(String status) {
    }

    /**
     * Schema version currently applied.
     *
     * @param currentVersion version identifier, or {@code UNKNOWN} when the
     *                       database cannot be reached
     */
    public record MigrationStatus(String currentVersion) {
    }
}
