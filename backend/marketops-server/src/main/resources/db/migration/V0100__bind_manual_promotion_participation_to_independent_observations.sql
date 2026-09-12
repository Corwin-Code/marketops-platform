-- Roots 014/016: exact manual promotion participation observation, not exit or residual release.
CREATE TABLE core.lc_promotion_observation (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, platform_listing_id uuid NOT NULL,
 provenance_id uuid NOT NULL REFERENCES core.fact_provenance(id),
 observed_at timestamptz NOT NULL, acquired_at timestamptz NOT NULL,
 participation_state text NOT NULL CHECK(participation_state IN ('PARTICIPATING','NOT_PARTICIPATING','UNKNOWN')),
 engagement_kind text NOT NULL CHECK(engagement_kind IN ('OFFICIAL_PROMOTION_PARTICIPATION','SELLER_DIRECT_DISCOUNT')),
 native_promotion_key text NOT NULL CHECK(length(btrim(native_promotion_key)) BETWEEN 1 AND 128),
 declaration jsonb,
 CHECK(declaration IS NULL OR (declaration->>'engagementKind'=engagement_kind AND declaration->>'nativePromotionKey'=native_promotion_key)),
 declaration_digest text GENERATED ALWAYS AS (ops.lc_promotion_terms_digest(declaration)) STORED,
 evidence_reference text NOT NULL CHECK(length(btrim(evidence_reference)) BETWEEN 1 AND 512),
 UNIQUE(id,organization_id),
 FOREIGN KEY(platform_listing_id,organization_id) REFERENCES core.platform_listing(id,organization_id),
 CHECK(acquired_at>=observed_at)
);
CREATE INDEX lc_promotion_observation_listing_ix ON core.lc_promotion_observation(platform_listing_id,observed_at DESC);
GRANT SELECT,INSERT ON core.lc_promotion_observation TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
 VALUES('core','lc_promotion_observation','NO_ROUTE',NULL,'append-only exact native promotion participation observation; not release authority');

ALTER TABLE ops.lc_manual_verification ADD COLUMN promotion_observation_id uuid
 REFERENCES core.lc_promotion_observation(id);
ALTER TABLE ops.lc_manual_verification DROP CONSTRAINT lc_manual_verification_management_evidence_ck;
ALTER TABLE ops.lc_manual_verification ADD CONSTRAINT lc_manual_verification_management_evidence_ck CHECK(
 (management_observation_id IS NULL OR promotion_observation_id IS NULL)
 AND (management_match='UNKNOWN' OR management_observation_id IS NOT NULL OR promotion_observation_id IS NOT NULL));

CREATE FUNCTION ops.lc_bind_promotion_participation(p_verification ops.lc_manual_verification) RETURNS jsonb
LANGUAGE plpgsql SET search_path=pg_catalog,ops,core,pg_temp SET timezone='UTC' SET DateStyle='ISO, YMD' AS $$
DECLARE packet ops.lc_manual_packet%ROWTYPE; action ops.lc_action%ROWTYPE;
 observed core.lc_promotion_observation%ROWTYPE; provenance core.fact_provenance%ROWTYPE;
 operation_at timestamptz; matches_target boolean;
