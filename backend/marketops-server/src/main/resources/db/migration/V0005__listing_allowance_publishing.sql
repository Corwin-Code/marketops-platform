-- V0005: the Owner publishes and retires listing launch allowances from the console.
--
-- Until now ops.lc_exposure_allowance had no writer at all: a row could only be
-- added with hand-written SQL, so every launch preview answered ALLOWANCE_MISSING.
-- This migration adds exactly one write path, two SECURITY DEFINER functions that
-- take a one-use authenticated control invocation (the same proof the calibration
-- lifecycle uses), check the Owner's LISTING_CALIBRATION_ACCEPT grant on the scope,
-- and keep the version and no-overlap rules. The application role still has no
-- INSERT or UPDATE on the table.
--
-- A read helper, ops.lc_allowance_occupancy, counts what an allowance row has
-- occupied now with exactly the rules ops.lc_allowance_projection uses, so the
-- maintenance page and the launch check can never disagree about headroom. It is
-- ops.lc_scope_occupancy applied to the row's scope and axis; the publish preview
-- calls that directly for a scope that has no current row yet.

SET LOCAL client_encoding = 'UTF8';
SET LOCAL standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', true);
SET LOCAL check_function_bodies = false;
SET LOCAL client_min_messages = warning;
SET LOCAL row_security = off;

-- ---------------------------------------------------------------- lineage and retirement

-- All new columns are nullable: rows inserted by hand before V0005 keep their meaning.
ALTER TABLE ops.lc_exposure_allowance
    ADD COLUMN publish_reason text,
    ADD COLUMN supersedes_allowance_id uuid,
    ADD COLUMN retired_at timestamp with time zone,
    ADD COLUMN retired_by_user_id uuid,
    ADD COLUMN retire_reason text;

ALTER TABLE ops.lc_exposure_allowance
    ADD CONSTRAINT lc_exposure_allowance_publish_reason_ck CHECK (((publish_reason IS NULL)
        OR ((length(btrim(publish_reason)) >= 1) AND (length(btrim(publish_reason)) <= 512)))),
    ADD CONSTRAINT lc_exposure_allowance_retirement_ck CHECK ((((retired_at IS NULL) AND (retired_by_user_id IS NULL)
        AND (retire_reason IS NULL)) OR ((status = 'RETIRED'::text) AND (retired_at IS NOT NULL)
        AND (retired_by_user_id IS NOT NULL) AND (length(btrim(retire_reason)) >= 1)
        AND (length(btrim(retire_reason)) <= 512)))),
    ADD CONSTRAINT lc_exposure_allowance_supersedes_ck CHECK (((supersedes_allowance_id IS NULL) OR (supersedes_allowance_id <> id)));

ALTER TABLE ONLY ops.lc_exposure_allowance
    ADD CONSTRAINT lc_exposure_allowance_supersedes_fk FOREIGN KEY (supersedes_allowance_id, organization_id)
        REFERENCES ops.lc_exposure_allowance(id, organization_id);

ALTER TABLE ONLY ops.lc_exposure_allowance
    ADD CONSTRAINT lc_exposure_allowance_retired_by_fk FOREIGN KEY (retired_by_user_id, organization_id)
        REFERENCES iam.user_account(id, organization_id);

-- ---------------------------------------------------------------- control invocation purposes

