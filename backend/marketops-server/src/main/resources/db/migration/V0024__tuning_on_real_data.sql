-- V0024: tuning against the pilot store's real data.
--
-- DATA_BLOCKED blocked every listing of a store where nothing has sold. The share of realized
-- profit inputs counts sales, fees, returns, advertising and tax per unit, which exist only once
-- something sold, so on a store without orders the share stays at the unit cost alone and the rule
-- triggered CRITICAL for every listing — declining the stock, return, funnel, advertising and price
-- rules behind it, including the stock-out of listings with no stock at all. With nothing sold in
-- the window (no completed and no ordered units, each zero or not reported) the missing inputs are
-- the business situation rather than a coverage gap: the rule now declines for want of a sample
-- (INSUFFICIENT_SAMPLE) and the later rules answer from their own inputs. An unresolved mapping,
-- stale or conflicting inputs, and missing inputs on a listing that did sell still block. The
-- rule keeps version 1: its findings record the condition they met.
UPDATE mart.diagnosis_rule
   SET statement = 'Mapping is unresolved, a required profit input is missing on a listing that sold in the window, or the freshest input is older than the domain freshness target; later rules are declined for this subject. With nothing sold in the window (no completed and no ordered units) the realized profit inputs cannot exist yet: the rule declines for want of a sample and later rules answer from their own inputs.'
 WHERE rule_code = 'DATA_BLOCKED' AND rule_version = 1;

INSERT INTO mart.diagnosis_rule_input (rule_code, rule_version, metric_code, requirement) VALUES
    ('DATA_BLOCKED', 1, 'COMPLETED_UNITS', 'OPTIONAL'),
    ('DATA_BLOCKED', 1, 'ORDERED_UNITS', 'OPTIONAL');
