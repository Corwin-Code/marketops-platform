-- An advertising verdict names the policy that authorised it.
--
-- ops.guardrail_evaluation requires a PASS to carry policy_id and
-- policy_version, and policy_id is a foreign key into ops.commercial_policy —
-- the price policy. An advertising decision has no commercial policy. It has a
-- decision policy bundle, which is the whole-combination-validated activation
-- record the write gate already checks.
--
-- So the constraint could not be satisfied by any advertising PASS. Combined
-- with the authority binder V0051 repaired, the effect was the same: no
-- advertising execution PASS could exist, so no advertising command could be
-- created.
--
-- Widening policy_id to mean two things would have been worse than the problem.
-- A column that points at one of two tables depending on a value elsewhere is a
-- column nothing can foreign-key, and the write gate reads this row. So the
-- shared table carries both identities, each with its own real foreign key, and
-- a PASS names exactly one of them.
--
-- Forward-only: two nullable columns and a replaced check. No existing row is
-- touched, and every existing row already satisfies the new rule because it
-- carries a commercial policy and no bundle.

ALTER TABLE ops.guardrail_evaluation ADD COLUMN ad_decision_bundle_id uuid;
ALTER TABLE ops.guardrail_evaluation ADD COLUMN ad_bundle_version integer;

ALTER TABLE ops.guardrail_evaluation
    ADD CONSTRAINT guardrail_evaluation_ad_bundle_fk
    FOREIGN KEY (ad_decision_bundle_id, organization_id)
    REFERENCES ops.ad_decision_policy_bundle (id, organization_id);

ALTER TABLE ops.guardrail_evaluation
    ADD CONSTRAINT guardrail_evaluation_ad_bundle_shape_ck
    CHECK ((ad_decision_bundle_id IS NULL) = (ad_bundle_version IS NULL));

-- Exactly one policy identity on a PASS, and never both. A verdict naming a
-- commercial policy and a decision bundle at once would be a verdict two
-- different authorities could each claim to have produced.
ALTER TABLE ops.guardrail_evaluation DROP CONSTRAINT guardrail_evaluation_policy_presence_ck;
ALTER TABLE ops.guardrail_evaluation
    ADD CONSTRAINT guardrail_evaluation_policy_presence_ck
    CHECK (outcome = 'BLOCK'
        OR ((policy_id IS NOT NULL AND policy_version IS NOT NULL)
             != (ad_decision_bundle_id IS NOT NULL AND ad_bundle_version IS NOT NULL)));

CREATE INDEX guardrail_evaluation_ad_bundle_ix
    ON ops.guardrail_evaluation (ad_decision_bundle_id, evaluated_at DESC)
    WHERE ad_decision_bundle_id IS NOT NULL;

-- The write gate already refuses a command whose recommendation has no EXECUTION
-- PASS. It should also refuse one whose PASS was authorised by a different
-- bundle from the one the command was created under, which was unaskable while
-- the verdict could not name a bundle at all.
CREATE FUNCTION ops.ad_bid_execution_pass_matches_bundle(p_command_id uuid)
RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, ops, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM ops.ad_bid_command c
          JOIN ops.guardrail_evaluation g
            ON g.recommendation_id = c.recommendation_id
           AND g.organization_id = c.organization_id
         WHERE c.id = p_command_id
           AND g.purpose = 'EXECUTION'
           AND g.outcome = 'PASS'
           AND g.ad_decision_bundle_id = c.bundle_id)
$$;
REVOKE ALL ON FUNCTION ops.ad_bid_execution_pass_matches_bundle(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.ad_bid_execution_pass_matches_bundle(uuid) TO marketops_app;
