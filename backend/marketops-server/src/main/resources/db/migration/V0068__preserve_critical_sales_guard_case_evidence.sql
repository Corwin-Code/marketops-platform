-- Mature Outcome guards already participate in Case calculation. Preserve
-- their evidence reference on the same canonical Case after targeted refresh.
ALTER TABLE mart.ad_case_evidence DROP CONSTRAINT ad_case_evidence_role_ck;
ALTER TABLE mart.ad_case_evidence ADD CONSTRAINT ad_case_evidence_role_ck
 CHECK (evidence_role IN (
    'OFFICIAL_SPEND', 'OFFICIAL_TRAFFIC', 'PROVIDER_ATTRIBUTION',
    'AD_LINKED_SALE', 'COMPANY_SALES', 'PROFIT_ECONOMICS',
    'OBJECT_CONFIGURATION', 'AFFECTED_SET', 'MAPPING',
    'CONVERSION_DEFINITION', 'ALLOWABLE_CPA_DEFINITION',
    'FRESHNESS_PROFILE', 'QUALIFICATION_POLICY', 'PRIORITY_POLICY',
    'HUMAN_SLO_PROFILE', 'SEMANTIC_PROFILE', 'POLICY_BUNDLE',
    'CRITICAL_SALES_GUARD'));
