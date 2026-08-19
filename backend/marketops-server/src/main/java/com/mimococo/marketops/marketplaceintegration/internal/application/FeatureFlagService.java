package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.marketplaceintegration.FeatureFlagDirectory;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FeatureFlag;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagScopeKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.FlagState;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RegistryStatus;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CapabilityRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.FeatureFlagRepository;
import com.mimococo.marketops.organizationaccount.MarketplaceAccountRef;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.StoreRef;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import com.mimococo.marketops.shared.ProductionWritePolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance operations and the published read side of feature-flag metadata.
 *
 * <p>A flag is created disabled. The disable direction is never gated. The
 * enable direction of a write-capability flag consults the global
 * production-write policy, which is false for the whole product, so such a
 * transition is refused and journaled; flag metadata can never represent an
 * enabled platform write that the process would not accept.
 */
@Service
public class FeatureFlagService implements FeatureFlagDirectory {

    static final String ENTITY_TYPE = "feature-flag";

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final FeatureFlagRepository flags;
    private final CapabilityRepository capabilities;
    private final OrganizationDirectory organizationDirectory;
    private final ProductionWritePolicy productionWritePolicy;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    FeatureFlagService(FeatureFlagRepository flags,
                       CapabilityRepository capabilities,
                       OrganizationDirectory organizationDirectory,
                       ProductionWritePolicy productionWritePolicy,
                       MetadataAuditRecorder auditRecorder,
                       IdGenerator idGenerator,
                       Clock clock) {
        this.flags = flags;
        this.capabilities = capabilities;
        this.organizationDirectory = organizationDirectory;
        this.productionWritePolicy = productionWritePolicy;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Register a flag in its disabled state. */
    @Transactional
    public FeatureFlag create(String operator,
                              String flagCode,
                              FlagKind flagKind,
                              FlagScopeKind scopeKind,
                              String platformCode,
                              UUID marketplaceAccountId,
                              UUID storeId,
                              UUID capabilityId,
                              String description) {
        String validCode = MetadataFieldPolicy.requireRegistryCode(flagCode);
        String validDescription = MetadataFieldPolicy.optionalText("description", description);
        if (flagKind == null || scopeKind == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        requireScopeReferences(scopeKind, platformCode, marketplaceAccountId,
                storeId, capabilityId);
        flags.findActiveByScope(validCode, scopeKind, platformCode, marketplaceAccountId,
                storeId, capabilityId).ifPresent(existing -> {
                    throw OperationRejectedException.duplicate(
                            AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                            ENTITY_TYPE, validCode, existing.id());
                });

        Instant now = clock.instant();
        FeatureFlag flag = new FeatureFlag(
                idGenerator.newId(), validCode, flagKind, scopeKind, platformCode,
                marketplaceAccountId, storeId, capabilityId, FlagState.DISABLED,
                validDescription, null, RegistryStatus.ACTIVE, now, now, 0L);
        flags.insert(flag);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.CREATE,
                ENTITY_TYPE, flag.id(), validCode,
                Map.of(
                        "flagCode", new FieldChange(null, validCode),
                        "flagKind", new FieldChange(null, flagKind.name()),
                        "scopeKind", new FieldChange(null, scopeKind.name()),
                        "scopeKey", new FieldChange(null, scopeKey(flag)),
                        "state", new FieldChange(null, FlagState.DISABLED.name()),
                        "status", new FieldChange(null, RegistryStatus.ACTIVE.name())),
                null, null));
        return flag;
    }

