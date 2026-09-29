-- V0019: estimate what one unit of a listing earns, and diagnose listings that do not sell.
--
-- 1. The marketplace's stated tariffs for a listing, kept with its price.
--    The Ozon price answer (POST /v5/product/info/prices, official OpenAPI checked 2026-09-29)
--    carries, per product and in the seller's price currency: the sales commission in percent per
--    fulfilment scheme (commissions.sales_percent_fbs / _fbo), the FBS processing ("first mile"),
--    trunk ("direct flow") and last-mile tariffs as minimum/maximum amounts, the FBO trunk and last
--    mile, the return tariffs, the maximum acquiring fee (acquiring) and the product's VAT rate
--    (price.vat, e.g. 0.05). They become columns of core.listing_price_observation, all optional:
--    an absent tariff stays absent and the estimate that needs it is unavailable, never zero.
--
-- 2. Metrics (definition version 2, like the rest of the set):
--    ORDERED_UNITS, SEARCH_USERS (marketplace analytics, grade C), LISTING_SELLABLE, CONTENT_RATING
--    (the 0-100 rating as a ratio of 100), PLATFORM_COMPETITOR_MIN_PRICE (grade C) and four
--    estimates at the observed buyer price, under the Owner's decisions of 2026-09-29: the store's
--    fulfilment commission, the highest stated logistics tariffs, acquiring, VAT at the listing's
--    stated rate (prices include VAT; no other turnover tax) and the unit purchase cost:
--    PROJECTED_UNIT_PROFIT, PROJECTED_UNIT_MARGIN, PROJECTED_BREAK_EVEN_PRICE, TARGET_MARGIN_PRICE
--    (the price that keeps the configured minimum unit margin, 15 %). Estimates are
--    ESTIMATED_EXPLAINED until a real settlement confirms the economics.
--
-- 3. Rules (version 1). Unlike the realized-profit chain they do not wait for sales: a listing
--    nobody buys is exactly what they describe, so a data block on realized profit does not
--    decline them. They diagnose and rank; none of them authorizes a platform write, and the price
--    gap rules rest on grade C competitor prices.

ALTER TABLE core.listing_price_observation
    ADD COLUMN sales_commission_percent_fbs numeric(7,4),
    ADD COLUMN sales_commission_percent_fbo numeric(7,4),
    ADD COLUMN fbs_first_mile_min numeric(18,4),
    ADD COLUMN fbs_first_mile_max numeric(18,4),
    ADD COLUMN fbs_direct_flow_min numeric(18,4),
    ADD COLUMN fbs_direct_flow_max numeric(18,4),
    ADD COLUMN fbs_last_mile numeric(18,4),
    ADD COLUMN fbs_return_flow numeric(18,4),
    ADD COLUMN fbo_direct_flow_min numeric(18,4),
    ADD COLUMN fbo_direct_flow_max numeric(18,4),
    ADD COLUMN fbo_last_mile numeric(18,4),
    ADD COLUMN fbo_return_flow numeric(18,4),
    ADD COLUMN acquiring_max numeric(18,4),
    ADD COLUMN vat_rate numeric(5,4);

ALTER TABLE core.listing_price_observation
    ADD CONSTRAINT listing_price_observation_commission_ck CHECK ((((sales_commission_percent_fbs IS NULL) OR ((sales_commission_percent_fbs >= (0)::numeric) AND (sales_commission_percent_fbs < (100)::numeric)))
        AND ((sales_commission_percent_fbo IS NULL) OR ((sales_commission_percent_fbo >= (0)::numeric) AND (sales_commission_percent_fbo < (100)::numeric))))),
    ADD CONSTRAINT listing_price_observation_tariffs_ck CHECK (((fbs_first_mile_min IS NULL) OR (fbs_first_mile_min >= (0)::numeric))
        AND ((fbs_first_mile_max IS NULL) OR (fbs_first_mile_max >= (0)::numeric))
        AND ((fbs_direct_flow_min IS NULL) OR (fbs_direct_flow_min >= (0)::numeric))
        AND ((fbs_direct_flow_max IS NULL) OR (fbs_direct_flow_max >= (0)::numeric))
        AND ((fbs_last_mile IS NULL) OR (fbs_last_mile >= (0)::numeric))
        AND ((fbs_return_flow IS NULL) OR (fbs_return_flow >= (0)::numeric))
        AND ((fbo_direct_flow_min IS NULL) OR (fbo_direct_flow_min >= (0)::numeric))
        AND ((fbo_direct_flow_max IS NULL) OR (fbo_direct_flow_max >= (0)::numeric))
        AND ((fbo_last_mile IS NULL) OR (fbo_last_mile >= (0)::numeric))
        AND ((fbo_return_flow IS NULL) OR (fbo_return_flow >= (0)::numeric))
        AND ((acquiring_max IS NULL) OR (acquiring_max >= (0)::numeric))),
    ADD CONSTRAINT listing_price_observation_vat_ck CHECK (((vat_rate IS NULL) OR ((vat_rate >= (0)::numeric) AND (vat_rate < (1)::numeric))));

