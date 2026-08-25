package com.mimococo.marketops.marketplaceintegration.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One acquisition call copied from the complete, consumed database authority envelope.
 *
 * <p>The constructor is private and the module boundary confines the functional factory to the
 * internal authorized executor. No grant object crosses this port boundary and every collection
 * is detached before the adapter sees it.
 */
public final class AcquisitionRequest {

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

    private AcquisitionRequest(
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
        this.fenceToken = fenceToken;
        this.leaseOwner = Objects.requireNonNull(leaseOwner, "leaseOwner");
        this.platformCode = Objects.requireNonNull(platformCode, "platformCode");
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
        this.credentialId = Objects.requireNonNull(credentialId, "credentialId");
        this.scopeGrantId = Objects.requireNonNull(scopeGrantId, "scopeGrantId");
        this.callSeq = callSeq;
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
    }

    /** Copy an already-validated internal authority into the immutable adapter request. */
    public static AcquisitionRequest fromDatabaseAuthority(
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
        return new AcquisitionRequest(
                decisionId, jobId, runId, fenceToken, leaseOwner, platformCode, endpointId,
                credentialId, scopeGrantId, callSeq, grantedAt, callAuthorityExpiresAt,
                runLeaseExpiresAt, serverPolicyDeadline, controlEpochScopes,
                controlEpochValues, boundarySetDigest);
    }

    public UUID decisionId() {
        return decisionId;
    }

    public UUID jobId() {
        return jobId;
    }

    public UUID runId() {
        return runId;
    }

    public long fenceToken() {
        return fenceToken;
    }

    public String leaseOwner() {
        return leaseOwner;
    }

    public String platformCode() {
        return platformCode;
    }

    public UUID endpointId() {
        return endpointId;
    }

    public UUID credentialId() {
        return credentialId;
    }

    public UUID scopeGrantId() {
        return scopeGrantId;
    }

    public int callSeq() {
        return callSeq;
    }

    public Instant grantedAt() {
        return grantedAt;
    }

    public Instant callAuthorityExpiresAt() {
        return callAuthorityExpiresAt;
    }

    public Instant runLeaseExpiresAt() {
        return runLeaseExpiresAt;
    }

    public Instant serverPolicyDeadline() {
        return serverPolicyDeadline;
    }

    public List<String> controlEpochScopes() {
        return controlEpochScopes;
    }

    public List<Long> controlEpochValues() {
        return controlEpochValues;
    }

    public String boundarySetDigest() {
        return boundarySetDigest;
    }
}
