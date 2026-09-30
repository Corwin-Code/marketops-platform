package com.mimococo.marketops.productlisting.internal.application;

import com.mimococo.marketops.productlisting.ListingItemDirectory;
import com.mimococo.marketops.productlisting.ListingKeyDirectory;
import com.mimococo.marketops.productlisting.internal.infrastructure.jdbc.PlatformListingRepository;
import com.mimococo.marketops.productlisting.internal.infrastructure.jdbc.PlatformPromotionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Item identifiers and store keys, read from what listing and promotion observations recorded. */
@Service
public class ListingItemDirectoryService implements ListingItemDirectory, ListingKeyDirectory {

    private final PlatformListingRepository listings;
    private final PlatformPromotionRepository promotions;
    private final java.time.Clock clock;

    ListingItemDirectoryService(PlatformListingRepository listings, PlatformPromotionRepository promotions,
                                java.time.Clock clock) {
        this.listings = listings;
        this.promotions = promotions;
        this.clock = clock;
    }

    @Override
    public Optional<ListingKeys> byItemKey(UUID storeId, String nativeItemKey) {
        if (storeId == null || nativeItemKey == null || nativeItemKey.isBlank()) {
            return Optional.empty();
        }
        return listings.findKeysByItem(storeId, nativeItemKey)
                .map(keys -> new ListingKeys(keys.nativeListingKey(), keys.nativeVariantKey()));
    }

    @Override
    public List<String> keys(UUID storeId, KeyKind kind, int offset, int limit) {
        if (storeId == null || offset < 0 || limit < 1) {
            return List.of();
        }
        if (kind == KeyKind.PROMOTION) {
            return promotions.currentKeys(storeId, clock.instant(), offset, limit);
        }
        return listings.storeKeys(storeId, kind == KeyKind.ITEM, offset, limit);
    }

    @Override
    public int keyCount(UUID storeId, KeyKind kind) {
        if (storeId == null) {
            return 0;
        }
        return kind == KeyKind.PROMOTION ? promotions.currentKeyCount(storeId, clock.instant())
                : listings.storeKeyCount(storeId, kind == KeyKind.ITEM);
    }
}
