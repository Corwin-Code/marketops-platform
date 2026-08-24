package com.mimococo.marketops.marketplaceintegration.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One authorised acquisition call, described entirely by identities.
 *
 * <p>Everything here is a reference: the job, the run that holds the lease, the
 * endpoint, and the credential as an opaque identifier. The request cannot
 * carry secret material because it has no field able to hold any, which is a
 * stronger statement than a rule asking callers not to.
 *
 * <p>Every acquisition names its endpoint. There is deliberately no
 * endpoint-less form: a call without an endpoint identity would have no home
 * for the per-endpoint permit and capability semantics that later gate it, and
 * an accidental null must not become that bypass.
 *
 * <p>{@code callAuthorityExpiresAt} is the instant the granted authority ends,
 * already capped by the control snapshot's validity boundary. A call must start
 * strictly before it; an implementation that receives an expired request must
 * refuse rather than call.
 */
public record AcquisitionRequest(
        UUID jobId,
        UUID runId,
        long fenceToken,
        UUID endpointId,
        UUID credentialId,
        int callSeq,
        Instant callAuthorityExpiresAt) {

    public AcquisitionRequest {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(callAuthorityExpiresAt, "callAuthorityExpiresAt");
        if (fenceToken <= 0) {
            throw new IllegalArgumentException("a fence token is always positive");
        }
        if (callSeq <= 0) {
            throw new IllegalArgumentException("a call sequence is always positive");
        }
    }
}
