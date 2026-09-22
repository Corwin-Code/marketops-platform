-- Root 015: exact activity fixed fee and Listing commercial amounts through existing finance intake.
-- No provider write, new ledger or production value is created.
ALTER TABLE core.finance_input_version
 ADD COLUMN promotion_kind text,
 ADD COLUMN native_promotion_key text,
 ADD COLUMN promotion_listing_ref_id uuid,
 ADD COLUMN promotion_terms_digest text,
 ADD CONSTRAINT finance_input_promotion_listing_fk FOREIGN KEY(promotion_listing_ref_id,organization_id)
   REFERENCES core.platform_listing(id,organization_id);
ALTER TABLE core.finance_input_version DROP CONSTRAINT finance_input_version_code_ck;
ALTER TABLE core.finance_input_version ADD CONSTRAINT finance_input_version_code_ck CHECK(input_code IN (
 'VARIABLE_TAX_RATE','PAYMENT_PROCESSING_RATE','RETURN_HANDLING_UNIT_COST','INBOUND_LOGISTICS_UNIT_COST',
 'REQUIRED_PROFIT_PER_UNIT','SAFETY_BUFFER_PER_UNIT','PROMOTION_FIXED_FEE',
 'PROMOTION_BUYER_PAYMENT_PER_UNIT','PROMOTION_SELLER_REVENUE_PER_UNIT','PROMOTION_PLATFORM_COMPENSATION_PER_UNIT'));
ALTER TABLE core.finance_input_version DROP CONSTRAINT finance_input_version_scope_ck;
ALTER TABLE core.finance_input_version ADD CONSTRAINT finance_input_version_scope_ck
 CHECK(scope_kind IN ('ORGANIZATION','STORE','PRODUCT_VARIANT','PROMOTION'));
ALTER TABLE core.finance_input_version DROP CONSTRAINT finance_input_version_scope_matrix_ck;
ALTER TABLE core.finance_input_version ADD CONSTRAINT finance_input_version_scope_matrix_ck CHECK(
 (scope_kind='ORGANIZATION' AND num_nonnulls(store_ref_id,product_variant_ref_id,promotion_kind,native_promotion_key)=0)
 OR (scope_kind='STORE' AND store_ref_id IS NOT NULL AND num_nonnulls(product_variant_ref_id,promotion_kind,native_promotion_key)=0)
 OR (scope_kind='PRODUCT_VARIANT' AND product_variant_ref_id IS NOT NULL AND num_nonnulls(store_ref_id,promotion_kind,native_promotion_key)=0)
 OR (scope_kind='PROMOTION' AND store_ref_id IS NOT NULL AND product_variant_ref_id IS NULL
   AND promotion_kind IS NOT NULL AND promotion_kind IN ('OFFICIAL_PROMOTION_PARTICIPATION','SELLER_DIRECT_DISCOUNT')
   AND native_promotion_key IS NOT NULL AND length(btrim(native_promotion_key)) BETWEEN 1 AND 128));
ALTER TABLE core.finance_input_version ADD CONSTRAINT finance_input_promotion_amount_ck CHECK(
 (input_code IN ('PROMOTION_FIXED_FEE','PROMOTION_BUYER_PAYMENT_PER_UNIT','PROMOTION_SELLER_REVENUE_PER_UNIT',
   'PROMOTION_PLATFORM_COMPENSATION_PER_UNIT'))=(scope_kind='PROMOTION')
 AND (scope_kind<>'PROMOTION' OR (value_kind='AMOUNT' AND effective_to IS NOT NULL)));
ALTER TABLE core.finance_input_version ADD CONSTRAINT finance_input_promotion_revenue_scope_ck CHECK(
 (input_code IN ('PROMOTION_BUYER_PAYMENT_PER_UNIT','PROMOTION_SELLER_REVENUE_PER_UNIT','PROMOTION_PLATFORM_COMPENSATION_PER_UNIT')
   AND promotion_listing_ref_id IS NOT NULL AND promotion_terms_digest IS NOT NULL AND promotion_terms_digest ~ '^[0-9a-f]{64}$')
 OR (input_code NOT IN ('PROMOTION_BUYER_PAYMENT_PER_UNIT','PROMOTION_SELLER_REVENUE_PER_UNIT','PROMOTION_PLATFORM_COMPENSATION_PER_UNIT')
   AND promotion_listing_ref_id IS NULL AND promotion_terms_digest IS NULL));
