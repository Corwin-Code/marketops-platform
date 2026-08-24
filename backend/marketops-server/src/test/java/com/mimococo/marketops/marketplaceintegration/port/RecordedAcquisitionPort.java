package com.mimococo.marketops.marketplaceintegration.port;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An acquisition port that answers from a canned script and records every call.
 *
 * <p>The fake proves a negative the real thing never could: it owns no HTTP
 * client, opens no socket and resolves no credential, so a flow that acquires
 * only through it demonstrably produced zero Marketplace outbound traffic. The
 * recorded requests let a test assert exactly what authority each call carried
 * — and that no call carried anything resembling secret material, because the
 * request type has no field able to hold any.
 */
public final class RecordedAcquisitionPort implements AcquisitionPort {

    private final List<AcquisitionRequest> recorded = new ArrayList<>();
    private final byte[] cannedBody;
    private final String cannedStatus;
    private final AcquisitionResult.AcquisitionOutcome cannedOutcome;

    public RecordedAcquisitionPort(
            String cannedBody,
            String cannedStatus,
            AcquisitionResult.AcquisitionOutcome cannedOutcome) {
        this.cannedBody = cannedBody.getBytes(StandardCharsets.UTF_8);
        this.cannedStatus = cannedStatus;
        this.cannedOutcome = cannedOutcome;
    }

    @Override
    public AcquisitionResult acquire(AcquisitionRequest request) {
        recorded.add(request);
        return new AcquisitionResult(cannedBody, cannedStatus, cannedOutcome, Instant.now());
    }

    /** Every request this port has answered, in call order. */
    public List<AcquisitionRequest> recorded() {
        return List.copyOf(recorded);
    }
}
