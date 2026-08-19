package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialMetadata;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialScopeUsability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialStoreScope;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RotationStanding;
import java.util.List;

/**
 * One credential with its derived read-side state.
 *
 * <p>Expiry, scope usability and rotation standing are computed at read time
 * from the stored row, the clock, the scope rows and the succession lineage;
 * none of them is stored, so none of them can go stale.
 *
 * @param credential the stored metadata row
 * @param expired whether the validity window has passed
 * @param scopeUsability derived coverage of the declared scope
 * @param rotationStatus derived position in the rotation lineage
 * @param storeScopes the credential's scope rows, active first
 */
public record CredentialView(
        CredentialMetadata credential,
        boolean expired,
        CredentialScopeUsability scopeUsability,
        RotationStanding rotationStatus,
        List<CredentialStoreScope> storeScopes) {
}
