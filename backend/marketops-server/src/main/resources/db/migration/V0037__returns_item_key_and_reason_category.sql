-- V0037: returns and sales named by the marketplace item (step one of returns and finance).
--
-- The Owner decided on 2026-10-02 to feed the return-quality check in three steps:
--   1. returns;
--   2. finance accruals by day;
--   3. retained sales and the return-quality evidence snapshot.
-- Ozon's returns (POST /v1/returns/list) and finance accruals name a product by its SKU and its seller
-- article, never by the product id the catalog keys listings by. The normalizer already resolves a
-- variant from `nativeItemKey` through the catalog (V0011). RETURNS and SALES only lacked the field.
--
-- Returns also get `reasonCategory`, one of `ledger.return_fact`'s reason categories, so a mapping can
-- classify a marketplace's reason. A value outside the categories, or none, is recorded as UNKNOWN,
-- with the marketplace's own words kept in `reasonNative`.

INSERT INTO staging.canonical_field (dataset_kind, field_name, value_kind, requirement, description, ordinal, repeated) VALUES
    ('RETURNS', 'nativeItemKey', 'TEXT', 'OPTIONAL', 'The marketplace item identifier, when the source names the variant by it instead of the listing and variant keys.', 12, false),
    ('RETURNS', 'reasonCategory', 'TEXT', 'OPTIONAL', 'QUALITY, SIZE_OR_FIT, NOT_AS_DESCRIBED, DAMAGED_IN_TRANSIT, CUSTOMER_CHANGED_MIND, LOGISTICS or OTHER; anything else is recorded as UNKNOWN.', 13, false),
    ('SALES', 'nativeItemKey', 'TEXT', 'OPTIONAL', 'The marketplace item identifier, when the source names the variant by it instead of the listing and variant keys.', 12, false);
