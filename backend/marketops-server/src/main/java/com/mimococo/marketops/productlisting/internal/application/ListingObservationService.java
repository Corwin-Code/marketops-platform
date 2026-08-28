package com.mimococo.marketops.productlisting.internal.application;

import com.mimococo.marketops.organizationaccount.MarketplaceAccountRef;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.StoreRef;
import com.mimococo.marketops.productlisting.ListingObservationSink;
import com.mimococo.marketops.productlisting.ObservedListing;
import com.mimococo.marketops.productlisting.ObservedListingVariant;
import com.mimococo.marketops.productlisting.internal.domain.ObservationLifecycle;
import com.mimococo.marketops.productlisting.internal.domain.PlatformListing;
import com.mimococo.marketops.productlisting.internal.domain.PlatformListingVariant;
import com.mimococo.marketops.productlisting.internal.infrastructure.jdbc.PlatformListingRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one writer of observed platform listing identity.
 *
 * <p>Recording is idempotent on the marketplace's own keys, so re-reading a page
 * or replaying stored evidence advances the observation window and creates no
 * second listing. The identifiers are returned to the caller so price, stock and
 * funnel facts from the same pass attach to exactly the variants that were just
 * recorded, rather than to whatever a second lookup happens to resolve.
 *
 * <p>Nothing here maps anything. Proposing and confirming a relationship to
 * internal identity is a separate, reviewable act, and keeping it out of the
 * observation path is what stops an acquisition run from quietly changing which
 * internal SKU a platform listing means.
 */
@Service
public class ListingObservationService implements ListingObservationSink {

    private final PlatformListingRepository listings;
    private final OrganizationDirectory organizationDirectory;
    private final IdGenerator idGenerator;

    ListingObservationService(PlatformListingRepository listings,
                              OrganizationDirectory organizationDirectory,
                              IdGenerator idGenerator) {
        this.listings = listings;
        this.organizationDirectory = organizationDirectory;
        this.idGenerator = idGenerator;
    }

    @Override
    @Transactional
    public Map<String, Map<String, UUID>> record(List<ObservedListing> observed,
                                                 Instant observedAt) {
        Map<String, Map<String, UUID>> resolved = new LinkedHashMap<>();
        Map<UUID, StorePlacement> storeCache = new HashMap<>();

        for (ObservedListing listing : observed) {
            StorePlacement store = storeCache.computeIfAbsent(listing.storeId(), this::requirePlacement);
            UUID listingId = recordListing(listing, store, observedAt);
            Map<String, UUID> variantIds = new LinkedHashMap<>();
            for (ObservedListingVariant variant : listing.variants()) {
                variantIds.put(variant.nativeVariantKey(),
                        recordVariant(variant, listingId, store, observedAt));
            }
            resolved.put(listing.nativeListingKey(), Map.copyOf(variantIds));
        }
        return Map.copyOf(resolved);
    }

    private UUID recordListing(ObservedListing observed,
                               StorePlacement store,
                               Instant observedAt) {
        PlatformListing listing = new PlatformListing(
                idGenerator.newId(), store.organizationId(), store.id(),
                store.marketplaceAccountId(), store.platformCode(),
                observed.nativeListingKey(), observed.nativeProductKey(), observed.title(),
                observed.nativeStatus(), observedAt, observedAt,
                ObservationLifecycle.OBSERVED, observedAt, observedAt, 0L);
        listings.observeListing(listing);
        // The insert may have been absorbed by an existing row, so the
        // identifier is read back rather than assumed to be the one generated.
        return listings.findListingByNativeKey(store.id(), observed.nativeListingKey())
                .map(PlatformListing::id)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.INTERNAL_ERROR));
    }

    private UUID recordVariant(ObservedListingVariant observed,
                               UUID listingId,
                               StorePlacement store,
                               Instant observedAt) {
        PlatformListingVariant variant = new PlatformListingVariant(
                idGenerator.newId(), store.organizationId(), listingId,
                observed.nativeVariantKey(), observed.nativeSkuKey(), observed.nativeBarcode(),
                observed.nativeColorLabel(), observed.nativeSizeLabel(), observed.nativeStatus(),
                observedAt, observedAt, ObservationLifecycle.OBSERVED,
                observedAt, observedAt, 0L);
        listings.observeVariant(variant);
        return listings.findVariantByNativeKey(listingId, observed.nativeVariantKey())
                .map(PlatformListingVariant::id)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.INTERNAL_ERROR));
    }

    /**
     * Resolve where a store sits in the ownership chain.
     *
     * <p>A listing carries its account and platform so the relational layer can
     * pin them to the store's own account. Both are read through the owning
     * module's directory rather than from its tables.
     */
    private StorePlacement requirePlacement(UUID storeId) {
        StoreRef store = organizationDirectory.store(storeId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        MarketplaceAccountRef account =
                organizationDirectory.marketplaceAccount(store.marketplaceAccountId())
                        .orElseThrow(() ->
                                OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        return new StorePlacement(store.id(), store.organizationId(),
                store.marketplaceAccountId(), account.platformCode());
    }

    /** One store together with the account and platform it belongs to. */
    private record StorePlacement(
            UUID id, UUID organizationId, UUID marketplaceAccountId, String platformCode) {
    }
}
