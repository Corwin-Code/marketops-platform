-- Expose the same settled sale quantity already consumed by settled contribution profit.
-- Definition version 2 matches the existing Metric engine family; no older definitions change.
INSERT INTO mart.metric_definition
    (metric_code,definition_version,display_name,unit_kind,formula_statement,domain,owner_label,status)
VALUES ('SETTLED_UNITS',2,'Settled units','COUNT',
    'Sum of SETTLED sale quantities over the exact half-open business window; absent source remains unavailable.',
    'SALES','analyticsdecision','ACTIVE');
