package com.mimococo.marketops.testfixture.conforming.assignableports.marketplaceintegration.adapter;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import java.time.Instant;
import java.util.Optional;

interface InternalAcquisitionPort extends AcquisitionPort {
}

final class InternalAcquisitionAdapter implements InternalAcquisitionPort {

    @Override
    public AcquisitionResult acquire(AcquisitionRequest request) {
        return new AcquisitionResult(
                new byte[0], "NOOP",
                AcquisitionResult.AcquisitionOutcome.UNKNOWN_STATE, Instant.EPOCH);
    }
}

interface InternalObjectStoragePort extends ObjectStoragePort {
}

final class InternalObjectStorageAdapter implements InternalObjectStoragePort {

    @Override
    public PutOutcome putIfAbsent(String objectRef, byte[] body) {
        return PutOutcome.STORED;
    }

    @Override
    public Optional<byte[]> read(String objectRef) {
        return Optional.empty();
    }

    @Override
    public boolean verify(String objectRef, String sha256Hex) {
        return false;
    }
}