INSERT INTO staging.canonical_field (dataset_kind, field_name, value_kind, requirement, description, ordinal) VALUES
    ('PRICE', 'salesCommissionPercentFbs', 'DECIMAL', 'OPTIONAL', 'Sales commission in percent when the seller fulfils (FBS), as the marketplace states it.', 16),
    ('PRICE', 'salesCommissionPercentFbo', 'DECIMAL', 'OPTIONAL', 'Sales commission in percent when the marketplace fulfils (FBO), as the marketplace states it.', 17),
    ('PRICE', 'fbsFirstMileMin', 'DECIMAL', 'OPTIONAL', 'Lowest stated FBS shipment processing tariff, in the price currency.', 18),
    ('PRICE', 'fbsFirstMileMax', 'DECIMAL', 'OPTIONAL', 'Highest stated FBS shipment processing tariff, in the price currency.', 19),
    ('PRICE', 'fbsDirectFlowMin', 'DECIMAL', 'OPTIONAL', 'Lowest stated FBS trunk logistics tariff, in the price currency.', 20),
    ('PRICE', 'fbsDirectFlowMax', 'DECIMAL', 'OPTIONAL', 'Highest stated FBS trunk logistics tariff, in the price currency.', 21),
    ('PRICE', 'fbsLastMile', 'DECIMAL', 'OPTIONAL', 'Stated FBS last-mile delivery tariff, in the price currency.', 22),
    ('PRICE', 'fbsReturnFlow', 'DECIMAL', 'OPTIONAL', 'Stated FBS return and cancellation tariff, in the price currency.', 23),
    ('PRICE', 'fboDirectFlowMin', 'DECIMAL', 'OPTIONAL', 'Lowest stated FBO trunk logistics tariff, in the price currency.', 24),
    ('PRICE', 'fboDirectFlowMax', 'DECIMAL', 'OPTIONAL', 'Highest stated FBO trunk logistics tariff, in the price currency.', 25),
    ('PRICE', 'fboLastMile', 'DECIMAL', 'OPTIONAL', 'Stated FBO last-mile delivery tariff, in the price currency.', 26),
    ('PRICE', 'fboReturnFlow', 'DECIMAL', 'OPTIONAL', 'Stated FBO return and cancellation tariff, in the price currency.', 27),
    ('PRICE', 'acquiringMax', 'DECIMAL', 'OPTIONAL', 'Highest stated acquiring fee per unit, in the price currency.', 28),
    ('PRICE', 'vatRate', 'DECIMAL', 'OPTIONAL', 'The VAT rate the listing is sold at (0.05 = 5 %), as the seller set it at the marketplace.', 29);

INSERT INTO mart.metric_definition (metric_code, definition_version, display_name, unit_kind, formula_statement, domain, owner_label, status) VALUES
    ('ORDERED_UNITS', 2, 'Ordered units', 'COUNT', 'Sum of units ordered over the exact half-open window as the marketplace analytics reports them; absence is not zero.', 'FUNNEL', 'analyticsdecision', 'ACTIVE'),
    ('SEARCH_USERS', 2, 'Search users', 'COUNT', 'Buyers who searched for the listing in the newest weekly search period ending at most seven days before the window end (marketplace analytics, evidence grade C); absence is not zero.', 'FUNNEL', 'analyticsdecision', 'ACTIVE'),
    ('LISTING_SELLABLE', 2, 'Listing sellable', 'COUNT', '1 when the newest listing health observation says buyers can buy the listing, 0 when it says they cannot; unavailable when unknown.', 'INVENTORY', 'analyticsdecision', 'ACTIVE'),
    ('CONTENT_RATING', 2, 'Content rating', 'RATIO', 'The marketplace content rating of the listing (0 to 100) divided by 100, from the newest observation.', 'QUALITY', 'analyticsdecision', 'ACTIVE'),
    ('PLATFORM_COMPETITOR_MIN_PRICE', 2, 'Lowest platform competitor price', 'MONEY', 'Lowest competitor price for the listing on the same marketplace, as the marketplace reports it with the newest price (platform analytics, evidence grade C); diagnosis only.', 'PROFIT', 'analyticsdecision', 'ACTIVE'),
    ('PROJECTED_UNIT_PROFIT', 2, 'Estimated unit profit', 'MONEY', 'OBSERVED_SELLING_PRICE less the sales commission for the store fulfilment scheme, the highest stated logistics tariffs, acquiring, VAT at the listing rate contained in the price, and UNIT_COST; an estimate until a settlement confirms it.', 'PROFIT', 'analyticsdecision', 'ACTIVE'),
    ('PROJECTED_UNIT_MARGIN', 2, 'Estimated unit margin', 'RATIO', 'PROJECTED_UNIT_PROFIT divided by OBSERVED_SELLING_PRICE.', 'PROFIT', 'analyticsdecision', 'ACTIVE'),
    ('PROJECTED_BREAK_EVEN_PRICE', 2, 'Estimated break-even price', 'MONEY', 'The price at which PROJECTED_UNIT_PROFIT would be zero under the same tariffs, rates and unit cost.', 'PROFIT', 'analyticsdecision', 'ACTIVE'),
    ('TARGET_MARGIN_PRICE', 2, 'Target margin price', 'MONEY', 'The price at which PROJECTED_UNIT_MARGIN would equal the configured minimum unit margin rate under the same tariffs, rates and unit cost.', 'PROFIT', 'analyticsdecision', 'ACTIVE');