-- Same function as V0001 with two new purposes appended; nothing else changes.
CREATE OR REPLACE FUNCTION iam.issue_ad_control_invocation_grant(p_purpose text, p_proof_hash text, p_actor uuid, p_org uuid, p_provider uuid, p_subject text, p_session text, p_authenticated timestamp with time zone, p_step_up_until timestamp with time zone, p_target uuid, p_version uuid, p_backend integer, p_transaction bigint) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'iam', 'pg_temp'
    AS $$ BEGIN
 IF p_purpose NOT IN ('COMPENSATION_PREVIEW','COMPENSATION_ENDORSE','COMPENSATION_APPROVE',
 'BUNDLE_DRAFT','BUNDLE_ENDORSE','BUNDLE_APPROVE','CONTAINMENT_STOP','AUTHORITY_VERSION_STOP','CONTAINMENT_REENABLE',
 'CONTAINMENT_ATTEST','CONTAINMENT_ENDORSE','MANUAL_POLICY_PUBLISH','MANUAL_PACKET_SELECT',
 'MANUAL_PACKET_ENDORSE','MANUAL_PACKET_APPROVE','MANUAL_EXECUTION_REPORT','MANUAL_EXECUTION_START','MANUAL_INDEPENDENT_VERIFY',
 'LISTING_ACTION_LAUNCH','LISTING_ACTION_REVIEW','LISTING_ACTION_APPROVE','LISTING_MANUAL_VERIFY',
 'LISTING_OCCUPATION_RELEASE','LISTING_CONTAINMENT_STOP','LISTING_CONTAINMENT_ATTEST',
 'LISTING_CONTAINMENT_CONSENT','LISTING_CONTAINMENT_REENABLE','LISTING_PROMOTION_EXIT',
 'LISTING_ISOLATION_DEPENDENCY_RECORD','LISTING_CALIBRATION_PREPARE','LISTING_CALIBRATION_VALIDATE',
 'LISTING_CALIBRATION_ACCEPT','LISTING_CALIBRATION_ACTIVATE',
 'LISTING_ALLOWANCE_PUBLISH','LISTING_ALLOWANCE_RETIRE') THEN
  RAISE EXCEPTION 'unknown control invocation purpose' USING ERRCODE='MO092'; END IF;
 PERFORM iam.issue_ad_invocation_grant(p_proof_hash,p_actor,p_org,p_provider,p_subject,p_session,
 p_authenticated,p_step_up_until,p_target,p_version,p_backend,p_transaction);
 UPDATE iam.ad_invocation_grant SET purpose=p_purpose WHERE proof_hash=p_proof_hash;
END $$;

-- ---------------------------------------------------------------- occupancy (read)

-- What one scope has occupied now on one axis, whichever allowance row the occupations
-- were taken under: the launch check counts every live occupation and engagement in
-- the scope, so a new version inherits them. The two branches are the occupied-value
-- parts of ops.lc_allowance_projection, unchanged except that the organization, scope
-- and unit come from parameters instead of from an action and an allowance row. Keep
-- them in step with that function. p_unit matters for REVENUE_EXPOSURE only.
CREATE FUNCTION ops.lc_scope_occupancy(p_org uuid, p_scope_kind text, p_platform text, p_store uuid, p_axis text, p_unit text, p_at timestamp with time zone) RETURNS jsonb
    LANGUAGE plpgsql STABLE
    SET search_path TO 'pg_catalog', 'ops', 'core', 'pg_temp'
    SET "TimeZone" TO 'UTC'
    AS $_$
DECLARE
 engagement ops.lc_promotion_engagement%ROWTYPE;
 axis text; members uuid[]; unresolved boolean:=false; occupied numeric:=0; snapshot jsonb; member jsonb;
 demand jsonb; live integer;
