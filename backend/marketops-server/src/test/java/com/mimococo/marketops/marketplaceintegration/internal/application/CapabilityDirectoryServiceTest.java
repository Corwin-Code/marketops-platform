package com.mimococo.marketops.marketplaceintegration.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.marketplaceintegration.CapabilityUsability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.Availability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilityAppliesTo;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilitySubjectStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ContractTestStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformCapability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ReadWriteClass;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RegistryStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.TriState;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationState;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CapabilityRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.SubjectStatusRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CapabilityDirectoryServiceTest {

    private CapabilityRepository capabilities;
    private SubjectStatusRepository subjectStatuses;
    private CapabilityDirectoryService directory;

    @BeforeEach
    void setUp() {
        capabilities = mock(CapabilityRepository.class);
        subjectStatuses = mock(SubjectStatusRepository.class);
        directory = new CapabilityDirectoryService(
                capabilities, subjectStatuses, new SimpleMeterRegistry());
    }

    @Test
    void absentRetiredDeprecatedAndUnverifiedCapabilitiesFailClosed() {
        UUID id = UUID.randomUUID();
        when(capabilities.findById(id)).thenReturn(Optional.empty());
        assertThat(directory.usabilityForAccount(null, UUID.randomUUID()))
                .isEqualTo(CapabilityUsability.UNKNOWN_CAPABILITY);
        assertThat(directory.usabilityForAccount(id, UUID.randomUUID()))
                .isEqualTo(CapabilityUsability.UNKNOWN_CAPABILITY);

        when(capabilities.findById(id)).thenReturn(Optional.of(capability(
                id, RegistryStatus.RETIRED, null, VerificationState.UNKNOWN)));
        assertThat(directory.usabilityForAccount(id, UUID.randomUUID()))
                .isEqualTo(CapabilityUsability.RETIRED);

        when(capabilities.findById(id)).thenReturn(Optional.of(capability(
                id, RegistryStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"),
                VerificationState.UNKNOWN)));
        assertThat(directory.usabilityForAccount(id, UUID.randomUUID()))
                .isEqualTo(CapabilityUsability.DEPRECATED);

        when(capabilities.findById(id)).thenReturn(Optional.of(capability(
                id, RegistryStatus.ACTIVE, null, VerificationState.UNVERIFIED)));
        assertThat(directory.usabilityForAccount(id, UUID.randomUUID()))
                .isEqualTo(CapabilityUsability.NOT_VERIFIED);
    }

    @Test
    void verifiedCapabilityRequiresExplicitSubjectAvailability() {
        UUID capabilityId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        PlatformCapability capability = capability(
                capabilityId, RegistryStatus.ACTIVE, null, VerificationState.VERIFIED);
        when(capabilities.findById(capabilityId)).thenReturn(Optional.of(capability));

        when(subjectStatuses.findByCapabilityAndAccount(capabilityId, accountId))
                .thenReturn(Optional.empty());
        assertThat(directory.usabilityForAccount(capabilityId, accountId))
                .isEqualTo(CapabilityUsability.SUBJECT_NOT_AVAILABLE);

        when(subjectStatuses.findByCapabilityAndAccount(capabilityId, accountId))
                .thenReturn(Optional.of(subject(
                        capabilityId, accountId, null, Availability.UNKNOWN)));
        assertThat(directory.usabilityForAccount(capabilityId, accountId))
                .isEqualTo(CapabilityUsability.SUBJECT_NOT_AVAILABLE);

        when(subjectStatuses.findByCapabilityAndAccount(capabilityId, accountId))
                .thenReturn(Optional.of(subject(
                        capabilityId, accountId, null, Availability.AVAILABLE)));
        assertThat(directory.usabilityForAccount(capabilityId, accountId))
                .isEqualTo(CapabilityUsability.NOT_VERIFIED);
        when(capabilities.hasCurrentAccountEvidence(capabilityId, accountId)).thenReturn(true);
        assertThat(directory.usabilityForAccount(capabilityId, accountId))
                .isEqualTo(CapabilityUsability.USABLE);

        when(subjectStatuses.findByCapabilityAndStore(capabilityId, storeId))
                .thenReturn(Optional.of(subject(
                        capabilityId, null, storeId, Availability.AVAILABLE)));
        assertThat(directory.usabilityForStore(capabilityId, storeId))
                .isEqualTo(CapabilityUsability.NOT_VERIFIED);
        when(capabilities.hasCurrentStoreEvidence(capabilityId, storeId)).thenReturn(true);
        assertThat(directory.usabilityForStore(capabilityId, storeId))
                .isEqualTo(CapabilityUsability.USABLE);
    }

    private static PlatformCapability capability(UUID id,
                                                  RegistryStatus status,
                                                  Instant deprecatedAt,
                                                  VerificationState verification) {
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        return new PlatformCapability(id, "OZON", "orders.read", "Read Orders", null,
                CapabilityAppliesTo.MARKETPLACE_ACCOUNT, ReadWriteClass.READ,
                TriState.NO, verification,
                verification == VerificationState.VERIFIED ? now : null,
                verification == VerificationState.VERIFIED ? "doc://evidence" : null,
                verification == VerificationState.VERIFIED ? "Platform docs" : null,
                "platform-team", ContractTestStatus.NOT_IMPLEMENTED, deprecatedAt, null,
                status, now, now, 0);
    }

    private static CapabilitySubjectStatus subject(UUID capabilityId,
                                                   UUID accountId,
                                                   UUID storeId,
                                                   Availability availability) {
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        return new CapabilitySubjectStatus(UUID.randomUUID(), UUID.randomUUID(), "OZON",
                capabilityId, accountId, storeId, availability,
                availability == Availability.AVAILABLE ? now : null,
                availability == Availability.AVAILABLE ? "doc://evidence" : null,
                availability == Availability.AVAILABLE ? "Platform docs" : null,
                now, now, 0);
    }
}
