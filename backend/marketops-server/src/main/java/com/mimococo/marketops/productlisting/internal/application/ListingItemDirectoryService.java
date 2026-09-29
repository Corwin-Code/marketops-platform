package com.mimococo.marketops.productlisting.internal.application;

import com.mimococo.marketops.productlisting.ListingItemDirectory;
import com.mimococo.marketops.productlisting.ListingKeyDirectory;
import com.mimococo.marketops.productlisting.internal.infrastructure.jdbc.PlatformListingRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Item identifiers and store keys, read from what listing observations recorded. */
@Service
public class ListingItemDirectoryService implements ListingItemDirectory, ListingKeyDirectory {

    private final PlatformListingRepository listings;

    ListingItemDirectoryService(PlatformListingRepository listings) {
        this.listings = listings;
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
        return listings.storeKeys(storeId, kind == KeyKind.ITEM, offset, limit);
    }

    @Override
    public int keyCount(UUID storeId, KeyKind kind) {
        return storeId == null ? 0 : listings.storeKeyCount(storeId, kind == KeyKind.ITEM);
    }
}