BEGIN
 axis:=p_axis;
 IF axis IN ('CONCURRENT_LISTINGS','AFFECTED_VARIANTS') THEN
   WITH outstanding AS (
     SELECT a.platform_listing_id,a.affected_set_id FROM ops.lc_exposure_occupation o
     JOIN ops.lc_action a ON a.id=o.action_id JOIN core.platform_listing l ON l.id=a.platform_listing_id
     WHERE o.organization_id=p_org AND o.axis_code=axis AND o.state<>'RELEASED'
       AND (p_scope_kind='ORGANIZATION'
         OR (p_scope_kind='PLATFORM' AND p_platform=l.platform_code)
         OR (p_scope_kind='STORE' AND p_store=a.store_id)))
   SELECT CASE WHEN axis='CONCURRENT_LISTINGS' THEN
      (SELECT array_agg(DISTINCT platform_listing_id) FROM outstanding)
    ELSE (SELECT array_agg(DISTINCT value) FROM outstanding o JOIN core.lc_affected_set s ON s.id=o.affected_set_id,
      unnest(s.platform_listing_variant_ids) value) END,
    EXISTS(SELECT 1 FROM outstanding o LEFT JOIN core.lc_affected_set s ON s.id=o.affected_set_id
      WHERE axis='AFFECTED_VARIANTS' AND (s.id IS NULL OR s.resolution_state<>'COMPLETE'
        OR s.identity_lineage IS NULL OR coalesce(cardinality(s.platform_listing_variant_ids),0)=0))
    INTO members,unresolved;
   FOR engagement IN SELECT e.* FROM ops.lc_promotion_engagement e JOIN core.platform_listing l
      ON l.id=e.platform_listing_id AND l.organization_id=e.organization_id
     WHERE e.organization_id=p_org AND e.state IN ('ACTIVE','EXITING')
       AND NOT EXISTS(SELECT 1 FROM ops.lc_exposure_occupation o
         WHERE o.action_id=e.action_id AND o.axis_code=axis AND o.state<>'RELEASED')
       AND (p_scope_kind='ORGANIZATION'
         OR (p_scope_kind='PLATFORM' AND p_platform=l.platform_code)
         OR (p_scope_kind='STORE' AND p_store=e.store_id)) LOOP
     IF axis='CONCURRENT_LISTINGS' THEN members:=array_append(coalesce(members,'{}'),engagement.platform_listing_id);
     ELSE
       snapshot:=core.lc_listing_identity_snapshot(engagement.platform_listing_id,p_at);
       IF snapshot#>>'{nativeScope,state}'<>'COMPLETE' THEN unresolved:=true;
       ELSE
         FOR member IN SELECT value FROM jsonb_array_elements(snapshot->'members') LOOP
           members:=array_append(coalesce(members,'{}'),(member->>'listingVariantId')::uuid);
         END LOOP;
       END IF;
     END IF;
   END LOOP;
   SELECT array_agg(DISTINCT value ORDER BY value) INTO members FROM unnest(coalesce(members,'{}')) value;
   occupied:=coalesce(cardinality(members),0);
 ELSE
   SELECT coalesce(sum(o.occupied_value),0),coalesce(bool_or(o.state='UNKNOWN'
       AND (o.demand_evidence IS NULL OR o.occupied_value<=0)),false)
     INTO occupied,unresolved
   FROM ops.lc_exposure_occupation o JOIN ops.lc_action occupied_action ON occupied_action.id=o.action_id
   JOIN core.platform_listing l ON l.id=occupied_action.platform_listing_id
   WHERE o.organization_id=p_org AND o.axis_code=axis AND o.state<>'RELEASED'
     AND (p_scope_kind='ORGANIZATION'
       OR (p_scope_kind='PLATFORM' AND p_platform=l.platform_code)
       OR (p_scope_kind='STORE' AND p_store=occupied_action.store_id));
   FOR engagement IN SELECT e.* FROM ops.lc_promotion_engagement e JOIN core.platform_listing l
      ON l.id=e.platform_listing_id AND l.organization_id=e.organization_id
     WHERE e.organization_id=p_org
       AND ((axis='REVENUE_EXPOSURE' AND e.state<>'CLEARED')
         OR (axis='CATEGORY_SHARE' AND e.state IN ('ACTIVE','EXITING')))
       AND NOT EXISTS(SELECT 1 FROM ops.lc_exposure_occupation o
         WHERE o.action_id=e.action_id AND o.axis_code=axis AND o.state<>'RELEASED')
       AND (p_scope_kind='ORGANIZATION'
         OR (p_scope_kind='PLATFORM' AND p_platform=l.platform_code)
         OR (p_scope_kind='STORE' AND p_store=e.store_id)) LOOP
     demand:=engagement.axis_demands->axis;
     IF jsonb_typeof(demand) IS DISTINCT FROM 'object' OR demand->>'unitCode' IS DISTINCT FROM p_unit
        OR coalesce(demand->>'value','') !~ '^[0-9]+([.][0-9]{1,4})?$' THEN unresolved:=true;
     ELSE occupied:=occupied+(demand->>'value')::numeric; END IF;
   END LOOP;
 END IF;
 -- How many live occupation rows fall inside this scope, for the operator's orientation only.
 SELECT count(*) INTO live FROM ops.lc_exposure_occupation o JOIN ops.lc_action a ON a.id=o.action_id
   JOIN core.platform_listing l ON l.id=a.platform_listing_id
  WHERE o.organization_id=p_org AND o.axis_code=axis AND o.state<>'RELEASED'
    AND (p_scope_kind='ORGANIZATION'
      OR (p_scope_kind='PLATFORM' AND p_platform=l.platform_code)
      OR (p_scope_kind='STORE' AND p_store=a.store_id));
 RETURN jsonb_build_object('scopeKind',p_scope_kind,'platformCode',p_platform,'storeId',p_store,'axisCode',axis,
   'unitCode',p_unit,'occupiedValue',occupied,'unresolved',coalesce(unresolved,false),'liveOccupations',live,
   'evaluatedAt',p_at);
