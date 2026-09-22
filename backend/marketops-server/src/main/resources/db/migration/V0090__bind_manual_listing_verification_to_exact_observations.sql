-- A display reference is evidence for one listing/version/instant. Historical
-- verifications remain readable; no exact-observation binding is invented.
ALTER TABLE ops.lc_manual_verification ADD COLUMN observation_binding jsonb
    CHECK (observation_binding IS NULL OR jsonb_typeof(observation_binding)='object');

CREATE FUNCTION ops.lc_manual_verification_binds_observations()
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
REVOKE ALL ON FUNCTION ops.lc_manual_verification_binds_observations() FROM PUBLIC;
CREATE TRIGGER lc_manual_verification_binds_observations BEFORE INSERT ON ops.lc_manual_verification
    FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_verification_binds_observations();

CREATE FUNCTION ops.lc_manual_verified_action_requires_bound_observations()
RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,ops,pg_temp
AS $$
BEGIN
    IF NEW.execution_path='MANUAL' AND NEW.state='VERIFIED' AND OLD.state<>NEW.state AND NOT EXISTS(
        SELECT 1 FROM ops.lc_manual_verification verification
        JOIN ops.lc_manual_packet packet ON packet.id=verification.packet_id
        WHERE packet.action_id=NEW.id AND verification.management_match='MATCHED_TARGET'
          AND verification.observation_binding->>'targetTextDigest'=NEW.target_text_digest
          AND verification.observation_binding->>'actionId'=NEW.id::text) THEN
        RAISE EXCEPTION 'manual verification cannot reuse unbound historical observations' USING ERRCODE='MO092';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION ops.lc_manual_verified_action_requires_bound_observations() FROM PUBLIC;
CREATE TRIGGER lc_manual_verified_action_requires_bound_observations BEFORE UPDATE ON ops.lc_action
    FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_verified_action_requires_bound_observations();
