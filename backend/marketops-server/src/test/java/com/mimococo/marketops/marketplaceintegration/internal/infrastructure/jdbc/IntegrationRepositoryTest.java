package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import static com.mimococo.marketops.testsupport.EmptyJdbcClient.emptyJdbcClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.marketplaceintegration.internal.domain.Availability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilityAppliesTo;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilitySubjectStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ContractTestStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialMetadata;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialScopeMode;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialStoreScope;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FeatureFlag;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagScopeKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagState;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PaginationModel;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PermissionRequirement;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformCapability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformEndpoint;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ReadWriteClass;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RegistryStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RequirementKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.StoreScopeStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.TriState;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationEvent;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class IntegrationRepositoryTest {

    private static final UUID ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

    @Test
    void registryRepositoriesExposeVersionedWriteAndEmptyReadSemantics() {
        JdbcClient jdbc = emptyJdbcClient();
        CapabilityRepository capabilities = new CapabilityRepository(jdbc);
        EndpointRepository endpoints = new EndpointRepository(jdbc);
        SubjectStatusRepository subjects = new SubjectStatusRepository(jdbc);
        PermissionRequirementRepository requirements = new PermissionRequirementRepository(jdbc);
        VerificationEventRepository events = new VerificationEventRepository(jdbc);

        PlatformCapability capability = mock(PlatformCapability.class);
        when(capability.appliesTo()).thenReturn(CapabilityAppliesTo.MARKETPLACE_ACCOUNT);
        when(capability.readWriteClass()).thenReturn(ReadWriteClass.READ);
        when(capability.subscriptionRequired()).thenReturn(TriState.UNKNOWN);
        when(capability.verificationState()).thenReturn(VerificationState.UNVERIFIED);
        when(capability.contractTestStatus()).thenReturn(ContractTestStatus.NOT_IMPLEMENTED);
        when(capability.status()).thenReturn(RegistryStatus.ACTIVE);
        when(capability.createdAt()).thenReturn(NOW);
        when(capability.updatedAt()).thenReturn(NOW);
        PlatformEndpoint endpoint = mock(PlatformEndpoint.class);
        when(endpoint.readWriteClass()).thenReturn(ReadWriteClass.READ);
        when(endpoint.paginationModel()).thenReturn(PaginationModel.NONE);
        when(endpoint.idempotencySupport()).thenReturn(TriState.UNKNOWN);
        when(endpoint.verificationState()).thenReturn(VerificationState.UNVERIFIED);
        when(endpoint.contractTestStatus()).thenReturn(ContractTestStatus.NOT_IMPLEMENTED);
        when(endpoint.status()).thenReturn(RegistryStatus.ACTIVE);
        when(endpoint.createdAt()).thenReturn(NOW);
        when(endpoint.updatedAt()).thenReturn(NOW);
        CapabilitySubjectStatus subject = mock(CapabilitySubjectStatus.class);
        when(subject.availability()).thenReturn(Availability.UNKNOWN);
        when(subject.createdAt()).thenReturn(NOW);
        when(subject.updatedAt()).thenReturn(NOW);
        PermissionRequirement requirement = mock(PermissionRequirement.class);
        when(requirement.requirementKind()).thenReturn(RequirementKind.API_ROLE);
        when(requirement.verificationState()).thenReturn(VerificationState.UNVERIFIED);
        when(requirement.status()).thenReturn(RegistryStatus.ACTIVE);
        when(requirement.createdAt()).thenReturn(NOW);
        when(requirement.updatedAt()).thenReturn(NOW);
        VerificationEvent event = mock(VerificationEvent.class);
        when(event.verifiedAt()).thenReturn(NOW);
        when(event.occurredAt()).thenReturn(NOW);

        capabilities.insert(capability);
        assertThat(capabilities.update(capability, 1)).isFalse();
        assertThat(capabilities.findById(ID)).isEmpty();
        assertThat(capabilities.findByCode("OZON", "orders.read")).isEmpty();
        assertThat(capabilities.list("OZON", null, 50)).isEmpty();

        endpoints.insert(endpoint);
        assertThat(endpoints.update(endpoint, 1)).isFalse();
        assertThat(endpoints.findById(ID)).isEmpty();
        assertThat(endpoints.findByCode("OZON", "orders", "v1")).isEmpty();
        assertThat(endpoints.list("OZON", null, null, 50)).isEmpty();
        assertThat(endpoints.countActiveByCapability(ID)).isZero();

        subjects.insert(subject);
        assertThat(subjects.findById(ID)).isEmpty();
        assertThat(subjects.findByCapabilityAndAccount(ID, ID)).isEmpty();
        assertThat(subjects.findByCapabilityAndStore(ID, ID)).isEmpty();
        assertThat(subjects.listByCapability(ID, 50)).isEmpty();

        requirements.insert(requirement);
        assertThat(requirements.findById(ID)).isEmpty();
        assertThat(requirements.findDuplicate("OZON", RequirementKind.API_ROLE,
                "orders.read", ID, null)).isEmpty();
        assertThat(requirements.listByCapability(ID, 50)).isEmpty();
        assertThat(requirements.listByEndpoint(ID, 50)).isEmpty();

        events.insert(event);
        assertThat(events.listByCapability(ID, 50)).isEmpty();
        assertThat(events.listByEndpoint(ID, 50)).isEmpty();
        verify(jdbc, atLeastOnce()).sql(anyString());
    }

    @Test
    void credentialAndFlagRepositoriesExposeVersionedWriteAndEmptyReadSemantics() {
        JdbcClient jdbc = emptyJdbcClient();
        CredentialPurposeRepository purposes = new CredentialPurposeRepository(jdbc);
        CredentialRepository credentials = new CredentialRepository(jdbc);
        FeatureFlagRepository flags = new FeatureFlagRepository(jdbc);

        CredentialMetadata credential = mock(CredentialMetadata.class);
        when(credential.scopeMode()).thenReturn(CredentialScopeMode.ACCOUNT);
        when(credential.status()).thenReturn(CredentialStatus.ACTIVE);
        when(credential.verificationState()).thenReturn(VerificationState.UNVERIFIED);
        when(credential.effectiveFrom()).thenReturn(NOW);
        when(credential.expiresAt()).thenReturn(NOW.plusSeconds(3600));
        when(credential.createdAt()).thenReturn(NOW);
        when(credential.updatedAt()).thenReturn(NOW);
        CredentialStoreScope scope = mock(CredentialStoreScope.class);
        when(scope.status()).thenReturn(StoreScopeStatus.ACTIVE);
        when(scope.createdAt()).thenReturn(NOW);
        when(scope.updatedAt()).thenReturn(NOW);
        FeatureFlag flag = mock(FeatureFlag.class);
        when(flag.flagKind()).thenReturn(FlagKind.OPERATIONAL);
        when(flag.scopeKind()).thenReturn(FlagScopeKind.GLOBAL);
        when(flag.state()).thenReturn(FlagState.DISABLED);
        when(flag.status()).thenReturn(RegistryStatus.ACTIVE);
        when(flag.createdAt()).thenReturn(NOW);
        when(flag.updatedAt()).thenReturn(NOW);

        assertThat(purposes.purposeExists("READ")).isFalse();
        credentials.insert(credential);
        assertThat(credentials.update(credential, 1)).isFalse();
        assertThat(credentials.findById(ID)).isEmpty();
        assertThat(credentials.findByCode(ID, "CREDENTIAL")).isEmpty();
        assertThat(credentials.listByAccount(ID, null, 50)).isEmpty();
        assertThat(credentials.findLiveBySecretReference("secret-ref://vault/path")).isEmpty();
        assertThat(credentials.countNotRevokedByAccount(ID)).isZero();
        assertThat(credentials.countNotRevokedByOrganization(ID)).isZero();
        assertThat(credentials.countLiveReplacers(ID)).isZero();
        credentials.insertScope(scope);
        assertThat(credentials.updateScope(scope, 1)).isFalse();
        assertThat(credentials.findScopeById(ID)).isEmpty();
        assertThat(credentials.findActiveScope(ID, ID)).isEmpty();
        assertThat(credentials.listScopes(ID)).isEmpty();
        assertThat(credentials.countActiveScopes(ID)).isZero();
        assertThat(credentials.countActiveScopesByStore(ID)).isZero();

        flags.insert(flag);
        assertThat(flags.update(flag, 1)).isFalse();
        assertThat(flags.findById(ID)).isEmpty();
        assertThat(flags.findActiveByScope("FLAG", FlagScopeKind.GLOBAL,
                null, null, null, null)).isEmpty();
        assertThat(flags.list(null, null, 50)).isEmpty();
        assertThat(flags.countActiveByAccount(ID)).isZero();
        assertThat(flags.countActiveByStore(ID)).isZero();
        verify(jdbc, atLeastOnce()).sql(anyString());
    }
}