END $_$;

-- What one allowance row has occupied now: the occupancy of its scope and axis, and
-- the headroom its limit and reserve leave.
CREATE FUNCTION ops.lc_allowance_occupancy(p_allowance uuid, p_at timestamp with time zone) RETURNS jsonb
    LANGUAGE plpgsql STABLE
    SET search_path TO 'pg_catalog', 'ops', 'core', 'pg_temp'
    SET "TimeZone" TO 'UTC'
    AS $_$
DECLARE allowance ops.lc_exposure_allowance%ROWTYPE; occupancy jsonb;
BEGIN
 SELECT * INTO allowance FROM ops.lc_exposure_allowance WHERE id=p_allowance;
 IF NOT FOUND THEN RAISE EXCEPTION 'allowance does not exist' USING ERRCODE='MO036'; END IF;
 occupancy:=ops.lc_scope_occupancy(allowance.organization_id,allowance.scope_kind,allowance.platform_code,
   allowance.store_ref_id,allowance.axis_code,allowance.unit_code,p_at);
 RETURN jsonb_build_object('allowanceId',allowance.id,'axisCode',allowance.axis_code,
   'occupiedValue',occupancy->'occupiedValue','unresolved',occupancy->'unresolved',
   'headroom',allowance.limit_value-allowance.reserve_value-(occupancy->>'occupiedValue')::numeric,
   'liveOccupations',occupancy->'liveOccupations','evaluatedAt',p_at);
END $_$;

-- ---------------------------------------------------------------- publish

