package com.mimococo.marketops.testfixture.violation.objectstoragecaller.reporting;

import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import java.util.Optional;

/**
 * A second caller of the object store, written to be rejected.
 *
 * <p>It reads Raw bytes directly instead of asking the custody service, so it
 * bypasses the digest verification that makes a stored object evidence rather
 * than a claim. The rule that rejects it is the reason custody has one caller.
 */
public final class EvidenceExporter {

    private final ObjectStoragePort objectStorage;

    public EvidenceExporter(ObjectStoragePort objectStorage) {
        this.objectStorage = objectStorage;
    }

    /** Read stored bytes without going through custody. */
    public Optional<byte[]> export(String objectRef) {
        return objectStorage.read(objectRef);
    }
}
