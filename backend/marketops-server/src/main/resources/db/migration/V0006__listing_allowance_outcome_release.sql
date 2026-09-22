-- V0006: release a description change's allowance occupation once its outcome period has matured.
--
-- Until now an applied description change had no release path at all: only NOT_APPLIED_PROVEN
-- (the API command ended without any provider call) and the promotion STOP_EVIDENCE /
-- OBLIGATION_CLEARED phases released anything. A successfully applied description therefore held
-- its CONCURRENT_LISTINGS / AFFECTED_VARIANTS / money / share occupation forever, which turned the
-- description allowance into a lifetime cap on how many listings were ever changed.
--
-- New basis OUTCOME_MATURED. An ACQUIRED occupation of a LISTING_DESCRIPTION_CHANGE action is
-- released when all of the following hold at release time:
--   * the action is VERIFIED, i.e. the change was applied and confirmed:
--       MANUAL: a QUALIFIED, MATCHED_TARGET, DISPLAYED manual verification of the exact target on a
--               VERIFIED packet (the same evidence that moved the action to VERIFIED);
--       API:    a MANAGEMENT_VERIFIED execution receipt of a READBACK_MATCHED command whose readback
--               matched the exact target (the same evidence that moved the action to VERIFIED);
--   * no later manual verification contradicts it (MATCHED_PRIOR, DIFFERENT or NOT_DISPLAYED);
--   * the listing is not contained (ops.lc_scope_contained, which also covers unreleased outcome
--     failures) and has no open unresolved-change investigation, and the action has no open
--     unauthorised-deviation record;
--   * now >= confirmation time + RESPONSIBILITY_SLO.outcomeMaturityDays of the action's own frozen
--     calibration package. The value must be an explicit integer 1..3660, exactly as the Java
--     ListingResponsibilitySchedule reads it (3660 there is the upper bound, not a default). When
--     it is absent or malformed nothing is released: the state is MATURITY_UNRESOLVED and the
--     occupation stays, the conservative side of the allowance.
-- Promotion occupations are never touched here; they keep their own two-phase release.
--
-- Attribution. lc_exposure_occupation_release_ck requires released_by_user_id, a human
-- iam.user_account (service accounts live in a separate table and cannot be referenced). No
-- person performs this release; it is executed under a standing policy the Owner accepted: the
-- calibration package that carries outcomeMaturityDays. released_by_user_id is therefore the
-- package's accepting user (ops.lc_calibration_governance.accepted_by_user_id, which the table
-- CHECK forces to differ from drafter and validator). The journal row itself is SYSTEM-attributed
-- to the fixed component 'listing-allowance-release', like ops.audit_diagnostic_export and the
-- advertising outcome quarantine, and records whether a timer or a named maintenance operator
-- triggered the pass. A package without an acceptor is POLICY_ACCEPTOR_UNRESOLVED: no release.

SET LOCAL client_min_messages = warning;
SELECT pg_catalog.set_config('search_path', '', true);

ALTER TABLE ops.lc_exposure_occupation DROP CONSTRAINT lc_exposure_occupation_basis_ck;
ALTER TABLE ops.lc_exposure_occupation ADD CONSTRAINT lc_exposure_occupation_basis_ck CHECK (
    (release_basis IS NULL) OR (release_basis = ANY (ARRAY['STOP_EVIDENCE'::text, 'OBLIGATION_CLEARED'::text,
        'NOT_APPLIED_PROVEN'::text, 'OUTCOME_MATURED'::text])));

-- Where one description action stands on the way to OUTCOME_MATURED. Read-only.
-- state: MATURED | NOT_YET_MATURED | NOT_APPLICABLE | UNCONFIRMED | CONTRADICTED | CONTAINED
--        | INVESTIGATION_OPEN | MATURITY_UNRESOLVED | POLICY_ACCEPTOR_UNRESOLVED
CREATE FUNCTION ops.lc_description_outcome_maturity(p_action uuid, p_at timestamp with time zone) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'ops', 'core', 'pg_temp'
    AS $$