-- Publish a new allowance version for one organization, scope and axis.
--
-- The actor and organization come from the consumed invocation proof, never from
-- a parameter. The unit is derived from the axis (COUNT, RATIO, or the single store
-- currency of the scope for REVENUE_EXPOSURE). The currently effective row of the
-- same scope and axis ends where the new one starts; a row that was scheduled to
-- start at or after that instant is retired. Both steps and the insert happen under
-- the organization's exposure lock and an exclusive allowance table lock, so no
-- launch evaluates a half-published configuration and the no-overlap exclusion holds.
CREATE FUNCTION ops.publish_lc_exposure_allowance(p_id uuid, p_proof text, p_scope_kind text, p_platform text, p_store uuid, p_axis text, p_limit numeric, p_reserve numeric, p_effective_from timestamp with time zone, p_evidence text, p_reason text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE
 g iam.ad_invocation_grant%ROWTYPE; now_at timestamptz; starts timestamptz; unit text; currencies text[];
 unpriced boolean; scope text; next_version integer; previous uuid; ended uuid[]; replaced uuid[];
BEGIN
 g:=ops.consume_ad_control_invocation(p_proof,'LISTING_ALLOWANCE_PUBLISH',p_id,p_id);
 IF p_scope_kind IS NULL OR p_scope_kind NOT IN ('ORGANIZATION','PLATFORM','STORE')
    OR (p_scope_kind='ORGANIZATION' AND (p_platform IS NOT NULL OR p_store IS NOT NULL))
    OR (p_scope_kind='PLATFORM' AND (p_platform IS NULL OR p_store IS NOT NULL))
    OR (p_scope_kind='STORE' AND (p_platform IS NOT NULL OR p_store IS NULL)) THEN
  RAISE EXCEPTION 'an allowance names one exact scope' USING ERRCODE='MO036';
 END IF;
 IF p_scope_kind='PLATFORM' AND NOT EXISTS(SELECT 1 FROM core.marketplace_platform WHERE code=p_platform) THEN
  RAISE EXCEPTION 'unknown platform' USING ERRCODE='MO036';
 END IF;
 IF p_scope_kind='STORE' AND NOT EXISTS(SELECT 1 FROM core.store s
     WHERE s.id=p_store AND s.organization_id=g.organization_id AND s.status<>'RETIRED') THEN
  RAISE EXCEPTION 'the store is outside the actor organization or retired' USING ERRCODE='MO092';
 END IF;
 IF NOT ops.lc_calibration_actor_scope(g.actor_user_id,g.organization_id,p_scope_kind,p_store,'LISTING_CALIBRATION_ACCEPT') THEN
  RAISE EXCEPTION 'a current Owner publishes launch allowances' USING ERRCODE='MO092';
 END IF;
 IF p_axis IS NULL OR p_axis NOT IN ('CONCURRENT_LISTINGS','AFFECTED_VARIANTS','REVENUE_EXPOSURE','CATEGORY_SHARE') THEN
  RAISE EXCEPTION 'unknown allowance axis' USING ERRCODE='MO036';
 END IF;
 -- numeric(18,4) would round silently, so an over-precise value is refused instead.
 IF p_limit IS NULL OR p_reserve IS NULL OR p_limit<=0 OR p_reserve<0 OR p_reserve>=p_limit
    OR p_limit<>round(p_limit,4) OR p_reserve<>round(p_reserve,4) OR p_limit>=100000000000000 THEN
  RAISE EXCEPTION 'an allowance needs 0 <= reserve < limit with at most four decimals' USING ERRCODE='MO036';
 END IF;
 IF p_evidence IS NULL OR length(btrim(p_evidence)) NOT BETWEEN 1 AND 512
    OR p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 1 AND 512 THEN
  RAISE EXCEPTION 'an allowance needs an evidence reference and a reason' USING ERRCODE='MO036';
 END IF;
 IF p_axis IN ('CONCURRENT_LISTINGS','AFFECTED_VARIANTS') THEN
  IF p_limit<>trunc(p_limit) OR p_reserve<>trunc(p_reserve) THEN
   RAISE EXCEPTION 'a count allowance is a whole number' USING ERRCODE='MO036';
  END IF;
  unit:='COUNT';
 ELSIF p_axis='CATEGORY_SHARE' THEN
  unit:='RATIO';
 ELSE
  SELECT array_agg(DISTINCT s.currency_code) FILTER (WHERE s.currency_code IS NOT NULL),
         bool_or(s.currency_code IS NULL)
    INTO currencies,unpriced
    FROM core.store s JOIN core.marketplace_account a ON a.id=s.marketplace_account_id
   WHERE s.organization_id=g.organization_id AND s.status<>'RETIRED'
     AND (p_scope_kind='ORGANIZATION' OR (p_scope_kind='PLATFORM' AND a.platform_code=p_platform)
       OR (p_scope_kind='STORE' AND s.id=p_store));
  IF coalesce(cardinality(currencies),0)<>1 OR coalesce(unpriced,false) THEN
   RAISE EXCEPTION 'revenue exposure needs exactly one store currency in its scope' USING ERRCODE='MO036';
  END IF;
  unit:=currencies[1];
 END IF;

 -- Same order as ops.acquire_lc_launch_allowance: organization lock, then the table.
 -- SHARE ROW EXCLUSIVE conflicts with the launch's SHARE lock and with itself.
 PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(g.organization_id::text));
 LOCK TABLE ops.lc_exposure_allowance IN SHARE ROW EXCLUSIVE MODE;
 now_at:=clock_timestamp();
 starts:=coalesce(p_effective_from,now_at);
 IF starts<now_at THEN
  -- A few minutes of clock difference with the browser mean "now"; anything older
  -- would rewrite the range a past launch was evaluated against.
  IF starts<now_at-interval '5 minutes' THEN
   RAISE EXCEPTION 'an allowance starts now or later' USING ERRCODE='MO036';
  END IF;
  starts:=now_at;
 END IF;
 scope:=p_scope_kind||':'||coalesce(p_platform,'')||':'||coalesce(p_store::text,'');

 SELECT coalesce(max(a.allowance_version),0)+1 INTO next_version FROM ops.lc_exposure_allowance a
  WHERE a.organization_id=g.organization_id AND a.scope_key=scope AND a.axis_code=p_axis;
 SELECT a.id INTO previous FROM ops.lc_exposure_allowance a
  WHERE a.organization_id=g.organization_id AND a.scope_key=scope AND a.axis_code=p_axis AND a.status='ACTIVE'
    AND a.effective_from<=starts AND (a.effective_to IS NULL OR a.effective_to>starts)
  ORDER BY a.effective_from DESC LIMIT 1;

 WITH r AS (
  UPDATE ops.lc_exposure_allowance a SET status='RETIRED',retired_at=now_at,retired_by_user_id=g.actor_user_id,
     retire_reason=btrim(p_reason)
   WHERE a.organization_id=g.organization_id AND a.scope_key=scope AND a.axis_code=p_axis AND a.status='ACTIVE'
     AND a.effective_from>=starts
  RETURNING a.id)
 SELECT coalesce(array_agg(r.id ORDER BY r.id),'{}') INTO replaced FROM r;

 WITH e AS (
  UPDATE ops.lc_exposure_allowance a SET effective_to=starts
   WHERE a.organization_id=g.organization_id AND a.scope_key=scope AND a.axis_code=p_axis AND a.status='ACTIVE'
     AND a.effective_from<starts AND (a.effective_to IS NULL OR a.effective_to>starts)
  RETURNING a.id)
 SELECT coalesce(array_agg(e.id ORDER BY e.id),'{}') INTO ended FROM e;

 INSERT INTO ops.lc_exposure_allowance(id,organization_id,allowance_version,scope_kind,platform_code,store_ref_id,
   axis_code,limit_value,reserve_value,unit_code,published_by_user_id,published_at,evidence_reference,
   effective_from,effective_to,status,publish_reason,supersedes_allowance_id)
 VALUES(p_id,g.organization_id,next_version,p_scope_kind,p_platform,p_store,p_axis,p_limit,p_reserve,unit,
   g.actor_user_id,now_at,btrim(p_evidence),starts,NULL,'ACTIVE',btrim(p_reason),coalesce(previous,replaced[1]));

 RETURN jsonb_build_object('allowanceId',p_id,'allowanceVersion',next_version,'unitCode',unit,
   'effectiveFrom',starts,'endedAllowanceIds',to_jsonb(ended),'retiredAllowanceIds',to_jsonb(replaced));