    /**
     * Switch a flag.
     *
     * <p>Disabling always succeeds from an active flag. Enabling a
     * write-capability flag is refused while production writes are globally
     * disabled, and the refusal is journaled and observable.
     */
    @Transactional
    public FeatureFlag changeState(String operator,
                                   UUID id,
                                   FlagState targetState,
                                   String reason,
                                   long expectedVersion) {
        FeatureFlag current = require(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (targetState == null || current.status() != RegistryStatus.ACTIVE
                || targetState == current.state()) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENTITY_TYPE, current.id(), current.flagCode());
        }
        if (targetState == FlagState.ENABLED
                && current.flagKind() == FlagKind.WRITE_CAPABILITY
                && !productionWritePolicy.productionWritesEnabled()) {
            log.atWarn()
                    .addKeyValue("event", "production_write_denied")
                    .addKeyValue("flagCode", current.flagCode())
                    .addKeyValue("scopeKey", scopeKey(current))
                    .addKeyValue("actorId", operator)
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("Write-capability flag enable refused");
            throw OperationRejectedException.forEntity(
                    ErrorCode.PRODUCTION_WRITE_DISABLED,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENTITY_TYPE, current.id(), current.flagCode());
        }

        FeatureFlag updated = new FeatureFlag(
                current.id(), current.flagCode(), current.flagKind(), current.scopeKind(),
                current.platformCode(), current.marketplaceAccountId(), current.storeId(),
                current.capabilityId(), targetState, current.description(), validReason,
                current.status(), current.createdAt(), clock.instant(),
                expectedVersion + 1);
        CredentialService.applyVersioned(flags.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator,
                AuditAction.STATUS_CHANGE, ENTITY_TYPE, current.id(), current.flagCode(),
                Map.of("state", new FieldChange(
                        current.state().name(), targetState.name())),
                validReason, null));
        return updated;
    }

    /** Retire a flag; only a disabled flag can retire. */
    @Transactional
    public FeatureFlag retire(String operator, UUID id, String reason, long expectedVersion) {
        FeatureFlag current = require(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (current.status() != RegistryStatus.ACTIVE
                || current.state() != FlagState.DISABLED) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENTITY_TYPE, current.id(), current.flagCode());
        }

        FeatureFlag retired = new FeatureFlag(
                current.id(), current.flagCode(), current.flagKind(), current.scopeKind(),
                current.platformCode(), current.marketplaceAccountId(), current.storeId(),
                current.capabilityId(), current.state(), current.description(),
                validReason, RegistryStatus.RETIRED, current.createdAt(),
                clock.instant(), expectedVersion + 1);
        CredentialService.applyVersioned(flags.update(retired, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator,
                AuditAction.STATUS_CHANGE, ENTITY_TYPE, current.id(), current.flagCode(),
                Map.of("status", new FieldChange(
                        current.status().name(), RegistryStatus.RETIRED.name())),
                validReason, null));
        return retired;
    }

    /** Load one flag. */
    public FeatureFlag view(UUID id) {
        return require(id);
    }

    /** List flags by code and scope key with a keyset cursor. */
    public List<FeatureFlag> list(String afterFlagCode, String afterScopeKey, int limit) {
        String scopeKeyCursor = afterFlagCode == null ? null
                : Objects.toString(afterScopeKey, "");
        return flags.list(afterFlagCode, scopeKeyCursor, Math.clamp(limit, 1, 200));
    }

    /** Count active flags scoped to one marketplace account. */
    public long countActiveByAccount(UUID marketplaceAccountId) {
        return flags.countActiveByAccount(marketplaceAccountId);
    }

    /** Count active flags scoped to one store. */
    public long countActiveByStore(UUID storeId) {
        return flags.countActiveByStore(storeId);
    }

    @Override
    public boolean isEnabledGlobal(String flagCode) {
        return isEnabled(flagCode, FlagScopeKind.GLOBAL, null, null, null, null);
    }

    @Override
    public boolean isEnabledForPlatform(String flagCode, String platformCode) {
        return platformCode != null
                && isEnabled(flagCode, FlagScopeKind.PLATFORM, platformCode,
                        null, null, null);
    }

    @Override
    public boolean isEnabledForAccount(String flagCode, UUID marketplaceAccountId) {
        return marketplaceAccountId != null
                && isEnabled(flagCode, FlagScopeKind.MARKETPLACE_ACCOUNT, null,
                        marketplaceAccountId, null, null);
    }

    @Override
    public boolean isEnabledForStore(String flagCode, UUID storeId) {
        return storeId != null
                && isEnabled(flagCode, FlagScopeKind.STORE, null, null, storeId, null);
    }

    @Override
    public boolean isEnabledForCapability(String flagCode, UUID capabilityId) {
        return capabilityId != null
                && isEnabled(flagCode, FlagScopeKind.CAPABILITY, null, null, null,
                        capabilityId);
    }

    private boolean isEnabled(String flagCode, FlagScopeKind scopeKind,
                              String platformCode, UUID marketplaceAccountId,
                              UUID storeId, UUID capabilityId) {
        if (flagCode == null) {
            return false;
        }
        return flags.findActiveByScope(flagCode, scopeKind, platformCode,
                        marketplaceAccountId, storeId, capabilityId)
                .map(flag -> flag.state() == FlagState.ENABLED)
                .orElse(false);
    }

    private FeatureFlag require(UUID id) {
        return flags.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                        ENTITY_TYPE, id, null));
    }

    private void requireScopeReferences(FlagScopeKind scopeKind,
                                        String platformCode,
                                        UUID marketplaceAccountId,
                                        UUID storeId,
                                        UUID capabilityId) {
        boolean shapeValid = switch (scopeKind) {
            case GLOBAL -> platformCode == null && marketplaceAccountId == null
                    && storeId == null && capabilityId == null;
            case PLATFORM -> platformCode != null && marketplaceAccountId == null
                    && storeId == null && capabilityId == null;
            case MARKETPLACE_ACCOUNT -> platformCode == null
                    && marketplaceAccountId != null && storeId == null
                    && capabilityId == null;
            case STORE -> platformCode == null && marketplaceAccountId == null
                    && storeId != null && capabilityId == null;
            case CAPABILITY -> platformCode == null && marketplaceAccountId == null
                    && storeId == null && capabilityId != null;
        };
        if (!shapeValid) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        switch (scopeKind) {
            case GLOBAL -> {
            }
            case PLATFORM -> {
                if (organizationDirectory.platform(platformCode).isEmpty()) {
                    throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
                }
            }
            case MARKETPLACE_ACCOUNT -> {
                MarketplaceAccountRef account = organizationDirectory
                        .marketplaceAccount(marketplaceAccountId)
                        .orElseThrow(() -> OperationRejectedException.forEntity(
                                ErrorCode.RESOURCE_NOT_FOUND,
                                AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                                ENTITY_TYPE, marketplaceAccountId, null));
                if (!"ACTIVE".equals(account.status())) {
                    throw OperationRejectedException.of(
                            ErrorCode.INVALID_STATE_TRANSITION);
                }
            }
            case STORE -> {
                StoreRef store = organizationDirectory.store(storeId)
                        .orElseThrow(() -> OperationRejectedException.forEntity(
                                ErrorCode.RESOURCE_NOT_FOUND,
                                AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                                ENTITY_TYPE, storeId, null));
                if (!"ACTIVE".equals(store.status())) {
                    throw OperationRejectedException.of(
                            ErrorCode.INVALID_STATE_TRANSITION);
                }
            }
            case CAPABILITY -> {
                RegistryStatus status = capabilities.findById(capabilityId)
                        .orElseThrow(() -> OperationRejectedException.forEntity(
                                ErrorCode.RESOURCE_NOT_FOUND,
                                AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                                ENTITY_TYPE, capabilityId, null))
                        .status();
                if (status != RegistryStatus.ACTIVE) {
                    throw OperationRejectedException.of(
                            ErrorCode.INVALID_STATE_TRANSITION);
                }
            }
        }
    }

    /** The scope key as the database's stored generated column renders it. */
    private static String scopeKey(FeatureFlag flag) {
        return flag.scopeKind().name() + ':'
                + Objects.toString(flag.platformCode(), "")
                + ':' + (flag.marketplaceAccountId() == null
                        ? "" : flag.marketplaceAccountId().toString())
                + ':' + (flag.storeId() == null ? "" : flag.storeId().toString())
                + ':' + (flag.capabilityId() == null ? "" : flag.capabilityId().toString());
    }
}