DECLARE action ops.lc_action%ROWTYPE; confirmation_id uuid; confirmed_at timestamptz; path text;
 slo jsonb; days_node jsonb; maturity_days integer; acceptor uuid; matures_at timestamptz; result jsonb; state text;
BEGIN
 IF p_at IS NULL THEN RAISE EXCEPTION 'evaluation time required' USING ERRCODE='MO092'; END IF;
 SELECT * INTO action FROM ops.lc_action WHERE id=p_action;
 IF NOT FOUND THEN RAISE EXCEPTION 'action does not exist' USING ERRCODE='MO090'; END IF;
 result:=jsonb_build_object('actionId',action.id,'organizationId',action.organization_id,
   'platformListingId',action.platform_listing_id,'executionPath',action.execution_path,
   'calibrationPackageId',action.calibration_package_id,'calibrationVersion',action.calibration_version,
   'evaluatedAt',p_at);
 IF action.action_kind<>'LISTING_DESCRIPTION_CHANGE' OR action.calibration_package_id IS NULL
    OR EXISTS(SELECT 1 FROM ops.lc_promotion_engagement e WHERE e.action_id=action.id) THEN
   RETURN result||jsonb_build_object('state','NOT_APPLICABLE');
 END IF;
 IF action.state<>'VERIFIED' THEN
   RETURN result||jsonb_build_object('state','UNCONFIRMED');
 END IF;

 IF action.execution_path='MANUAL' THEN
   path:='MANUAL_VERIFICATION';
   SELECT v.id,v.verified_at INTO confirmation_id,confirmed_at
     FROM ops.lc_manual_verification v JOIN ops.lc_manual_packet p ON p.id=v.packet_id
    WHERE p.action_id=action.id AND p.state='VERIFIED'
      AND v.management_match='MATCHED_TARGET' AND v.qualification_state='QUALIFIED'
      AND v.display_state='DISPLAYED' AND v.management_observation_id IS NOT NULL
      AND v.display_observation_id IS NOT NULL
      AND v.observation_binding->>'targetTextDigest'=action.target_text_digest
    ORDER BY v.verified_at,v.id LIMIT 1;
 ELSE
   path:='API_READBACK';
   SELECT r.id,r.recorded_at INTO confirmation_id,confirmed_at
     FROM ops.lc_execution_receipt r
     JOIN ops.lc_description_command c ON c.id=r.command_id
     JOIN ops.lc_description_command_readback rb ON rb.id=r.readback_id AND rb.command_id=c.id
    WHERE r.action_id=action.id AND c.action_id=action.id AND r.execution_state='MANAGEMENT_VERIFIED'
      AND c.state='READBACK_MATCHED' AND rb.match_state='MATCHES_TARGET'
      AND c.target_text_digest=action.target_text_digest
    ORDER BY r.recorded_at,r.id LIMIT 1;
 END IF;
 result:=result||jsonb_build_object('confirmationPath',path);
 IF confirmation_id IS NULL THEN
   RETURN result||jsonb_build_object('state','UNCONFIRMED');
 END IF;
 result:=result||jsonb_build_object('confirmationEvidenceId',confirmation_id,'confirmedAt',confirmed_at);

 SELECT value_json INTO slo FROM core.lc_calibration_value
  WHERE package_id=action.calibration_package_id AND category_code='RESPONSIBILITY_SLO';
 days_node:=CASE WHEN jsonb_typeof(slo)='object' THEN slo->'outcomeMaturityDays' END;
 -- An explicit JSON integer only (30, not 30.0 or "30"); the cast runs only after the shape check.
 IF coalesce(jsonb_typeof(days_node),'')='number' THEN
   IF days_node::text ~ '^[0-9]{1,4}$' THEN
     maturity_days:=(days_node::text)::integer;
     IF maturity_days NOT BETWEEN 1 AND 3660 THEN maturity_days:=NULL; END IF;
   END IF;
 END IF;
 IF maturity_days IS NOT NULL THEN
   matures_at:=confirmed_at+make_interval(days=>maturity_days);
   result:=result||jsonb_build_object('outcomeMaturityDays',maturity_days,'maturesAt',matures_at);
 END IF;
 SELECT g.accepted_by_user_id INTO acceptor FROM ops.lc_calibration_governance g
  WHERE g.package_id=action.calibration_package_id AND g.accepted_at IS NOT NULL;
 result:=result||jsonb_build_object('policyAcceptedByUserId',acceptor);

 state:=CASE
   WHEN EXISTS(SELECT 1 FROM ops.lc_manual_verification v JOIN ops.lc_manual_packet p ON p.id=v.packet_id
        WHERE p.action_id=action.id AND v.verified_at>confirmed_at AND v.verified_at<=p_at
          AND (v.management_match IN ('MATCHED_PRIOR','DIFFERENT') OR v.display_state='NOT_DISPLAYED'))
     THEN 'CONTRADICTED'
   WHEN ops.lc_scope_contained(action.organization_id,action.platform_listing_id) THEN 'CONTAINED'
   WHEN EXISTS(SELECT 1 FROM ops.lc_late_association l
        WHERE l.organization_id=action.organization_id AND l.state<>'CLOSED'
          AND ((l.platform_listing_id=action.platform_listing_id AND l.association_kind='UNRESOLVED_CHANGE')
            OR (l.action_id=action.id AND l.association_kind='UNAUTHORISED_DEVIATION')))
     THEN 'INVESTIGATION_OPEN'
   WHEN maturity_days IS NULL THEN 'MATURITY_UNRESOLVED'
   WHEN acceptor IS NULL THEN 'POLICY_ACCEPTOR_UNRESOLVED'
   WHEN p_at<matures_at THEN 'NOT_YET_MATURED'
   ELSE 'MATURED' END;
 RETURN result||jsonb_build_object('state',state);
