package com.mimococo.marketops.productlisting.internal.domain;

/**
 * Whether a barcode still identifies its internal variant.
 *
 * <p>Retirement releases the value so a legitimate re-registration is possible,
 * while the retired row stays readable next to the mapping decisions that were
 * made while it was live.
 */
public enum BarcodeStatus {
    ACTIVE,
    RETIRED
}