END $$;

-- ---------------------------------------------------------------- retire

-- Retire one active allowance with a reason.
--
-- A current row ends now: from that instant launches in its scope see
-- ALLOWANCE_MISSING on its axis (unless another scope row applies). A scheduled row
-- is cancelled before it starts, and the active row of its scope and axis that ends
-- exactly where it starts (the one publish ended there for it) takes over its range,
-- so the version before it continues instead of leaving a gap. That row is found by
-- adjacency, not by supersedes_allowance_id: after an earlier cancellation handed a
-- range back, the row ending there is no longer the one the cancelled row superseded.
-- The no-overlap exclusion allows at most one such row.
-- Live occupations are untouched and stay counted by whatever row replaces it.
CREATE FUNCTION ops.retire_lc_exposure_allowance(p_id uuid, p_proof text, p_reason text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE g iam.ad_invocation_grant%ROWTYPE; a ops.lc_exposure_allowance%ROWTYPE; now_at timestamptz;
 restored uuid;
BEGIN
 g:=ops.consume_ad_control_invocation(p_proof,'LISTING_ALLOWANCE_RETIRE',p_id,p_id);
 IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 1 AND 512 THEN
  RAISE EXCEPTION 'retiring an allowance needs a reason' USING ERRCODE='MO036';
 END IF;
 SELECT * INTO a FROM ops.lc_exposure_allowance WHERE id=p_id;
 IF NOT FOUND OR a.organization_id<>g.organization_id THEN
  RAISE EXCEPTION 'the allowance is outside the actor organization' USING ERRCODE='MO092';
 END IF;
 IF NOT ops.lc_calibration_actor_scope(g.actor_user_id,a.organization_id,a.scope_kind,a.store_ref_id,'LISTING_CALIBRATION_ACCEPT') THEN
  RAISE EXCEPTION 'a current Owner retires launch allowances' USING ERRCODE='MO092';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(a.organization_id::text));
 LOCK TABLE ops.lc_exposure_allowance IN SHARE ROW EXCLUSIVE MODE;
 now_at:=clock_timestamp();
 SELECT * INTO a FROM ops.lc_exposure_allowance WHERE id=p_id;
 IF a.status<>'ACTIVE' OR (a.effective_to IS NOT NULL AND a.effective_to<=now_at) THEN
  RAISE EXCEPTION 'only a current or scheduled allowance is retired' USING ERRCODE='MO091';
 END IF;
 UPDATE ops.lc_exposure_allowance SET status='RETIRED',retired_at=now_at,retired_by_user_id=g.actor_user_id,
   retire_reason=btrim(p_reason),
   effective_to=CASE WHEN a.effective_from<now_at THEN now_at ELSE a.effective_to END
  WHERE id=p_id;
 -- Only once the cancelled row has left the ACTIVE set can its predecessor take the
 -- range back without tripping lc_exposure_allowance_no_overlap.
 IF a.effective_from>=now_at THEN
  UPDATE ops.lc_exposure_allowance p SET effective_to=a.effective_to
   WHERE p.organization_id=a.organization_id AND p.scope_key=a.scope_key AND p.axis_code=a.axis_code
     AND p.status='ACTIVE' AND p.effective_to=a.effective_from
  RETURNING p.id INTO restored;
 END IF;
 RETURN jsonb_build_object('allowanceId',p_id,'retiredAt',now_at,'restoredAllowanceId',restored,
   'restoredEffectiveToBefore',CASE WHEN restored IS NOT NULL THEN a.effective_from END,
   'restoredEffectiveTo',CASE WHEN restored IS NOT NULL THEN a.effective_to END);
