-- Frozen roots 012/013/014/016/017/018.
-- This migration only closes the existing local promotion/manual/allowance/containment
-- lifecycle.  It creates no provider route and enables no external write.

-- ---------------------------------------------------------------------------
-- One independently observed, finite promotion context for an exact period.
-- Historical V0100 observations remain single-activity facts and are never
-- upgraded to complete inventory evidence.
-- ---------------------------------------------------------------------------

ALTER TABLE core.lc_promotion_observation
  ADD COLUMN context_coverage text NOT NULL DEFAULT 'SINGLE_ACTIVITY_ONLY',
  ADD COLUMN coverage_from timestamptz,
  ADD COLUMN coverage_until timestamptz,
  ADD COLUMN verification_expires_at timestamptz,
  ADD COLUMN context_snapshot jsonb,
  ADD COLUMN context_digest text;

ALTER TABLE core.lc_promotion_observation ADD CONSTRAINT lc_promotion_observation_context_coverage_ck
 CHECK(context_coverage IN ('SINGLE_ACTIVITY_ONLY','COMPLETE_ENUMERATION'));
ALTER TABLE core.lc_promotion_observation ADD CONSTRAINT lc_promotion_observation_context_shape_ck CHECK(
 (context_coverage='SINGLE_ACTIVITY_ONLY' AND num_nonnulls(coverage_from,coverage_until,
    verification_expires_at,context_snapshot,context_digest)=0)
 OR (context_coverage='COMPLETE_ENUMERATION' AND coverage_from IS NOT NULL AND coverage_until IS NOT NULL
    AND verification_expires_at IS NOT NULL AND context_snapshot IS NOT NULL AND context_digest IS NOT NULL
    AND coverage_from<coverage_until AND verification_expires_at>acquired_at
    AND jsonb_typeof(context_snapshot)='array' AND jsonb_array_length(context_snapshot)<=64
    AND context_digest ~ '^[0-9a-f]{64}$'));

CREATE FUNCTION core.lc_validate_promotion_context_observation() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,core,ops,iam,pg_temp
SET timezone='UTC' SET DateStyle='ISO, YMD' AS $$
DECLARE context_item jsonb; demand record; target_found boolean:=false; identity_count integer;
BEGIN
 IF NEW.context_coverage='SINGLE_ACTIVITY_ONLY' THEN RETURN NEW; END IF;
 FOR context_item IN SELECT value FROM jsonb_array_elements(NEW.context_snapshot) LOOP
   IF jsonb_typeof(context_item)<>'object' OR (SELECT count(*) FROM jsonb_object_keys(context_item))<>9
      OR EXISTS(SELECT 1 FROM jsonb_object_keys(context_item) key WHERE key NOT IN
        ('declaration','participationState','effectiveFrom','effectiveTo','newTransactionsState',
         'residualObligationState','originalAuthorityReference','originalAuthorityValidUntil','axisDemands'))
      OR ops.lc_promotion_terms_digest(context_item->'declaration') IS NULL
      OR context_item->>'participationState' NOT IN ('PARTICIPATING','NOT_PARTICIPATING','UNKNOWN')
      OR context_item->>'newTransactionsState' NOT IN ('OPEN','STOPPED','UNKNOWN')
      OR context_item->>'residualObligationState' NOT IN ('OUTSTANDING','CLEARED','UNKNOWN')
      OR coalesce(context_item->>'effectiveFrom','') !~ '^[-+0-9T:. Z]+$'
      OR coalesce(context_item->>'effectiveTo','') !~ '^[-+0-9T:. Z]+$'
      OR (context_item->>'effectiveFrom')::timestamptz >= (context_item->>'effectiveTo')::timestamptz
      OR (context_item->>'participationState'='PARTICIPATING' AND context_item->>'newTransactionsState'<>'OPEN')
      OR (context_item->>'participationState'='NOT_PARTICIPATING' AND context_item->>'newTransactionsState'='OPEN')
      OR (context_item->>'participationState'='UNKNOWN' AND
          (context_item->>'newTransactionsState'<>'UNKNOWN' OR context_item->>'residualObligationState'<>'UNKNOWN'))
      OR (context_item->>'participationState'='PARTICIPATING' AND
          (jsonb_typeof(context_item->'originalAuthorityReference') IS DISTINCT FROM 'string'
           OR length(btrim(coalesce(context_item->>'originalAuthorityReference',''))) NOT BETWEEN 1 AND 512
           OR jsonb_typeof(context_item->'originalAuthorityValidUntil') IS DISTINCT FROM 'string'
           OR coalesce(context_item->>'originalAuthorityValidUntil','') !~ '^[-+0-9T:. Z]+$'
           OR (context_item->>'originalAuthorityValidUntil')::timestamptz<=NEW.acquired_at))
      OR (context_item->>'participationState'<>'PARTICIPATING' AND
          (jsonb_typeof(context_item->'originalAuthorityReference') IS DISTINCT FROM 'null'
           OR jsonb_typeof(context_item->'originalAuthorityValidUntil') IS DISTINCT FROM 'null'))
      OR jsonb_typeof(context_item->'axisDemands') IS DISTINCT FROM 'object'
      OR (SELECT count(*) FROM jsonb_object_keys(context_item->'axisDemands'))>4
      OR EXISTS(SELECT 1 FROM jsonb_object_keys(context_item->'axisDemands') axis
          WHERE axis NOT IN ('CONCURRENT_LISTINGS','AFFECTED_VARIANTS','REVENUE_EXPOSURE','CATEGORY_SHARE')) THEN
     RAISE EXCEPTION 'complete promotion context requires bounded exact activity records' USING ERRCODE='MO092';
   END IF;
   FOR demand IN SELECT key,value FROM jsonb_each(context_item->'axisDemands') LOOP
     IF jsonb_typeof(demand.value)<>'object' OR (SELECT count(*) FROM jsonb_object_keys(demand.value))<>3
        OR EXISTS(SELECT 1 FROM jsonb_object_keys(demand.value) key
             WHERE key NOT IN ('value','unitCode','evidenceReference'))
        OR coalesce(demand.value->>'value','') !~ '^[0-9]+([.][0-9]{1,4})?$'
        OR length(demand.value->>'value')>19
        OR length(btrim(coalesce(demand.value->>'unitCode',''))) NOT BETWEEN 1 AND 32
        OR length(btrim(coalesce(demand.value->>'evidenceReference',''))) NOT BETWEEN 1 AND 512 THEN
       RAISE EXCEPTION 'promotion axis demand requires an exact value, unit and evidence reference' USING ERRCODE='MO092';
     END IF;
   END LOOP;
   IF context_item#>>'{declaration,engagementKind}'=NEW.engagement_kind
      AND context_item#>>'{declaration,nativePromotionKey}'=NEW.native_promotion_key THEN
     target_found:=true;
     IF context_item->>'participationState'<>NEW.participation_state OR NEW.declaration IS NULL
        OR context_item->'declaration' IS DISTINCT FROM NEW.declaration THEN
       RAISE EXCEPTION 'promotion context target contradicts the observed activity fact' USING ERRCODE='MO093';
     END IF;
   END IF;
 END LOOP;
 SELECT count(*) INTO identity_count FROM (
   SELECT context_entry#>>'{declaration,engagementKind}',context_entry#>>'{declaration,nativePromotionKey}'
   FROM jsonb_array_elements(NEW.context_snapshot) context_entry GROUP BY 1,2) identities;
 IF identity_count<>jsonb_array_length(NEW.context_snapshot) OR NOT target_found THEN
   RAISE EXCEPTION 'promotion context identities must be unique and include the observed target' USING ERRCODE='MO092';
 END IF;
 NEW.context_digest:=encode(sha256(convert_to(jsonb_build_object(
   'organizationId',NEW.organization_id,'listingId',NEW.platform_listing_id,
   'observationId',NEW.id,'observedAt',NEW.observed_at,'acquiredAt',NEW.acquired_at,
   'coverageFrom',NEW.coverage_from,'coverageUntil',NEW.coverage_until,
   'verificationExpiresAt',NEW.verification_expires_at,'records',NEW.context_snapshot)::text,'UTF8')),'hex');
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION core.lc_validate_promotion_context_observation() FROM PUBLIC;
CREATE TRIGGER lc_promotion_context_observation_valid BEFORE INSERT ON core.lc_promotion_observation
 FOR EACH ROW EXECUTE FUNCTION core.lc_validate_promotion_context_observation();

CREATE INDEX lc_promotion_context_current_ix ON core.lc_promotion_observation
 (platform_listing_id,observed_at DESC,acquired_at DESC) WHERE context_coverage='COMPLETE_ENUMERATION';

CREATE FUNCTION ops.lc_promotion_observation_is_independent_current(p_observation uuid,p_at timestamptz)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT EXISTS(
   SELECT 1 FROM core.lc_promotion_observation o
   JOIN core.fact_provenance p ON p.id=o.provenance_id AND p.organization_id=o.organization_id
   JOIN core.platform_listing l ON l.id=o.platform_listing_id AND l.organization_id=o.organization_id
   WHERE o.id=p_observation AND o.context_coverage='COMPLETE_ENUMERATION'
     AND o.observed_at<=p_at AND o.acquired_at<=p_at AND o.verification_expires_at>p_at
     AND p.source_time=o.observed_at AND p.ingestion_time=o.acquired_at
     AND ((p.source_kind='MARKETPLACE_RAW' AND p.raw_observation_id IS NOT NULL)
       OR (p.source_kind='MANUAL_ENTRY' AND p.recorded_by_user_id IS NOT NULL
         AND ops.lc_actor_holds_action(p.recorded_by_user_id,o.organization_id,l.store_id,'LISTING_MANUAL_VERIFY'))))