BEGIN
 SELECT * INTO STRICT packet FROM ops.lc_manual_packet WHERE id=p_verification.packet_id;
 SELECT * INTO STRICT action FROM ops.lc_action WHERE id=packet.action_id;
 IF action.action_kind<>'LISTING_PROMOTION_ACTION' OR action.promotion_terms_digest IS NULL
   OR packet.promotion_terms_digest IS DISTINCT FROM action.promotion_terms_digest
   OR packet.organization_id<>p_verification.organization_id OR p_verification.management_observation_id IS NOT NULL
   OR p_verification.display_observation_id IS NOT NULL OR p_verification.display_state<>'UNKNOWN'
   OR p_verification.management_match='MATCHED_PRIOR' THEN
   RAISE EXCEPTION 'promotion participation uses its own exact evidence, not description or release inference' USING ERRCODE='MO092';
 END IF;
 SELECT max(operation_time) INTO operation_at FROM ops.lc_manual_report
   WHERE packet_id=packet.id AND reporter_user_id=packet.executor_user_id AND reported_at<=p_verification.verified_at;
 IF operation_at IS NULL OR operation_at<packet.issued_at OR operation_at>packet.expires_at THEN
   RAISE EXCEPTION 'promotion participation needs the reported operation within the exact packet authority' USING ERRCODE='MO092';
 END IF;
 IF p_verification.promotion_observation_id IS NULL THEN
   IF p_verification.management_match<>'UNKNOWN' THEN
     RAISE EXCEPTION 'participation qualification needs an observation' USING ERRCODE='MO093';
   END IF;
   RETURN jsonb_build_object('actionId',action.id,'packetId',packet.id,'promotionTermsDigest',action.promotion_terms_digest,
     'purpose','PARTICIPATION','claimExtent','UNKNOWN');
 END IF;
 SELECT * INTO STRICT observed FROM core.lc_promotion_observation WHERE id=p_verification.promotion_observation_id;
 SELECT * INTO STRICT provenance FROM core.fact_provenance WHERE id=observed.provenance_id;
 IF observed.organization_id<>action.organization_id OR provenance.organization_id<>action.organization_id
   OR observed.platform_listing_id<>action.platform_listing_id OR observed.observed_at<operation_at
   OR observed.acquired_at>p_verification.verified_at
   OR provenance.source_time IS DISTINCT FROM observed.observed_at
   OR provenance.ingestion_time IS DISTINCT FROM observed.acquired_at
   OR observed.native_promotion_key IS DISTINCT FROM action.promotion_terms->>'nativePromotionKey'
   OR observed.engagement_kind IS DISTINCT FROM action.promotion_terms->>'engagementKind' THEN
   RAISE EXCEPTION 'promotion observation is outside exact native identity, operation or custody time' USING ERRCODE='MO092';
 END IF;
 IF provenance.source_kind='MANUAL_ENTRY' THEN
   IF p_verification.verification_basis<>'INDEPENDENT_HUMAN' OR p_verification.verifier_user_id IS NULL
     OR NOT ops.lc_actor_holds_action(p_verification.verifier_user_id,action.organization_id,action.store_id,'LISTING_MANUAL_VERIFY')
     OR provenance.recorded_by_user_id IS NULL
     OR provenance.recorded_by_user_id=packet.executor_user_id
     OR EXISTS(SELECT 1 FROM ops.lc_manual_report r WHERE r.packet_id=packet.id
          AND r.reporter_user_id=provenance.recorded_by_user_id) THEN
     RAISE EXCEPTION 'executor reports cannot become independent promotion evidence' USING ERRCODE='MO092';
   END IF;
 ELSIF provenance.source_kind<>'MARKETPLACE_RAW' OR provenance.raw_observation_id IS NULL
   OR p_verification.verification_basis<>'OFFICIAL_EVIDENCE' THEN
   RAISE EXCEPTION 'official participation needs retained marketplace raw custody' USING ERRCODE='MO092';
 END IF;
 matches_target:=coalesce(observed.participation_state='PARTICIPATING' AND observed.declaration_digest=action.promotion_terms_digest,false);
 IF (p_verification.management_match='MATCHED_TARGET' AND NOT matches_target)
   OR (p_verification.management_match='DIFFERENT' AND (matches_target OR observed.participation_state='UNKNOWN'
       OR (observed.participation_state='PARTICIPATING' AND observed.declaration_digest IS NULL))) THEN
   RAISE EXCEPTION 'participation claim contradicts the exact observation' USING ERRCODE='MO093';
 END IF;
 RETURN jsonb_build_object('actionId',action.id,'listingId',action.platform_listing_id,'packetId',packet.id,
   'promotionTermsDigest',action.promotion_terms_digest,'promotionObservationId',observed.id,
   'observedDeclarationDigest',observed.declaration_digest,'participationState',observed.participation_state,
   'observationDigest',encode(sha256(convert_to(to_jsonb(observed)::text,'UTF8')),'hex'),
   'operationAt',operation_at,'observedAt',observed.observed_at,'verifiedAt',p_verification.verified_at,
   'purpose','PARTICIPATION','claimExtent','OBSERVED_INSTANT_ONLY');
END
$$;
REVOKE ALL ON FUNCTION ops.lc_bind_promotion_participation(ops.lc_manual_verification) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_bind_promotion_participation(ops.lc_manual_verification) TO marketops_app;

CREATE OR REPLACE FUNCTION ops.lc_manual_verification_binds_observations()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,ops,core,pg_temp
SET timezone='UTC'
SET DateStyle='ISO, YMD'
AS $$
DECLARE
    packet ops.lc_manual_packet%ROWTYPE;
    action ops.lc_action%ROWTYPE;
    management core.lc_description_observation%ROWTYPE;
    display core.lc_display_observation%ROWTYPE;
    provenance core.fact_provenance%ROWTYPE;
    after_operation timestamptz;