END $$;

-- One bounded, idempotent release pass. Only ACQUIRED occupations of VERIFIED description actions
-- whose outcome period has matured are released; each release writes one SYSTEM journal row.
-- p_trigger is SCHEDULED (no operator) or MAINTENANCE (the loopback operator attribution).
CREATE FUNCTION ops.release_lc_matured_description_occupations(p_limit integer, p_trigger text, p_operator text,
    p_correlation_id text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'ops', 'core', 'pg_temp'
    AS $$
DECLARE candidate record; released record; basis jsonb; now_at timestamptz; considered integer:=0;
 occupations jsonb:='[]'::jsonb; actions jsonb:='[]'::jsonb; skipped jsonb:='[]'::jsonb; released_here integer;
BEGIN
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 500 THEN
   RAISE EXCEPTION 'release pass size must be between 1 and 500' USING ERRCODE='MO092';
 END IF;
 IF p_trigger IS NULL OR p_trigger NOT IN ('SCHEDULED','MAINTENANCE')
    OR (p_trigger='SCHEDULED' AND p_operator IS NOT NULL)
    OR (p_trigger='MAINTENANCE' AND (p_operator IS NULL OR p_operator !~ '^[a-z0-9][a-z0-9._-]{0,63}$')) THEN
   RAISE EXCEPTION 'release trigger and its attribution do not agree' USING ERRCODE='MO092';
 END IF;
 IF p_correlation_id IS NULL OR p_correlation_id !~ '^[A-Za-z0-9._:-]{1,64}$' THEN
   RAISE EXCEPTION 'correlation id required' USING ERRCODE='MO092';
 END IF;
 now_at:=clock_timestamp();
 FOR candidate IN
   SELECT a.id,a.organization_id FROM ops.lc_action a
    WHERE a.action_kind='LISTING_DESCRIPTION_CHANGE' AND a.state='VERIFIED'
      AND EXISTS(SELECT 1 FROM ops.lc_exposure_occupation o WHERE o.action_id=a.id AND o.state='ACQUIRED')
      AND ops.lc_description_outcome_maturity(a.id,now_at)->>'state'='MATURED'
    ORDER BY a.updated_at,a.id
    LIMIT p_limit
 LOOP
   considered:=considered+1;
   -- Same serialisation as acquisition and every other release of this organization's allowance.
   PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(candidate.organization_id::text));
   PERFORM 1 FROM ops.lc_action WHERE id=candidate.id FOR SHARE;
   now_at:=clock_timestamp();
   basis:=ops.lc_description_outcome_maturity(candidate.id,now_at);
   IF basis->>'state'<>'MATURED' THEN
     skipped:=skipped||jsonb_build_object('actionId',candidate.id,'state',basis->>'state');
     CONTINUE;
   END IF;
   released_here:=0;
   FOR released IN
     UPDATE ops.lc_exposure_occupation o SET state='RELEASED',released_at=now_at,release_basis='OUTCOME_MATURED',
        release_evidence_reference='lc-outcome-matured:'||lower(basis->>'confirmationPath')||':'
          ||(basis->>'confirmationEvidenceId'),
        released_by_user_id=(basis->>'policyAcceptedByUserId')::uuid,
        release_evidence_id=(basis->>'confirmationEvidenceId')::uuid,
        release_evidence=basis||jsonb_build_object('purpose','OUTCOME_MATURED','axisCode',o.axis_code,
          'releasedAt',now_at,'trigger',p_trigger,'operator',p_operator)
      WHERE o.action_id=candidate.id AND o.state='ACQUIRED'
      RETURNING o.id,o.axis_code,o.release_evidence_reference
   LOOP
     released_here:=released_here+1;
     INSERT INTO ops.metadata_audit_event(id,actor_type,actor_id,source_domain,action,entity_type,entity_id,
       change_summary,reason,correlation_id,evidence_ref)
     VALUES(gen_random_uuid(),'SYSTEM','listing-allowance-release','listingconversion','STATUS_CHANGE',
       'lc-exposure-occupation',released.id,
       jsonb_build_object(
         'state',jsonb_build_object('previous','ACQUIRED','current','RELEASED'),
         'releaseBasis',jsonb_build_object('previous',NULL,'current','OUTCOME_MATURED'),
         'actionId',jsonb_build_object('previous',NULL,'current',candidate.id::text),
         'axisCode',jsonb_build_object('previous',NULL,'current',released.axis_code),
         'confirmedAt',jsonb_build_object('previous',NULL,'current',basis->>'confirmedAt'),
         'maturesAt',jsonb_build_object('previous',NULL,'current',basis->>'maturesAt'),
         'releasedBy',jsonb_build_object('previous',NULL,'current',basis->>'policyAcceptedByUserId'),
         'trigger',jsonb_build_object('previous',NULL,'current',
            CASE WHEN p_operator IS NULL THEN p_trigger ELSE p_trigger||':'||p_operator END)),
       'outcome observation period matured after the applied change was confirmed',
       p_correlation_id,released.release_evidence_reference);
     occupations:=occupations||jsonb_build_object('occupationId',released.id,'actionId',candidate.id,
       'axisCode',released.axis_code);
   END LOOP;
   IF released_here>0 THEN actions:=actions||to_jsonb(candidate.id); END IF;
 END LOOP;
 RETURN jsonb_build_object('trigger',p_trigger,'evaluatedAt',now_at,'considered',considered,
   'releasedActions',actions,'releasedOccupations',occupations,'skipped',skipped,
   'limitReached',considered>=p_limit);
END $$;

REVOKE ALL ON FUNCTION ops.lc_description_outcome_maturity(p_action uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_description_outcome_maturity(p_action uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.release_lc_matured_description_occupations(p_limit integer, p_trigger text, p_operator text, p_correlation_id text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.release_lc_matured_description_occupations(p_limit integer, p_trigger text, p_operator text, p_correlation_id text) TO marketops_app;