CREATE FUNCTION core.guard_promotion_finance_listing_scope() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NEW.promotion_listing_ref_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM core.platform_listing l
     WHERE l.id=NEW.promotion_listing_ref_id AND l.organization_id=NEW.organization_id AND l.store_id=NEW.store_ref_id) THEN
  RAISE EXCEPTION 'promotion finance input must name its exact Listing Store' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION core.guard_promotion_finance_listing_scope() FROM PUBLIC;
CREATE TRIGGER finance_input_promotion_listing_scope BEFORE INSERT OR UPDATE ON core.finance_input_version
 FOR EACH ROW EXECUTE FUNCTION core.guard_promotion_finance_listing_scope();
ALTER TABLE core.finance_input_version DROP CONSTRAINT finance_input_version_no_overlap;
ALTER TABLE core.finance_input_version ADD CONSTRAINT finance_input_version_no_overlap EXCLUDE USING gist(
 organization_id WITH =,input_code WITH =,scope_kind WITH =,
 coalesce(store_ref_id,product_variant_ref_id,'00000000-0000-0000-0000-000000000000'::uuid) WITH =,
 coalesce(promotion_kind,'') WITH =,coalesce(native_promotion_key,'') WITH =,
 coalesce(promotion_listing_ref_id,'00000000-0000-0000-0000-000000000000'::uuid) WITH =,
 tstzrange(effective_from,effective_to,'[)') WITH &&) WHERE(status='ACTIVE' OR (scope_kind='PROMOTION' AND status='ENDED'));
-- Historical profiles retain their proposed-price meaning. No commercial basis is backfilled.
ALTER TABLE core.economics_projection_component ADD COLUMN price_basis text;
ALTER TABLE core.economics_projection_component ADD CONSTRAINT economics_projection_component_price_basis_ck
 CHECK (price_basis IS NULL OR price_basis IN ('PROPOSED_PRICE','BUYER_PAYMENT','SELLER_REVENUE'));

-- Known local obligations are a dependency, not proof that the platform inventory is complete.
CREATE FUNCTION ops.lc_known_promotion_context(p_org uuid,p_listing uuid) RETURNS jsonb
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 WITH records AS (
   SELECT coalesce(jsonb_agg(jsonb_build_object(
       'id',e.id,'version',e.version,'state',e.state,'engagementKind',e.engagement_kind,
       'nativePromotionKey',e.native_promotion_key,'actionId',e.action_id,'adopted',e.adopted,
       'terms',e.terms,'priceFreeze',e.price_freeze,'autoParticipation',e.auto_participation,
       'termsEvidenceReference',e.terms_evidence_reference,'obligations',e.obligations,
       'newTransactionsStoppedAt',e.new_transactions_stopped_at)
       ORDER BY e.id),'[]'::jsonb) AS material
     FROM ops.lc_promotion_engagement e
    WHERE e.organization_id=p_org AND e.platform_listing_id=p_listing AND e.state<>'CLEARED'
 ) SELECT jsonb_build_object('coverage','KNOWN_RECORDS_ONLY','records',material,
       'digest',encode(sha256(convert_to(material::text,'UTF8')),'hex')) FROM records
$$;
REVOKE ALL ON FUNCTION ops.lc_known_promotion_context(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_known_promotion_context(uuid,uuid) TO marketops_app;

CREATE FUNCTION ops.lc_fence_promotion_context_change() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE org uuid;
BEGIN
 IF TG_OP='UPDATE' THEN
   FOR org IN SELECT DISTINCT id FROM (VALUES(OLD.organization_id),(NEW.organization_id)) AS x(id) ORDER BY id LOOP
     PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(org::text));
   END LOOP;
 ELSE
   PERFORM pg_advisory_xact_lock(hashtext('lc_exposure_organization'),hashtext(NEW.organization_id::text));
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_fence_promotion_context_change() FROM PUBLIC;
CREATE TRIGGER lc_fence_promotion_context_change BEFORE INSERT OR UPDATE ON ops.lc_promotion_engagement
 FOR EACH ROW EXECUTE FUNCTION ops.lc_fence_promotion_context_change();
