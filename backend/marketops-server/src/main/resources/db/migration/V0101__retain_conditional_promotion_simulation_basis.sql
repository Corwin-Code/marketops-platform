-- Root 015: retain conditional inputs without claiming a published metric or qualified admission.
-- Historical snapshots are unknown; do not reconstruct them from a lossy Java toString digest.
ALTER TABLE ops.lc_simulation
 ADD COLUMN model_version text NOT NULL DEFAULT 'LEGACY_UNQUALIFIED',
 ADD COLUMN input_snapshot jsonb,
 ADD COLUMN conditional_scenarios_passed boolean,
 ALTER COLUMN calculation_run_id DROP NOT NULL;
ALTER TABLE ops.lc_simulation ADD CONSTRAINT lc_simulation_basis_ck CHECK (
 (model_version='LEGACY_UNQUALIFIED' AND calculation_run_id IS NOT NULL AND input_snapshot IS NULL
    AND conditional_scenarios_passed IS NULL)
 OR (model_version='LC_CONDITIONAL_PROFIT_2' AND calculation_run_id IS NULL
    AND demand_gate_passed IS NULL AND input_snapshot IS NOT NULL
    AND jsonb_typeof(input_snapshot)='object'
    AND octet_length(input_snapshot::text)<=524288
    AND inputs_digest=encode(sha256(convert_to(input_snapshot::text,'UTF8')),'hex')
    AND input_snapshot->>'sourceKind'='CALLER_ASSUMPTIONS'
    AND input_snapshot->>'qualificationState'='UNQUALIFIED'
    AND input_snapshot->>'modelVersion'=model_version
    AND input_snapshot->>'candidateId'=candidate_id::text
    AND input_snapshot->>'organizationId'=organization_id::text
    AND jsonb_typeof(input_snapshot->'inputs')='object'
    AND jsonb_typeof(input_snapshot->'context')='object'
    AND jsonb_typeof(input_snapshot->'scenarios')='array') IS TRUE
);
