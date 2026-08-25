package com.mimococo.marketops.testfixture.violation.assignableobjectstorage.reporting;

import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.testfixture.violation.assignableobjectstorage.marketplaceintegration.adapter.OwningObjectStorageAdapter;
import java.util.Optional;

interface ExternalObjectStoragePort extends ObjectStoragePort {
}

final class ExternalSubinterfaceObjectStorageAdapter implements ExternalObjectStoragePort {

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

final class ExternalInheritedObjectStorageAdapter extends OwningObjectStorageAdapter {
}
