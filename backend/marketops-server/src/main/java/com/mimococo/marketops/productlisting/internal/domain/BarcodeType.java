package com.mimococo.marketops.productlisting.internal.domain;

/**
 * The barcode symbologies this product records.
 *
 * <p>{@code UNKNOWN} is first class: a barcode whose symbology nobody recorded
 * is still a usable mapping signal, and guessing the symbology from the digit
 * count would be an invented fact.
 */
public enum BarcodeType {
    EAN13,
    EAN8,
    UPC,
    ITF14,
    INTERNAL,
    UNKNOWN
}
