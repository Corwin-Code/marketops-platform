package com.mimococo.marketops.marketplaceintegration.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One acquisition call derived without rebinding from a structured grant.
 *
 * <p>The constructor is private and the only production factory is the owning
 * module's {@link AuthorizedAcquisitionExecutor}. Every accessor delegates to
 * the immutable {@link CallAuthorityGrant}; callers cannot combine an expiry
 * from one decision with another Job, run, endpoint, Credential or fence.
 */
public final class AcquisitionRequest {

    private final CallAuthorityGrant grant;

    private AcquisitionRequest(CallAuthorityGrant grant) {
        this.grant = Objects.requireNonNull(grant, "grant");
    }

    static AcquisitionRequest from(CallAuthorityGrant grant) {
        return new AcquisitionRequest(grant);
    }

    public UUID decisionId() {
        return grant.decisionId();
    }

    public UUID jobId() {
        return grant.jobId();
    }

    public UUID runId() {
        return grant.runId();
    }

    public long fenceToken() {
        return grant.fenceToken();
    }

    public String leaseOwner() {
        return grant.leaseOwner();
    }

    public String platformCode() {
        return grant.platformCode();
    }

    public UUID endpointId() {
        return grant.endpointId();
    }

    public UUID credentialId() {
        return grant.credentialId();
    }

    public UUID scopeGrantId() {
        return grant.scopeGrantId();
    }

    public int callSeq() {
        return grant.callSeq();
    }

    public Instant grantedAt() {
        return grant.grantedAt();
    }

    public Instant callAuthorityExpiresAt() {
        return grant.callAuthorityExpiresAt();
    }

    public List<String> controlEpochScopes() {
        return grant.controlEpochScopes();
    }

    public List<Long> controlEpochValues() {
        return grant.controlEpochValues();
    }

    public String boundarySetDigest() {
        return grant.boundarySetDigest();
    }
}
