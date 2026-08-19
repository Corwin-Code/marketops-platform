package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.organizationaccount.CoreEntityReferenceCheck;
import com.mimococo.marketops.organizationaccount.CoreEntityType;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Retirement veto for core entities that live integration metadata still
 * references.
 *
 * <p>An organization or account with a non-revoked credential, a store inside
 * an active credential scope, or an entity carrying an active feature flag
 * cannot retire: the credential must be revoked, the scope withdrawn or the
 * flag retired first, so the journal records the teardown explicitly.
 */
@Component
class IntegrationCoreReferenceCheck implements CoreEntityReferenceCheck {

    private final CredentialService credentialService;
    private final FeatureFlagService featureFlagService;

    IntegrationCoreReferenceCheck(CredentialService credentialService,
                                  FeatureFlagService featureFlagService) {
        this.credentialService = credentialService;
        this.featureFlagService = featureFlagService;
    }

    @Override
    public boolean hasActiveReferences(CoreEntityType entityType, UUID entityId) {
        return switch (entityType) {
            case ORGANIZATION ->
                    credentialService.countNotRevokedByOrganization(entityId) > 0;
            case MARKETPLACE_ACCOUNT ->
                    credentialService.countNotRevokedByAccount(entityId) > 0
                            || featureFlagService.countActiveByAccount(entityId) > 0;
            case STORE -> credentialService.countActiveScopesByStore(entityId) > 0
                    || featureFlagService.countActiveByStore(entityId) > 0;
            case LEGAL_ENTITY, WAREHOUSE -> false;
        };
    }
}
