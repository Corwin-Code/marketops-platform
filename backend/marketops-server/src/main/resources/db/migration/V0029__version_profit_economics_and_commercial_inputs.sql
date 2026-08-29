-- R2 closes the profit/minimum-price semantic gap without changing historical
-- definitions or values. Version 1 remains readable and retired; version 2 is
-- the only live definition set.

ALTER TABLE core.finance_input_version
    DROP CONSTRAINT finance_input_version_code_ck;

ALTER TABLE core.finance_input_version
    ADD CONSTRAINT finance_input_version_code_ck
        CHECK (input_code IN (
            'VARIABLE_TAX_RATE', 'PAYMENT_PROCESSING_RATE',
            'RETURN_HANDLING_UNIT_COST', 'INBOUND_LOGISTICS_UNIT_COST',
            'REQUIRED_PROFIT_PER_UNIT', 'SAFETY_BUFFER_PER_UNIT')),
    ADD CONSTRAINT finance_input_version_commercial_amount_ck CHECK (
        input_code NOT IN ('REQUIRED_PROFIT_PER_UNIT', 'SAFETY_BUFFER_PER_UNIT')
        OR value_kind = 'AMOUNT');

UPDATE mart.metric_definition
   SET status = 'RETIRED'
 WHERE status = 'ACTIVE';

INSERT INTO mart.metric_definition
    (metric_code, definition_version, display_name, unit_kind, formula_statement,
     domain, owner_label, status)
SELECT metric_code,
       2,
       CASE metric_code
           WHEN 'MINIMUM_PRICE' THEN 'Minimum price'
           ELSE display_name
       END,
       unit_kind,
       CASE metric_code
           WHEN 'PLATFORM_FEES' THEN
               'Sum of platform fee amounts over the exact half-open window excluding advertising and variable tax.'
           WHEN 'OPERATIONAL_CONTRIBUTION_PROFIT' THEN
               'COMPLETED_NET_SALES less unit cost of completed units, platform fees, return loss, advertising spend and a required sourced variable tax estimate; unavailable when any component is absent.'
           WHEN 'SETTLED_CONTRIBUTION_PROFIT' THEN
               'SETTLED_NET_SALES less unit cost of settled units, settled platform fees, return loss, advertising spend and actual variable tax; unavailable when any component is absent.'
           WHEN 'MINIMUM_PRICE' THEN
               'BREAK_EVEN_PRICE plus REQUIRED_PROFIT_PER_UNIT plus SAFETY_BUFFER_PER_UNIT; every amount must be sourced, canonical and currency-consistent.'
           WHEN 'DATA_COMPLETENESS' THEN
               'Share of the eight required canonical profit and commercial inputs that resolved, bound to the effective listing mapping.'
           ELSE formula_statement
       END,
       domain,
       owner_label,
       'ACTIVE'
  FROM mart.metric_definition
 WHERE definition_version = 1;

INSERT INTO mart.metric_definition
    (metric_code, definition_version, display_name, unit_kind, formula_statement,
     domain, owner_label, status) VALUES
    ('PLATFORM_FEES_PER_UNIT', 2, 'Platform fees per completed unit', 'MONEY',
        'PLATFORM_FEES over the exact half-open window divided by COMPLETED_UNITS; absence is not zero.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('RETURN_LOSS_PER_UNIT', 2, 'Return loss per completed unit', 'MONEY',
        'RETURN_LOSS over the exact half-open window divided by COMPLETED_UNITS; absence is not zero.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('AD_SPEND_PER_UNIT', 2, 'Advertising spend per completed unit', 'MONEY',
        'AD_SPEND over the exact half-open window divided by COMPLETED_UNITS; absence is not zero.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('VARIABLE_TAX_PER_UNIT', 2, 'Actual variable tax per completed unit', 'MONEY',
        'Explicitly published VARIABLE_TAX fee amount over the exact half-open window divided by COMPLETED_UNITS; absence is not zero.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('REQUIRED_PROFIT_PER_UNIT', 2, 'Required profit per unit', 'MONEY',
        'The effective sourced company-owned REQUIRED_PROFIT_PER_UNIT amount; no default is permitted.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('SAFETY_BUFFER_PER_UNIT', 2, 'Safety buffer per unit', 'MONEY',
        'The effective sourced company-owned SAFETY_BUFFER_PER_UNIT amount; no default is permitted.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('BREAK_EVEN_PRICE', 2, 'Break-even price', 'MONEY',
        'UNIT_COST plus canonical platform-fee, return-loss, advertising-spend and actual-variable-tax amounts per completed unit.',
        'PROFIT', 'analyticsdecision', 'ACTIVE');
