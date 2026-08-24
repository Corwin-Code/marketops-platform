package com.mimococo.marketops.marketplaceintegration.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The database-derived authority for exactly one acquisition call.
 *
 * <p>The value binds the decision journal row, lease and fence, complete call
 * identity, call sequence and consumed control snapshot. It contains opaque
 * metadata identifiers only; credential secret material has no representation.
 * An acquisition request is derived from this whole value by
 * {@link AuthorizedAcquisitionExecutor}, so an expiry cannot be transferred to
 * another Job, run, endpoint or Credential.
 */
public record CallAuthorityGrant(
        UUID decisionId,
        UUID jobId,
        UUID runId,
        long fenceToken,
        String leaseOwner,
        String platformCode,
        UUID endpointId,
        UUID credentialId,
        UUID scopeGrantId,
        int callSeq,
        Instant grantedAt,
        Instant callAuthorityExpiresAt,
        List<String> controlEpochScopes,
        List<Long> controlEpochValues,
        String boundarySetDigest) {

    private static final List<String> REQUIRED_EPOCH_SCOPES =
            List.of("JOB", "MARKETPLACE_ACCOUNT", "ORGANIZATION", "SERVICE_ACCOUNT");

    /** Validate and detach the structured database result. */
    public CallAuthorityGrant {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Objects.requireNonNull(platformCode, "platformCode");
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(scopeGrantId, "scopeGrantId");
        Objects.requireNonNull(grantedAt, "grantedAt");
        Objects.requireNonNull(callAuthorityExpiresAt, "callAuthorityExpiresAt");
        controlEpochScopes = List.copyOf(controlEpochScopes);
        controlEpochValues = List.copyOf(controlEpochValues);
        Objects.requireNonNull(boundarySetDigest, "boundarySetDigest");
        if (fenceToken <= 0) {
            throw new IllegalArgumentException("a fence token is always positive");
        }
        if (leaseOwner.isBlank()) {
            throw new IllegalArgumentException("a lease owner is required");
        }
        if (platformCode.isBlank()) {
            throw new IllegalArgumentException("a platform code is required");
        }
        if (callSeq <= 0) {
            throw new IllegalArgumentException("a call sequence is always positive");
        }
        if (!callAuthorityExpiresAt.isAfter(grantedAt)) {
            throw new IllegalArgumentException("call authority must expire after it is granted");
        }
        if (!controlEpochScopes.equals(REQUIRED_EPOCH_SCOPES)
                || controlEpochValues.size() != REQUIRED_EPOCH_SCOPES.size()
                || controlEpochValues.stream().anyMatch(epoch -> epoch == null || epoch < 0)) {
            throw new IllegalArgumentException("the complete four-scope epoch tuple is required");
        }
        if (!boundarySetDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("the boundary digest must be lowercase SHA-256");
        }
    }
}
