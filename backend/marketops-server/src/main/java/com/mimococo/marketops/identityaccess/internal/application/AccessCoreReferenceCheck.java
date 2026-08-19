package com.mimococo.marketops.identityaccess.internal.application;

import com.mimococo.marketops.identityaccess.internal.domain.ScopeResourceType;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.ScopeGrantRepository;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.ServiceAccountRepository;
import com.mimococo.marketops.organizationaccount.CoreEntityReferenceCheck;
import com.mimococo.marketops.organizationaccount.CoreEntityType;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Retirement veto for core entities that live access metadata still references.
 *
 * <p>An entity with an active grant pointing at it, or an organization that
 * still owns a non-revoked service account, cannot retire: the grant or
 * account must be revoked first, so the journal records the authorization
 * teardown explicitly.
 */
@Component
class AccessCoreReferenceCheck implements CoreEntityReferenceCheck {

    private final ScopeGrantRepository grants;
    private final ServiceAccountRepository serviceAccounts;

    AccessCoreReferenceCheck(ScopeGrantRepository grants,
                             ServiceAccountRepository serviceAccounts) {
        this.grants = grants;
        this.serviceAccounts = serviceAccounts;
    }

    @Override
    public boolean hasActiveReferences(CoreEntityType entityType, UUID entityId) {
        ScopeResourceType resourceType = switch (entityType) {
            case ORGANIZATION -> ScopeResourceType.ORGANIZATION;
            case LEGAL_ENTITY -> ScopeResourceType.LEGAL_ENTITY;
            case MARKETPLACE_ACCOUNT -> ScopeResourceType.MARKETPLACE_ACCOUNT;
            case STORE -> ScopeResourceType.STORE;
            case WAREHOUSE -> ScopeResourceType.WAREHOUSE;
        };
        if (grants.countActiveByResource(resourceType, entityId) > 0) {
            return true;
        }
        return entityType == CoreEntityType.ORGANIZATION
                && serviceAccounts.countNotRevokedByOrganization(entityId) > 0;
    }
}
