-- Root 010: productlisting retains native scope observations; mappings keep their existing owner.
-- An observed or mapped member is not evidence of a complete native universe.
-- The native snapshot calls accepted Policy resolution under UTC. Its source digest must
-- also be session-independent. Historical mismatching acceptance stays unqualified;
-- never rewrite accepted digests to silently approve changed bytes.
ALTER FUNCTION ops.lc_calibration_digest(uuid) SET timezone='UTC';
ALTER FUNCTION ops.lc_calibration_digest(uuid) SET datestyle='ISO, YMD';
CREATE TABLE core.platform_listing_scope_observation (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL,
 platform_listing_id uuid NOT NULL, provenance_id uuid NOT NULL REFERENCES core.fact_provenance(id),
 scope_kind text NOT NULL CHECK(scope_kind IN ('WHOLE_LISTING','NATIVE_VARIANT')),
 native_scope_key text NOT NULL CHECK(length(btrim(native_scope_key)) BETWEEN 1 AND 512),
 native_variant_keys text[] NOT NULL CHECK(cardinality(native_variant_keys)<=4096),
 coverage_state text NOT NULL CHECK(coverage_state IN ('COMPLETE','PARTIAL','UNKNOWN')),
 expected_member_count integer CHECK(expected_member_count BETWEEN 0 AND 4096),
 continuation_reference text,
 source_reference text NOT NULL CHECK(length(btrim(source_reference)) BETWEEN 1 AND 512),
 scope_basis_reference text NOT NULL CHECK(length(btrim(scope_basis_reference)) BETWEEN 1 AND 512),
 observed_at timestamptz NOT NULL, recorded_at timestamptz NOT NULL,
 verification_expires_at timestamptz NOT NULL,
 FOREIGN KEY(platform_listing_id,organization_id) REFERENCES core.platform_listing(id,organization_id),
 CHECK(observed_at<=recorded_at AND verification_expires_at>recorded_at),
 CHECK(coverage_state<>'COMPLETE' OR (expected_member_count IS NOT NULL
      AND expected_member_count=cardinality(native_variant_keys) AND continuation_reference IS NULL)),
 CHECK(scope_kind<>'NATIVE_VARIANT' OR (cardinality(native_variant_keys)=1 AND native_variant_keys[1]=native_scope_key))
);
CREATE INDEX platform_listing_scope_current_ix ON core.platform_listing_scope_observation
 (platform_listing_id,scope_kind,observed_at DESC,recorded_at DESC);
GRANT SELECT,INSERT ON core.platform_listing_scope_observation TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note)
 VALUES('core','platform_listing_scope_observation','NO_ROUTE',NULL,
   'productlisting append-only native enumeration source evidence; never an internal mapping or platform write');

CREATE FUNCTION core.validate_platform_listing_scope_observation() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE provenance core.fact_provenance; listing core.platform_listing; keys text[];
BEGIN
 SELECT * INTO listing FROM core.platform_listing WHERE id=NEW.platform_listing_id AND organization_id=NEW.organization_id;
 SELECT * INTO provenance FROM core.fact_provenance WHERE id=NEW.provenance_id AND organization_id=NEW.organization_id;
 IF listing.id IS NULL OR provenance.id IS NULL OR provenance.source_time IS DISTINCT FROM NEW.observed_at
   OR provenance.ingestion_time IS DISTINCT FROM NEW.recorded_at
   OR provenance.source_kind NOT IN ('MANUAL_ENTRY','MARKETPLACE_RAW') THEN
   RAISE EXCEPTION 'native enumeration must retain exact source identity and custody times' USING ERRCODE='23514';
 END IF;
 IF NEW.scope_kind='WHOLE_LISTING' AND NEW.native_scope_key<>listing.native_listing_key THEN
   RAISE EXCEPTION 'native listing scope mismatch' USING ERRCODE='23514';
 END IF;
 IF EXISTS(SELECT 1 FROM unnest(NEW.native_variant_keys) k WHERE k IS NULL OR length(btrim(k))=0 OR length(k)>512) THEN
   RAISE EXCEPTION 'exact bounded native member keys required' USING ERRCODE='23514';
 END IF;
 SELECT ARRAY(SELECT DISTINCT k FROM unnest(NEW.native_variant_keys) k ORDER BY k) INTO keys;
 IF cardinality(keys)<>cardinality(NEW.native_variant_keys) THEN
   RAISE EXCEPTION 'duplicate native member does not enlarge coverage' USING ERRCODE='23514';
 END IF;
 NEW.native_variant_keys:=keys;
 RETURN NEW;
END $$;
CREATE TRIGGER platform_listing_scope_observation_valid BEFORE INSERT ON core.platform_listing_scope_observation
 FOR EACH ROW EXECUTE FUNCTION core.validate_platform_listing_scope_observation();
REVOKE ALL ON FUNCTION core.validate_platform_listing_scope_observation() FROM PUBLIC;

