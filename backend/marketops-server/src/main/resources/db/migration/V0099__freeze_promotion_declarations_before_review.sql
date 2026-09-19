-- Root 014: freeze the existing bounded commercial declaration before review/approval.
-- This is exact identity, not a provider-status, fee-qualification or release attestation.
CREATE FUNCTION ops.lc_promotion_terms_digest(p_terms jsonb) RETURNS text
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE part jsonb;
BEGIN
 IF p_terms IS NULL THEN RETURN NULL; END IF;
 IF jsonb_typeof(p_terms)<>'object' OR octet_length(p_terms::text)>131072 THEN
   RAISE EXCEPTION 'bounded promotion declaration required' USING ERRCODE='MO036';
 END IF;
 IF (SELECT count(*) FROM jsonb_object_keys(p_terms))<>7
   OR EXISTS(SELECT 1 FROM jsonb_object_keys(p_terms) k WHERE k NOT IN
     ('engagementKind','nativePromotionKey','terms','priceFreeze','autoParticipation','termsEvidenceReference','obligations'))
   OR coalesce(p_terms->>'engagementKind','') NOT IN ('OFFICIAL_PROMOTION_PARTICIPATION','SELLER_DIRECT_DISCOUNT')
   OR jsonb_typeof(p_terms->'nativePromotionKey') IS DISTINCT FROM 'string'
   OR length(btrim(p_terms->>'nativePromotionKey'))=0 OR length(p_terms->>'nativePromotionKey')>128
   OR jsonb_typeof(p_terms->'termsEvidenceReference') IS DISTINCT FROM 'string'
   OR length(btrim(p_terms->>'termsEvidenceReference'))=0 OR length(p_terms->>'termsEvidenceReference')>512
   OR jsonb_typeof(p_terms->'priceFreeze') IS DISTINCT FROM 'boolean'
   OR jsonb_typeof(p_terms->'autoParticipation') IS DISTINCT FROM 'boolean' THEN
   RAISE EXCEPTION 'exact promotion declaration shape required' USING ERRCODE='MO036';
 END IF;
 FOREACH part IN ARRAY ARRAY[p_terms->'terms',p_terms->'obligations'] LOOP
   IF jsonb_typeof(part) IS DISTINCT FROM 'object' THEN
     RAISE EXCEPTION 'promotion terms and obligations are explicit maps' USING ERRCODE='MO036';
   END IF;
   IF (SELECT count(*) FROM jsonb_object_keys(part)) NOT BETWEEN 1 AND 64
     OR EXISTS(SELECT 1 FROM jsonb_each(part) e WHERE length(btrim(e.key))=0 OR length(e.key)>128
       OR jsonb_typeof(e.value)<>'string' OR length(btrim(e.value#>>'{}'))=0 OR length(e.value#>>'{}')>512) THEN
     RAISE EXCEPTION 'bounded explicit promotion values required' USING ERRCODE='MO036';
   END IF;
 END LOOP;
 RETURN encode(sha256(convert_to(p_terms::text,'UTF8')),'hex');
END
$$;
REVOKE ALL ON FUNCTION ops.lc_promotion_terms_digest(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_promotion_terms_digest(jsonb) TO marketops_app;
ALTER TABLE ops.lc_action ADD COLUMN promotion_terms jsonb;
ALTER TABLE ops.lc_action ADD COLUMN promotion_terms_digest text
 GENERATED ALWAYS AS (ops.lc_promotion_terms_digest(promotion_terms)) STORED;

CREATE FUNCTION ops.lc_action_binds_promotion_terms() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE digest text; candidate_kind text;
BEGIN
 IF TG_OP='UPDATE' AND NEW.promotion_terms IS DISTINCT FROM OLD.promotion_terms THEN
   RAISE EXCEPTION 'promotion declaration is immutable; prepare a new exact action' USING ERRCODE='MO092';
 END IF;
 IF NEW.action_kind<>'LISTING_PROMOTION_ACTION' THEN
   IF NEW.promotion_terms IS NOT NULL THEN
     RAISE EXCEPTION 'a description action cannot carry promotion terms' USING ERRCODE='MO092';
   END IF;
   RETURN NEW;
 END IF;
 -- Old NULL is historical evidence, never silently backfilled. Closing remains possible.
 IF TG_OP='INSERT' OR (TG_OP='UPDATE' AND NEW.state IS DISTINCT FROM OLD.state
       AND NEW.state IN ('REVIEWED','APPROVED','LAUNCHED')) THEN
   IF NEW.promotion_terms IS NULL OR NEW.execution_path<>'MANUAL' THEN
     RAISE EXCEPTION 'promotion declaration is frozen before review and manual launch' USING ERRCODE='MO092';
   END IF;
   digest:=ops.lc_promotion_terms_digest(NEW.promotion_terms);
   SELECT c.candidate_kind INTO candidate_kind FROM ops.lc_candidate c WHERE c.id=NEW.candidate_id;
   IF candidate_kind IS DISTINCT FROM NEW.promotion_terms->>'engagementKind'
     OR NOT EXISTS(SELECT 1 FROM ops.recommendation rec WHERE rec.id=NEW.recommendation_id
        AND rec.organization_id=NEW.organization_id AND rec.proposed_parameters->>'promotionTermsDigest'=digest) THEN
     RAISE EXCEPTION 'promotion candidate, declaration and recommendation identity disagree' USING ERRCODE='MO092';
   END IF;
 END IF;
 RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION ops.lc_action_binds_promotion_terms() FROM PUBLIC;
CREATE TRIGGER lc_action_binds_promotion_terms BEFORE INSERT OR UPDATE ON ops.lc_action
 FOR EACH ROW EXECUTE FUNCTION ops.lc_action_binds_promotion_terms();

ALTER TABLE ops.lc_manual_packet ADD COLUMN promotion_terms_digest text;
CREATE FUNCTION ops.lc_manual_packet_binds_promotion_terms() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE action ops.lc_action%ROWTYPE;
BEGIN
 SELECT * INTO STRICT action FROM ops.lc_action WHERE id=NEW.action_id;
 IF action.action_kind='LISTING_PROMOTION_ACTION' THEN
   IF action.promotion_terms_digest IS NULL THEN
     RAISE EXCEPTION 'historical unbound promotion cannot acquire a new execution packet' USING ERRCODE='MO092';
   END IF;
   IF NEW.promotion_terms_digest IS NOT NULL AND NEW.promotion_terms_digest<>action.promotion_terms_digest THEN
     RAISE EXCEPTION 'packet names different promotion terms' USING ERRCODE='MO092';
   END IF;
   NEW.promotion_terms_digest:=action.promotion_terms_digest;
 END IF;
 RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION ops.lc_manual_packet_binds_promotion_terms() FROM PUBLIC;
CREATE TRIGGER lc_manual_packet_binds_promotion_terms BEFORE INSERT ON ops.lc_manual_packet
 FOR EACH ROW EXECUTE FUNCTION ops.lc_manual_packet_binds_promotion_terms();

CREATE FUNCTION ops.lc_promotion_entry_matches_approved_declaration() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,ops,pg_temp AS $$
DECLARE action ops.lc_action%ROWTYPE; actual jsonb;
BEGIN
 IF TG_OP='UPDATE' THEN
   IF OLD.action_id IS NOT NULL AND ROW(NEW.organization_id,NEW.store_id,NEW.platform_listing_id,
       NEW.action_id,NEW.engagement_kind,NEW.native_promotion_key,NEW.terms,NEW.price_freeze,
       NEW.auto_participation,NEW.terms_evidence_reference,NEW.obligations,NEW.adopted)
     IS DISTINCT FROM ROW(OLD.organization_id,OLD.store_id,OLD.platform_listing_id,
       OLD.action_id,OLD.engagement_kind,OLD.native_promotion_key,OLD.terms,OLD.price_freeze,
       OLD.auto_participation,OLD.terms_evidence_reference,OLD.obligations,OLD.adopted) THEN
     RAISE EXCEPTION 'bound commercial declaration is immutable; changed terms need new authority' USING ERRCODE='MO092';
   END IF;
   IF NEW.action_id IS NOT DISTINCT FROM OLD.action_id THEN RETURN NEW; END IF;
 END IF;
 IF NEW.action_id IS NULL THEN RETURN NEW; END IF; -- Adoption remains a separate observed-fact path.
 SELECT * INTO STRICT action FROM ops.lc_action WHERE id=NEW.action_id;
 actual:=jsonb_build_object('engagementKind',NEW.engagement_kind,'nativePromotionKey',NEW.native_promotion_key,
   'terms',NEW.terms,'priceFreeze',NEW.price_freeze,'autoParticipation',NEW.auto_participation,
   'termsEvidenceReference',NEW.terms_evidence_reference,'obligations',NEW.obligations);
 IF action.action_kind<>'LISTING_PROMOTION_ACTION' OR action.execution_path<>'MANUAL'
   OR action.state NOT IN ('LAUNCHED','VERIFIED') OR action.promotion_terms IS DISTINCT FROM actual
   OR action.organization_id<>NEW.organization_id OR action.platform_listing_id<>NEW.platform_listing_id
   OR action.store_id<>NEW.store_id THEN
   RAISE EXCEPTION 'entry cannot acquire the authority of different approved promotion terms' USING ERRCODE='MO092';
 END IF;
 RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION ops.lc_promotion_entry_matches_approved_declaration() FROM PUBLIC;
CREATE TRIGGER lc_promotion_entry_matches_approved_declaration BEFORE INSERT OR UPDATE ON ops.lc_promotion_engagement
 FOR EACH ROW EXECUTE FUNCTION ops.lc_promotion_entry_matches_approved_declaration();
