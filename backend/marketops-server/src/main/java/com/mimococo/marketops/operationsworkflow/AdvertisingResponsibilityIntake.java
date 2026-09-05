package com.mimococo.marketops.operationsworkflow;

import java.util.UUID;

/** Creates work in the existing Task authority without requiring a writable bid. */
public interface AdvertisingResponsibilityIntake {
    void synchronizeObject(UUID organizationId, UUID adNativeObjectId);

    UUID ensureResponsibility(UUID caseId, UUID calculationRunId, String accountableRoleCode);
}
