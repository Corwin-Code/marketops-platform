package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.CapabilityDirectory;
import com.mimococo.marketops.marketplaceintegration.CapabilityUsability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.Availability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilitySubjectStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformCapability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RegistryStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationState;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CapabilityRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.SubjectStatusRepository;
import com.mimococo.marketops.shared.CorrelationId;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fail-closed subject-aware capability evaluation.
 *
 * <p>{@link CapabilityUsability#USABLE} requires an active, non-deprecated,
 * verified capability and a subject whose availability is explicitly recorded
 * as available with provenance. Under the registry's current data no
 * evaluation can satisfy the verification conjunct, so every verdict is a
 * refusal, and each refusal is observable as a structured event and a bounded
 * counter.
 */
@Service
class CapabilityDirectoryService implements CapabilityDirectory {

    private static final Logger log = LoggerFactory.getLogger(CapabilityDirectoryService.class);

    private final CapabilityRepository capabilities;
    private final SubjectStatusRepository subjectStatuses;
    private final MeterRegistry meterRegistry;

    CapabilityDirectoryService(CapabilityRepository capabilities,
                               SubjectStatusRepository subjectStatuses,
                               MeterRegistry meterRegistry) {
        this.capabilities = capabilities;
        this.subjectStatuses = subjectStatuses;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public CapabilityUsability usabilityForAccount(UUID capabilityId,
                                                   UUID marketplaceAccountId) {
        return evaluate(capabilityId, capability ->
                subjectStatuses.findByCapabilityAndAccount(
                        capability.id(), marketplaceAccountId),
                () -> capabilities.hasCurrentAccountEvidence(capabilityId,marketplaceAccountId));
    }

    @Override
    public CapabilityUsability usabilityForStore(UUID capabilityId, UUID storeId) {
        return evaluate(capabilityId, capability ->
                subjectStatuses.findByCapabilityAndStore(capability.id(), storeId),
                () -> capabilities.hasCurrentStoreEvidence(capabilityId,storeId));
    }

    private CapabilityUsability evaluate(UUID capabilityId, SubjectLookup subjectLookup, java.util.function.BooleanSupplier currentEvidence) {
        Optional<PlatformCapability> found = capabilityId == null
                ? Optional.empty()
                : capabilities.findById(capabilityId);
        if (found.isEmpty()) {
            return observeRefusal(capabilityId, null, CapabilityUsability.UNKNOWN_CAPABILITY);
        }
        PlatformCapability capability = found.get();
        if (capability.status() == RegistryStatus.RETIRED) {
            return observeRefusal(capabilityId, capability, CapabilityUsability.RETIRED);
        }
        if (capability.deprecatedAt() != null) {
            return observeRefusal(capabilityId, capability, CapabilityUsability.DEPRECATED);
        }
        if (capability.verificationState() != VerificationState.VERIFIED) {
            return observeRefusal(capabilityId, capability, CapabilityUsability.NOT_VERIFIED);
        }
        boolean subjectAvailable = subjectLookup.find(capability)
                .map(CapabilitySubjectStatus::availability)
                .map(availability -> availability == Availability.AVAILABLE)
                .orElse(false);
        if (!subjectAvailable) {
            return observeRefusal(
                    capabilityId, capability, CapabilityUsability.SUBJECT_NOT_AVAILABLE);
        }
        if (!currentEvidence.getAsBoolean()) {
            return observeRefusal(capabilityId,capability,CapabilityUsability.NOT_VERIFIED);
        }
        return CapabilityUsability.USABLE;
    }

    private CapabilityUsability observeRefusal(UUID capabilityId,
                                               PlatformCapability capability,
                                               CapabilityUsability verdict) {
        log.atWarn()
                .addKeyValue("event", "capability_denied")
                .addKeyValue("capabilityId", String.valueOf(capabilityId))
                .addKeyValue("verificationState", capability == null
                        ? "ABSENT" : capability.verificationState().name())
                .addKeyValue("verdict", verdict.name())
                .addKeyValue("correlationId", CorrelationId.current())
                .log("Capability evaluation refused");
        meterRegistry.counter("marketops.capability.denials",
                "state", verdict.name()).increment();
        return verdict;
    }

    /** Lookup of the subject-status row belonging to one capability. */
    private interface SubjectLookup {
        Optional<CapabilitySubjectStatus> find(PlatformCapability capability);
    }
}
