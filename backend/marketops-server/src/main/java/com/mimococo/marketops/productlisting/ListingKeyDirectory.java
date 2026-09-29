package com.mimococo.marketops.productlisting;

import java.util.List;
import java.util.UUID;

/**
 * The keys a store's observed listings are recorded under, for requests that
 * name products rather than page through a listing.
 *
 * <p>Some marketplace methods are asked about named products (Ozon: product
 * status by product id, content rating by SKU). The names come from what the
 * catalog recorded, in one stable order, so a request can be split into
 * batches by offset and every key is asked exactly once.
 */
public interface ListingKeyDirectory {

    /** Which recorded key a request names products by. */
    enum KeyKind {

        /** The marketplace listing key (Ozon: product id). */
        LISTING,

        /** The marketplace item key (Ozon: SKU). */
        ITEM
    }

    /** One batch of a store's recorded keys of one kind, in a stable order. */
    List<String> keys(UUID storeId, KeyKind kind, int offset, int limit);

    /** How many keys of one kind a store has recorded. */
    int keyCount(UUID storeId, KeyKind kind);
}
