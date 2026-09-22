-- Preserve historical frozen sets. New sets bind the actual identity authority,
-- including mapping intervals/versions and open conflicts. This is identity
-- lineage, not proof that the acquired native membership universe is complete.
CREATE FUNCTION core.lc_listing_identity_snapshot(p_listing uuid, p_at timestamptz)
RETURNS jsonb
LANGUAGE sql STABLE
SET search_path = pg_catalog, core, pg_temp
SET timezone = 'UTC'
SET datestyle = 'ISO, YMD'
AS $$
    SELECT jsonb_build_object(
        'schema', 'listing-identity-snapshot-v2',
        'listingId', l.id, 'organizationId', l.organization_id,
        'storeId', l.store_id, 'platformCode', l.platform_code,
        'nativeListingKey', l.native_listing_key, 'listingStatus', l.status,
        'members', coalesce((
            SELECT jsonb_agg(jsonb_build_object(
                'listingVariantId', v.id, 'nativeVariantKey', v.native_variant_key,
                'mappings', coalesce((
                    SELECT jsonb_agg(jsonb_build_object(
                        'mappingId', m.id, 'mappingVersion', m.version,
                        'productVariantId', m.product_variant_id,
                        'effectiveFrom', m.effective_from, 'effectiveTo', m.effective_to,
                        'mappingStatus', m.status, 'productVariantStatus', pv.status,
                        'productStatus', p.status) ORDER BY m.id)
                    FROM core.listing_mapping m
                    JOIN core.product_variant pv ON pv.id = m.product_variant_id
                    JOIN core.product p ON p.id = pv.product_id
                    WHERE m.platform_listing_variant_id = v.id
                      AND m.status IN ('ACTIVE', 'ENDED')
                      AND m.effective_from <= p_at
                      AND (m.effective_to IS NULL OR m.effective_to > p_at)
                ), '[]'::jsonb),
                'openConflicts', coalesce((
                    SELECT jsonb_agg(jsonb_build_object('conflictId', c.id,
                        'version', c.version, 'kind', c.conflict_kind) ORDER BY c.id)
                    FROM core.mapping_conflict c
                    WHERE c.platform_listing_variant_id = v.id AND c.state = 'OPEN'
                ), '[]'::jsonb)
            ) ORDER BY v.id)
            FROM core.platform_listing_variant v
            WHERE v.platform_listing_id = l.id AND v.status = 'OBSERVED'
        ), '[]'::jsonb))
    FROM core.platform_listing l WHERE l.id = p_listing
$$;
REVOKE ALL ON FUNCTION core.lc_listing_identity_snapshot(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lc_listing_identity_snapshot(uuid,timestamptz) TO marketops_app;

CREATE OR REPLACE FUNCTION core.lc_listing_affected_set_digest(p_listing uuid)
RETURNS text
LANGUAGE sql STABLE
SET search_path = pg_catalog, core, pg_temp
AS $$
    SELECT encode(sha256(convert_to(
        core.lc_listing_identity_snapshot(p_listing, statement_timestamp())::text,
        'UTF8')), 'hex')
$$;

ALTER TABLE core.lc_affected_set ADD COLUMN identity_lineage jsonb;
ALTER TABLE core.lc_affected_set ADD CONSTRAINT lc_affected_set_identity_lineage_ck
    CHECK (identity_lineage IS NULL OR (jsonb_typeof(identity_lineage) = 'object'
        AND affected_set_digest = encode(sha256(convert_to(identity_lineage::text, 'UTF8')), 'hex')));

CREATE FUNCTION core.lc_affected_set_capture_identity()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, core, pg_temp
AS $$
DECLARE
    snapshot jsonb;
    variant_ids uuid[];
    product_ids uuid[];
BEGIN
    snapshot := core.lc_listing_identity_snapshot(NEW.platform_listing_id, NEW.resolved_at);
    IF snapshot IS NULL OR (snapshot->>'organizationId')::uuid <> NEW.organization_id
       OR NEW.affected_set_digest <> encode(sha256(convert_to(snapshot::text, 'UTF8')), 'hex') THEN
        RAISE EXCEPTION 'affected-set identity changed during capture' USING ERRCODE = 'MO093';
    END IF;
    SELECT coalesce(array_agg((v->>'listingVariantId')::uuid ORDER BY (v->>'listingVariantId')::uuid), '{}')
      INTO variant_ids FROM jsonb_array_elements(snapshot->'members') v;
    SELECT coalesce(array_agg(DISTINCT (m->>'productVariantId')::uuid ORDER BY (m->>'productVariantId')::uuid), '{}')
      INTO product_ids FROM jsonb_array_elements(snapshot->'members') v,
                            jsonb_array_elements(v->'mappings') m
     WHERE jsonb_array_length(v->'openConflicts') = 0;
    IF ARRAY(SELECT DISTINCT unnest(NEW.platform_listing_variant_ids) ORDER BY 1) <> variant_ids
       OR ARRAY(SELECT DISTINCT unnest(NEW.product_variant_ids) ORDER BY 1) <> product_ids THEN
        RAISE EXCEPTION 'affected-set members must equal the identity snapshot' USING ERRCODE = 'MO093';
    END IF;
    NEW.identity_lineage := snapshot;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION core.lc_affected_set_capture_identity() FROM PUBLIC;
CREATE TRIGGER lc_affected_set_capture_identity BEFORE INSERT ON core.lc_affected_set
    FOR EACH ROW EXECUTE FUNCTION core.lc_affected_set_capture_identity();
