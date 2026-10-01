-- V0028: buyer price jump (Owner decision 2026-10-01).
--
-- A buyer price that rises sharply overnight stops sales without anybody deciding it: on the pilot
-- store the promotion "Акция для товаров со схемой FBS" (15 participating products) ended at
-- 2026-09-30 21:00 UTC, and in the next price collection 14 of 41 listings were offered at a
-- higher buyer price (on average 1.54 times the earlier one), 8 of them without any promotion
-- price left. Nothing in the diagnosis said so.
--
-- RECENT_LOW_BUYER_PRICE is the lowest price a buyer was offered (the seller's promotion price
-- when there was one, the selling price otherwise) among the price observations of the configured
-- lookback before the window end, in the currency of the newest observation. It cites the lowest
-- and the newest observation and is as fresh as the newest: an old low is the point of the
-- comparison, not stale data.
--
-- BUYER_PRICE_JUMP triggers when the newest buyer price is at least the configured least rise
-- above that low. A warning, because it can stop sales from one day to the next; a person decides
-- whether to join a promotion again or to change the price. Once the higher price has held for
-- the whole lookback, the low catches up and the finding clears.
INSERT INTO mart.metric_definition (metric_code, definition_version, display_name, unit_kind, formula_statement, domain, owner_label, status) VALUES
    ('RECENT_LOW_BUYER_PRICE', 2, 'Recent lowest buyer price', 'MONEY', 'The lowest price a buyer was offered (the seller''s promotion price when there was one, the selling price otherwise) among the price observations of the configured lookback before the window end, in the currency of the newest observation; as fresh as the newest observation.', 'PROFIT', 'analyticsdecision', 'ACTIVE');

INSERT INTO mart.diagnosis_rule (rule_code, rule_version, ordinal, display_name, statement, default_severity, blocks_execution, status) VALUES
    ('BUYER_PRICE_JUMP', 1, 19, 'Buyer price jump', 'The newest buyer price is at least the configured least rise above the lowest buyer price of the configured lookback before the window end, most often because a promotion ended or the price was raised: buyers who saw the lower price now see a much higher one. Evaluated whether or not realized profit data is complete.', 'WARNING', false, 'ACTIVE');

INSERT INTO mart.diagnosis_rule_input (rule_code, rule_version, metric_code, requirement) VALUES
    ('BUYER_PRICE_JUMP', 1, 'OBSERVED_SELLING_PRICE', 'REQUIRED'),
    ('BUYER_PRICE_JUMP', 1, 'RECENT_LOW_BUYER_PRICE', 'REQUIRED');
