package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Turns a listing identifier into the store it belongs to and checks the actor
 * against that store, in one place.
 *
 * <p>A listing outside the actor's organization is refused as out of scope,
 * not as missing, so a caller cannot probe another organization's identifiers.
 */
@Service
public class ListingScopeAuthorization {

    /** What a controller needs to know about a listing it may act on. */
    public record ListingScope(UUID listingId, UUID organizationId, UUID storeId, String platformCode,
                               String nativeListingKey) {
    }

    private final ListingFactRepository facts;
    private final BusinessAuthorization authorization;

    ListingScopeAuthorization(ListingFactRepository facts, BusinessAuthorization authorization) {
        this.facts = facts;
        this.authorization = authorization;
    }

    public ListingScope require(AuthenticatedActor actor, UUID listingId, ActionScopeCode scope) {
        ListingFactRepository.ListingContext listing = facts.listing(listingId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!actor.organizationId().equals(listing.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        authorization.require(actor, scope, ResourceScope.store(listing.storeId()));
        return new ListingScope(listing.id(), listing.organizationId(), listing.storeId(), listing.platformCode(),
                listing.nativeListingKey());
    }
}
