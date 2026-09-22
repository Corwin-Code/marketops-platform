-- S4-DR-R1-001/002: use the existing live identity/grant authority for both
-- purposes. Role eligibility does not itself grant any organization/store/data scope.
INSERT INTO iam.action_scope (code, display_name, description, requires_step_up, ordinal) VALUES
 ('LISTING_OUTCOME_EVALUATE', 'Evaluate listing outcome',
  'Record qualified evidence under the exact frozen listing evaluation plan.', false, 42),
 ('LISTING_DECISION_EVIDENCE_VIEW', 'Read listing decision evidence',
  'Read financial evidence only within the granted store and affected product scopes.', false, 43);

INSERT INTO iam.business_role_action_scope (role_code, action_code) VALUES
 ('OWNER', 'LISTING_OUTCOME_EVALUATE'),
 ('OPS_LEAD', 'LISTING_OUTCOME_EVALUATE'),
 ('OPERATIONS', 'LISTING_OUTCOME_EVALUATE'),
 ('FINANCE', 'LISTING_OUTCOME_EVALUATE'),
 ('FINANCE_ANALYST', 'LISTING_OUTCOME_EVALUATE'),
 ('RISK_AUTHORITY', 'LISTING_OUTCOME_EVALUATE'),
 ('OWNER', 'LISTING_DECISION_EVIDENCE_VIEW'),
 ('OPS_LEAD', 'LISTING_DECISION_EVIDENCE_VIEW'),
 ('FINANCE', 'LISTING_DECISION_EVIDENCE_VIEW'),
 ('FINANCE_ANALYST', 'LISTING_DECISION_EVIDENCE_VIEW'),
 ('AUDITOR', 'LISTING_DECISION_EVIDENCE_VIEW');

-- Financial reads use the identities actually consumed by this calculation,
-- not whichever SKU a listing happens to map to when its history is read.
-- Legacy rows carry no proven disclosure scope and remain masked.
ALTER TABLE ops.lc_simulation ADD COLUMN evidence_product_variant_ids uuid[];
ALTER TABLE ops.lc_simulation ADD CONSTRAINT lc_simulation_evidence_scope_ck
 CHECK (evidence_product_variant_ids IS NULL OR
        (cardinality(evidence_product_variant_ids) > 0 AND array_position(evidence_product_variant_ids, NULL) IS NULL));