INSERT INTO mart.diagnosis_rule (rule_code, rule_version, ordinal, display_name, statement, default_severity, blocks_execution, status) VALUES
    ('LISTING_NOT_SELLABLE', 1, 10, 'Listing not sellable', 'The newest listing health observation says buyers cannot buy the listing (hidden or removed). Evaluated whether or not realized profit data is complete.', 'CRITICAL', false, 'ACTIVE'),
    ('DEMAND_NOT_CONVERTING', 1, 11, 'Demand not converting', 'An in-stock listing was searched for by at least the configured number of buyers in the newest weekly search period and no unit was ordered in the window. Evaluated whether or not realized profit data is complete.', 'WARNING', false, 'ACTIVE'),
    ('PRICE_GAP_REDUCIBLE', 1, 12, 'Price gap reducible', 'The buyer price is above the lowest competitor price on the marketplace and the target margin price is at or below it: the gap can be closed while keeping the minimum margin. Grade C competitor data; diagnosis only.', 'WARNING', false, 'ACTIVE'),
    ('PRICE_GAP_PARTIAL', 1, 13, 'Price gap partly reducible', 'The buyer price is above the lowest competitor price, the estimated break-even price is at or below it and the target margin price above it: the gap can be closed only below the minimum margin. Grade C competitor data; diagnosis only.', 'WARNING', false, 'ACTIVE'),
    ('PRICE_GAP_STRUCTURAL', 1, 14, 'Price gap structural', 'The buyer price is above the lowest competitor price and the estimated break-even price is above it too: matching the competitor loses money on every unit, so cost, fulfilment or positioning has to change. Grade C competitor data; diagnosis only.', 'WARNING', false, 'ACTIVE'),
    ('LOW_SEARCH_EXPOSURE', 1, 15, 'Low search exposure', 'An in-stock listing was searched for by fewer buyers than the configured exposure floor in the newest weekly search period: it appears in few searches.', 'WARNING', false, 'ACTIVE'),
    ('CONTENT_BELOW_TARGET', 1, 16, 'Content below target', 'The marketplace content rating of the listing is below the configured floor.', 'WARNING', false, 'ACTIVE');

INSERT INTO mart.diagnosis_rule_input (rule_code, rule_version, metric_code, requirement) VALUES
    ('LISTING_NOT_SELLABLE', 1, 'LISTING_SELLABLE', 'REQUIRED'),
    ('DEMAND_NOT_CONVERTING', 1, 'SEARCH_USERS', 'REQUIRED'),
    ('DEMAND_NOT_CONVERTING', 1, 'ORDERED_UNITS', 'REQUIRED'),
    ('DEMAND_NOT_CONVERTING', 1, 'PLATFORM_AVAILABLE_UNITS', 'REQUIRED'),
    ('PRICE_GAP_REDUCIBLE', 1, 'OBSERVED_SELLING_PRICE', 'REQUIRED'),
    ('PRICE_GAP_REDUCIBLE', 1, 'PLATFORM_COMPETITOR_MIN_PRICE', 'REQUIRED'),
    ('PRICE_GAP_REDUCIBLE', 1, 'TARGET_MARGIN_PRICE', 'REQUIRED'),
    ('PRICE_GAP_PARTIAL', 1, 'OBSERVED_SELLING_PRICE', 'REQUIRED'),
    ('PRICE_GAP_PARTIAL', 1, 'PLATFORM_COMPETITOR_MIN_PRICE', 'REQUIRED'),
    ('PRICE_GAP_PARTIAL', 1, 'PROJECTED_BREAK_EVEN_PRICE', 'REQUIRED'),
    ('PRICE_GAP_PARTIAL', 1, 'TARGET_MARGIN_PRICE', 'REQUIRED'),
    ('PRICE_GAP_STRUCTURAL', 1, 'OBSERVED_SELLING_PRICE', 'REQUIRED'),
    ('PRICE_GAP_STRUCTURAL', 1, 'PLATFORM_COMPETITOR_MIN_PRICE', 'REQUIRED'),
    ('PRICE_GAP_STRUCTURAL', 1, 'PROJECTED_BREAK_EVEN_PRICE', 'REQUIRED'),
    ('LOW_SEARCH_EXPOSURE', 1, 'SEARCH_USERS', 'REQUIRED'),
    ('LOW_SEARCH_EXPOSURE', 1, 'PLATFORM_AVAILABLE_UNITS', 'REQUIRED'),
    ('LOW_SEARCH_EXPOSURE', 1, 'LISTING_SELLABLE', 'OPTIONAL'),
    ('CONTENT_BELOW_TARGET', 1, 'CONTENT_RATING', 'REQUIRED');
