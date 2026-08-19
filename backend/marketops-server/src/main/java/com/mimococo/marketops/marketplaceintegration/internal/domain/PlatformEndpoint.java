package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One physical platform endpoint or endpoint version in the registry.
 *
 * <p>Every operational fact that has not been recorded from official evidence
 * stays {@code UNKNOWN} or {@code null}; nothing here is guessed. Capability
 * and replacement links are pinned to the endpoint's own platform by composite
 * foreign keys.
 *
 * @param id identifier
 * @param platformCode marketplace platform the endpoint belongs to
 * @param endpointCode internal registry code, unique with the api version
 * @param apiVersion platform API version label
 * @param httpMethod HTTP method, or {@code null} while unrecorded
 * @param pathTemplate path template, or {@code null} while unrecorded
 * @param capabilityId same-platform capability served, or {@code null}
 * @param readWriteClass whether the endpoint reads or mutates platform state
 * @param paginationModel recorded pagination behaviour
 * @param rateLimitPerMinute recorded rate limit, or {@code null}
 * @param rateLimitNote free-text rate-limit detail, or {@code null}
 * @param quotaNote free-text quota detail, or {@code null}
 * @param idempotencySupport whether retries are safe on this endpoint
 * @param lateDataBehavior recorded late-data behaviour, or {@code null}
 * @param freshnessExpectation recorded freshness expectation, or {@code null}
 * @param businessKeyNote recorded business-key semantics, or {@code null}
 * @param schemaVersion recorded payload schema version, or {@code null}
 * @param deprecatedAt deprecation time, or {@code null}
 * @param replacementEndpointId same-platform successor, or {@code null}
 * @param verificationState recorded verification state
 * @param lastVerifiedAt time of the recorded verification, or {@code null}
 * @param evidenceRef reference to the verification evidence, or {@code null}
 * @param verifiedSourceTitle title of the verified source, or {@code null}
 * @param ownerLabel person or team responsible for keeping the row current
 * @param contractTestStatus recorded contract-test standing
 * @param status registry lifecycle status
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record PlatformEndpoint(
        UUID id,
        String platformCode,
        String endpointCode,
        String apiVersion,
        String httpMethod,
        String pathTemplate,
        UUID capabilityId,
        ReadWriteClass readWriteClass,
        PaginationModel paginationModel,
        Integer rateLimitPerMinute,
        String rateLimitNote,
        String quotaNote,
        TriState idempotencySupport,
        String lateDataBehavior,
        String freshnessExpectation,
        String businessKeyNote,
        String schemaVersion,
        Instant deprecatedAt,
        UUID replacementEndpointId,
        VerificationState verificationState,
        Instant lastVerifiedAt,
        String evidenceRef,
        String verifiedSourceTitle,
        String ownerLabel,
        ContractTestStatus contractTestStatus,
        RegistryStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
