package com.mimococo.marketops.productlisting;

/**
 * What an operator calls a listing variant.
 *
 * <p>A queue of identifiers is not a work list: people recognise a product by
 * its name, its SKU and the marketplace's own key. This value carries those
 * names for display only. It decides nothing, and nothing may be keyed on it —
 * the listing variant identifier stays the subject of every rule and write.
 *
 * <p>The catalogue side is present only while the listing variant has an
 * active confirmed mapping. An unmapped listing variant still reports what
 * the marketplace said about it, and {@code null} everywhere else, so a caller
 * shows "unmapped" rather than a guessed product.
 *
 * @param productName our product name, or {@code null} when unmapped
 * @param variantName our variant name, or {@code null} when unmapped
 * @param skuCode our SKU code, or {@code null} when unmapped
 * @param platformCode marketplace the listing lives on
 * @param platformSkuKey the marketplace's seller SKU key, or {@code null}
 * @param colorLabel the catalogue colour, falling back to the marketplace's
 * @param sizeLabel the catalogue size, falling back to the marketplace's
 */
public record SubjectIdentity(
        String productName,
        String variantName,
        String skuCode,
        String platformCode,
        String platformSkuKey,
        String colorLabel,
        String sizeLabel) {
}
