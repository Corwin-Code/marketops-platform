package com.mimococo.marketops.productlisting.internal.application;

import com.mimococo.marketops.productlisting.ListingItemDirectory;
import com.mimococo.marketops.productlisting.internal.infrastructure.jdbc.PlatformListingRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Item identifiers resolved through what listing observations recorded. */
@Service
public class ListingItemDirectoryService implements ListingItemDirectory {

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
}