BEGIN
    SELECT * INTO STRICT packet FROM ops.lc_manual_packet WHERE id=NEW.packet_id;
    SELECT * INTO STRICT action FROM ops.lc_action WHERE id=packet.action_id;
    IF action.action_kind='LISTING_PROMOTION_ACTION' THEN
        NEW.observation_binding:=ops.lc_bind_promotion_participation(NEW);
        RETURN NEW;
    ELSIF NEW.promotion_observation_id IS NOT NULL THEN
        RAISE EXCEPTION 'description verification cannot borrow promotion evidence' USING ERRCODE='MO092';
    END IF;
    SELECT greatest(packet.issued_at,coalesce(max(r.operation_time),packet.issued_at))
        INTO after_operation FROM ops.lc_manual_report r WHERE r.packet_id=packet.id AND r.reported_at<=NEW.verified_at;
    IF packet.organization_id<>NEW.organization_id OR NEW.verified_at<after_operation THEN
        RAISE EXCEPTION 'verification belongs to the exact packet after its recorded operation' USING ERRCODE='MO092';
    END IF;
    IF NEW.management_observation_id IS NOT NULL THEN
        SELECT * INTO STRICT management FROM core.lc_description_observation WHERE id=NEW.management_observation_id;
        SELECT * INTO STRICT provenance FROM core.fact_provenance WHERE id=management.provenance_id;
        IF management.organization_id<>NEW.organization_id OR provenance.organization_id<>NEW.organization_id
            OR management.platform_listing_id<>action.platform_listing_id
            OR management.observed_at<after_operation OR management.acquired_at>NEW.verified_at THEN
            RAISE EXCEPTION 'management observation is outside the exact operation identity or evidence time' USING ERRCODE='MO092';
        END IF;
        IF provenance.source_kind='MANUAL_ENTRY' AND (provenance.recorded_by_user_id=packet.executor_user_id
            OR EXISTS(SELECT 1 FROM ops.lc_manual_report r WHERE r.packet_id=packet.id AND r.reporter_user_id=provenance.recorded_by_user_id)) THEN
            RAISE EXCEPTION 'executor-entered management text is not independent verification evidence' USING ERRCODE='MO092';
        END IF;
    END IF;
    IF NEW.display_observation_id IS NOT NULL THEN
        SELECT * INTO STRICT display FROM core.lc_display_observation WHERE id=NEW.display_observation_id;
        SELECT * INTO STRICT provenance FROM core.fact_provenance WHERE id=display.provenance_id;
        IF display.organization_id<>NEW.organization_id OR provenance.organization_id<>NEW.organization_id
            OR display.platform_listing_id<>action.platform_listing_id
            OR display.observed_at<after_operation OR display.acquired_at>NEW.verified_at THEN
            RAISE EXCEPTION 'customer display observation is outside the exact operation identity or evidence time' USING ERRCODE='MO092';
        END IF;
        IF display.display_state<>NEW.display_state OR
            (NEW.display_state='DISPLAYED' AND display.displayed_text_digest IS DISTINCT FROM action.target_text_digest) THEN
            RAISE EXCEPTION 'claimed customer target display contradicts the retained observation' USING ERRCODE='MO093';
        END IF;
        IF display.evidence_grade='INDEPENDENT_HUMAN' THEN
            IF NEW.verification_basis='OFFICIAL_EVIDENCE' OR display.observer_user_id=packet.executor_user_id
                OR EXISTS(SELECT 1 FROM ops.lc_manual_report r WHERE r.packet_id=packet.id AND r.reporter_user_id=display.observer_user_id)
                OR provenance.source_kind<>'MANUAL_ENTRY' OR provenance.recorded_by_user_id IS DISTINCT FROM display.observer_user_id THEN
                RAISE EXCEPTION 'customer display needs independently captured human evidence' USING ERRCODE='MO092';
            END IF;
        ELSIF provenance.source_kind<>'MARKETPLACE_RAW' OR provenance.raw_observation_id IS NULL THEN
            RAISE EXCEPTION 'official customer display needs retained marketplace source bytes' USING ERRCODE='MO092';
        END IF;
    ELSIF NEW.display_state<>'UNKNOWN' THEN
        RAISE EXCEPTION 'customer display requires its own exact observation' USING ERRCODE='MO093';
    END IF;
    NEW.observation_binding:=jsonb_build_object(
        'actionId',action.id,'listingId',action.platform_listing_id,'targetTextDigest',action.target_text_digest,
        'packetId',packet.id,'afterOperation',after_operation,'verifiedAt',NEW.verified_at,
        'managementObservationId',NEW.management_observation_id,
        'managementObservationDigest',CASE WHEN NEW.management_observation_id IS NULL THEN NULL
            ELSE encode(sha256(convert_to(to_jsonb(management)::text,'UTF8')),'hex') END,
        'displayObservationId',NEW.display_observation_id,
        'displayObservationDigest',CASE WHEN NEW.display_observation_id IS NULL THEN NULL
            ELSE encode(sha256(convert_to(to_jsonb(display)::text,'UTF8')),'hex') END,
        'displayClaimExtent','OBSERVED_INSTANT_ONLY');
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION ops.lc_manual_verification_is_independent()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
DECLARE packet ops.lc_manual_packet%ROWTYPE; action ops.lc_action%ROWTYPE; observed text;
BEGIN
    SELECT * INTO packet FROM ops.lc_manual_packet WHERE id = NEW.packet_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'verification names an unknown packet' USING ERRCODE = 'MO090'; END IF;
    SELECT * INTO action FROM ops.lc_action WHERE id = packet.action_id;
    IF NEW.verifier_user_id IS NOT NULL AND (NEW.verifier_user_id = packet.executor_user_id
        OR EXISTS (SELECT 1 FROM ops.lc_manual_report r
                    WHERE r.packet_id = packet.id AND r.reporter_user_id = NEW.verifier_user_id)) THEN
        RAISE EXCEPTION 'the executor does not verify the execution' USING ERRCODE = 'MO092';
    END IF;
    IF action.action_kind='LISTING_PROMOTION_ACTION' THEN RETURN NEW; END IF;
    IF NEW.verification_basis = 'OFFICIAL_EVIDENCE' AND NOT EXISTS (
        SELECT 1 FROM core.lc_description_observation o
          JOIN core.fact_provenance p ON p.id = o.provenance_id
         WHERE o.id = NEW.management_observation_id AND p.source_kind = 'MARKETPLACE_RAW') THEN
        RAISE EXCEPTION 'official evidence is a marketplace-sourced observation' USING ERRCODE = 'MO092';
    END IF;
    IF NEW.management_observation_id IS NOT NULL THEN
        SELECT o.text_digest INTO observed FROM core.lc_description_observation o
         WHERE o.id = NEW.management_observation_id AND o.platform_listing_id = action.platform_listing_id
           AND o.observed_at >= packet.issued_at;
        IF observed IS NULL THEN
            RAISE EXCEPTION 'a verification observation is of this listing after the packet was issued'
                USING ERRCODE = 'MO092';
        END IF;
        IF (NEW.management_match = 'MATCHED_TARGET' AND observed IS DISTINCT FROM action.target_text_digest)
            OR (NEW.management_match = 'MATCHED_PRIOR' AND observed IS DISTINCT FROM action.current_text_digest)
            OR (NEW.management_match = 'DIFFERENT'
                AND (observed = action.target_text_digest OR observed = action.current_text_digest)) THEN
            RAISE EXCEPTION 'the claimed management match contradicts the observation digest'
                USING ERRCODE = 'MO093';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION ops.lc_manual_verified_action_requires_bound_observations()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,ops,pg_temp
