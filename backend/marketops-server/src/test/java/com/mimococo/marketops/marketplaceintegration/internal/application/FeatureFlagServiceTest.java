package com.mimococo.marketops.marketplaceintegration.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FeatureFlag;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagScopeKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagState;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformCapability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RegistryStatus;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CapabilityRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.FeatureFlagRepository;
import com.mimococo.marketops.organizationaccount.MarketplaceAccountRef;
import com.mimococo.marketops.organizationaccount.MarketplacePlatformRef;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.StoreRef;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import com.mimococo.marketops.shared.ProductionWritePolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeatureFlagServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID NEW_ID = uuid(1);
    private static final UUID ORG_ID = uuid(2);
    private static final UUID ACCOUNT_ID = uuid(3);
    private static final UUID STORE_ID = uuid(4);
    private static final UUID CAPABILITY_ID = uuid(5);
    private static final UUID FLAG_ID = uuid(6);
    private static final MetadataAuditRecorder AUDIT = mock(MetadataAuditRecorder.class);
    private static final IdGenerator IDS = () -> NEW_ID;

    @Test
    void createCoversEveryScopeShapeAndReferenceKind() {
        Fixture fixture = fixture();
        assertThat(fixture.service.create("operator", "global.flag", FlagKind.OPERATIONAL,
                FlagScopeKind.GLOBAL, null, null, null, null, null).scopeKind())
                .isEqualTo(FlagScopeKind.GLOBAL);
        assertThat(fixture.service.create("operator", "platform.flag", FlagKind.OPERATIONAL,
                FlagScopeKind.PLATFORM, "OZON", null, null, null, null).scopeKind())
                .isEqualTo(FlagScopeKind.PLATFORM);
        assertThat(fixture.service.create("operator", "account.flag", FlagKind.OPERATIONAL,
                FlagScopeKind.MARKETPLACE_ACCOUNT, null, ACCOUNT_ID, null, null, null).scopeKind())
                .isEqualTo(FlagScopeKind.MARKETPLACE_ACCOUNT);
        assertThat(fixture.service.create("operator", "store.flag", FlagKind.OPERATIONAL,
                FlagScopeKind.STORE, null, null, STORE_ID, null, null).scopeKind())
                .isEqualTo(FlagScopeKind.STORE);
        assertThat(fixture.service.create("operator", "capability.flag", FlagKind.OPERATIONAL,
                FlagScopeKind.CAPABILITY, null, null, null, CAPABILITY_ID, null).scopeKind())
                .isEqualTo(FlagScopeKind.CAPABILITY);
    }

    @Test
    void stateRetirementQueriesAndPublishedReadsCoverBothDirections() {
        Fixture fixture = fixture();
        FeatureFlag disabled = flag(FlagKind.OPERATIONAL, FlagState.DISABLED, RegistryStatus.ACTIVE);
        when(fixture.flags.findById(FLAG_ID)).thenReturn(Optional.of(disabled));
        when(fixture.flags.update(any(), anyLong())).thenReturn(true);
        when(fixture.flags.list(any(), any(), anyInt())).thenReturn(List.of(disabled));
        when(fixture.flags.countActiveByAccount(ACCOUNT_ID)).thenReturn(2L);
        when(fixture.flags.countActiveByStore(STORE_ID)).thenReturn(3L);

        assertThat(fixture.service.changeState("operator", FLAG_ID, FlagState.ENABLED,
                "enable", 0).state()).isEqualTo(FlagState.ENABLED);
        assertThat(fixture.service.retire("operator", FLAG_ID, "closed", 0).status())
                .isEqualTo(RegistryStatus.RETIRED);
        assertThat(fixture.service.view(FLAG_ID)).isEqualTo(disabled);
        assertThat(fixture.service.list("flag", null, 500)).containsExactly(disabled);
        assertThat(fixture.service.list(null, "ignored", 0)).containsExactly(disabled);
        assertThat(fixture.service.countActiveByAccount(ACCOUNT_ID)).isEqualTo(2);
        assertThat(fixture.service.countActiveByStore(STORE_ID)).isEqualTo(3);

        FeatureFlag enabled = flag(FlagKind.OPERATIONAL, FlagState.ENABLED, RegistryStatus.ACTIVE);
        when(fixture.flags.findActiveByScope(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(enabled));
        assertThat(fixture.service.isEnabledGlobal("flag")).isTrue();
        assertThat(fixture.service.isEnabledForPlatform("flag", "OZON")).isTrue();
        assertThat(fixture.service.isEnabledForAccount("flag", ACCOUNT_ID)).isTrue();
        assertThat(fixture.service.isEnabledForStore("flag", STORE_ID)).isTrue();
        assertThat(fixture.service.isEnabledForCapability("flag", CAPABILITY_ID)).isTrue();
        assertThat(fixture.service.isEnabledGlobal(null)).isFalse();
        assertThat(fixture.service.isEnabledForPlatform("flag", null)).isFalse();
        assertThat(fixture.service.isEnabledForAccount("flag", null)).isFalse();
        assertThat(fixture.service.isEnabledForStore("flag", null)).isFalse();
        assertThat(fixture.service.isEnabledForCapability("flag", null)).isFalse();
    }

    @Test
    void creationRejectsInvalidShapesMissingReferencesInactiveTargetsAndDuplicates() {
        Fixture fixture = fixture();
        assertCode(() -> fixture.service.create("operator", "flag", null,
                FlagScopeKind.GLOBAL, null, null, null, null, null),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> fixture.service.create("operator", "flag", FlagKind.OPERATIONAL,
                FlagScopeKind.GLOBAL, "OZON", null, null, null, null),
                ErrorCode.VALIDATION_FAILED);

        when(fixture.organizations.platform("MISSING")).thenReturn(Optional.empty());
        assertCode(() -> fixture.service.create("operator", "flag", FlagKind.OPERATIONAL,
                FlagScopeKind.PLATFORM, "MISSING", null, null, null, null),
                ErrorCode.VALIDATION_FAILED);
        when(fixture.organizations.marketplaceAccount(ACCOUNT_ID)).thenReturn(Optional.of(
                new MarketplaceAccountRef(ACCOUNT_ID, ORG_ID, uuid(20), "OZON", "ACCOUNT",
                        "SUSPENDED")));
        assertCode(() -> fixture.service.create("operator", "flag", FlagKind.OPERATIONAL,
                FlagScopeKind.MARKETPLACE_ACCOUNT, null, ACCOUNT_ID, null, null, null),
                ErrorCode.INVALID_STATE_TRANSITION);
        when(fixture.organizations.store(STORE_ID))
                .thenReturn(Optional.of(new StoreRef(STORE_ID, ORG_ID, ACCOUNT_ID,
                        "STORE", "SUSPENDED")));
        assertCode(() -> fixture.service.create("operator", "flag", FlagKind.OPERATIONAL,
                FlagScopeKind.STORE, null, null, STORE_ID, null, null),
                ErrorCode.INVALID_STATE_TRANSITION);
        PlatformCapability retired = mock(PlatformCapability.class);
        when(retired.status()).thenReturn(RegistryStatus.RETIRED);
        when(fixture.capabilities.findById(CAPABILITY_ID)).thenReturn(Optional.of(retired));
        assertCode(() -> fixture.service.create("operator", "flag", FlagKind.OPERATIONAL,
                FlagScopeKind.CAPABILITY, null, null, null, CAPABILITY_ID, null),
                ErrorCode.INVALID_STATE_TRANSITION);

        FeatureFlag existing =
                flag(FlagKind.OPERATIONAL, FlagState.DISABLED, RegistryStatus.ACTIVE);
        when(fixture.flags.findActiveByScope("flag", FlagScopeKind.GLOBAL,
                null, null, null, null)).thenReturn(Optional.of(existing));
        assertCode(() -> fixture.service.create("operator", "flag", FlagKind.OPERATIONAL,
                FlagScopeKind.GLOBAL, null, null, null, null, null),
                ErrorCode.DUPLICATE_IDENTITY);
    }

    @Test
    void writeGateAndLifecycleTransitionsFailClosed() {
        Fixture fixture = fixture();
        FeatureFlag write =
                flag(FlagKind.WRITE_CAPABILITY, FlagState.DISABLED, RegistryStatus.ACTIVE);
        when(fixture.flags.findById(FLAG_ID)).thenReturn(Optional.of(write));
        when(fixture.policy.productionWritesEnabled()).thenReturn(false);
        assertCode(() -> fixture.service.changeState("operator", FLAG_ID, FlagState.ENABLED,
                "enable", 0), ErrorCode.PRODUCTION_WRITE_DISABLED);
        assertCode(() -> fixture.service.changeState("operator", FLAG_ID, FlagState.DISABLED,
                "same", 0), ErrorCode.INVALID_STATE_TRANSITION);

        FeatureFlag enabled =
                flag(FlagKind.OPERATIONAL, FlagState.ENABLED, RegistryStatus.ACTIVE);
        when(fixture.flags.findById(FLAG_ID)).thenReturn(Optional.of(enabled));
        assertCode(() -> fixture.service.retire("operator", FLAG_ID, "closed", 0),
                ErrorCode.INVALID_STATE_TRANSITION);
        when(fixture.flags.findById(uuid(99))).thenReturn(Optional.empty());
        assertCode(() -> fixture.service.view(uuid(99)), ErrorCode.RESOURCE_NOT_FOUND);
        when(fixture.flags.findById(FLAG_ID))
                .thenReturn(Optional.of(flag(FlagKind.OPERATIONAL,
                        FlagState.DISABLED, RegistryStatus.ACTIVE)));
        assertCode(() -> fixture.service.retire("operator", FLAG_ID, "closed", 0),
                ErrorCode.VERSION_CONFLICT);
    }

    private static Fixture fixture() {
        FeatureFlagRepository flags = mock(FeatureFlagRepository.class);
        CapabilityRepository capabilities = mock(CapabilityRepository.class);
        OrganizationDirectory organizations = mock(OrganizationDirectory.class);
        ProductionWritePolicy policy = mock(ProductionWritePolicy.class);
        when(organizations.platform("OZON"))
                .thenReturn(Optional.of(new MarketplacePlatformRef("OZON", "Ozon", "ACTIVE")));
        when(organizations.marketplaceAccount(ACCOUNT_ID)).thenReturn(Optional.of(
                new MarketplaceAccountRef(ACCOUNT_ID, ORG_ID, uuid(20), "OZON", "ACCOUNT",
                        "ACTIVE")));
        when(organizations.store(STORE_ID))
                .thenReturn(Optional.of(new StoreRef(STORE_ID, ORG_ID, ACCOUNT_ID,
                        "STORE", "ACTIVE")));
        PlatformCapability capability = mock(PlatformCapability.class);
        when(capability.status()).thenReturn(RegistryStatus.ACTIVE);
        when(capabilities.findById(CAPABILITY_ID)).thenReturn(Optional.of(capability));
        FeatureFlagService service = new FeatureFlagService(
                flags, capabilities, organizations, policy, AUDIT, IDS, CLOCK);
        return new Fixture(flags, capabilities, organizations, policy, service);
    }

    private static FeatureFlag flag(FlagKind kind, FlagState state, RegistryStatus status) {
        return new FeatureFlag(
                FLAG_ID, "flag", kind, FlagScopeKind.GLOBAL, null, null, null, null,
                state, "description", null, status,
                NOW.minusSeconds(60), NOW.minusSeconds(30), 0);
    }

    private static void assertCode(ThrowingAction action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(OperationRejectedException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private record Fixture(
            FeatureFlagRepository flags,
            CapabilityRepository capabilities,
            OrganizationDirectory organizations,
            ProductionWritePolicy policy,
            FeatureFlagService service) {
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
