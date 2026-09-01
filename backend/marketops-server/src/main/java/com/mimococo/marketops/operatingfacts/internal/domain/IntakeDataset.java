package com.mimococo.marketops.operatingfacts.internal.domain;

import java.util.List;
import java.util.Optional;

/**
 * The internal fact families this product accepts from a file or from a person,
 * and the fields each of them needs.
 *
 * <p>The target vocabulary is defined here rather than recorded as evidence,
 * because it is this system's own model: what a purchase cost is made of is a
 * MarketOps decision, unlike the column names a particular company's
 * spreadsheet happens to use. Those column names arrive from the registered
 * schema profile and are mapped onto these fields.
 *
 * <p>Every dataset carries an effective instant. Cost and finance inputs are
 * versioned facts and an unstated effective date would make a profit figure
 * unreproducible; a stock count without a time is a number nobody can compare.
 */
public enum IntakeDataset {

    /** What one internal variant costs to buy. */
    PURCHASE_COST(List.of(
            new Field("skuCode", FieldKind.TEXT, true),
            new Field("unitCost", FieldKind.DECIMAL, true),
            new Field("currencyCode", FieldKind.TEXT, true),
            new Field("effectiveFrom", FieldKind.INSTANT, true),
            new Field("costKind", FieldKind.TEXT, false))),

    /** What the company itself holds, per warehouse. */
    INTERNAL_STOCK(List.of(
            new Field("skuCode", FieldKind.TEXT, true),
            new Field("warehouseCode", FieldKind.TEXT, true),
            new Field("quantityOnHand", FieldKind.INTEGER, true),
            new Field("observedAt", FieldKind.INSTANT, true),
            new Field("quantityReserved", FieldKind.INTEGER, false),
            new Field("quantityQualityLocked", FieldKind.INTEGER, false),
            new Field("quantityDamaged", FieldKind.INTEGER, false),
            new Field("quantityWrittenOff", FieldKind.INTEGER, false),
            new Field("sellable", FieldKind.TEXT, false),
            new Field("returnReentryId", FieldKind.TEXT, false))),

    /** A company-owned input to the profit definition. */
    FINANCE_INPUT(List.of(
            new Field("inputCode", FieldKind.TEXT, true),
            new Field("scopeKind", FieldKind.TEXT, true),
            new Field("valueKind", FieldKind.TEXT, true),
            new Field("effectiveFrom", FieldKind.INSTANT, true),
            new Field("scopeCode", FieldKind.TEXT, false),
            new Field("rateValue", FieldKind.DECIMAL, false),
            new Field("amountValue", FieldKind.DECIMAL, false),
            new Field("currencyCode", FieldKind.TEXT, false)));

    private final List<Field> fields;

    IntakeDataset(List<Field> fields) {
        this.fields = List.copyOf(fields);
    }

    /** Every field this dataset understands. */
    public List<Field> fields() {
        return fields;
    }

    /** The fields a row must carry to be accepted. */
    public List<Field> requiredFields() {
        return fields.stream().filter(Field::required).toList();
    }

    /** One field by name, when this dataset has it. */
    public Optional<Field> field(String name) {
        return fields.stream().filter(candidate -> candidate.name().equals(name)).findFirst();
    }

    /** How a field's text is converted. */
    public enum FieldKind {
        TEXT,
        INTEGER,
        DECIMAL,
        INSTANT
    }

    /**
     * One target field of an intake dataset.
     *
     * @param name the internal field name a profile maps a column onto
     * @param kind how the text is converted
     * @param required whether a row without it is rejected
     */
    public record Field(String name, FieldKind kind, boolean required) {
    }
}
