package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal, one-shot value mapped from one row returned by the database grant primitive.
 *
 * <p>This type is not a module API and is never returned by the JDBC gateway. The architecture
 * boundary confines construction to the database-row mapper and consumption to the JDBC authority
 * adapter. The value contains opaque metadata identities only and has no representation for secret
 * material.
 */
final class CallAuthorityGrant {

    private static final List<String> REQUIRED_EPOCH_SCOPES =
            List.of("JOB", "MARKETPLACE_ACCOUNT", "ORGANIZATION", "SERVICE_ACCOUNT");

    private final UUID decisionId;
    private final UUID jobId;
    private final UUID runId;
    private final long fenceToken;
    private final String leaseOwner;
    private final String platformCode;
    private final UUID endpointId;
    private final UUID credentialId;
    private final UUID scopeGrantId;
    private final int callSeq;
    private final Instant grantedAt;
    private final Instant callAuthorityExpiresAt;
    private final Instant runLeaseExpiresAt;
    private final Instant serverPolicyDeadline;
    private final List<String> controlEpochScopes;
    private final List<Long> controlEpochValues;
    private final String boundarySetDigest;
    private final AtomicBoolean consumed = new AtomicBoolean();

    /** Validate and detach every column of the structured database result. */
    CallAuthorityGrant(
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
            Instant runLeaseExpiresAt,
            Instant serverPolicyDeadline,
            List<String> controlEpochScopes,
            List<Long> controlEpochValues,
            String boundarySetDigest) {
        this.decisionId = Objects.requireNonNull(decisionId, "decisionId");
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.leaseOwner = requireText(leaseOwner, "leaseOwner");
        this.platformCode = requireText(platformCode, "platformCode");
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
        this.credentialId = Objects.requireNonNull(credentialId, "credentialId");
        this.scopeGrantId = Objects.requireNonNull(scopeGrantId, "scopeGrantId");
        this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
        this.callAuthorityExpiresAt =
                Objects.requireNonNull(callAuthorityExpiresAt, "callAuthorityExpiresAt");
        this.runLeaseExpiresAt =
                Objects.requireNonNull(runLeaseExpiresAt, "runLeaseExpiresAt");
        this.serverPolicyDeadline =
                Objects.requireNonNull(serverPolicyDeadline, "serverPolicyDeadline");
        this.controlEpochScopes = List.copyOf(controlEpochScopes);
        this.controlEpochValues = List.copyOf(controlEpochValues);
        this.boundarySetDigest = Objects.requireNonNull(boundarySetDigest, "boundarySetDigest");
        this.fenceToken = fenceToken;
        this.callSeq = callSeq;

        if (fenceToken <= 0) {
            throw new IllegalArgumentException("a fence token is always positive");
        }
        if (callSeq <= 0) {
            throw new IllegalArgumentException("a call sequence is always positive");
        }
        if (!callAuthorityExpiresAt.isAfter(grantedAt)) {
            throw new IllegalArgumentException("call authority must expire after it is granted");
        }
        if (callAuthorityExpiresAt.isAfter(runLeaseExpiresAt)) {
            throw new IllegalArgumentException("call authority must not outlive its run lease");
        }
        if (callAuthorityExpiresAt.isAfter(serverPolicyDeadline)) {
            throw new IllegalArgumentException("call authority must not exceed server policy");
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

    /** Atomically consume this in-process decision once. */
    boolean consumeForAcquisition() {
        return consumed.compareAndSet(false, true);
    }

    UUID decisionId() {
        return decisionId;
    }

    UUID jobId() {
        return jobId;
    }

    UUID runId() {
        return runId;
    }

    long fenceToken() {
        return fenceToken;
    }

    String leaseOwner() {
        return leaseOwner;
    }

    String platformCode() {
        return platformCode;
    }

    UUID endpointId() {
        return endpointId;
    }

    UUID credentialId() {
        return credentialId;
    }

    UUID scopeGrantId() {
        return scopeGrantId;
    }

    int callSeq() {
        return callSeq;
    }

    Instant grantedAt() {
        return grantedAt;
    }

    Instant callAuthorityExpiresAt() {
        return callAuthorityExpiresAt;
    }

    Instant runLeaseExpiresAt() {
        return runLeaseExpiresAt;
    }

    Instant serverPolicyDeadline() {
        return serverPolicyDeadline;
    }

    List<String> controlEpochScopes() {
        return controlEpochScopes;
    }

    List<Long> controlEpochValues() {
        return controlEpochValues;
    }

    String boundarySetDigest() {
        return boundarySetDigest;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
