package com.mimococo.marketops.testfixture.violation.assignableobjectstorage.marketplaceintegration.adapter;

import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import java.util.Optional;

/** Owning-module base used to prove an outside inherited implementation is rejected. */
public class OwningObjectStorageAdapter implements ObjectStoragePort {

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