$$;
REVOKE ALL ON FUNCTION ops.lc_promotion_observation_is_independent_current(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_promotion_observation_is_independent_current(uuid,timestamptz) TO marketops_app;

-- The digest excludes the call clock.  It changes only with the requested period
-- or retained facts, so a prepared simulation can safely bind it.
CREATE FUNCTION ops.lc_current_promotion_context(
 p_org uuid,p_listing uuid,p_period_start timestamptz,p_period_end timestamptz,p_as_of timestamptz)
RETURNS jsonb LANGUAGE plpgsql STABLE SET search_path=pg_catalog
SET timezone='UTC' SET DateStyle='ISO, YMD' AS $$
DECLARE latest core.lc_promotion_observation%ROWTYPE; known jsonb; records jsonb:='[]';
 gaps text[]:='{}'; coverage text; material jsonb; local_conflict boolean:=false;
BEGIN
 known:=ops.lc_known_promotion_context(p_org,p_listing);
 IF p_period_start IS NULL OR p_period_end IS NULL OR p_as_of IS NULL
    OR p_period_start>=p_period_end OR p_as_of>statement_timestamp()
    OR NOT EXISTS(SELECT 1 FROM core.platform_listing l WHERE l.id=p_listing AND l.organization_id=p_org) THEN
   material:=jsonb_build_object('coverage','UNQUALIFIED','listingId',p_listing,
     'periodStart',p_period_start,'periodEnd',p_period_end,'records',coalesce(known->'records','[]'::jsonb),
     'gaps',jsonb_build_array('INVALID_CONTEXT_QUERY'));
   RETURN material||jsonb_build_object('digest',encode(sha256(convert_to(material::text,'UTF8')),'hex'));
 END IF;
 SELECT * INTO latest FROM core.lc_promotion_observation o
  WHERE o.organization_id=p_org AND o.platform_listing_id=p_listing
    AND o.observed_at<=p_as_of AND o.acquired_at<=p_as_of
  ORDER BY o.observed_at DESC,o.acquired_at DESC,o.id DESC LIMIT 1;
 IF latest.id IS NULL OR latest.context_coverage<>'COMPLETE_ENUMERATION' THEN
   material:=jsonb_build_object('coverage','KNOWN_RECORDS_ONLY','listingId',p_listing,
     'periodStart',p_period_start,'periodEnd',p_period_end,'records',coalesce(known->'records','[]'::jsonb),
     'gaps',jsonb_build_array('COMPLETE_ENUMERATION_MISSING'),'knownRecordsDigest',known->>'digest');
   RETURN material||jsonb_build_object('digest',encode(sha256(convert_to(material::text,'UTF8')),'hex'));
 END IF;
 IF latest.coverage_from>p_period_start OR latest.coverage_until<p_period_end THEN
   gaps:=array_append(gaps,'ENUMERATION_PERIOD_UNCOVERED');
 END IF;
 IF NOT ops.lc_promotion_observation_is_independent_current(latest.id,p_as_of) THEN
   gaps:=array_append(gaps,'ENUMERATION_NOT_CURRENT_OR_INDEPENDENT');
 END IF;
 SELECT EXISTS(
   SELECT 1 FROM ops.lc_promotion_engagement e
   WHERE e.organization_id=p_org AND e.platform_listing_id=p_listing AND e.state<>'CLEARED'
     AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(latest.context_snapshot) item
       WHERE item#>>'{declaration,engagementKind}'=e.engagement_kind
         AND item#>>'{declaration,nativePromotionKey}'=e.native_promotion_key
         AND ops.lc_promotion_terms_digest(item->'declaration')=ops.lc_promotion_terms_digest(jsonb_build_object(
           'engagementKind',e.engagement_kind,'nativePromotionKey',e.native_promotion_key,'terms',e.terms,
           'priceFreeze',e.price_freeze,'autoParticipation',e.auto_participation,
           'termsEvidenceReference',e.terms_evidence_reference,'obligations',e.obligations))
         AND CASE e.state WHEN 'ACTIVE' THEN item->>'participationState'='PARTICIPATING'
              WHEN 'EXITING' THEN item->>'participationState'='PARTICIPATING'
              WHEN 'STOPPED' THEN item->>'newTransactionsState'='STOPPED'
              ELSE false END)) INTO local_conflict;
 IF local_conflict THEN gaps:=array_append(gaps,'KNOWN_RECORD_CONFLICT_OR_OMISSION'); END IF;
 SELECT coalesce(jsonb_agg(item||jsonb_build_object(
     'promotionTermsDigest',ops.lc_promotion_terms_digest(item->'declaration'),
     'commonApplicableFrom',CASE WHEN (item->>'effectiveTo')::timestamptz>p_period_start
       AND (item->>'effectiveFrom')::timestamptz<p_period_end
       THEN greatest((item->>'effectiveFrom')::timestamptz,p_period_start) END,
     'commonApplicableUntil',CASE WHEN (item->>'effectiveTo')::timestamptz>p_period_start
       AND (item->>'effectiveFrom')::timestamptz<p_period_end
       THEN least((item->>'effectiveTo')::timestamptz,p_period_end) END,
     'localEngagementId',local.id,'localEngagementState',local.state,
     'localEngagementCreatedAt',local.created_at,'localExitAuthorizedAt',local.exit_authorized_at,
     'localNewTransactionsStoppedAt',local.new_transactions_stopped_at,
     'localObligationsClearedAt',local.obligations_cleared_at)
     ORDER BY item#>>'{declaration,engagementKind}',item#>>'{declaration,nativePromotionKey}'),'[]'::jsonb)
   INTO records
 FROM jsonb_array_elements(latest.context_snapshot) item
 LEFT JOIN LATERAL (SELECT e.id,e.state,e.created_at,e.exit_authorized_at,
      e.new_transactions_stopped_at,e.obligations_cleared_at FROM ops.lc_promotion_engagement e
   WHERE e.organization_id=p_org AND e.platform_listing_id=p_listing
     AND e.engagement_kind=item#>>'{declaration,engagementKind}'
     AND e.native_promotion_key=item#>>'{declaration,nativePromotionKey}'
   ORDER BY e.created_at DESC,e.id DESC LIMIT 1) local ON true;
 coverage:=CASE WHEN cardinality(gaps)=0 THEN 'QUALIFIED_COMPLETE' ELSE 'UNQUALIFIED' END;
 material:=jsonb_build_object('coverage',coverage,'organizationId',p_org,'listingId',p_listing,
   'periodStart',p_period_start,'periodEnd',p_period_end,'observationId',latest.id,
   'observedAt',latest.observed_at,'acquiredAt',latest.acquired_at,
   'coverageFrom',latest.coverage_from,'coverageUntil',latest.coverage_until,
   'verificationExpiresAt',latest.verification_expires_at,'contextDigest',latest.context_digest,
   'records',records,'gaps',to_jsonb(gaps),'knownRecordsDigest',known->>'digest');
 RETURN material||jsonb_build_object('digest',encode(sha256(convert_to(material::text,'UTF8')),'hex'));
END $$;
REVOKE ALL ON FUNCTION ops.lc_current_promotion_context(uuid,uuid,timestamptz,timestamptz,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_current_promotion_context(uuid,uuid,timestamptz,timestamptz,timestamptz) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Existing engagements remain facts.  Adoption qualification is a separate
-- current stewardship result, and all exact terms stay immutable.
-- ---------------------------------------------------------------------------

ALTER TABLE ops.lc_promotion_engagement
  ADD COLUMN terms_digest text,
  ADD COLUMN source_context_observation_id uuid REFERENCES core.lc_promotion_observation(id),
  ADD COLUMN original_authority_reference text,
  ADD COLUMN original_authority_valid_until timestamptz,
  ADD COLUMN responsible_user_id uuid REFERENCES iam.user_account(id),
  ADD COLUMN adoption_qualification_state text NOT NULL DEFAULT 'HISTORICAL_UNQUALIFIED',
  ADD COLUMN axis_demands jsonb,
  ADD COLUMN exit_authority_reference text,
  ADD COLUMN exit_evidence_id uuid,
  ADD COLUMN exit_evidence jsonb,
  ADD COLUMN stop_evidence_observation_id uuid REFERENCES core.lc_promotion_observation(id),
  ADD COLUMN obligation_evidence_observation_id uuid REFERENCES core.lc_promotion_observation(id);

UPDATE ops.lc_promotion_engagement SET terms_digest=ops.lc_promotion_terms_digest(jsonb_build_object(
  'engagementKind',engagement_kind,'nativePromotionKey',native_promotion_key,'terms',terms,
  'priceFreeze',price_freeze,'autoParticipation',auto_participation,
  'termsEvidenceReference',terms_evidence_reference,'obligations',obligations))
 WHERE native_promotion_key IS NOT NULL
   AND (SELECT count(*) FROM jsonb_object_keys(terms))>0
   AND (SELECT count(*) FROM jsonb_object_keys(obligations))>0;
ALTER TABLE ops.lc_promotion_engagement ADD CONSTRAINT lc_promotion_engagement_terms_digest_ck
 CHECK(terms_digest IS NULL OR terms_digest=ops.lc_promotion_terms_digest(jsonb_build_object(
  'engagementKind',engagement_kind,'nativePromotionKey',native_promotion_key,'terms',terms,
  'priceFreeze',price_freeze,'autoParticipation',auto_participation,
  'termsEvidenceReference',terms_evidence_reference,'obligations',obligations)));

ALTER TABLE ops.lc_promotion_engagement ADD CONSTRAINT lc_promotion_engagement_adoption_qualification_ck
 CHECK(adoption_qualification_state IN ('HISTORICAL_UNQUALIFIED','FACT_RECORDED_UNQUALIFIED',
   'QUALIFIED_CURRENT_STEWARDSHIP','ACTION_AUTHORIZED'));
ALTER TABLE ops.lc_promotion_engagement ADD CONSTRAINT lc_promotion_engagement_adoption_material_ck CHECK(
 (NOT adopted AND adoption_qualification_state IN ('HISTORICAL_UNQUALIFIED','ACTION_AUTHORIZED')
    AND num_nonnulls(source_context_observation_id,original_authority_reference,
      original_authority_valid_until,responsible_user_id)=0)
 OR (adopted AND adoption_qualification_state IN ('HISTORICAL_UNQUALIFIED','FACT_RECORDED_UNQUALIFIED'))
 OR (adopted AND adoption_qualification_state='QUALIFIED_CURRENT_STEWARDSHIP'
    AND source_context_observation_id IS NOT NULL AND original_authority_reference IS NOT NULL
    AND original_authority_valid_until IS NOT NULL AND responsible_user_id IS NOT NULL AND axis_demands IS NOT NULL));
ALTER TABLE ops.lc_promotion_engagement ADD CONSTRAINT lc_promotion_engagement_release_evidence_shape_ck CHECK(
 (new_transactions_stopped_at IS NULL)=(stop_evidence_observation_id IS NULL)
 AND (obligations_cleared_at IS NULL)=(obligation_evidence_observation_id IS NULL));

CREATE FUNCTION ops.lc_qualify_promotion_engagement() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog SET timezone='UTC' SET DateStyle='ISO, YMD' AS $$
DECLARE observed core.lc_promotion_observation%ROWTYPE; provenance core.fact_provenance%ROWTYPE;
 item jsonb; context jsonb; now_at timestamptz:=statement_timestamp();
BEGIN
 NEW.terms_digest:=ops.lc_promotion_terms_digest(jsonb_build_object(
   'engagementKind',NEW.engagement_kind,'nativePromotionKey',NEW.native_promotion_key,'terms',NEW.terms,
   'priceFreeze',NEW.price_freeze,'autoParticipation',NEW.auto_participation,
   'termsEvidenceReference',NEW.terms_evidence_reference,'obligations',NEW.obligations));
 IF TG_OP='UPDATE' THEN
   IF ROW(NEW.organization_id,NEW.store_id,NEW.platform_listing_id,NEW.action_id,NEW.engagement_kind,
       NEW.native_promotion_key,NEW.terms,NEW.price_freeze,NEW.auto_participation,
       NEW.terms_evidence_reference,NEW.adopted,NEW.obligations,NEW.source_context_observation_id,
       NEW.original_authority_reference,NEW.original_authority_valid_until,NEW.responsible_user_id,
       NEW.adoption_qualification_state,NEW.axis_demands)
      IS DISTINCT FROM
      ROW(OLD.organization_id,OLD.store_id,OLD.platform_listing_id,OLD.action_id,OLD.engagement_kind,
       OLD.native_promotion_key,OLD.terms,OLD.price_freeze,OLD.auto_participation,
       OLD.terms_evidence_reference,OLD.adopted,OLD.obligations,OLD.source_context_observation_id,
       OLD.original_authority_reference,OLD.original_authority_valid_until,OLD.responsible_user_id,
       OLD.adoption_qualification_state,OLD.axis_demands) THEN
     RAISE EXCEPTION 'promotion identity, terms, authority and adopted responsibility are immutable' USING ERRCODE='MO092';
   END IF;
   RETURN NEW;
 END IF;
 IF NOT NEW.adopted THEN
   NEW.adoption_qualification_state:='ACTION_AUTHORIZED';
   NEW.source_context_observation_id:=NULL; NEW.original_authority_reference:=NULL;
   NEW.original_authority_valid_until:=NULL; NEW.responsible_user_id:=NULL; NEW.axis_demands:=NULL;
   RETURN NEW;
 END IF;
 NEW.adoption_qualification_state:='FACT_RECORDED_UNQUALIFIED';
 NEW.axis_demands:=NULL;
 IF NEW.source_context_observation_id IS NULL OR NEW.original_authority_reference IS NULL
    OR length(btrim(NEW.original_authority_reference)) NOT BETWEEN 1 AND 512
    OR NEW.original_authority_valid_until IS NULL OR NEW.original_authority_valid_until<=now_at
    OR NEW.responsible_user_id IS NULL
    OR NOT ops.lc_actor_holds_action(NEW.responsible_user_id,NEW.organization_id,NEW.store_id,'LISTING_PROMOTION_MANAGE') THEN
   RETURN NEW;
 END IF;
 SELECT * INTO observed FROM core.lc_promotion_observation o WHERE o.id=NEW.source_context_observation_id
   AND o.organization_id=NEW.organization_id AND o.platform_listing_id=NEW.platform_listing_id;
 IF observed.id IS NULL OR NOT ops.lc_promotion_observation_is_independent_current(observed.id,now_at) THEN RETURN NEW; END IF;
 SELECT * INTO provenance FROM core.fact_provenance p WHERE p.id=observed.provenance_id;
 IF provenance.source_kind='MANUAL_ENTRY' AND provenance.recorded_by_user_id=NEW.responsible_user_id THEN RETURN NEW; END IF;
 SELECT value INTO item FROM jsonb_array_elements(observed.context_snapshot)
  WHERE value#>>'{declaration,engagementKind}'=NEW.engagement_kind
    AND value#>>'{declaration,nativePromotionKey}'=NEW.native_promotion_key
    AND value->>'participationState'='PARTICIPATING'
    AND value->>'newTransactionsState'='OPEN'
    AND value->>'originalAuthorityReference'=NEW.original_authority_reference
    AND (value->>'originalAuthorityValidUntil')::timestamptz=NEW.original_authority_valid_until
    AND (value->>'originalAuthorityValidUntil')::timestamptz>now_at
    AND ops.lc_promotion_terms_digest(value->'declaration')=ops.lc_promotion_terms_digest(jsonb_build_object(
      'engagementKind',NEW.engagement_kind,'nativePromotionKey',NEW.native_promotion_key,'terms',NEW.terms,
      'priceFreeze',NEW.price_freeze,'autoParticipation',NEW.auto_participation,
      'termsEvidenceReference',NEW.terms_evidence_reference,'obligations',NEW.obligations));
 IF item IS NULL THEN RETURN NEW; END IF;
 context:=ops.lc_current_promotion_context(NEW.organization_id,NEW.platform_listing_id,
   (item->>'effectiveFrom')::timestamptz,(item->>'effectiveTo')::timestamptz,now_at);
 IF context->>'coverage'<>'QUALIFIED_COMPLETE' OR (context->>'observationId')::uuid<>observed.id THEN RETURN NEW; END IF;
 NEW.adoption_qualification_state:='QUALIFIED_CURRENT_STEWARDSHIP';
 NEW.axis_demands:=item->'axisDemands';
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_qualify_promotion_engagement() FROM PUBLIC;
CREATE TRIGGER lc_promotion_engagement_qualification BEFORE INSERT OR UPDATE ON ops.lc_promotion_engagement
 FOR EACH ROW EXECUTE FUNCTION ops.lc_qualify_promotion_engagement();

-- Replace the enum-only exit with exact frozen reason authority and a fact
-- that proves this reason applies.  The old application signature is closed.
REVOKE ALL ON FUNCTION ops.authorize_lc_promotion_exit(uuid,uuid,text,text) FROM marketops_app;
CREATE FUNCTION ops.authorize_lc_promotion_exit(
 p_engagement uuid,p_actor uuid,p_proof text,p_reason_code text,p_authority_reference text,p_evidence_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,core,iam,pg_temp AS $$
DECLARE engagement ops.lc_promotion_engagement%ROWTYPE; action ops.lc_action%ROWTYPE;
 grant_row iam.ad_invocation_grant%ROWTYPE; evidence jsonb; now_at timestamptz;
BEGIN
 SELECT * INTO engagement FROM ops.lc_promotion_engagement WHERE id=p_engagement FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'engagement does not exist' USING ERRCODE='MO090'; END IF;
 PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(engagement.organization_id::text));
 now_at:=clock_timestamp();
 IF engagement.state<>'ACTIVE' THEN RAISE EXCEPTION 'only an active engagement is exited' USING ERRCODE='MO091'; END IF;
 IF p_reason_code NOT IN ('MARGIN_BELOW_BOUND','RETURN_RATE_ABOVE_BOUND','SUPPLY_COVERAGE_LOST',
      'PLATFORM_TERMS_CHANGED','OWNER_DECISION')
    OR length(btrim(coalesce(p_authority_reference,''))) NOT BETWEEN 1 AND 512
    OR engagement.obligations->>('exit.'||p_reason_code) IS DISTINCT FROM p_authority_reference THEN
   RAISE EXCEPTION 'exact reason-bound prior exit authority is required' USING ERRCODE='MO092';
 END IF;
 IF engagement.action_id IS NOT NULL THEN SELECT * INTO STRICT action FROM ops.lc_action WHERE id=engagement.action_id; END IF;
 IF p_reason_code='PLATFORM_TERMS_CHANGED' THEN
   SELECT jsonb_build_object('kind','COMPLETE_PROMOTION_CONTEXT','observationId',o.id,
      'contextDigest',o.context_digest,'observedTermsDigest',
      ops.lc_promotion_terms_digest(item.value->'declaration')) INTO evidence
   FROM core.lc_promotion_observation o
   JOIN core.fact_provenance provenance ON provenance.id=o.provenance_id
   CROSS JOIN LATERAL jsonb_array_elements(o.context_snapshot) item
   WHERE o.id=p_evidence_id AND o.organization_id=engagement.organization_id
     AND o.platform_listing_id=engagement.platform_listing_id
     AND ops.lc_promotion_observation_is_independent_current(o.id,now_at)
     AND o.coverage_from<=o.observed_at AND o.coverage_until>o.observed_at
     AND (provenance.source_kind<>'MANUAL_ENTRY' OR provenance.recorded_by_user_id<>p_actor)
     AND o.observed_at>=engagement.created_at
     AND item.value#>>'{declaration,engagementKind}'=engagement.engagement_kind
     AND item.value#>>'{declaration,nativePromotionKey}'=engagement.native_promotion_key
     AND ops.lc_promotion_terms_digest(item.value->'declaration')<>engagement.terms_digest;
 ELSIF p_reason_code='OWNER_DECISION' THEN
   IF NOT EXISTS(SELECT 1 FROM iam.user_role_assignment r WHERE r.user_id=p_actor
       AND r.organization_id=engagement.organization_id AND r.role_code='OWNER' AND r.status='ACTIVE'
       AND r.effective_from<=now_at AND (r.effective_to IS NULL OR r.effective_to>now_at)) THEN
     RAISE EXCEPTION 'current Owner authority is required for the frozen Owner exit option' USING ERRCODE='MO092';
   END IF;
   IF engagement.action_id IS NULL THEN
     IF engagement.adoption_qualification_state<>'QUALIFIED_CURRENT_STEWARDSHIP'
        OR engagement.original_authority_valid_until<=now_at OR p_evidence_id<>engagement.source_context_observation_id
        OR NOT ops.lc_actor_holds_action(engagement.responsible_user_id,engagement.organization_id,
             engagement.store_id,'LISTING_PROMOTION_MANAGE') THEN
       RAISE EXCEPTION 'adopted activity exit requires its still-applicable original authority' USING ERRCODE='MO092';
     END IF;
     evidence:=jsonb_build_object('kind','ADOPTED_ORIGINAL_AUTHORITY','observationId',p_evidence_id,
       'originalAuthorityReference',engagement.original_authority_reference);
   ELSE
     SELECT jsonb_build_object('kind','FROZEN_ACTION_APPROVAL','approvalDecisionId',d.id,
       'actionId',action.id,'promotionTermsDigest',action.promotion_terms_digest) INTO evidence
     FROM ops.lc_action_binding b JOIN ops.approval_decision d ON d.id=b.approval_decision_id
     WHERE b.action_id=action.id AND d.id=p_evidence_id AND d.decision='APPROVED'
       AND d.decided_at<=engagement.created_at AND action.promotion_terms_digest=engagement.terms_digest;
   END IF;
 ELSE
   IF engagement.action_id IS NULL THEN
     RAISE EXCEPTION 'adopted margin, return or supply deviation needs a governed forward Action' USING ERRCODE='MO092';
   END IF;
   SELECT jsonb_build_object('kind','CURRENT_GUARDRAIL_FAILURE','guardrailEvaluationId',g.id,
      'actionId',action.id,'reasonCode',p_reason_code,'evaluatedAt',g.evaluated_at) INTO evidence
   FROM ops.guardrail_evaluation g
   WHERE g.id=p_evidence_id AND g.organization_id=engagement.organization_id
     AND g.recommendation_id=action.recommendation_id AND g.evaluated_at>=engagement.created_at
     AND g.detail->>'actionId'=action.id::text AND CASE p_reason_code
       WHEN 'MARGIN_BELOW_BOUND' THEN g.detail->>'protectionRecheck.currentProfitVerdict'='FAIL'
       WHEN 'RETURN_RATE_ABOVE_BOUND' THEN g.detail->>'protectionRecheck.currentReturnVerdict'='FAIL'
       WHEN 'SUPPLY_COVERAGE_LOST' THEN g.detail->>'protectionRecheck.supplyVerdict'='FAIL' END;
 END IF;
 IF evidence IS NULL THEN RAISE EXCEPTION 'exit reason is not proven by the named exact evidence' USING ERRCODE='MO092'; END IF;
 IF NOT ops.lc_actor_holds_action(p_actor,engagement.organization_id,engagement.store_id,'LISTING_PROMOTION_MANAGE') THEN
   RAISE EXCEPTION 'current promotion management authority required' USING ERRCODE='MO092';
 END IF;
 grant_row:=ops.consume_ad_control_invocation(p_proof,'LISTING_PROMOTION_EXIT',p_engagement,p_engagement);
 IF grant_row.actor_user_id<>p_actor OR grant_row.organization_id<>engagement.organization_id THEN
   RAISE EXCEPTION 'exit actor must match the authenticated invocation' USING ERRCODE='MO092';
 END IF;
 UPDATE ops.lc_promotion_engagement SET exit_reason_code=p_reason_code,exit_authority_reference=p_authority_reference,
   exit_evidence_id=p_evidence_id,exit_evidence=evidence,exit_authorized_by_user_id=p_actor,
   exit_authorized_at=now_at,state='EXITING',updated_at=now_at,version=version+1 WHERE id=p_engagement;
END $$;
REVOKE ALL ON FUNCTION ops.authorize_lc_promotion_exit(uuid,uuid,text,text,text,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.authorize_lc_promotion_exit(uuid,uuid,text,text,text,uuid) TO marketops_app;

CREATE FUNCTION ops.release_lc_promotion_engagement(
 p_engagement uuid,p_actor uuid,p_proof text,p_release_kind text,p_observation uuid,p_reference text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,ops,core,iam,pg_temp AS $$
DECLARE engagement ops.lc_promotion_engagement%ROWTYPE; observed core.lc_promotion_observation%ROWTYPE;
 provenance core.fact_provenance%ROWTYPE; item jsonb; grant_row iam.ad_invocation_grant%ROWTYPE;
 now_at timestamptz; evidence jsonb; phase_time timestamptz;
BEGIN
 SELECT * INTO engagement FROM ops.lc_promotion_engagement WHERE id=p_engagement FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'engagement does not exist' USING ERRCODE='MO090'; END IF;
 PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(engagement.organization_id::text));
 now_at:=clock_timestamp();
 IF length(btrim(coalesce(p_reference,''))) NOT BETWEEN 1 AND 512 THEN
   RAISE EXCEPTION 'release evidence reference required' USING ERRCODE='MO092';
 END IF;
 SELECT * INTO observed FROM core.lc_promotion_observation o WHERE o.id=p_observation
   AND o.organization_id=engagement.organization_id AND o.platform_listing_id=engagement.platform_listing_id;
 IF observed.id IS NULL OR NOT ops.lc_promotion_observation_is_independent_current(observed.id,now_at)
    OR observed.coverage_from>observed.observed_at OR observed.coverage_until<=observed.observed_at THEN
   RAISE EXCEPTION 'release requires a current independent complete promotion observation' USING ERRCODE='MO092';
 END IF;
 SELECT * INTO STRICT provenance FROM core.fact_provenance WHERE id=observed.provenance_id;
 IF provenance.source_kind='MANUAL_ENTRY' AND (provenance.recorded_by_user_id=p_actor
    OR provenance.recorded_by_user_id=engagement.exit_authorized_by_user_id
    OR EXISTS(SELECT 1 FROM ops.lc_manual_packet p LEFT JOIN ops.lc_manual_report r ON r.packet_id=p.id
       WHERE p.action_id=engagement.action_id AND (p.executor_user_id=provenance.recorded_by_user_id
          OR r.reporter_user_id=provenance.recorded_by_user_id))) THEN
   RAISE EXCEPTION 'exit author or executor cannot independently prove release' USING ERRCODE='MO092';
 END IF;
 SELECT value INTO item FROM jsonb_array_elements(observed.context_snapshot)
  WHERE value#>>'{declaration,engagementKind}'=engagement.engagement_kind
    AND value#>>'{declaration,nativePromotionKey}'=engagement.native_promotion_key;
 IF item IS NULL THEN RAISE EXCEPTION 'release evidence omits the exact native engagement' USING ERRCODE='MO092'; END IF;
 IF p_release_kind='NEW_TRANSACTIONS_STOPPED' AND engagement.state='EXITING' THEN
   phase_time:=engagement.exit_authorized_at;
   IF item->>'participationState'<>'NOT_PARTICIPATING' OR item->>'newTransactionsState'<>'STOPPED' THEN
     RAISE EXCEPTION 'new-transaction cessation is not independently proven' USING ERRCODE='MO093';
   END IF;
   evidence:=jsonb_build_object('purpose','NEW_TRANSACTIONS_STOPPED','engagementId',engagement.id,
     'observationId',observed.id,'contextDigest',observed.context_digest,'termsDigest',engagement.terms_digest,
     'observedTermsDigest',ops.lc_promotion_terms_digest(item->'declaration'),
     'observedAt',observed.observed_at,'residualObligationState',item->>'residualObligationState');
 ELSIF p_release_kind='OBLIGATIONS_CLEARED' AND engagement.state='STOPPED' THEN
   phase_time:=engagement.new_transactions_stopped_at;
   IF item->>'newTransactionsState'<>'STOPPED' OR item->>'residualObligationState'<>'CLEARED' THEN
     RAISE EXCEPTION 'historical obligations are not independently proven cleared' USING ERRCODE='MO093';
   END IF;
   evidence:=jsonb_build_object('purpose','RESIDUAL_OBLIGATIONS_CLEARED','engagementId',engagement.id,
     'observationId',observed.id,'contextDigest',observed.context_digest,'termsDigest',engagement.terms_digest,
     'observedTermsDigest',ops.lc_promotion_terms_digest(item->'declaration'),
     'observedAt',observed.observed_at,'residualObligationState','CLEARED');
 ELSE
   RAISE EXCEPTION 'promotion release phase is out of order' USING ERRCODE='MO091';
 END IF;
 IF observed.observed_at<phase_time OR observed.acquired_at>now_at THEN
   RAISE EXCEPTION 'release observation must follow this exact lifecycle phase' USING ERRCODE='MO092';
 END IF;
 IF NOT ops.lc_actor_holds_action(p_actor,engagement.organization_id,engagement.store_id,'LISTING_PROMOTION_MANAGE') THEN
   RAISE EXCEPTION 'current promotion management authority required' USING ERRCODE='MO092';
 END IF;
 grant_row:=ops.consume_ad_control_invocation(p_proof,'LISTING_OCCUPATION_RELEASE',p_engagement,p_engagement);
 IF grant_row.actor_user_id<>p_actor OR grant_row.organization_id<>engagement.organization_id THEN
   RAISE EXCEPTION 'release actor must match the authenticated invocation' USING ERRCODE='MO092';
 END IF;
 IF p_release_kind='NEW_TRANSACTIONS_STOPPED' THEN
   UPDATE ops.lc_promotion_engagement SET state='STOPPED',new_transactions_stopped_at=now_at,
     stop_evidence_observation_id=observed.id,updated_at=now_at,version=version+1 WHERE id=engagement.id;
   UPDATE ops.lc_exposure_occupation SET state='RELEASED',released_at=now_at,release_basis='STOP_EVIDENCE',
     release_evidence_reference=p_reference,released_by_user_id=p_actor,release_evidence_id=observed.id,
     release_evidence=evidence||jsonb_build_object('axisCode',axis_code)
    WHERE action_id=engagement.action_id AND state<>'RELEASED'
      AND axis_code IN ('CONCURRENT_LISTINGS','AFFECTED_VARIANTS','CATEGORY_SHARE');
 ELSE
   UPDATE ops.lc_promotion_engagement SET state='CLEARED',obligations_cleared_at=now_at,
     obligation_evidence_observation_id=observed.id,updated_at=now_at,version=version+1 WHERE id=engagement.id;
   UPDATE ops.lc_exposure_occupation SET state='RELEASED',released_at=now_at,release_basis='OBLIGATION_CLEARED',
     release_evidence_reference=p_reference,released_by_user_id=p_actor,release_evidence_id=observed.id,
     release_evidence=evidence||jsonb_build_object('axisCode',axis_code)
    WHERE action_id=engagement.action_id AND state<>'RELEASED' AND axis_code='REVENUE_EXPOSURE';
 END IF;
END $$;
REVOKE ALL ON FUNCTION ops.release_lc_promotion_engagement(uuid,uuid,text,text,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.release_lc_promotion_engagement(uuid,uuid,text,text,uuid,text) TO marketops_app;

-- The application may no longer directly claim lifecycle state or alter exact
-- terms/obligations.  Insert remains the existing factual record path; triggers
-- derive qualification and reject post-insert authority changes.
REVOKE UPDATE (terms,obligations,new_transactions_stopped_at,obligations_cleared_at,state,updated_at,version)
 ON ops.lc_promotion_engagement FROM marketops_app;

-- ---------------------------------------------------------------------------
-- Manual facts are retained even when reported late or outside authority.  A
-- separate qualification controls whether they may advance the Action.
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.lc_manual_packet_requires_current_authority() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE action ops.lc_action%ROWTYPE; binding ops.lc_action_binding%ROWTYPE; now_at timestamptz:=clock_timestamp();
BEGIN
 SELECT * INTO action FROM ops.lc_action WHERE id=NEW.action_id;
 SELECT * INTO binding FROM ops.lc_action_binding WHERE action_id=NEW.action_id;
 IF action.id IS NULL OR binding.id IS NULL OR action.organization_id<>NEW.organization_id
    OR action.state<>'LAUNCHED' OR action.execution_path<>'MANUAL'
    OR NEW.launch_id IS DISTINCT FROM (SELECT id FROM ops.lc_launch WHERE action_id=action.id)
    OR NEW.issued_at>now_at OR NEW.expires_at<=now_at OR NEW.expires_at>binding.expires_at
    OR cardinality(ops.lc_binding_gaps(action.id))>0
    OR ops.lc_scope_contained(action.organization_id,action.platform_listing_id)
    OR NOT ops.lc_actor_holds_action(NEW.issued_by_user_id,action.organization_id,action.store_id,'LISTING_ACTION_LAUNCH')
    OR NOT ops.lc_actor_holds_action(NEW.executor_user_id,action.organization_id,action.store_id,'LISTING_MANUAL_EXECUTE') THEN
   RAISE EXCEPTION 'manual packet requires the exact current launch, binding, scope and people' USING ERRCODE='MO092';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_manual_packet_requires_current_authority() FROM PUBLIC;
CREATE TRIGGER zz_lc_manual_packet_current_authority BEFORE INSERT ON ops.lc_manual_packet
 FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_packet_requires_current_authority();

ALTER TABLE ops.lc_manual_report
 ADD COLUMN operation_qualification text NOT NULL DEFAULT 'HISTORICAL_UNQUALIFIED',
 ADD COLUMN deviation_reason text;
ALTER TABLE ops.lc_manual_report ADD CONSTRAINT lc_manual_report_operation_qualification_ck CHECK(
 operation_qualification IN ('HISTORICAL_UNQUALIFIED','WITHIN_PACKET_AUTHORITY','LAWFUL_LATE_REPORT','UNAUTHORISED_DEVIATION'));
ALTER TABLE ops.lc_manual_report ADD CONSTRAINT lc_manual_report_deviation_shape_ck CHECK(
 (operation_qualification='UNAUTHORISED_DEVIATION')=(deviation_reason IS NOT NULL));

CREATE FUNCTION ops.lc_classify_manual_report_operation() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE packet ops.lc_manual_packet%ROWTYPE;
BEGIN
 SELECT * INTO STRICT packet FROM ops.lc_manual_packet WHERE id=NEW.packet_id;
 IF NEW.reported_at<NEW.operation_time OR NEW.organization_id<>packet.organization_id
    OR NEW.reporter_user_id<>packet.executor_user_id THEN
   RAISE EXCEPTION 'manual report chronology and executor must match the exact packet' USING ERRCODE='MO092';
 END IF;
 IF NEW.operation_time<packet.issued_at THEN
   NEW.operation_qualification:='UNAUTHORISED_DEVIATION'; NEW.deviation_reason:='OPERATION_BEFORE_PACKET_AUTHORITY';
 ELSIF NEW.operation_time>packet.expires_at THEN
   NEW.operation_qualification:='UNAUTHORISED_DEVIATION'; NEW.deviation_reason:='OPERATION_AFTER_PACKET_AUTHORITY';
 ELSIF NEW.reported_at>packet.expires_at THEN
   NEW.operation_qualification:='LAWFUL_LATE_REPORT'; NEW.deviation_reason:=NULL;
 ELSE
   NEW.operation_qualification:='WITHIN_PACKET_AUTHORITY'; NEW.deviation_reason:=NULL;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_classify_manual_report_operation() FROM PUBLIC;
CREATE TRIGGER lc_manual_report_classifies_operation BEFORE INSERT ON ops.lc_manual_report
 FOR EACH ROW EXECUTE FUNCTION ops.lc_classify_manual_report_operation();

ALTER TABLE ops.lc_manual_verification
 ADD COLUMN qualification_state text NOT NULL DEFAULT 'HISTORICAL_UNQUALIFIED';
ALTER TABLE ops.lc_manual_verification ADD CONSTRAINT lc_manual_verification_qualification_ck CHECK(
 qualification_state IN ('HISTORICAL_UNQUALIFIED','QUALIFIED','UNQUALIFIED','DEVIATION_RETAINED','CURRENTLY_CONTAINED'));

CREATE FUNCTION ops.lc_classify_manual_verification() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE packet ops.lc_manual_packet%ROWTYPE; action ops.lc_action%ROWTYPE;
BEGIN
 SELECT * INTO STRICT packet FROM ops.lc_manual_packet WHERE id=NEW.packet_id;
 SELECT * INTO STRICT action FROM ops.lc_action WHERE id=packet.action_id;
 NEW.qualification_state:='UNQUALIFIED';
 IF EXISTS(SELECT 1 FROM ops.lc_manual_report r WHERE r.packet_id=packet.id
      AND r.operation_qualification='UNAUTHORISED_DEVIATION') THEN
   NEW.qualification_state:='DEVIATION_RETAINED'; RETURN NEW;
 END IF;
 IF ops.lc_scope_contained(action.organization_id,action.platform_listing_id) THEN
   NEW.qualification_state:='CURRENTLY_CONTAINED'; RETURN NEW;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM ops.lc_manual_report r WHERE r.packet_id=packet.id
      AND r.report_state='APPLIED'
      AND r.operation_qualification IN ('WITHIN_PACKET_AUTHORITY','LAWFUL_LATE_REPORT')
      AND r.operation_time=coalesce((NEW.observation_binding->>'operationAt')::timestamptz,
                                    (NEW.observation_binding->>'afterOperation')::timestamptz)) THEN RETURN NEW; END IF;
 IF NEW.management_match='MATCHED_TARGET'
    AND NEW.observation_binding->>'actionId'=action.id::text
    AND ((action.action_kind='LISTING_PROMOTION_ACTION'
          AND NEW.observation_binding->>'purpose'='PARTICIPATION'
          AND NEW.observation_binding->>'participationState'='PARTICIPATING'
          AND NEW.observation_binding->>'promotionTermsDigest'=action.promotion_terms_digest)
      OR (action.action_kind='LISTING_DESCRIPTION_CHANGE'
          AND NEW.management_observation_id IS NOT NULL
          AND NEW.display_observation_id IS NOT NULL AND NEW.display_state='DISPLAYED'
          AND NEW.observation_binding->>'targetTextDigest'=action.target_text_digest)) THEN
   NEW.qualification_state:='QUALIFIED';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_classify_manual_verification() FROM PUBLIC;
-- Trigger order is lexical; the existing binder first creates observation_binding.
CREATE TRIGGER zz_lc_manual_verification_qualification BEFORE INSERT ON ops.lc_manual_verification
 FOR EACH ROW EXECUTE FUNCTION ops.lc_classify_manual_verification();

-- A repository or a later service cannot bypass the fact/qualification split
-- by moving the Action directly after an unqualified retained verification.
CREATE OR REPLACE FUNCTION ops.lc_manual_verified_action_requires_bound_observations()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,ops,pg_temp AS $$
BEGIN
 IF NEW.execution_path='MANUAL' AND NEW.state='VERIFIED' AND OLD.state<>NEW.state AND NOT EXISTS(
   SELECT 1 FROM ops.lc_manual_verification verification
   JOIN ops.lc_manual_packet packet ON packet.id=verification.packet_id
   WHERE packet.action_id=NEW.id AND verification.management_match='MATCHED_TARGET'
     AND verification.qualification_state='QUALIFIED'
     AND ((NEW.action_kind='LISTING_DESCRIPTION_CHANGE'
           AND verification.observation_binding->>'targetTextDigest'=NEW.target_text_digest)
       OR (NEW.action_kind='LISTING_PROMOTION_ACTION'
           AND verification.observation_binding->>'purpose'='PARTICIPATION'
           AND verification.observation_binding->>'claimExtent'='OBSERVED_INSTANT_ONLY'
           AND verification.observation_binding->>'participationState'='PARTICIPATING'
           AND verification.observation_binding->>'promotionTermsDigest'=ops.lc_promotion_terms_digest(NEW.promotion_terms)))
     AND verification.observation_binding->>'actionId'=NEW.id::text) THEN
   RAISE EXCEPTION 'manual verification must be qualified for this exact Action and observed result' USING ERRCODE='MO092';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_manual_verified_action_requires_bound_observations() FROM PUBLIC;

-- ---------------------------------------------------------------------------
-- All accepted allowance axes use retained Action evidence.  The launch JSON
-- argument remains wire-compatible but has no authority.  Known standalone
-- engagements count before they qualify for stewardship; missing numeric facts
-- make admission unresolved rather than silently becoming zero.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION ops.lc_allowance_projection(p_action uuid,p_at timestamptz)
RETURNS jsonb LANGUAGE plpgsql STABLE
SET search_path=pg_catalog,ops,core,pg_temp SET timezone='UTC' SET DateStyle='ISO, YMD' AS $$
DECLARE
 action ops.lc_action%ROWTYPE; listing core.platform_listing%ROWTYPE; simulation ops.lc_simulation%ROWTYPE;
 allowance ops.lc_exposure_allowance%ROWTYPE; engagement ops.lc_promotion_engagement%ROWTYPE;
 resolved_package uuid; policy jsonb; reserves jsonb; required_axes jsonb; axis text;
 composition text; gaps text[]:='{}'; axes jsonb:='[]'; scoped_ids uuid[];
 occupied numeric; requested numeric; canonical numeric; reserve numeric; headroom numeric;
 variants uuid[]; members uuid[]; unresolved boolean; snapshot jsonb; member jsonb; demand jsonb;
 promotion_context jsonb; promotion_revenue numeric; promotion_fixed numeric; promotion_currency text;
 material_revenue numeric; material_currency text; material_share numeric; currency_count integer;
 demand_source jsonb;
BEGIN
 SELECT * INTO action FROM ops.lc_action WHERE id=p_action;
 IF NOT FOUND THEN RAISE EXCEPTION 'action does not exist' USING ERRCODE='MO090'; END IF;
 SELECT * INTO STRICT listing FROM core.platform_listing WHERE id=action.platform_listing_id;
 SELECT (r.value->>'currentPackageId')::uuid INTO resolved_package
   FROM (SELECT ops.lc_action_calibration_recheck(action.id,p_at) AS value) r
   WHERE r.value->>'state' IN ('CURRENT','UNCHANGED_DEPENDENCIES');
 IF resolved_package IS NULL THEN gaps:=array_append(gaps,'ALLOWANCE_POLICY_UNRESOLVED');
 ELSE
   SELECT value_json INTO policy FROM core.lc_calibration_value v
     WHERE v.package_id=resolved_package AND category_code='ALLOWANCE_AXES';
   SELECT value_json INTO reserves FROM core.lc_calibration_value v
     WHERE v.package_id=resolved_package AND category_code='ALLOWANCE_RESERVE';
 END IF;
 IF jsonb_typeof(policy)='array' THEN required_axes:=policy; composition:='SINGLE_SCOPE';
 ELSIF jsonb_typeof(policy)='object' AND policy->>'scopeComposition'='ALL_APPLICABLE'
    AND NOT EXISTS(SELECT 1 FROM jsonb_object_keys(policy) k WHERE k NOT IN ('axes','scopeComposition')) THEN
   required_axes:=policy->'axes'; composition:='ALL_APPLICABLE';
 END IF;
 IF jsonb_typeof(required_axes) IS DISTINCT FROM 'array' THEN
   required_axes:='[]'; gaps:=array_append(gaps,'ALLOWANCE_AXES_UNRESOLVED');
 END IF;
 IF jsonb_array_length(required_axes)=0 OR jsonb_array_length(required_axes)>4
   OR EXISTS(SELECT 1 FROM jsonb_array_elements(required_axes) x WHERE jsonb_typeof(x)<>'string'
     OR x#>>'{}' NOT IN ('CONCURRENT_LISTINGS','AFFECTED_VARIANTS','REVENUE_EXPOSURE','CATEGORY_SHARE'))
   OR (SELECT count(*)<>count(DISTINCT x) FROM jsonb_array_elements(required_axes) x) THEN
   required_axes:='[]'; gaps:=array_append(gaps,'ALLOWANCE_AXES_UNRESOLVED');
 END IF;
 SELECT array_agg(DISTINCT value ORDER BY value) INTO variants FROM core.lc_affected_set s,
   unnest(s.platform_listing_variant_ids) value WHERE s.id=action.affected_set_id
     AND s.resolution_state='COMPLETE' AND s.identity_lineage IS NOT NULL;

 -- Current retained-sales evidence is the canonical category share and, for
 -- non-promotion actions, the exact monetary exposure of the affected members.
 IF action.materiality_evidence->>'state'='QUALIFIED'
    AND jsonb_typeof(action.materiality_evidence#>'{projection,memberValues}')='array'
    AND coalesce(action.materiality_evidence#>>'{projection,share}','') ~ '^[0-9]+([.][0-9]+)?$' THEN
   material_share:=(action.materiality_evidence#>>'{projection,share}')::numeric;
   SELECT sum((value->>'numericValue')::numeric),min(value->>'currencyCode'),count(DISTINCT value->>'currencyCode')
     INTO material_revenue,material_currency,currency_count
   FROM jsonb_array_elements(action.materiality_evidence#>'{projection,memberValues}')
   WHERE coalesce(value->>'numericValue','') ~ '^[0-9]+([.][0-9]+)?$';
   IF currency_count<>1 OR material_revenue IS NULL THEN material_revenue:=NULL; material_currency:=NULL; END IF;
 END IF;

 IF action.action_kind='LISTING_PROMOTION_ACTION' THEN
   SELECT s.* INTO simulation FROM ops.recommendation r JOIN ops.lc_simulation s
     ON s.id::text=r.proposed_parameters->>'simulationId'
      AND s.inputs_digest=r.proposed_parameters->>'simulationInputsDigest'
    WHERE r.id=action.recommendation_id AND s.organization_id=action.organization_id
      AND s.candidate_id=action.candidate_id AND s.computed_at<=action.created_at
      AND s.input_snapshot->>'qualificationState'='QUALIFIED_CONDITIONAL_ECONOMICS'
      AND s.input_snapshot->>'nativeIdentityDigest'=action.affected_set_digest
      AND s.input_snapshot->>'promotionTermsDigest'=action.promotion_terms_digest;
   IF simulation.id IS NOT NULL
      AND coalesce(simulation.input_snapshot#>>'{context,periodStart}','') ~ '^[-+0-9T:. Z]+$'
      AND coalesce(simulation.input_snapshot#>>'{context,periodEnd}','') ~ '^[-+0-9T:. Z]+$' THEN
     promotion_context:=ops.lc_current_promotion_context(action.organization_id,action.platform_listing_id,
       (simulation.input_snapshot#>>'{context,periodStart}')::timestamptz,
       (simulation.input_snapshot#>>'{context,periodEnd}')::timestamptz,p_at);
     IF promotion_context->>'coverage'='QUALIFIED_COMPLETE'
        AND promotion_context->>'digest'=simulation.input_snapshot#>>'{knownPromotionContext,digest}'
        AND simulation.input_snapshot#>>'{revenueEvidence,state}'='COMMERCIAL_REVENUE_MATCH'
        AND simulation.input_snapshot#>>'{fixedFeeEvidence,state}'='ACTIVITY_FIXED_FEE_MATCH'
        AND coalesce(simulation.input_snapshot#>>'{inputs,expenses,fixedPromotionFee,amount}','') ~ '^[0-9]+([.][0-9]+)?$' THEN
       SELECT max((result->>'netRevenue')::numeric) INTO promotion_revenue
       FROM jsonb_array_elements(simulation.results) result
       JOIN jsonb_array_elements(simulation.scenario_set) scenario ON scenario->>'code'=result->>'code'
       WHERE scenario->>'necessary'='true' AND result->>'state'='COMPUTED'
         AND coalesce(result->>'netRevenue','') ~ '^[0-9]+([.][0-9]+)?$';
       promotion_fixed:=(simulation.input_snapshot#>>'{inputs,expenses,fixedPromotionFee,amount}')::numeric;
       promotion_currency:=simulation.input_snapshot#>>'{inputs,currencyCode}';
       IF promotion_revenue IS NOT NULL THEN promotion_revenue:=promotion_revenue+promotion_fixed; END IF;
     END IF;
   END IF;
 END IF;

 FOR axis IN SELECT jsonb_array_elements_text(required_axes) ORDER BY 1 LOOP
   IF coalesce(reserves->>axis,'') !~ '^[0-9]+([.][0-9]{1,4})?$' OR length(reserves->>axis)>19 THEN
     gaps:=array_append(gaps,axis||':RESERVE_UNRESOLVED'); CONTINUE;
   END IF;
   reserve:=(reserves->>axis)::numeric;
   SELECT array_agg(a.id ORDER BY a.scope_key,a.id) INTO scoped_ids
     FROM ops.lc_allowances_for(action.organization_id,action.platform_listing_id,p_at) a WHERE a.axis_code=axis;
   IF coalesce(cardinality(scoped_ids),0)=0 THEN gaps:=array_append(gaps,axis||':ALLOWANCE_MISSING'); CONTINUE; END IF;
   IF composition='SINGLE_SCOPE' AND cardinality(scoped_ids)<>1 THEN
     gaps:=array_append(gaps,axis||':SCOPE_COMPOSITION_UNRESOLVED'); CONTINUE;
   END IF;
   FOR allowance IN SELECT a.* FROM ops.lc_exposure_allowance a WHERE a.id=ANY(scoped_ids)
       ORDER BY a.scope_key,a.id LOOP
     members:='{}'; unresolved:=false; occupied:=0; requested:=NULL; canonical:=NULL; demand_source:=NULL;
     IF axis IN ('CONCURRENT_LISTINGS','AFFECTED_VARIANTS') THEN
       WITH outstanding AS (
         SELECT a.platform_listing_id,a.affected_set_id FROM ops.lc_exposure_occupation o
         JOIN ops.lc_action a ON a.id=o.action_id JOIN core.platform_listing l ON l.id=a.platform_listing_id
         WHERE o.organization_id=action.organization_id AND o.axis_code=axis AND o.state<>'RELEASED'
           AND (allowance.scope_kind='ORGANIZATION'
             OR (allowance.scope_kind='PLATFORM' AND allowance.platform_code=l.platform_code)
             OR (allowance.scope_kind='STORE' AND allowance.store_ref_id=a.store_id)))
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
         WHERE e.organization_id=action.organization_id AND e.state IN ('ACTIVE','EXITING')
           AND NOT EXISTS(SELECT 1 FROM ops.lc_exposure_occupation o
             WHERE o.action_id=e.action_id AND o.axis_code=axis AND o.state<>'RELEASED')
           AND (allowance.scope_kind='ORGANIZATION'
             OR (allowance.scope_kind='PLATFORM' AND allowance.platform_code=l.platform_code)
             OR (allowance.scope_kind='STORE' AND allowance.store_ref_id=e.store_id)) LOOP
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
       IF axis='CONCURRENT_LISTINGS' AND allowance.unit_code='COUNT' THEN
         canonical:=1; requested:=CASE WHEN action.platform_listing_id=ANY(coalesce(members,'{}')) THEN 0 ELSE 1 END;
       ELSIF axis='AFFECTED_VARIANTS' AND allowance.unit_code='COUNT' AND cardinality(variants)>0 THEN
         canonical:=cardinality(variants);
         SELECT count(*) INTO requested FROM unnest(variants) value WHERE NOT(value=ANY(coalesce(members,'{}')));
       END IF;
       demand_source:=jsonb_build_object('kind','FROZEN_AFFECTED_IDENTITY','affectedSetId',action.affected_set_id,
          'affectedSetDigest',action.affected_set_digest);
     ELSE
       SELECT coalesce(sum(o.occupied_value),0),coalesce(bool_or(o.state='UNKNOWN'
           AND (o.demand_evidence IS NULL OR o.occupied_value<=0)),false)
         INTO occupied,unresolved
       FROM ops.lc_exposure_occupation o JOIN ops.lc_action occupied_action ON occupied_action.id=o.action_id
       JOIN core.platform_listing l ON l.id=occupied_action.platform_listing_id
       WHERE o.organization_id=action.organization_id AND o.axis_code=axis AND o.state<>'RELEASED'
         AND (allowance.scope_kind='ORGANIZATION'
           OR (allowance.scope_kind='PLATFORM' AND allowance.platform_code=l.platform_code)
           OR (allowance.scope_kind='STORE' AND allowance.store_ref_id=occupied_action.store_id));
       FOR engagement IN SELECT e.* FROM ops.lc_promotion_engagement e JOIN core.platform_listing l
          ON l.id=e.platform_listing_id AND l.organization_id=e.organization_id
         WHERE e.organization_id=action.organization_id
           AND ((axis='REVENUE_EXPOSURE' AND e.state<>'CLEARED')
             OR (axis='CATEGORY_SHARE' AND e.state IN ('ACTIVE','EXITING')))
           AND NOT EXISTS(SELECT 1 FROM ops.lc_exposure_occupation o
             WHERE o.action_id=e.action_id AND o.axis_code=axis AND o.state<>'RELEASED')
           AND (allowance.scope_kind='ORGANIZATION'
             OR (allowance.scope_kind='PLATFORM' AND allowance.platform_code=l.platform_code)
             OR (allowance.scope_kind='STORE' AND allowance.store_ref_id=e.store_id)) LOOP
         demand:=engagement.axis_demands->axis;
         IF jsonb_typeof(demand) IS DISTINCT FROM 'object' OR demand->>'unitCode' IS DISTINCT FROM allowance.unit_code
            OR coalesce(demand->>'value','') !~ '^[0-9]+([.][0-9]{1,4})?$' THEN unresolved:=true;
         ELSE occupied:=occupied+(demand->>'value')::numeric; END IF;
       END LOOP;
       IF axis='REVENUE_EXPOSURE' THEN
         IF action.action_kind='LISTING_PROMOTION_ACTION' AND promotion_revenue IS NOT NULL
            AND allowance.unit_code=promotion_currency THEN
           canonical:=ceil(promotion_revenue*10000)/10000;
           demand_source:=jsonb_build_object('kind','SELECTED_QUALIFIED_SIMULATION','simulationId',simulation.id,
             'inputsDigest',simulation.inputs_digest,'contextDigest',promotion_context->>'digest',
             'maximumNecessaryRevenue',promotion_revenue-promotion_fixed,'fixedCommitment',promotion_fixed);
         ELSIF action.action_kind<>'LISTING_PROMOTION_ACTION' AND material_revenue IS NOT NULL
            AND allowance.unit_code=material_currency THEN
           canonical:=ceil(material_revenue*10000)/10000;
           demand_source:=jsonb_build_object('kind','CANONICAL_RETAINED_SALES_EXPOSURE',
             'materialityEvidence',action.materiality_evidence);
         END IF;
       ELSIF axis='CATEGORY_SHARE' AND allowance.unit_code='RATIO' AND material_share IS NOT NULL THEN
         canonical:=ceil(material_share*10000)/10000;
         demand_source:=jsonb_build_object('kind','CANONICAL_RETAINED_SALES_SHARE',
           'materialityEvidence',action.materiality_evidence);
       END IF;
       requested:=CASE WHEN EXISTS(SELECT 1 FROM ops.lc_exposure_occupation o
          WHERE o.action_id=action.id AND o.axis_code=axis AND o.state<>'RELEASED') THEN 0 ELSE canonical END;
     END IF;
     IF unresolved OR requested IS NULL THEN gaps:=array_append(gaps,axis||':CANONICAL_DEMAND_UNRESOLVED'); requested:=NULL; END IF;
     IF allowance.reserve_value<reserve THEN gaps:=array_append(gaps,axis||':RESERVE_BELOW_ACCEPTED_POLICY'); END IF;
     headroom:=allowance.limit_value-allowance.reserve_value-occupied;
     axes:=axes||jsonb_build_array(jsonb_build_object('allowanceId',allowance.id,'axisCode',axis,
       'scopeKind',allowance.scope_kind,'limitValue',allowance.limit_value,'reserveValue',allowance.reserve_value,
       'occupiedValue',occupied,'requestedValue',requested,'canonicalDemand',canonical,'demandSource',demand_source,
       'headroom',headroom,'sufficient',requested IS NOT NULL AND requested<=headroom,
       'unitCode',allowance.unit_code,'scopeKey',allowance.scope_key,'allowanceVersion',allowance.allowance_version));
   END LOOP;
 END LOOP;
 RETURN jsonb_build_object('platformListingId',action.platform_listing_id,'actionId',action.id,
   'affectedSetId',action.affected_set_id,'affectedSetDigest',action.affected_set_digest,
   'calibrationPackageId',resolved_package,'policy',policy,'evaluatedAt',p_at,
   'axes',axes,'resolved',cardinality(gaps)=0,'gaps',to_jsonb(gaps));
END $$;
REVOKE ALL ON FUNCTION ops.lc_allowance_projection(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_allowance_projection(uuid,timestamptz) TO marketops_app;

-- ---------------------------------------------------------------------------
-- A late association remains a retained event.  Only a qualified verification
-- of the same Listing and exact observed target may resolve it; an unauthorised
-- deviation additionally needs a later Action and approval.
-- ---------------------------------------------------------------------------

ALTER TABLE ops.lc_late_association ADD COLUMN closure_assessment jsonb;

-- Earlier CLOSED rows were accepted on an arbitrary verification foreign key.
-- Keep that historical assertion in the assessment, but reopen the actual work.
UPDATE ops.lc_late_association
   SET closure_assessment=jsonb_build_object('qualification','HISTORICAL_UNQUALIFIED',
         'priorVerificationId',closure_verification_id,'reopenedAt',clock_timestamp()),
       closure_verification_id=NULL,
       state=CASE association_kind WHEN 'UNRESOLVED_CHANGE' THEN 'UNDER_VERIFICATION'
              WHEN 'UNAUTHORISED_DEVIATION' THEN 'OPEN' ELSE 'LINKED' END,
       updated_at=clock_timestamp(),version=version+1
 WHERE state='CLOSED';
UPDATE ops.lc_late_association
   SET closure_assessment=jsonb_build_object('qualification','HISTORICAL_UNQUALIFIED',
         'priorVerificationId',closure_verification_id,'reviewedAt',clock_timestamp()),
       closure_verification_id=NULL,updated_at=clock_timestamp(),version=version+1
 WHERE state<>'CLOSED' AND closure_verification_id IS NOT NULL;

ALTER TABLE ops.lc_late_association ADD CONSTRAINT lc_late_association_qualified_closure_ck CHECK(
 (state='CLOSED' AND closure_verification_id IS NOT NULL
    AND closure_assessment->>'qualification'='QUALIFIED')
 OR (state<>'CLOSED' AND closure_verification_id IS NULL));

DROP TRIGGER lc_late_association_is_lawful ON ops.lc_late_association;
CREATE OR REPLACE FUNCTION ops.lc_late_association_is_lawful() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE action ops.lc_action%ROWTYPE; observed core.lc_description_observation%ROWTYPE;
 provenance core.fact_provenance%ROWTYPE; packet ops.lc_manual_packet%ROWTYPE;
 report ops.lc_manual_report%ROWTYPE; listing core.platform_listing%ROWTYPE;
BEGIN
 SELECT * INTO STRICT listing FROM core.platform_listing WHERE id=NEW.platform_listing_id
   AND organization_id=NEW.organization_id;
 SELECT * INTO STRICT observed FROM core.lc_description_observation WHERE id=NEW.observation_id
   AND organization_id=NEW.organization_id AND platform_listing_id=NEW.platform_listing_id;
 SELECT * INTO STRICT provenance FROM core.fact_provenance WHERE id=observed.provenance_id
   AND organization_id=NEW.organization_id;
 IF NEW.recorded_at<observed.acquired_at OR observed.observed_at>NEW.recorded_at
    OR NOT ops.lc_actor_holds_action(NEW.recorded_by_user_id,NEW.organization_id,listing.store_id,
         'LISTING_MANUAL_VERIFY') THEN
   RAISE EXCEPTION 'late association needs the retained event and current verifier authority' USING ERRCODE='MO092';
 END IF;
 IF NEW.action_id IS NOT NULL THEN
   SELECT * INTO action FROM ops.lc_action WHERE id=NEW.action_id;
   IF action.id IS NULL OR action.organization_id<>NEW.organization_id
      OR action.platform_listing_id<>NEW.platform_listing_id THEN
     RAISE EXCEPTION 'late association action must belong to the exact Listing' USING ERRCODE='MO092';
   END IF;
 END IF;
 IF NEW.state IS DISTINCT FROM (CASE NEW.association_kind WHEN 'LAWFUL_LATE_REPORT' THEN 'LINKED'
      WHEN 'UNAUTHORISED_DEVIATION' THEN 'OPEN' ELSE 'UNDER_VERIFICATION' END)
    OR NEW.closure_verification_id IS NOT NULL THEN
   RAISE EXCEPTION 'a new late association starts in its exact unresolved state' USING ERRCODE='MO092';
 END IF;
 IF NEW.association_kind='LAWFUL_LATE_REPORT' THEN
   SELECT p.* INTO packet FROM ops.lc_manual_packet p JOIN ops.lc_manual_report r ON r.packet_id=p.id
    WHERE p.action_id=action.id AND p.organization_id=NEW.organization_id
      AND r.operation_time=NEW.operation_time AND r.reported_at=NEW.report_time
      AND r.report_state='APPLIED' AND r.operation_qualification='LAWFUL_LATE_REPORT'
    ORDER BY r.id LIMIT 1;
   SELECT r.* INTO report FROM ops.lc_manual_report r WHERE r.packet_id=packet.id
      AND r.operation_time=NEW.operation_time AND r.reported_at=NEW.report_time
      AND r.report_state='APPLIED' AND r.operation_qualification='LAWFUL_LATE_REPORT' LIMIT 1;
   IF action.id IS NULL OR packet.id IS NULL OR report.id IS NULL OR action.execution_path<>'MANUAL'
      OR report.reporter_user_id<>packet.executor_user_id
      OR observed.text_digest IS DISTINCT FROM action.target_text_digest
      OR observed.observed_at<NEW.operation_time OR observed.acquired_at>NEW.recorded_at
      OR NOT EXISTS(SELECT 1 FROM ops.lc_launch l WHERE l.action_id=action.id
           AND l.id=packet.launch_id AND l.launched_at<=NEW.operation_time)
      OR (provenance.source_kind='MANUAL_ENTRY' AND
          (provenance.recorded_by_user_id IS NULL OR provenance.recorded_by_user_id=packet.executor_user_id))
      OR (provenance.source_kind='MARKETPLACE_RAW' AND provenance.raw_observation_id IS NULL)
      OR provenance.source_kind NOT IN ('MANUAL_ENTRY','MARKETPLACE_RAW') THEN
     RAISE EXCEPTION 'lawful late reporting must reproduce the exact authorised operation and independent result'
       USING ERRCODE='MO092';
   END IF;
 ELSIF NEW.association_kind='UNAUTHORISED_DEVIATION' THEN
   IF NEW.operation_time IS NULL OR NEW.operation_time>NEW.recorded_at
      OR NEW.report_time IS NULL OR NEW.report_time<NEW.operation_time
      OR length(btrim(coalesce(NEW.authority_gap,'')))=0
      OR length(btrim(coalesce(NEW.forward_disposition,'')))=0 THEN
     RAISE EXCEPTION 'deviation chronology, authority gap and forward disposition are retained' USING ERRCODE='MO092';
   END IF;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_late_association_is_lawful() FROM PUBLIC;
CREATE TRIGGER lc_late_association_is_lawful BEFORE INSERT ON ops.lc_late_association
 FOR EACH ROW EXECUTE FUNCTION ops.lc_late_association_is_lawful();

CREATE FUNCTION ops.close_lc_late_association(
 p_association uuid,p_actor uuid,p_proof text,p_verification uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE association ops.lc_late_association%ROWTYPE; verification ops.lc_manual_verification%ROWTYPE;
 packet ops.lc_manual_packet%ROWTYPE; action ops.lc_action%ROWTYPE; observed core.lc_description_observation%ROWTYPE;
 management core.lc_description_observation%ROWTYPE; approval ops.approval_decision%ROWTYPE;
 grant_row iam.ad_invocation_grant%ROWTYPE; listing core.platform_listing%ROWTYPE; launch_at timestamptz;
 assessment jsonb; event_at timestamptz;
BEGIN
 SELECT * INTO association FROM ops.lc_late_association WHERE id=p_association FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'late association does not exist' USING ERRCODE='MO090'; END IF;
 IF association.state NOT IN ('OPEN','UNDER_VERIFICATION') THEN
   RAISE EXCEPTION 'only unresolved or unauthorised work is closed' USING ERRCODE='MO091';
 END IF;
 SELECT * INTO STRICT listing FROM core.platform_listing WHERE id=association.platform_listing_id
   AND organization_id=association.organization_id;
 grant_row:=ops.consume_ad_control_invocation(p_proof,'LISTING_MANUAL_VERIFY',p_association,p_association);
 IF grant_row.actor_user_id<>p_actor OR grant_row.organization_id<>association.organization_id
    OR NOT ops.lc_actor_holds_action(p_actor,association.organization_id,listing.store_id,'LISTING_MANUAL_VERIFY') THEN
   RAISE EXCEPTION 'closure actor must match a current exact-scope verifier invocation' USING ERRCODE='MO092';
 END IF;
 SELECT * INTO verification FROM ops.lc_manual_verification WHERE id=p_verification FOR UPDATE;
 IF verification.id IS NULL OR verification.organization_id<>association.organization_id
    OR verification.qualification_state<>'QUALIFIED' OR verification.management_match<>'MATCHED_TARGET'
    OR verification.verified_at<association.recorded_at THEN
   RAISE EXCEPTION 'a current qualified later verification is required' USING ERRCODE='MO092';
 END IF;
 SELECT * INTO STRICT packet FROM ops.lc_manual_packet WHERE id=verification.packet_id;
 SELECT * INTO STRICT action FROM ops.lc_action WHERE id=packet.action_id;
 SELECT * INTO STRICT observed FROM core.lc_description_observation WHERE id=association.observation_id;
 SELECT * INTO management FROM core.lc_description_observation WHERE id=verification.management_observation_id;
 IF action.organization_id<>association.organization_id OR action.platform_listing_id<>association.platform_listing_id
    OR action.action_kind<>'LISTING_DESCRIPTION_CHANGE' OR management.id IS NULL
    OR management.platform_listing_id<>association.platform_listing_id
    OR management.text_digest IS DISTINCT FROM observed.text_digest
    OR action.target_text_digest IS DISTINCT FROM observed.text_digest
    OR verification.observation_binding->>'actionId' IS DISTINCT FROM action.id::text
    OR verification.observation_binding->>'managementObservationId' IS DISTINCT FROM management.id::text THEN
   RAISE EXCEPTION 'verification does not resolve this exact Listing event and target' USING ERRCODE='MO092';
 END IF;
 event_at:=coalesce(association.operation_time,observed.observed_at);
 IF association.association_kind='UNAUTHORISED_DEVIATION' THEN
   SELECT d.* INTO approval FROM ops.lc_action_binding b JOIN ops.approval_decision d ON d.id=b.approval_decision_id
    WHERE b.action_id=action.id AND d.decision='APPROVED';
   SELECT l.launched_at INTO launch_at FROM ops.lc_launch l WHERE l.action_id=action.id;
   IF action.id IS NOT DISTINCT FROM association.action_id OR action.created_at<=event_at
      OR approval.id IS NULL OR approval.decided_at<=event_at OR launch_at IS NULL OR launch_at<=event_at THEN
     RAISE EXCEPTION 'an unauthorised deviation needs a distinct forward Action and approval' USING ERRCODE='MO092';
   END IF;
 ELSIF association.association_kind='UNRESOLVED_CHANGE' THEN
   IF association.action_id IS NOT NULL AND action.id<>association.action_id THEN
     RAISE EXCEPTION 'the verification belongs to another proposed association' USING ERRCODE='MO092';
   ELSIF association.action_id IS NULL AND action.created_at<=event_at THEN
     RAISE EXCEPTION 'an unassociated change needs a forward governed Action' USING ERRCODE='MO092';
   END IF;
 ELSE
   RAISE EXCEPTION 'a lawful late report is already linked and is not closed here' USING ERRCODE='MO091';
 END IF;
 assessment:=jsonb_build_object('qualification','QUALIFIED','associationId',association.id,
   'associationKind',association.association_kind,'eventObservationId',observed.id,
   'eventTextDigest',observed.text_digest,'resolutionActionId',action.id,
   'resolutionApprovalId',approval.id,'verificationId',verification.id,
   'managementObservationId',management.id,'verifiedAt',verification.verified_at,
   'resolvedAt',clock_timestamp());
 UPDATE ops.lc_late_association SET state='CLOSED',closure_verification_id=verification.id,
   closure_assessment=assessment,updated_at=clock_timestamp(),version=version+1 WHERE id=association.id;
END $$;
REVOKE ALL ON FUNCTION ops.close_lc_late_association(uuid,uuid,text,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.close_lc_late_association(uuid,uuid,text,uuid) TO marketops_app;
REVOKE UPDATE (association_kind,action_id,operation_time,report_time,authority_gap,forward_disposition,
 closure_verification_id,state,updated_at,version) ON ops.lc_late_association FROM marketops_app;

-- ---------------------------------------------------------------------------
-- Shared containment follows only a retained dependency whose exact current
-- cause is used by both Listings.  Independent Listings never enter the path.
-- ---------------------------------------------------------------------------

CREATE FUNCTION ops.lc_listing_uses_isolation_dependency(
 p_org uuid,p_listing uuid,p_kind text,p_reference text,p_at timestamptz) RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT CASE p_kind
  WHEN 'SHARED_TEMPLATE' THEN EXISTS(
    SELECT 1 FROM ops.lc_action a CROSS JOIN LATERAL jsonb_each(a.calibration_dependencies->'values') dependency
     WHERE a.organization_id=p_org AND a.platform_listing_id=p_listing
       AND a.state NOT IN ('CLOSED','CANCELLED')
       AND (ops.lc_action_calibration_recheck(a.id,p_at)->>'state') IN ('CURRENT','UNCHANGED_DEPENDENCIES')
       AND p_reference='lc-calibration-value:'||dependency.key||':'||
         encode(sha256(convert_to(dependency.value::text,'UTF8')),'hex'))
  WHEN 'SHARED_CAMPAIGN' THEN EXISTS(
    SELECT 1 FROM ops.lc_promotion_engagement e WHERE e.organization_id=p_org
      AND e.platform_listing_id=p_listing AND e.state<>'CLEARED' AND e.terms_digest IS NOT NULL
      AND p_reference='lc-promotion-terms:'||e.terms_digest)
  WHEN 'SHARED_VARIANT_SET' THEN EXISTS(
    SELECT 1 FROM core.lc_affected_set s WHERE s.organization_id=p_org
      AND s.platform_listing_id=p_listing AND s.resolution_state='COMPLETE'
      AND s.affected_set_digest=core.lc_listing_affected_set_digest(p_listing)
      AND p_reference ~ '^lc-product-variant:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      AND substring(p_reference from 20)::uuid=ANY(s.product_variant_ids))
  ELSE false END
$$;
REVOKE ALL ON FUNCTION ops.lc_listing_uses_isolation_dependency(uuid,uuid,text,text,timestamptz) FROM PUBLIC;

CREATE FUNCTION ops.lc_shared_containment_has_current_cause() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.cause_class='SHARED_VERSION' AND NOT EXISTS(
   SELECT 1 FROM core.platform_listing listing
   JOIN ops.lc_isolation_dependency dependency
     ON dependency.organization_id=listing.organization_id
    AND dependency.from_listing_id=listing.id
    AND dependency.proof_reference=NEW.evidence_reference
   WHERE listing.organization_id=NEW.organization_id
     AND (NEW.scope_kind='ORGANIZATION'
       OR (NEW.scope_kind='PLATFORM' AND NEW.platform_code=listing.platform_code)
       OR (NEW.scope_kind='STORE' AND NEW.store_id=listing.store_id)
       OR (NEW.scope_kind='LISTING' AND NEW.platform_listing_id=listing.id)
       OR (NEW.scope_kind='BATCH' AND EXISTS(SELECT 1 FROM ops.lc_batch_member member
          JOIN ops.lc_action action ON action.id=member.action_id
          WHERE member.batch_id=NEW.batch_id AND action.platform_listing_id=listing.id
            AND member.sequence_no=(SELECT max(latest.sequence_no) FROM ops.lc_batch_member latest
              WHERE latest.batch_id=member.batch_id AND latest.action_id=member.action_id)
            AND member.membership_state='ACTIVE')))
     AND ops.lc_listing_uses_isolation_dependency(NEW.organization_id,listing.id,
           dependency.dependency_kind,dependency.proof_reference,NEW.stopped_at)
     AND ops.lc_listing_uses_isolation_dependency(NEW.organization_id,dependency.to_listing_id,
           dependency.dependency_kind,dependency.proof_reference,NEW.stopped_at)) THEN
   RAISE EXCEPTION 'shared containment requires a retained dependency and current exact cause on both Listings'
     USING ERRCODE='MO092';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_shared_containment_has_current_cause() FROM PUBLIC;
CREATE TRIGGER lc_shared_containment_has_current_cause BEFORE INSERT ON ops.lc_containment
 FOR EACH ROW EXECUTE FUNCTION ops.lc_shared_containment_has_current_cause();

CREATE OR REPLACE FUNCTION iam.issue_ad_control_invocation_grant(p_purpose text,p_proof_hash text,p_actor uuid,p_org uuid,
 p_provider uuid,p_subject text,p_session text,p_authenticated timestamptz,p_step_up_until timestamptz,
 p_target uuid,p_version uuid,p_backend integer,p_transaction bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,iam,pg_temp AS $$ BEGIN
 IF p_purpose NOT IN ('COMPENSATION_PREVIEW','COMPENSATION_ENDORSE','COMPENSATION_APPROVE',
 'BUNDLE_DRAFT','BUNDLE_ENDORSE','BUNDLE_APPROVE','CONTAINMENT_STOP','AUTHORITY_VERSION_STOP','CONTAINMENT_REENABLE',
 'CONTAINMENT_ATTEST','CONTAINMENT_ENDORSE','MANUAL_POLICY_PUBLISH','MANUAL_PACKET_SELECT',
 'MANUAL_PACKET_ENDORSE','MANUAL_PACKET_APPROVE','MANUAL_EXECUTION_REPORT','MANUAL_EXECUTION_START','MANUAL_INDEPENDENT_VERIFY',
 'LISTING_ACTION_LAUNCH','LISTING_ACTION_REVIEW','LISTING_ACTION_APPROVE','LISTING_MANUAL_VERIFY',
 'LISTING_OCCUPATION_RELEASE','LISTING_CONTAINMENT_STOP','LISTING_CONTAINMENT_ATTEST',
 'LISTING_CONTAINMENT_CONSENT','LISTING_CONTAINMENT_REENABLE','LISTING_PROMOTION_EXIT',
 'LISTING_ISOLATION_DEPENDENCY_RECORD','LISTING_CALIBRATION_PREPARE','LISTING_CALIBRATION_VALIDATE',
 'LISTING_CALIBRATION_ACCEPT','LISTING_CALIBRATION_ACTIVATE') THEN
  RAISE EXCEPTION 'unknown control invocation purpose' USING ERRCODE='MO092'; END IF;
 PERFORM iam.issue_ad_invocation_grant(p_proof_hash,p_actor,p_org,p_provider,p_subject,p_session,
 p_authenticated,p_step_up_until,p_target,p_version,p_backend,p_transaction);
 UPDATE iam.ad_invocation_grant SET purpose=p_purpose WHERE proof_hash=p_proof_hash;
END $$;

CREATE FUNCTION ops.record_lc_isolation_dependency(
 p_id uuid,p_actor uuid,p_org uuid,p_proof text,p_from uuid,p_to uuid,p_kind text,p_reference text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE grant_row iam.ad_invocation_grant%ROWTYPE; from_store uuid; to_store uuid; now_at timestamptz;
BEGIN
 SELECT store_id INTO from_store FROM core.platform_listing WHERE id=p_from AND organization_id=p_org;
 SELECT store_id INTO to_store FROM core.platform_listing WHERE id=p_to AND organization_id=p_org;
 IF from_store IS NULL OR to_store IS NULL OR p_from=p_to
    OR p_kind NOT IN ('SHARED_TEMPLATE','SHARED_VARIANT_SET','SHARED_CAMPAIGN')
    OR length(btrim(coalesce(p_reference,''))) NOT BETWEEN 1 AND 512 THEN
   RAISE EXCEPTION 'bounded exact Listing dependency required' USING ERRCODE='MO092';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(p_org::text));
 now_at:=clock_timestamp();
 grant_row:=ops.consume_ad_control_invocation(p_proof,'LISTING_ISOLATION_DEPENDENCY_RECORD',p_id,p_id);
 IF grant_row.actor_user_id<>p_actor OR grant_row.organization_id<>p_org
    OR NOT ops.lc_actor_holds_action(p_actor,p_org,from_store,'LISTING_ACTION_PREPARE')
    OR NOT ops.lc_actor_holds_action(p_actor,p_org,to_store,'LISTING_ACTION_PREPARE') THEN
   RAISE EXCEPTION 'dependency recorder must match current authority for both Listings' USING ERRCODE='MO092';
 END IF;
 IF NOT ops.lc_listing_uses_isolation_dependency(p_org,p_from,p_kind,p_reference,now_at)
    OR NOT ops.lc_listing_uses_isolation_dependency(p_org,p_to,p_kind,p_reference,now_at) THEN
   RAISE EXCEPTION 'both Listings must currently use the exact shared cause' USING ERRCODE='MO092';
 END IF;
 INSERT INTO ops.lc_isolation_dependency(id,organization_id,from_listing_id,to_listing_id,dependency_kind,
   proof_reference,proven_at,recorded_by_user_id)
 VALUES(p_id,p_org,p_from,p_to,p_kind,p_reference,now_at,p_actor);
 RETURN p_id;
END $$;
REVOKE ALL ON FUNCTION ops.record_lc_isolation_dependency(uuid,uuid,uuid,text,uuid,uuid,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.record_lc_isolation_dependency(uuid,uuid,uuid,text,uuid,uuid,text,text) TO marketops_app;
REVOKE INSERT ON ops.lc_isolation_dependency FROM marketops_app;

CREATE FUNCTION ops.lc_shared_isolation_scope(p_org uuid,p_source uuid)
RETURNS TABLE(root_listing_id uuid,platform_listing_id uuid)
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 WITH RECURSIVE direct_causes(root_listing_id,dependency_kind,proof_reference) AS (
   SELECT DISTINCT listing.id,d.dependency_kind,c.evidence_reference
   FROM ops.lc_containment c JOIN core.platform_listing listing ON listing.organization_id=c.organization_id
   JOIN ops.lc_isolation_dependency d ON d.organization_id=c.organization_id
      AND d.from_listing_id=listing.id AND d.proof_reference=c.evidence_reference
   WHERE c.organization_id=p_org AND c.state='ACTIVE' AND c.cause_class='SHARED_VERSION'
     AND (p_source IS NULL OR listing.id=p_source)
     AND (c.scope_kind='ORGANIZATION'
       OR (c.scope_kind='PLATFORM' AND c.platform_code=listing.platform_code)
       OR (c.scope_kind='STORE' AND c.store_id=listing.store_id)
       OR (c.scope_kind='LISTING' AND c.platform_listing_id=listing.id)
       OR (c.scope_kind='BATCH' AND EXISTS(SELECT 1 FROM ops.lc_batch_member member
          JOIN ops.lc_action action ON action.id=member.action_id
          WHERE member.batch_id=c.batch_id AND action.platform_listing_id=listing.id
            AND member.sequence_no=(SELECT max(latest.sequence_no) FROM ops.lc_batch_member latest
              WHERE latest.batch_id=member.batch_id AND latest.action_id=member.action_id)
            AND member.membership_state='ACTIVE')))
 ), walk(root_listing_id,platform_listing_id,dependency_kind,proof_reference) AS (
   SELECT root_listing_id,root_listing_id,dependency_kind,proof_reference
     FROM direct_causes
   UNION
   SELECT walk.root_listing_id,d.to_listing_id,walk.dependency_kind,walk.proof_reference
     FROM walk JOIN ops.lc_isolation_dependency d ON d.organization_id=p_org
       AND d.from_listing_id=walk.platform_listing_id
       AND d.dependency_kind=walk.dependency_kind AND d.proof_reference=walk.proof_reference
    WHERE ops.lc_listing_uses_isolation_dependency(p_org,d.to_listing_id,d.dependency_kind,
            d.proof_reference,statement_timestamp())
 )
 SELECT DISTINCT walk.root_listing_id,walk.platform_listing_id FROM walk
$$;
REVOKE ALL ON FUNCTION ops.lc_shared_isolation_scope(uuid,uuid) FROM PUBLIC;

CREATE FUNCTION ops.lc_current_isolation_scope(p_org uuid,p_source uuid)
RETURNS TABLE(platform_listing_id uuid) LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
 WITH RECURSIVE walk(platform_listing_id,dependency_kind,proof_reference) AS (
   SELECT d.from_listing_id,d.dependency_kind,d.proof_reference
     FROM ops.lc_isolation_dependency d
    WHERE d.organization_id=p_org AND d.from_listing_id=p_source
      AND ops.lc_listing_uses_isolation_dependency(p_org,d.from_listing_id,d.dependency_kind,
            d.proof_reference,statement_timestamp())
   UNION
   SELECT d.to_listing_id,walk.dependency_kind,walk.proof_reference
     FROM walk JOIN ops.lc_isolation_dependency d ON d.organization_id=p_org
       AND d.from_listing_id=walk.platform_listing_id
       AND d.dependency_kind=walk.dependency_kind AND d.proof_reference=walk.proof_reference
    WHERE ops.lc_listing_uses_isolation_dependency(p_org,d.to_listing_id,d.dependency_kind,
            d.proof_reference,statement_timestamp())
 )
 SELECT p_source WHERE EXISTS(SELECT 1 FROM core.platform_listing l WHERE l.id=p_source AND l.organization_id=p_org)
 UNION SELECT platform_listing_id FROM walk
$$;
REVOKE ALL ON FUNCTION ops.lc_current_isolation_scope(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_current_isolation_scope(uuid,uuid) TO marketops_app;

ALTER FUNCTION ops.lc_scope_contained(uuid,uuid) RENAME TO lc_scope_contained_v0117;
CREATE FUNCTION ops.lc_scope_contained(p_org uuid,p_listing uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog AS $$
 SELECT ops.lc_scope_contained_v0117(p_org,p_listing) OR EXISTS(
   SELECT 1 FROM ops.lc_shared_isolation_scope(p_org,NULL) scope
    WHERE scope.platform_listing_id=p_listing AND scope.root_listing_id<>p_listing)
$$;
REVOKE ALL ON FUNCTION ops.lc_scope_contained(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_scope_contained(uuid,uuid) TO marketops_app;

-- V0117 already verifies invocation identity, current technical/business roles,
-- and exact Outcome repair.  Shared causes add one further current-fact check:
-- every dependent consumer must have stopped using the exact cause before release.
ALTER FUNCTION ops.reenable_lc_containment(uuid,uuid,text) RENAME TO reenable_lc_containment_v0117;
REVOKE ALL ON FUNCTION ops.reenable_lc_containment_v0117(uuid,uuid,text) FROM PUBLIC,marketops_app;
CREATE FUNCTION ops.reenable_lc_containment(p_containment uuid,p_actor uuid,p_proof text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
DECLARE containment ops.lc_containment%ROWTYPE; repair ops.lc_containment_attestation%ROWTYPE;
 consent ops.lc_containment_attestation%ROWTYPE; cause_kind text;
BEGIN
 SELECT * INTO containment FROM ops.lc_containment WHERE id=p_containment FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'containment does not exist' USING ERRCODE='MO090'; END IF;
 PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(containment.organization_id::text));
 IF containment.cause_class='SHARED_VERSION' THEN
   SELECT dependency_kind INTO cause_kind FROM ops.lc_isolation_dependency d
    JOIN ops.lc_shared_isolation_scope(containment.organization_id,NULL) scope
      ON scope.root_listing_id=d.from_listing_id
    WHERE d.organization_id=containment.organization_id
      AND d.proof_reference=containment.evidence_reference LIMIT 1;
   SELECT * INTO repair FROM ops.lc_containment_attestation WHERE containment_id=containment.id
      AND attestation_kind='REPAIR_ATTESTATION';
   SELECT * INTO consent FROM ops.lc_containment_attestation WHERE containment_id=containment.id
      AND attestation_kind='BUSINESS_CONSENT';
   IF cause_kind IS NULL OR repair.id IS NULL OR consent.id IS NULL
      OR repair.attested_at<=containment.stopped_at OR consent.attested_at<repair.attested_at
      OR EXISTS(SELECT 1 FROM ops.lc_shared_isolation_scope(containment.organization_id,NULL) scope
          WHERE EXISTS(SELECT 1 FROM ops.lc_isolation_dependency d
             WHERE d.organization_id=containment.organization_id
               AND d.proof_reference=containment.evidence_reference
               AND d.from_listing_id=scope.root_listing_id
               AND ops.lc_listing_uses_isolation_dependency(containment.organization_id,
                    scope.platform_listing_id,d.dependency_kind,d.proof_reference,statement_timestamp()))) THEN
     RAISE EXCEPTION 'shared cause is unqualified, still used, or not subsequently and independently released'
       USING ERRCODE='MO092';
   END IF;
 END IF;
 PERFORM ops.reenable_lc_containment_v0117(p_containment,p_actor,p_proof);
END $$;
REVOKE ALL ON FUNCTION ops.reenable_lc_containment(uuid,uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.reenable_lc_containment(uuid,uuid,text) TO marketops_app;