AS $$
BEGIN
    IF NEW.execution_path='MANUAL' AND NEW.state='VERIFIED' AND OLD.state<>NEW.state AND NOT EXISTS(
        SELECT 1 FROM ops.lc_manual_verification verification
        JOIN ops.lc_manual_packet packet ON packet.id=verification.packet_id
        WHERE packet.action_id=NEW.id AND verification.management_match='MATCHED_TARGET'
          AND ((NEW.action_kind='LISTING_DESCRIPTION_CHANGE'
                AND verification.observation_binding->>'targetTextDigest'=NEW.target_text_digest)
            OR (NEW.action_kind='LISTING_PROMOTION_ACTION'
                AND verification.observation_binding->>'purpose'='PARTICIPATION'
                AND verification.observation_binding->>'claimExtent'='OBSERVED_INSTANT_ONLY'
                AND verification.observation_binding->>'participationState'='PARTICIPATING'
                AND verification.observation_binding->>'promotionTermsDigest'=ops.lc_promotion_terms_digest(NEW.promotion_terms)))
          AND verification.observation_binding->>'actionId'=NEW.id::text) THEN
        RAISE EXCEPTION 'manual verification cannot reuse unbound historical observations' USING ERRCODE='MO092';
    END IF;
    RETURN NEW;
END
$$;