END $$;

-- ---------------------------------------------------------------- catalogue and privileges

UPDATE platform.control_route_inventory
   SET routing_note='owner-published cumulative exposure bound per axis with disposal reserve; written only by ops.publish_lc_exposure_allowance and ops.retire_lc_exposure_allowance'
 WHERE schema_name='ops' AND table_name='lc_exposure_allowance';

COMMENT ON FUNCTION ops.publish_lc_exposure_allowance(uuid, text, text, text, uuid, text, numeric, numeric, timestamp with time zone, text, text)
    IS 'Owner publishes a new launch allowance version; the only writer of ops.lc_exposure_allowance with ops.retire_lc_exposure_allowance.';

REVOKE ALL ON FUNCTION ops.lc_scope_occupancy(p_org uuid, p_scope_kind text, p_platform text, p_store uuid, p_axis text, p_unit text, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_scope_occupancy(p_org uuid, p_scope_kind text, p_platform text, p_store uuid, p_axis text, p_unit text, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.lc_allowance_occupancy(p_allowance uuid, p_at timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.lc_allowance_occupancy(p_allowance uuid, p_at timestamp with time zone) TO marketops_app;

REVOKE ALL ON FUNCTION ops.publish_lc_exposure_allowance(p_id uuid, p_proof text, p_scope_kind text, p_platform text, p_store uuid, p_axis text, p_limit numeric, p_reserve numeric, p_effective_from timestamp with time zone, p_evidence text, p_reason text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.publish_lc_exposure_allowance(p_id uuid, p_proof text, p_scope_kind text, p_platform text, p_store uuid, p_axis text, p_limit numeric, p_reserve numeric, p_effective_from timestamp with time zone, p_evidence text, p_reason text) TO marketops_app;

REVOKE ALL ON FUNCTION ops.retire_lc_exposure_allowance(p_id uuid, p_proof text, p_reason text) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.retire_lc_exposure_allowance(p_id uuid, p_proof text, p_reason text) TO marketops_app;
