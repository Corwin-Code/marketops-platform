-- V0017: keep the unit cost the seller entered at the marketplace, beside the price it was stated with.
--
-- The Ozon price answer (POST /v5/product/info/prices, official docs checked 2026-09-29) carries
-- price.net_price, "Себестоимость товара": the seller's own unit cost as entered in the seller
-- cabinet, in the currency of the seller's prices. The Owner accepted it as the source of the
-- pilot's cost of goods (2026-09-29), so it becomes a fact before anything builds a cost version
-- from it: the cost version then names this observation as its evidence.
--   * seller_cost_price is in the observation's currency_code;
--   * a cost of zero is how an unfilled field reads, so only a positive amount is kept and an
--     absent one stays absent.
-- It is a seller statement, not a settlement: profit built on it stays Estimated until a real
-- settlement confirms the economics.

ALTER TABLE core.listing_price_observation
    ADD COLUMN seller_cost_price numeric(18,4);

ALTER TABLE core.listing_price_observation
    ADD CONSTRAINT listing_price_observation_seller_cost_ck CHECK (((seller_cost_price IS NULL) OR (seller_cost_price > (0)::numeric)));

INSERT INTO staging.canonical_field (dataset_kind, field_name, value_kind, requirement, description, ordinal) VALUES
    ('PRICE', 'sellerCostPrice', 'DECIMAL', 'OPTIONAL', 'The seller''s own unit cost as entered at the marketplace, in the price currency.', 15);
