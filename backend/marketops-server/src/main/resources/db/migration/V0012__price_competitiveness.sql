-- V0012: keep the marketplace's own view of how competitive a price is.
--
-- The Ozon price answer (POST /v5/product/info/prices, official docs checked 2026-09-29) carries
-- price_indexes: a final class (color_index: WITHOUT_INDEX, SUPER, GREEN, YELLOW, RED) and the
-- lowest competitor price Ozon found on Ozon (ozon_index_data) and on other marketplaces
-- (external_index_data), each with its own currency. On the pilot store 14 of 41 products were
-- RED and 27 had no index; a missing competitor price is answered as 0 with an empty currency.
--
-- These are platform analytics (confidence C in the requirements baseline): they explain and may
-- rank a diagnosis; they never drive an automatic price change or a profit figure (HR-07).
--   * price_index_native keeps the marketplace's word verbatim; no internal vocabulary exists yet;
--   * a competitor price is kept only with a positive amount and a currency code, so the absence
--     Ozon writes as 0 / '' stays absent instead of becoming a price of zero.

ALTER TABLE core.listing_price_observation
    ADD COLUMN price_index_native text,
    ADD COLUMN platform_competitor_min_price numeric(18,4),
    ADD COLUMN platform_competitor_currency_code text,
    ADD COLUMN external_competitor_min_price numeric(18,4),
    ADD COLUMN external_competitor_currency_code text;

ALTER TABLE core.listing_price_observation
    ADD CONSTRAINT listing_price_observation_index_native_ck CHECK (((price_index_native IS NULL) OR ((length(btrim(price_index_native)) >= 1) AND (length(btrim(price_index_native)) <= 64)))),
    ADD CONSTRAINT listing_price_observation_platform_competitor_ck CHECK ((((platform_competitor_min_price IS NULL) = (platform_competitor_currency_code IS NULL))
        AND ((platform_competitor_min_price IS NULL) OR (platform_competitor_min_price > (0)::numeric))
        AND ((platform_competitor_currency_code IS NULL) OR (platform_competitor_currency_code ~ '^[A-Z]{3}$'::text)))),
    ADD CONSTRAINT listing_price_observation_external_competitor_ck CHECK ((((external_competitor_min_price IS NULL) = (external_competitor_currency_code IS NULL))
        AND ((external_competitor_min_price IS NULL) OR (external_competitor_min_price > (0)::numeric))
        AND ((external_competitor_currency_code IS NULL) OR (external_competitor_currency_code ~ '^[A-Z]{3}$'::text))));

INSERT INTO staging.canonical_field (dataset_kind, field_name, value_kind, requirement, description, ordinal) VALUES
    ('PRICE', 'priceIndexNative', 'TEXT', 'OPTIONAL', 'The marketplace''s own word for how competitive the price is (platform analytics).', 10),
    ('PRICE', 'platformCompetitorMinPrice', 'DECIMAL', 'OPTIONAL', 'Lowest competitor price on the same marketplace, as the marketplace reports it.', 11),
    ('PRICE', 'platformCompetitorCurrencyCode', 'TEXT', 'OPTIONAL', 'Currency of the lowest competitor price on the same marketplace.', 12),
    ('PRICE', 'externalCompetitorMinPrice', 'DECIMAL', 'OPTIONAL', 'Lowest competitor price on other marketplaces, as the marketplace reports it.', 13),
    ('PRICE', 'externalCompetitorCurrencyCode', 'TEXT', 'OPTIONAL', 'Currency of the lowest competitor price on other marketplaces.', 14);
