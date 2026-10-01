package com.mimococo.marketops.marketplaceintegration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Whether a store's content writes can leave this process now (W2).
 *
 * @param platformCode the marketplace the store sells on, or {@code null}
 * @param productionWritesEnabled the deployment's production-write switch
 * @param workerEnabled whether this process runs the content worker
 * @param capabilityId the content capability, or {@code null} when none is registered
 * @param capabilityVerification its verification state, or {@code null}
 * @param storeAvailability the capability's availability for the store, or {@code null}
 * @param evidenceValidUntil when the verified evidence lapses, or {@code null}
 * @param capabilitySwitchEnabled whether the capability's switch is on
 * @param globalSwitchEnabled whether the global switch is on
 * @param reasons every condition that stops a content write regardless of the listing, empty
 *        when none does (the allowlist and the approval are per listing and per change)
 */
public record ContentWriteStatus(
        UUID storeId,
        String platformCode,
        boolean productionWritesEnabled,
        boolean workerEnabled,
        UUID capabilityId,
        String capabilityVerification,
        String storeAvailability,
        Instant evidenceValidUntil,
        boolean capabilitySwitchEnabled,
        boolean globalSwitchEnabled,
        List<String> reasons) {

    public ContentWriteStatus {
        reasons = List.copyOf(reasons);
    }
}