-- Source receipts remain separate from the semantic dependency digest: unchanged re-observation
-- must not invalidate an approval merely because custody ids or timestamps advanced.
CREATE FUNCTION core.current_listing_scope_observation(p_listing uuid,p_at timestamptz)
RETURNS core.platform_listing_scope_observation LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT s FROM core.platform_listing_scope_observation s
 WHERE s.platform_listing_id=p_listing AND s.scope_kind='WHOLE_LISTING'
   AND s.observed_at<=p_at AND s.recorded_at<=p_at
 ORDER BY s.observed_at DESC,s.recorded_at DESC,s.id DESC LIMIT 1
$$;
REVOKE ALL ON FUNCTION core.current_listing_scope_observation(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.current_listing_scope_observation(uuid,timestamptz) TO marketops_app;
ALTER TABLE core.lc_affected_set ADD COLUMN native_scope_observation_id uuid
 REFERENCES core.platform_listing_scope_observation(id);

-- Keep the earlier observed-identity representation for historical attribution and diagnostics.
ALTER FUNCTION core.lc_listing_identity_snapshot(uuid,timestamptz) RENAME TO listing_observed_identity_snapshot;
CREATE FUNCTION core.lc_listing_identity_snapshot(p_listing uuid,p_at timestamptz) RETURNS jsonb
LANGUAGE plpgsql STABLE SET search_path=pg_catalog SET timezone='UTC' SET DateStyle='ISO, YMD' AS $$
DECLARE snapshot jsonb; receipt core.platform_listing_scope_observation; qualification text:='INCOMPLETE';
 reasons text[]:='{}'; identity_count integer; divergent integer; members jsonb;
 source_kind text; freshness_rule jsonb; maximum_age_seconds integer;
BEGIN
 snapshot:=core.listing_observed_identity_snapshot(p_listing,p_at);
 IF snapshot IS NULL THEN RETURN NULL; END IF;
 IF snapshot->>'listingStatus'<>'OBSERVED' THEN reasons:=array_append(reasons,'NATIVE_LISTING_NOT_CURRENT'); END IF;
 receipt:=core.current_listing_scope_observation(p_listing,p_at);
 IF receipt.id IS NULL THEN reasons:=array_append(reasons,'NATIVE_SCOPE_UNPROVEN');
 ELSE
   SELECT count(DISTINCT (s.native_variant_keys,s.coverage_state,s.expected_member_count,s.continuation_reference))
    INTO divergent FROM core.platform_listing_scope_observation s
    WHERE s.platform_listing_id=p_listing AND s.scope_kind='WHOLE_LISTING'
     AND s.observed_at=receipt.observed_at AND s.recorded_at<=p_at;
   IF divergent>1 THEN qualification:='CONFLICTED'; reasons:=array_append(reasons,'NATIVE_SCOPE_CONFLICT'); END IF;
   SELECT p.source_kind INTO source_kind FROM core.fact_provenance p WHERE p.id=receipt.provenance_id;
   SELECT v.value_json->'nativeScope'->source_kind INTO freshness_rule
    FROM core.platform_listing l
    CROSS JOIN LATERAL core.lc_resolve_calibration(l.organization_id,l.platform_code,l.store_id,p_at) r
    JOIN core.lc_calibration_value v ON v.package_id=r.package_id AND v.category_code='FRESHNESS_RULE'
    WHERE l.id=p_listing AND r.resolution_state='RESOLVED';
   IF freshness_rule IS NULL OR jsonb_typeof(freshness_rule)<>'object'
      OR coalesce(freshness_rule->>'maximumAgeSeconds','') !~ '^[0-9]{1,10}$' THEN
     reasons:=array_append(reasons,'NATIVE_SCOPE_FRESHNESS_UNQUALIFIED');
   ELSIF (freshness_rule->>'maximumAgeSeconds')::numeric NOT BETWEEN 1 AND 2147483647 THEN
     reasons:=array_append(reasons,'NATIVE_SCOPE_FRESHNESS_UNQUALIFIED');
   ELSE
     maximum_age_seconds:=(freshness_rule->>'maximumAgeSeconds')::integer;
     IF receipt.observed_at+maximum_age_seconds*interval '1 second'<=p_at THEN
       reasons:=array_append(reasons,'NATIVE_SCOPE_SOURCE_STALE');
     END IF;
   END IF;
   IF receipt.coverage_state<>'COMPLETE' THEN reasons:=array_append(reasons,'NATIVE_ENUMERATION_INCOMPLETE'); END IF;
   IF receipt.verification_expires_at<=p_at THEN reasons:=array_append(reasons,'NATIVE_SCOPE_VERIFICATION_EXPIRED'); END IF;
   SELECT count(*) INTO identity_count FROM jsonb_array_elements(snapshot->'members') m
    WHERE m->>'nativeVariantKey'=ANY(receipt.native_variant_keys);
   IF identity_count<>cardinality(receipt.native_variant_keys) THEN reasons:=array_append(reasons,'NATIVE_MEMBER_IDENTITY_MISSING'); END IF;
   IF EXISTS(SELECT 1 FROM core.platform_listing_variant v WHERE v.platform_listing_id=p_listing AND v.status='OBSERVED'
       AND NOT v.native_variant_key=ANY(receipt.native_variant_keys) AND v.last_seen_at>receipt.observed_at) THEN
     reasons:=array_append(reasons,'NATIVE_MEMBER_OUTSIDE_CAPTURED_SCOPE');
   END IF;
   IF cardinality(reasons)=0 THEN
     qualification:='COMPLETE';
     -- A complete later native enumeration may exclude an old observed identity without deleting its history.
     SELECT coalesce(jsonb_agg(m ORDER BY m->>'listingVariantId'),'[]'::jsonb) INTO members
      FROM jsonb_array_elements(snapshot->'members') m WHERE m->>'nativeVariantKey'=ANY(receipt.native_variant_keys);
     snapshot:=jsonb_set(snapshot,'{members}',members);
   END IF;
 END IF;
 RETURN snapshot || jsonb_build_object('schema','listing-identity-snapshot-v3','nativeScope',
   jsonb_build_object('state',qualification,'reasonCodes',to_jsonb(reasons),
     'scopeKind',receipt.scope_kind,'nativeScopeKey',receipt.native_scope_key,'nativeVariantKeys',to_jsonb(receipt.native_variant_keys),
     'sourceKind',source_kind,'maximumAgeSeconds',maximum_age_seconds));
END $$;
REVOKE ALL ON FUNCTION core.lc_listing_identity_snapshot(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lc_listing_identity_snapshot(uuid,timestamptz) TO marketops_app;

-- CREATE OR REPLACE rebinds the earlier SQL function to the new scope-aware snapshot, not the renamed body.
CREATE OR REPLACE FUNCTION core.lc_listing_affected_set_digest(p_listing uuid) RETURNS text
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT encode(sha256(convert_to(core.lc_listing_identity_snapshot(p_listing,statement_timestamp())::text,'UTF8')),'hex')
$$;

CREATE OR REPLACE FUNCTION core.lc_affected_set_capture_identity() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE snapshot jsonb; variant_ids uuid[]; product_ids uuid[];
BEGIN
 snapshot:=core.lc_listing_identity_snapshot(NEW.platform_listing_id,NEW.resolved_at);
 IF snapshot IS NULL OR (snapshot->>'organizationId')::uuid<>NEW.organization_id
  OR NEW.affected_set_digest<>encode(sha256(convert_to(snapshot::text,'UTF8')),'hex') THEN
  RAISE EXCEPTION 'affected-set identity changed during capture' USING ERRCODE='MO093';
 END IF;
 SELECT coalesce(array_agg((v->>'listingVariantId')::uuid ORDER BY (v->>'listingVariantId')::uuid),'{}')
  INTO variant_ids FROM jsonb_array_elements(snapshot->'members') v;
 SELECT coalesce(array_agg(DISTINCT (m->>'productVariantId')::uuid ORDER BY (m->>'productVariantId')::uuid),'{}')
  INTO product_ids FROM jsonb_array_elements(snapshot->'members') v,jsonb_array_elements(v->'mappings') m
  WHERE jsonb_array_length(v->'openConflicts')=0 AND jsonb_array_length(v->'mappings')=1;
 IF ARRAY(SELECT DISTINCT unnest(NEW.platform_listing_variant_ids) ORDER BY 1)<>variant_ids
  OR ARRAY(SELECT DISTINCT unnest(NEW.product_variant_ids) ORDER BY 1)<>product_ids THEN
  RAISE EXCEPTION 'affected-set members must equal the identity snapshot' USING ERRCODE='MO093';
 END IF;
 IF NEW.resolution_state='COMPLETE' AND (snapshot#>>'{nativeScope,state}'<>'COMPLETE'
   OR cardinality(variant_ids)=0 OR EXISTS(SELECT 1 FROM jsonb_array_elements(snapshot->'members') v
       WHERE jsonb_array_length(v->'mappings')<>1 OR jsonb_array_length(v->'openConflicts')<>0
         OR EXISTS(SELECT 1 FROM jsonb_array_elements(v->'mappings') m
             WHERE m->>'mappingStatus' NOT IN ('ACTIVE','ENDED') OR m->>'productVariantStatus'<>'ACTIVE' OR m->>'productStatus'<>'ACTIVE'))) THEN
  RAISE EXCEPTION 'complete affected set requires complete native scope and active unambiguous identities' USING ERRCODE='MO093';
 END IF;
 NEW.native_scope_observation_id:=(core.current_listing_scope_observation(NEW.platform_listing_id,NEW.resolved_at)).id;
 NEW.identity_lineage:=snapshot;
 RETURN NEW;
END $$;
