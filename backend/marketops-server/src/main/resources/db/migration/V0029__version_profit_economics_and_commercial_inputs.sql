-- R2 closes the profit/minimum-price semantic gap without changing historical
-- definitions or values. Version 1 remains readable and retired; version 2 is
-- the only live definition set.

ALTER TABLE core.finance_input_version
    DROP CONSTRAINT finance_input_version_code_ck;

ALTER TABLE core.finance_input_version
    ADD CONSTRAINT finance_input_version_code_ck
        CHECK (input_code IN (
            'VARIABLE_TAX_RATE', 'PAYMENT_PROCESSING_RATE',
            'RETURN_HANDLING_UNIT_COST', 'INBOUND_LOGISTICS_UNIT_COST',
            'REQUIRED_PROFIT_PER_UNIT', 'SAFETY_BUFFER_PER_UNIT')),
    ADD CONSTRAINT finance_input_version_commercial_amount_ck CHECK (
        input_code NOT IN ('REQUIRED_PROFIT_PER_UNIT', 'SAFETY_BUFFER_PER_UNIT')
        OR value_kind = 'AMOUNT');

UPDATE mart.metric_definition
   SET status = 'RETIRED'
 WHERE status = 'ACTIVE';

INSERT INTO mart.metric_definition
    (metric_code, definition_version, display_name, unit_kind, formula_statement,
     domain, owner_label, status)
SELECT metric_code,
       2,
       CASE metric_code
           WHEN 'MINIMUM_PRICE' THEN 'Minimum price'
           ELSE display_name
       END,
       unit_kind,
       CASE metric_code
           WHEN 'PLATFORM_FEES' THEN
               'Sum of platform fee amounts over the exact half-open window excluding advertising and variable tax.'
           WHEN 'OPERATIONAL_CONTRIBUTION_PROFIT' THEN
               'COMPLETED_NET_SALES less unit cost of completed units, platform fees, return loss, advertising spend and a required sourced variable tax estimate; unavailable when any component is absent.'
           WHEN 'SETTLED_CONTRIBUTION_PROFIT' THEN
               'SETTLED_NET_SALES less unit cost of settled units, settled platform fees, return loss, advertising spend and actual variable tax; unavailable when any component is absent.'
           WHEN 'MINIMUM_PRICE' THEN
               'The least supported price whose exact projected profile components cover UNIT_COST plus REQUIRED_PROFIT_PER_UNIT plus SAFETY_BUFFER_PER_UNIT; every authority is versioned, sourced and currency-consistent.'
           WHEN 'DATA_COMPLETENESS' THEN
               'Share of the eight required canonical profit and commercial inputs that resolved, bound to the effective listing mapping.'
           ELSE formula_statement
       END,
       domain,
       owner_label,
       'ACTIVE'
  FROM mart.metric_definition
 WHERE definition_version = 1;

INSERT INTO mart.metric_definition
    (metric_code, definition_version, display_name, unit_kind, formula_statement,
     domain, owner_label, status) VALUES
    ('PLATFORM_FEES_PER_UNIT', 2, 'Platform fees per completed unit', 'MONEY',
        'PLATFORM_FEES over the exact half-open window divided by COMPLETED_UNITS; absence is not zero.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('RETURN_LOSS_PER_UNIT', 2, 'Return loss per completed unit', 'MONEY',
        'RETURN_LOSS over the exact half-open window divided by COMPLETED_UNITS; absence is not zero.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('AD_SPEND_PER_UNIT', 2, 'Advertising spend per completed unit', 'MONEY',
        'AD_SPEND over the exact half-open window divided by COMPLETED_UNITS; absence is not zero.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('VARIABLE_TAX_PER_UNIT', 2, 'Actual variable tax per completed unit', 'MONEY',
        'Explicitly published VARIABLE_TAX fee amount over the exact half-open window divided by COMPLETED_UNITS; absence is not zero.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('REQUIRED_PROFIT_PER_UNIT', 2, 'Required profit per unit', 'MONEY',
        'The effective sourced company-owned REQUIRED_PROFIT_PER_UNIT amount; no default is permitted.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('SAFETY_BUFFER_PER_UNIT', 2, 'Safety buffer per unit', 'MONEY',
        'The effective sourced company-owned SAFETY_BUFFER_PER_UNIT amount; no default is permitted.',
        'COST', 'analyticsdecision', 'ACTIVE'),
    ('BREAK_EVEN_PRICE', 2, 'Break-even price', 'MONEY',
        'The least supported price whose projected profile components cover UNIT_COST; the projection is versioned by platform, account, Store and fulfilment mode.',
        'PROFIT', 'analyticsdecision', 'ACTIVE');

-- V0029 is an unmerged candidate and has not been consumed by a persistent or
-- shared environment. It is therefore corrected in place for the bounded R2
-- closure. V0001-V0028 remain byte-immutable.

-- Promotion fees are distinct from advertising spend. Treating both as one
-- category would make an absent promotion family look covered by an ad fact.
ALTER TABLE ledger.finance_fee_fact
    DROP CONSTRAINT finance_fee_fact_category_ck;
ALTER TABLE ledger.finance_fee_fact
    ADD CONSTRAINT finance_fee_fact_category_ck
        CHECK (fee_category IN (
            'COMMISSION', 'FULFILLMENT', 'DELIVERY', 'STORAGE', 'PROMOTION',
            'RETURN_PROCESSING', 'ADVERTISING', 'VARIABLE_TAX',
            'OTHER_VARIABLE', 'UNKNOWN'));

-- -------------------------------------------------------------------------
-- Versioned price-dependent economics authority
-- -------------------------------------------------------------------------

CREATE TABLE core.economics_projection_profile (
    id                       uuid           NOT NULL,
    profile_version          integer        NOT NULL,
    organization_id          uuid           NOT NULL,
    platform_code            text           NOT NULL,
    marketplace_account_id   uuid           NOT NULL,
    store_id                 uuid           NOT NULL,
    fulfillment_mode_code    text           NOT NULL,
    currency_code            text           NOT NULL,
    effective_from           timestamptz    NOT NULL,
    effective_to             timestamptz,
    verification_state       text           NOT NULL,
    verified_at              timestamptz    NOT NULL,
    verification_expires_at  timestamptz,
    evidence_reference       text           NOT NULL,
    minimum_supported_price  numeric(18, 4) NOT NULL,
    maximum_supported_price  numeric(18, 4) NOT NULL,
    status                   text           NOT NULL,
    created_at               timestamptz    NOT NULL,
    CONSTRAINT economics_projection_profile_pk PRIMARY KEY (id),
    CONSTRAINT economics_projection_profile_org_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT economics_projection_profile_account_org_fk
        FOREIGN KEY (marketplace_account_id, organization_id)
        REFERENCES core.marketplace_account (id, organization_id),
    CONSTRAINT economics_projection_profile_account_platform_fk
        FOREIGN KEY (marketplace_account_id, platform_code)
        REFERENCES core.marketplace_account (id, platform_code),
    CONSTRAINT economics_projection_profile_store_org_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT economics_projection_profile_store_account_fk
        FOREIGN KEY (store_id, marketplace_account_id)
        REFERENCES core.store (id, marketplace_account_id),
    CONSTRAINT economics_projection_profile_mode_fk
        FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode (code),
    CONSTRAINT economics_projection_profile_scope_version_uq UNIQUE (
        organization_id, platform_code, marketplace_account_id, store_id,
        fulfillment_mode_code, profile_version),
    CONSTRAINT economics_projection_profile_version_ck CHECK (profile_version > 0),
    CONSTRAINT economics_projection_profile_currency_ck
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT economics_projection_profile_interval_ck
        CHECK (effective_to IS NULL OR effective_from < effective_to),
    CONSTRAINT economics_projection_profile_verification_ck CHECK (
        verification_state IN (
            'UNVERIFIED', 'ENGINEERING_VERIFIED', 'REAL_ACCOUNT_VERIFIED')),
    CONSTRAINT economics_projection_profile_verification_interval_ck CHECK (
        verification_expires_at IS NULL OR verified_at < verification_expires_at),
    CONSTRAINT economics_projection_profile_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT economics_projection_profile_price_domain_ck CHECK (
        minimum_supported_price > 0
        AND minimum_supported_price < maximum_supported_price),
    CONSTRAINT economics_projection_profile_status_ck
        CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE INDEX economics_projection_profile_scope_ix
    ON core.economics_projection_profile (
        organization_id, platform_code, marketplace_account_id, store_id,
        fulfillment_mode_code, status, effective_from DESC);

CREATE TABLE core.economics_projection_family (
    profile_id           uuid NOT NULL,
    family_code          text NOT NULL,
    applicability_state  text NOT NULL,
    evidence_reference   text NOT NULL,
    CONSTRAINT economics_projection_family_pk PRIMARY KEY (profile_id, family_code),
    CONSTRAINT economics_projection_family_profile_fk
        FOREIGN KEY (profile_id) REFERENCES core.economics_projection_profile (id),
    CONSTRAINT economics_projection_family_code_ck CHECK (family_code IN (
        'COMMISSION', 'FULFILLMENT_DELIVERY', 'STORAGE', 'PROMOTION',
        'OTHER_VARIABLE', 'RETURN_LOSS', 'ADVERTISING', 'VARIABLE_TAX')),
    CONSTRAINT economics_projection_family_applicability_ck CHECK (
        applicability_state IN ('REQUIRED', 'VERIFIED_NOT_APPLICABLE')),
    CONSTRAINT economics_projection_family_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512)
);

CREATE TABLE core.economics_projection_component (
    id                     uuid           NOT NULL,
    profile_id             uuid           NOT NULL,
    component_code         text           NOT NULL,
    family_code            text           NOT NULL,
    component_kind         text           NOT NULL,
    fixed_amount           numeric(18, 4),
    rate_value             numeric(12, 8),
    lower_price_inclusive  numeric(18, 4),
    upper_price_exclusive  numeric(18, 4),
    evidence_reference     text           NOT NULL,
    CONSTRAINT economics_projection_component_pk PRIMARY KEY (id),
    CONSTRAINT economics_projection_component_family_fk
        FOREIGN KEY (profile_id, family_code)
        REFERENCES core.economics_projection_family (profile_id, family_code),
    CONSTRAINT economics_projection_component_code_ck
        CHECK (component_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT economics_projection_component_kind_ck CHECK (
        component_kind IN ('FIXED', 'PERCENTAGE', 'FIXED_PLUS_PERCENTAGE')),
    CONSTRAINT economics_projection_component_shape_ck CHECK (
        (component_kind = 'FIXED' AND fixed_amount IS NOT NULL AND rate_value IS NULL)
        OR (component_kind = 'PERCENTAGE'
            AND fixed_amount IS NULL AND rate_value IS NOT NULL)
        OR (component_kind = 'FIXED_PLUS_PERCENTAGE'
            AND fixed_amount IS NOT NULL AND rate_value IS NOT NULL)),
    CONSTRAINT economics_projection_component_amount_ck
        CHECK (fixed_amount IS NULL OR fixed_amount >= 0),
    CONSTRAINT economics_projection_component_rate_ck
        CHECK (rate_value IS NULL OR (rate_value >= 0 AND rate_value < 1)),
    CONSTRAINT economics_projection_component_bounds_ck CHECK (
        lower_price_inclusive IS NULL OR upper_price_exclusive IS NULL
        OR lower_price_inclusive < upper_price_exclusive),
    CONSTRAINT economics_projection_component_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512)
);

CREATE INDEX economics_projection_component_profile_ix
    ON core.economics_projection_component (
        profile_id, family_code, component_code, lower_price_inclusive);

-- -------------------------------------------------------------------------
-- Append-only decision freshness authority
-- -------------------------------------------------------------------------

CREATE TABLE core.source_feed_watermark (
    id                       uuid        NOT NULL,
    organization_id          uuid        NOT NULL,
    platform_code            text        NOT NULL,
    marketplace_account_id   uuid        NOT NULL,
    store_id                 uuid        NOT NULL,
    feed_code                text        NOT NULL,
    source_updated_at        timestamptz,
    ingested_at              timestamptz NOT NULL,
    reconciled_at            timestamptz,
    evidence_reference       text        NOT NULL,
    verification_state       text        NOT NULL,
    recorded_at              timestamptz NOT NULL,
    CONSTRAINT source_feed_watermark_pk PRIMARY KEY (id),
    CONSTRAINT source_feed_watermark_equivalent_uq UNIQUE NULLS NOT DISTINCT (
        organization_id, platform_code, marketplace_account_id, store_id,
        feed_code, source_updated_at, ingested_at, reconciled_at,
        evidence_reference, verification_state),
    CONSTRAINT source_feed_watermark_org_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT source_feed_watermark_account_org_fk
        FOREIGN KEY (marketplace_account_id, organization_id)
        REFERENCES core.marketplace_account (id, organization_id),
    CONSTRAINT source_feed_watermark_account_platform_fk
        FOREIGN KEY (marketplace_account_id, platform_code)
        REFERENCES core.marketplace_account (id, platform_code),
    CONSTRAINT source_feed_watermark_store_org_fk
        FOREIGN KEY (store_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT source_feed_watermark_store_account_fk
        FOREIGN KEY (store_id, marketplace_account_id)
        REFERENCES core.store (id, marketplace_account_id),
    CONSTRAINT source_feed_watermark_feed_ck CHECK (feed_code IN (
        'PRICE', 'STOCK', 'SALES', 'RETURNS', 'FINANCE_FEES', 'ADVERTISING',
        'INTERNAL_COST', 'COMMERCIAL_INPUTS')),
    CONSTRAINT source_feed_watermark_evidence_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT source_feed_watermark_verification_ck
        CHECK (verification_state IN ('VERIFIED', 'UNVERIFIED')),
    CONSTRAINT source_feed_watermark_time_ck CHECK (
        source_updated_at IS NULL OR source_updated_at <= recorded_at),
    CONSTRAINT source_feed_watermark_ingestion_ck CHECK (ingested_at <= recorded_at),
    CONSTRAINT source_feed_watermark_reconciliation_ck CHECK (
        reconciled_at IS NULL OR reconciled_at <= recorded_at)
);

CREATE INDEX source_feed_watermark_scope_ix
    ON core.source_feed_watermark (
        organization_id, platform_code, marketplace_account_id, store_id,
        feed_code, recorded_at DESC);

-- Projection identities are first-class metric inputs, so equivalent reruns
-- deduplicate and a profile/component change appends a new current assertion.
ALTER TABLE mart.metric_input_reference
    DROP CONSTRAINT metric_input_reference_kind_ck;
ALTER TABLE mart.metric_input_reference
    ADD CONSTRAINT metric_input_reference_kind_ck CHECK (reference_kind IN (
        'FACT_PROVENANCE', 'COST_VERSION', 'FINANCE_INPUT_VERSION',
        'METRIC_VALUE', 'LISTING_MAPPING', 'ECONOMICS_PROFILE',
        'ECONOMICS_COMPONENT'));

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'economics_projection_profile', 'NO_ROUTE', NULL,
        'versioned business authority; never an outbound-call authorization'),
    ('core', 'economics_projection_family', 'NO_ROUTE', NULL,
        'required-family contract; never an outbound-call authorization'),
    ('core', 'economics_projection_component', 'NO_ROUTE', NULL,
        'deterministic economics input; never an outbound-call authorization'),
    ('core', 'source_feed_watermark', 'NO_ROUTE', NULL,
        'append-only freshness evidence; never an outbound-call authorization');

GRANT SELECT ON core.economics_projection_profile TO marketops_app;
GRANT SELECT ON core.economics_projection_family TO marketops_app;
GRANT SELECT ON core.economics_projection_component TO marketops_app;
GRANT SELECT, INSERT ON core.source_feed_watermark TO marketops_app;

-- -------------------------------------------------------------------------
-- Proposed-price and freshness authority bound to Guardrail and command
-- -------------------------------------------------------------------------

-- The recommendation parameter contract is one database function shared by the
-- snapshot, command constructor and command/worker authority predicates. The
-- optional mode is bounded like core.fulfillment_mode.code, but UNKNOWN is not
-- executable authority. Every unrecognised key fails closed.
CREATE FUNCTION ops.price_change_parameter_contract_is_valid(p_parameters jsonb)
RETURNS boolean
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT coalesce(
        jsonb_typeof(p_parameters) = 'object'
        AND (p_parameters - ARRAY['targetPrice', 'fulfillmentModeCode']) = '{}'::jsonb
        AND CASE
            WHEN jsonb_typeof(p_parameters -> 'targetPrice') = 'string'
             AND (p_parameters ->> 'targetPrice')
                    ~ '^[0-9]{1,14}([.][0-9]{1,4})?$'
            THEN (p_parameters ->> 'targetPrice')::numeric > 0
            ELSE false
        END
        AND (NOT (p_parameters ? 'fulfillmentModeCode')
             OR (jsonb_typeof(p_parameters -> 'fulfillmentModeCode') = 'string'
                 AND (p_parameters ->> 'fulfillmentModeCode')
                        ~ '^[A-Z][A-Z0-9_]{1,62}$'
                 AND (p_parameters ->> 'fulfillmentModeCode') <> 'UNKNOWN')),
        false)
$$;
REVOKE ALL ON FUNCTION ops.price_change_parameter_contract_is_valid(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.price_change_parameter_contract_is_valid(jsonb)
    TO marketops_app;

-- Preserve the V0020 function for historical provenance. The new explicit
-- p_as_of variant is the authority used by R2. Every effective-dated input in
-- this function resolves against that one supplied instant.
ALTER FUNCTION ops.price_authority_snapshot(uuid)
    RENAME TO price_authority_snapshot_v1;

CREATE FUNCTION ops.price_authority_snapshot_v1_at(
    p_recommendation_id uuid, p_as_of timestamptz)
RETURNS jsonb
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT jsonb_build_object(
        'proposal', jsonb_build_object(
            'id', r.id, 'organizationId', r.organization_id, 'storeId', r.store_id,
            'subjectKind', r.subject_kind, 'subjectId', r.subject_id,
            'actionKind', r.action_kind, 'parameters', r.proposed_parameters,
            'risk', r.risk_label, 'window', r.window_code,
            'validUntil', r.valid_until, 'entityDigest', r.entity_version_digest),
        'platformCode', l.platform_code, 'accountId', l.marketplace_account_id,
        'nativeListingKey', l.native_listing_key, 'nativeVariantKey', v.native_variant_key,
        'metrics', coalesce(metrics.items, '[]'::jsonb),
        'currentEntityDigest', metrics.entity_digest,
        'prior', prior.item, 'mapping', mapping.item,
        'mappingConflictOpen', coalesce(mapping.conflict_open, false),
        'policy', policy.item, 'policyLimits', coalesce(limits.items, '[]'::jsonb))
      FROM ops.recommendation r
      LEFT JOIN core.platform_listing_variant v
        ON v.id = r.subject_id AND v.organization_id = r.organization_id
       AND r.subject_kind = 'PLATFORM_LISTING_VARIANT'
      LEFT JOIN core.platform_listing l
        ON l.id = v.platform_listing_id AND l.organization_id = r.organization_id
       AND l.store_id = r.store_id
      LEFT JOIN LATERAL (
          SELECT jsonb_agg(to_jsonb(m) ORDER BY m.metric_code COLLATE "C") AS items,
                 encode(sha256(convert_to(string_agg(
                     m.metric_code || chr(31) || m.value_state || chr(31)
                     || m.input_digest || chr(31), '' ORDER BY m.metric_code COLLATE "C"),
                     'UTF8')), 'hex') AS entity_digest
            FROM (
                SELECT DISTINCT ON (mv.metric_code) mv.*
                  FROM mart.metric_value mv
                 WHERE mv.organization_id = r.organization_id
                   AND mv.subject_kind = r.subject_kind AND mv.subject_id = r.subject_id
                   AND mv.window_code = r.window_code AND mv.computed_at <= p_as_of
                 ORDER BY mv.metric_code, mv.computed_at DESC, mv.id DESC
            ) m
      ) metrics ON true
      LEFT JOIN LATERAL (
          SELECT jsonb_build_object('id', p.id, 'currency', p.currency_code,
                     'price', coalesce(p.discount_price, p.selling_price, p.list_price),
                     'observedAt', p.observed_at, 'provenanceId', p.provenance_id) AS item
            FROM core.listing_price_observation p
           WHERE p.organization_id = r.organization_id
             AND p.platform_listing_variant_id = v.id
             AND p.observed_at <= p_as_of
             AND NOT EXISTS (SELECT 1 FROM core.listing_price_observation later
                              WHERE later.supersedes_fact_id = p.id
                                AND later.observed_at <= p_as_of)
           ORDER BY p.observed_at DESC, p.id DESC LIMIT 1
      ) prior ON true
      LEFT JOIN LATERAL (
          SELECT to_jsonb(m) AS item, m.product_variant_id,
                 EXISTS (
                     SELECT 1 FROM core.mapping_conflict conflict
                      WHERE conflict.platform_listing_variant_id = v.id
                        AND conflict.state = 'OPEN'
                        AND conflict.detected_at <= p_as_of) AS conflict_open
            FROM core.listing_mapping m
           WHERE m.organization_id = r.organization_id
             AND m.platform_listing_variant_id = v.id AND m.status = 'ACTIVE'
             AND m.effective_from <= p_as_of
             AND (m.effective_to IS NULL OR m.effective_to > p_as_of)
           ORDER BY m.effective_from DESC, m.id DESC LIMIT 1
      ) mapping ON true
      LEFT JOIN LATERAL (
          SELECT to_jsonb(p) AS item, p.id
            FROM ops.commercial_policy p
           WHERE p.organization_id = r.organization_id AND p.status = 'ACTIVE'
             AND p.effective_from <= p_as_of
             AND (p.effective_to IS NULL OR p.effective_to > p_as_of)
             AND (p.scope_kind = 'ORGANIZATION'
                  OR (p.scope_kind = 'PLATFORM' AND p.platform_code = l.platform_code)
                  OR (p.scope_kind = 'STORE' AND p.store_ref_id = r.store_id)
                  OR (p.scope_kind = 'PRODUCT_VARIANT'
                      AND p.product_variant_ref_id = mapping.product_variant_id))
           ORDER BY CASE p.scope_kind WHEN 'PRODUCT_VARIANT' THEN 1 WHEN 'STORE' THEN 2
                        WHEN 'PLATFORM' THEN 3 ELSE 4 END,
                    p.effective_from DESC, p.id DESC LIMIT 1
      ) policy ON true
      LEFT JOIN LATERAL (
          SELECT jsonb_agg(to_jsonb(lim) ORDER BY lim.limit_code) AS items
            FROM ops.commercial_policy_limit lim WHERE lim.policy_id = policy.id
      ) limits ON true
     WHERE r.id = p_recommendation_id
$$;
REVOKE ALL ON FUNCTION ops.price_authority_snapshot_v1_at(uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.price_authority_snapshot_v1_at(uuid, timestamptz)
    TO marketops_app;

CREATE FUNCTION ops.price_authority_snapshot(
    p_recommendation_id uuid, p_as_of timestamptz)
RETURNS jsonb
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    WITH recommendation AS (
        SELECT r.*
          FROM ops.recommendation r
         WHERE r.id = p_recommendation_id
    ), target AS (
        SELECT r.*,
               ops.price_change_parameter_contract_is_valid(r.proposed_parameters)
                   AS parameter_contract_valid,
               CASE WHEN (r.proposed_parameters ->> 'targetPrice')
                    ~ '^[0-9]{1,14}([.][0-9]{1,4})?$'
                    THEN (r.proposed_parameters ->> 'targetPrice')::numeric END
                   AS target_price
          FROM recommendation r
    ), listing_scope AS (
        SELECT target.*, listing.platform_code,
               listing.marketplace_account_id
          FROM target
          LEFT JOIN core.platform_listing_variant variant
            ON variant.id = target.subject_id
           AND variant.organization_id = target.organization_id
           AND target.subject_kind = 'PLATFORM_LISTING_VARIANT'
          LEFT JOIN core.platform_listing listing
            ON listing.id = variant.platform_listing_id
           AND listing.organization_id = target.organization_id
           AND listing.store_id = target.store_id
    ), mode_inventory AS (
        SELECT scope.id,
               count(declaration.*) AS active_mode_count,
               min(declaration.fulfillment_mode_code) AS only_mode,
               coalesce(jsonb_agg(jsonb_build_object(
                   'id', declaration.id,
                   'fulfillmentModeCode', declaration.fulfillment_mode_code,
                   'effectiveFrom', declaration.effective_from,
                   'effectiveTo', declaration.effective_to)
                   ORDER BY declaration.fulfillment_mode_code COLLATE "C",
                            declaration.id)
                   FILTER (WHERE declaration.id IS NOT NULL), '[]'::jsonb)
                   AS active_modes
          FROM listing_scope scope
          LEFT JOIN core.store_fulfillment_declaration declaration
            ON declaration.store_id = scope.store_id
           AND declaration.status = 'ACTIVE'
           AND declaration.effective_from <= p_as_of
           AND (declaration.effective_to IS NULL
                OR declaration.effective_to > p_as_of)
         GROUP BY scope.id
    ), selected_scope AS (
        SELECT scope.*,
               coalesce(CASE WHEN scope.proposed_parameters ? 'fulfillmentModeCode'
                        THEN scope.proposed_parameters ->> 'fulfillmentModeCode' END,
                   CASE WHEN inventory.active_mode_count = 1
                        THEN inventory.only_mode END) AS fulfillment_mode_code,
               inventory.active_mode_count, inventory.active_modes
          FROM listing_scope scope
          JOIN mode_inventory inventory ON inventory.id = scope.id
    ), selected_mode AS (
        SELECT scope.*,
               (SELECT count(*)
                  FROM core.store_fulfillment_declaration declaration
                 WHERE declaration.store_id = scope.store_id
                   AND declaration.fulfillment_mode_code = scope.fulfillment_mode_code
                   AND declaration.fulfillment_mode_code <> 'UNKNOWN'
                   AND declaration.status = 'ACTIVE'
                   AND declaration.effective_from <= p_as_of
                   AND (declaration.effective_to IS NULL
                        OR declaration.effective_to > p_as_of))
                   AS selected_mode_active_count
          FROM selected_scope scope
    ), current_profiles AS (
        SELECT profile.*
          FROM selected_mode scope
          JOIN core.economics_projection_profile profile
            ON profile.organization_id = scope.organization_id
           AND profile.platform_code = scope.platform_code
           AND profile.marketplace_account_id = scope.marketplace_account_id
           AND profile.store_id = scope.store_id
           AND profile.fulfillment_mode_code = scope.fulfillment_mode_code
           AND profile.status = 'ACTIVE'
           AND profile.effective_from <= p_as_of
           AND (profile.effective_to IS NULL
                OR profile.effective_to > p_as_of)
    ), profile_resolution AS (
        SELECT count(*) AS profile_count FROM current_profiles
    ), single_profile AS (
        SELECT profile.*
          FROM current_profiles profile
         WHERE (SELECT profile_count FROM profile_resolution) = 1
    ), family_contract AS (
        SELECT coalesce(jsonb_agg(jsonb_build_object(
                   'familyCode', family.family_code,
                   'applicability', family.applicability_state,
                   'evidenceReference', family.evidence_reference)
                   ORDER BY family.family_code COLLATE "C"), '[]'::jsonb) AS items
          FROM core.economics_projection_family family
         WHERE family.profile_id = (SELECT id FROM single_profile)
    ), all_components AS (
        SELECT coalesce(jsonb_agg(jsonb_build_object(
                   'id', component.id,
                   'componentCode', component.component_code,
                   'familyCode', component.family_code,
                   'componentKind', component.component_kind,
                   'fixedAmount', component.fixed_amount,
                   'rateValue', component.rate_value,
                   'lowerPriceInclusive', component.lower_price_inclusive,
                   'upperPriceExclusive', component.upper_price_exclusive,
                   'evidenceReference', component.evidence_reference)
                   ORDER BY component.family_code COLLATE "C",
                            component.component_code COLLATE "C",
                            component.lower_price_inclusive NULLS FIRST,
                            component.id), '[]'::jsonb) AS items
          FROM core.economics_projection_component component
         WHERE component.profile_id = (SELECT id FROM single_profile)
    ), selected_components AS (
        SELECT coalesce(jsonb_agg(jsonb_build_object(
                   'id', component.id,
                   'componentCode', component.component_code,
                   'familyCode', component.family_code)
                   ORDER BY component.family_code COLLATE "C",
                            component.component_code COLLATE "C", component.id),
                   '[]'::jsonb) AS items
          FROM core.economics_projection_component component
          JOIN selected_mode scope ON true
         WHERE component.profile_id = (SELECT id FROM single_profile)
           AND scope.target_price IS NOT NULL
           AND (component.lower_price_inclusive IS NULL
                OR scope.target_price >= component.lower_price_inclusive)
           AND (component.upper_price_exclusive IS NULL
                OR scope.target_price < component.upper_price_exclusive)
    ), latest_watermarks AS (
        SELECT DISTINCT ON (watermark.feed_code)
               watermark.*
          FROM selected_mode scope
          JOIN core.source_feed_watermark watermark
            ON watermark.organization_id = scope.organization_id
           AND watermark.platform_code = scope.platform_code
           AND watermark.marketplace_account_id = scope.marketplace_account_id
           AND watermark.store_id = scope.store_id
           AND watermark.verification_state = 'VERIFIED'
           AND watermark.recorded_at <= p_as_of
         ORDER BY watermark.feed_code, watermark.recorded_at DESC,
                  watermark.id DESC
    ), watermarks AS (
        SELECT coalesce(jsonb_agg(jsonb_build_object(
                   'id', watermark.id, 'feedCode', watermark.feed_code,
                   'sourceUpdatedAt', watermark.source_updated_at,
                   'ingestedAt', watermark.ingested_at,
                   'reconciledAt', watermark.reconciled_at,
                   'effectiveAt', coalesce(watermark.reconciled_at,
                       watermark.source_updated_at, watermark.ingested_at),
                   'evidenceReference', watermark.evidence_reference)
                   ORDER BY watermark.feed_code COLLATE "C"), '[]'::jsonb) AS items
          FROM latest_watermarks watermark
    )
    SELECT ops.price_authority_snapshot_v1_at(scope.id, p_as_of)
           || jsonb_build_object('economics', jsonb_build_object(
               'parameterContractValid', scope.parameter_contract_valid,
               'targetPrice', scope.target_price,
               'fulfillmentModeCode', scope.fulfillment_mode_code,
               'activeFulfillmentModeCount', scope.active_mode_count,
               'activeFulfillmentModes', scope.active_modes,
               'selectedModeActiveCount', scope.selected_mode_active_count,
               'profileCount', resolution.profile_count,
               'profile', CASE WHEN profile.id IS NULL THEN NULL
                    ELSE jsonb_build_object(
                        'id', profile.id, 'version', profile.profile_version,
                        'organizationId', profile.organization_id,
                        'platformCode', profile.platform_code,
                        'accountId', profile.marketplace_account_id,
                        'storeId', profile.store_id,
                        'fulfillmentModeCode', profile.fulfillment_mode_code,
                        'currencyCode', profile.currency_code,
                        'effectiveFrom', profile.effective_from,
                        'effectiveTo', profile.effective_to,
                        'verificationState', profile.verification_state,
                        'verifiedAt', profile.verified_at,
                        'verificationExpiresAt', profile.verification_expires_at,
                        'evidenceReference', profile.evidence_reference,
                        'minimumSupportedPrice', profile.minimum_supported_price,
                        'maximumSupportedPrice', profile.maximum_supported_price)
                    END,
               'familyContract', families.items,
               'allComponents', components.items,
               'selectedComponents', selected.items,
               'watermarks', watermarks.items))
      FROM selected_mode scope
      CROSS JOIN profile_resolution resolution
      LEFT JOIN single_profile profile ON true
      CROSS JOIN family_contract families
      CROSS JOIN all_components components
      CROSS JOIN selected_components selected
      CROSS JOIN watermarks
$$;
REVOKE ALL ON FUNCTION ops.price_authority_snapshot(uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.price_authority_snapshot(uuid, timestamptz) TO marketops_app;

-- Current-state callers (approval, command and worker) deliberately capture a
-- fresh statement instant. Guardrail capture uses the table-returning function
-- below so Java receives the exact same database instant used by the snapshot.
CREATE FUNCTION ops.price_authority_snapshot(p_recommendation_id uuid)
RETURNS jsonb
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT ops.price_authority_snapshot(p_recommendation_id, statement_timestamp())
$$;
REVOKE ALL ON FUNCTION ops.price_authority_snapshot(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.price_authority_snapshot(uuid) TO marketops_app;

CREATE FUNCTION ops.capture_price_authority_snapshot(p_recommendation_id uuid)
RETURNS TABLE (evaluation_as_of timestamptz, authority_snapshot jsonb)
LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT captured.at,
           ops.price_authority_snapshot(p_recommendation_id, captured.at)
      FROM (SELECT statement_timestamp() AS at) captured
$$;
REVOKE ALL ON FUNCTION ops.capture_price_authority_snapshot(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.capture_price_authority_snapshot(uuid) TO marketops_app;

-- One database predicate closes profile ambiguity/component coverage and all
-- eight feed ages against the transaction's own statement time. A new metric
-- row is neither read nor required for an authority to age out.
CREATE FUNCTION ops.r2_price_authority_is_current(p_snapshot jsonb, p_at timestamptz)
RETURNS boolean
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    max_age bigint;
    profile jsonb;
    family_count integer;
    watermark_count integer;
BEGIN
    IF p_snapshot IS NULL OR p_at IS NULL THEN RETURN false; END IF;
    SELECT (item ->> 'duration_seconds')::bigint INTO max_age
      FROM jsonb_array_elements(coalesce(p_snapshot -> 'policyLimits', '[]'::jsonb)) item
     WHERE item ->> 'limit_code' = 'MAX_INPUT_AGE_SECONDS';
    profile := p_snapshot #> '{economics,profile}';
    IF max_age IS NULL OR max_age < 0 OR profile IS NULL
       OR coalesce((p_snapshot #>> '{economics,parameterContractValid}')::boolean,
                   false) IS NOT TRUE
       OR (p_snapshot #>> '{economics,targetPrice}') IS NULL
       OR (p_snapshot #>> '{economics,fulfillmentModeCode}') IS NULL
       OR (p_snapshot #>> '{economics,fulfillmentModeCode}') = 'UNKNOWN'
       OR (p_snapshot #>> '{economics,profileCount}')::integer <> 1
       OR (p_snapshot #>> '{economics,selectedModeActiveCount}')::integer <> 1
       OR profile ->> 'verificationState' NOT IN (
            'ENGINEERING_VERIFIED', 'REAL_ACCOUNT_VERIFIED')
       OR (profile ->> 'verifiedAt')::timestamptz > p_at
       OR (profile ->> 'effectiveFrom')::timestamptz > p_at
       OR (profile ->> 'effectiveTo') IS NOT NULL
          AND (profile ->> 'effectiveTo')::timestamptz <= p_at
       OR (profile ->> 'verificationExpiresAt') IS NOT NULL
          AND (profile ->> 'verificationExpiresAt')::timestamptz <= p_at THEN
        RETURN false;
    END IF;

    SELECT count(*) INTO family_count
      FROM jsonb_array_elements(
          coalesce(p_snapshot #> '{economics,familyContract}', '[]'::jsonb));
    IF family_count <> 8 THEN RETURN false; END IF;

    -- Every required family has component authority. Every component code has
    -- exactly one price-applicable tier; overlap and gaps both fail closed.
    IF EXISTS (
        SELECT 1
          FROM jsonb_array_elements(
                   p_snapshot #> '{economics,familyContract}') family
         WHERE family ->> 'applicability' = 'REQUIRED'
           AND NOT EXISTS (
               SELECT 1 FROM jsonb_array_elements(
                   p_snapshot #> '{economics,allComponents}') component
                WHERE component ->> 'familyCode' = family ->> 'familyCode'))
       OR EXISTS (
        SELECT 1
          FROM jsonb_array_elements(
                   p_snapshot #> '{economics,allComponents}') component
          JOIN jsonb_array_elements(
                   p_snapshot #> '{economics,familyContract}') family
            ON family ->> 'familyCode' = component ->> 'familyCode'
         WHERE family ->> 'applicability' = 'VERIFIED_NOT_APPLICABLE')
       OR EXISTS (
        SELECT 1
          FROM (SELECT component ->> 'familyCode' AS family_code,
                       component ->> 'componentCode' AS component_code
                  FROM jsonb_array_elements(
                      p_snapshot #> '{economics,allComponents}') component
                 GROUP BY 1, 2) authority
         WHERE (SELECT count(*)
                  FROM jsonb_array_elements(
                      p_snapshot #> '{economics,selectedComponents}') selected
                 WHERE selected ->> 'familyCode' = authority.family_code
                   AND selected ->> 'componentCode' = authority.component_code) <> 1) THEN
        RETURN false;
    END IF;

    SELECT count(DISTINCT watermark ->> 'feedCode') INTO watermark_count
      FROM jsonb_array_elements(
          coalesce(p_snapshot #> '{economics,watermarks}', '[]'::jsonb)) watermark;
    IF watermark_count <> 8 OR EXISTS (
        SELECT 1
          FROM unnest(ARRAY['PRICE','STOCK','SALES','RETURNS','FINANCE_FEES',
                            'ADVERTISING','INTERNAL_COST','COMMERCIAL_INPUTS']) feed
         WHERE NOT EXISTS (
             SELECT 1 FROM jsonb_array_elements(
                 p_snapshot #> '{economics,watermarks}') watermark
              WHERE watermark ->> 'feedCode' = feed)
            OR EXISTS (
             SELECT 1 FROM jsonb_array_elements(
                 p_snapshot #> '{economics,watermarks}') watermark
              WHERE watermark ->> 'feedCode' = feed
                AND ((watermark ->> 'effectiveAt') IS NULL
                  OR (watermark ->> 'effectiveAt')::timestamptz > p_at
                  OR extract(epoch FROM (
                      p_at - (watermark ->> 'effectiveAt')::timestamptz)) > max_age))) THEN
        RETURN false;
    END IF;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION ops.r2_price_authority_is_current(jsonb, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.r2_price_authority_is_current(jsonb, timestamptz)
    TO marketops_app;

CREATE OR REPLACE FUNCTION ops.bind_price_authority_snapshot()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE snapshot jsonb;
        evaluated_snapshot jsonb;
BEGIN
    SELECT ops.price_authority_snapshot(NEW.recommendation_id) INTO snapshot;
    IF snapshot IS NULL OR snapshot #>> '{proposal,organizationId}'
            IS DISTINCT FROM NEW.organization_id::text THEN
        RAISE EXCEPTION 'recommendation ownership does not match' USING ERRCODE = 'MO032';
    END IF;
    IF TG_TABLE_NAME = 'guardrail_evaluation' THEN
        SELECT ops.price_authority_snapshot(NEW.recommendation_id, NEW.evaluated_at)
          INTO evaluated_snapshot;
        IF NEW.authority_snapshot IS DISTINCT FROM evaluated_snapshot THEN
            RAISE EXCEPTION 'guardrail inputs do not match evaluation as-of authority'
                USING ERRCODE = 'MO032';
        END IF;
        IF NEW.outcome = 'PASS'
           AND (NEW.authority_snapshot IS DISTINCT FROM snapshot
                OR NOT ops.r2_price_authority_is_current(
                    NEW.authority_snapshot, statement_timestamp())) THEN
            RAISE EXCEPTION 'guardrail authority is stale or incomplete'
                USING ERRCODE = 'MO032';
        END IF;
    ELSIF TG_TABLE_NAME = 'approval_decision' THEN
        IF NEW.decision IN ('APPROVED', 'POLICY_AUTHORIZED') THEN
            IF NOT ops.r2_price_authority_is_current(snapshot, statement_timestamp())
               OR NEW.entity_version_digest IS DISTINCT FROM snapshot ->> 'currentEntityDigest'
               OR NEW.entity_version_digest IS DISTINCT FROM snapshot #>> '{proposal,entityDigest}'
               OR NOT EXISTS (
                   SELECT 1 FROM ops.guardrail_evaluation g
                    WHERE g.recommendation_id = NEW.recommendation_id
                      AND g.organization_id = NEW.organization_id
                      AND g.purpose = 'APPROVAL' AND g.outcome = 'PASS'
                      AND g.authority_snapshot = snapshot) THEN
                RAISE EXCEPTION 'approval has no matching current guardrail authority'
                    USING ERRCODE = 'MO032';
            END IF;
        END IF;
        NEW.authority_snapshot := snapshot;
    ELSE
        IF NOT ops.r2_price_authority_is_current(snapshot, statement_timestamp()) THEN
            RAISE EXCEPTION 'command authority is stale or incomplete'
                USING ERRCODE = 'MO032';
        END IF;
        NEW.authority_snapshot := snapshot;
        NEW.fulfillment_mode_code := snapshot #>> '{economics,fulfillmentModeCode}';
    END IF;
    RETURN NEW;
END;
$$;

-- The selected economics mode is durable command authority. The trigger writes
-- it from the captured snapshot; the predicates below require it to remain
-- identical through command creation and every worker/write-gate recheck.
ALTER TABLE ops.price_command ADD COLUMN fulfillment_mode_code text;
ALTER TABLE ops.price_command
    ADD CONSTRAINT price_command_fulfillment_mode_fk
        FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode (code)
        NOT VALID,
    ADD CONSTRAINT price_command_fulfillment_mode_ck
        CHECK (fulfillment_mode_code IS NULL OR
               (fulfillment_mode_code ~ '^[A-Z][A-Z0-9_]{1,62}$'
                AND fulfillment_mode_code <> 'UNKNOWN')) NOT VALID;

ALTER FUNCTION ops.price_command_authority_matches(uuid)
    RENAME TO price_command_authority_matches_v1;

CREATE OR REPLACE FUNCTION ops.price_command_authority_matches_v1(p_command_id uuid)
RETURNS boolean LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT coalesce(bool_and(
        c.authority_snapshot = ops.price_authority_snapshot(r.id)
        AND a.authority_snapshot = c.authority_snapshot
        AND a.recommendation_id = r.id AND a.organization_id = c.organization_id
        AND r.organization_id = c.organization_id AND r.store_id = c.store_id
        AND r.subject_kind = 'PLATFORM_LISTING_VARIANT'
        AND r.subject_id = c.platform_listing_variant_id AND r.action_kind = 'PRICE_CHANGE'
        AND c.entity_version_digest = r.entity_version_digest
        AND c.entity_version_digest = c.authority_snapshot ->> 'currentEntityDigest'
        AND c.authority_snapshot #>> '{mapping,id}' IS NOT NULL
        AND c.authority_snapshot #>> '{policy,id}' IS NOT NULL
        AND ops.price_change_parameter_contract_is_valid(r.proposed_parameters)
        AND c.fulfillment_mode_code =
            c.authority_snapshot #>> '{economics,fulfillmentModeCode}'
        AND c.idempotency_key = 'pc-' || r.id::text
        AND c.target_price = CASE WHEN (r.proposed_parameters ->> 'targetPrice')
            ~ '^[0-9]{1,14}([.][0-9]{1,4})?$'
            THEN (r.proposed_parameters ->> 'targetPrice')::numeric END
        AND c.prior_price = (c.authority_snapshot #>> '{prior,price}')::numeric
        AND c.prior_price > 0 AND c.target_price <> c.prior_price
        AND c.currency_code = c.authority_snapshot #>> '{prior,currency}'
        AND c.prior_price_observation_id::text = c.authority_snapshot #>> '{prior,id}'
        AND c.platform_code = c.authority_snapshot ->> 'platformCode'
        AND cap.capability_code = 'price-change' AND cap.read_write_class = 'WRITE'
        AND cap.platform_code = c.platform_code
        AND EXISTS (SELECT 1 FROM core.organization org
                    JOIN core.store store ON store.organization_id=org.id
                    JOIN core.marketplace_account account ON account.id=store.marketplace_account_id
                    JOIN core.legal_entity legal ON legal.id=account.legal_entity_id
                    JOIN core.platform_listing listing ON listing.store_id=store.id
                    JOIN core.platform_listing_variant variant ON variant.platform_listing_id=listing.id
                    JOIN core.product_variant sku ON sku.id=(c.authority_snapshot #>> '{mapping,product_variant_id}')::uuid
                    JOIN core.product product ON product.id=sku.product_id
                    WHERE org.id=c.organization_id AND store.id=c.store_id AND variant.id=c.platform_listing_variant_id
                      AND org.status='ACTIVE' AND store.status='ACTIVE' AND account.status='ACTIVE'
                      AND legal.status='ACTIVE' AND listing.status='OBSERVED' AND variant.status='OBSERVED'
                      AND sku.status='ACTIVE' AND product.status='ACTIVE')
        AND EXISTS (SELECT 1 FROM ops.guardrail_evaluation g
                     WHERE g.recommendation_id = r.id AND g.organization_id = c.organization_id
                       AND g.purpose = 'EXECUTION' AND g.outcome = 'PASS'
                       AND g.authority_snapshot = c.authority_snapshot
                       AND g.policy_id::text = c.authority_snapshot #>> '{policy,id}'
                       AND g.policy_version::text = c.authority_snapshot #>> '{policy,policy_version}')
    ), false)
      FROM ops.price_command c
      JOIN ops.recommendation r ON r.id = c.recommendation_id
      JOIN ops.approval_decision a ON a.id = c.approval_decision_id
      JOIN platform.platform_capability cap ON cap.id = c.capability_id
     WHERE c.id = p_command_id
$$;

CREATE FUNCTION ops.price_command_authority_matches(p_command_id uuid)
RETURNS boolean LANGUAGE sql STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT ops.price_command_authority_matches_v1(p_command_id)
       AND ops.r2_price_authority_is_current(
            command.authority_snapshot, statement_timestamp())
      FROM ops.price_command command
     WHERE command.id = p_command_id
$$;
REVOKE ALL ON FUNCTION ops.price_command_authority_matches(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.price_command_authority_matches(uuid) TO marketops_app;

CREATE OR REPLACE FUNCTION ops.create_price_command(
    p_recommendation_id uuid, p_expected_version bigint,
    p_actor_id uuid, p_correlation_id text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    r ops.recommendation%ROWTYPE;
    a ops.approval_decision%ROWTYPE;
    snapshot jsonb;
    capability uuid;
    command_id uuid;
    target numeric;
    fulfillment_mode text;
BEGIN
    SELECT * INTO r FROM ops.recommendation WHERE id = p_recommendation_id FOR UPDATE;
    IF r.id IS NULL OR r.version IS DISTINCT FROM p_expected_version
       OR p_correlation_id IS NULL OR length(btrim(p_correlation_id)) NOT BETWEEN 1 AND 128
       OR NOT EXISTS (
           SELECT 1 FROM iam.user_account u
           JOIN iam.user_role_assignment role ON role.user_id = u.id AND role.status = 'ACTIVE'
           JOIN iam.business_role_action_scope action ON action.role_code = role.role_code
           JOIN iam.user_scope_grant grant_row ON grant_row.user_id = u.id
           JOIN core.store s ON s.id = r.store_id AND s.organization_id = u.organization_id
           JOIN core.marketplace_account account ON account.id = s.marketplace_account_id
            WHERE u.id = p_actor_id AND u.status = 'ACTIVE'
              AND u.organization_id = r.organization_id
              AND action.action_code = 'PRICE_CHANGE_APPROVE'
              AND role.effective_from <= clock_timestamp()
              AND (role.effective_to IS NULL OR role.effective_to > clock_timestamp())
              AND grant_row.action_code = action.action_code AND grant_row.status = 'ACTIVE'
              AND grant_row.effective_from <= clock_timestamp()
              AND (grant_row.effective_to IS NULL OR grant_row.effective_to > clock_timestamp())
              AND (grant_row.organization_ref_id = r.organization_id
                   OR grant_row.store_ref_id = r.store_id
                   OR grant_row.marketplace_account_ref_id = account.id
                   OR grant_row.legal_entity_ref_id = account.legal_entity_id)) THEN
        RAISE EXCEPTION 'command actor, scope or version invalid' USING ERRCODE = 'MO032';
    END IF;
    SELECT id INTO command_id FROM ops.price_command WHERE recommendation_id = r.id;
    IF command_id IS NOT NULL THEN RETURN command_id; END IF;
    IF r.state NOT IN ('APPROVED', 'POLICY_AUTHORIZED')
       OR r.action_kind <> 'PRICE_CHANGE'
       OR r.subject_kind <> 'PLATFORM_LISTING_VARIANT'
       OR r.valid_until <= clock_timestamp()
       OR NOT ops.price_change_parameter_contract_is_valid(r.proposed_parameters) THEN
        RAISE EXCEPTION 'proposal is not an executable price change' USING ERRCODE = 'MO032';
    END IF;
    snapshot := ops.price_authority_snapshot(r.id);
    SELECT * INTO a FROM ops.approval_decision
     WHERE recommendation_id = r.id AND decision IN ('APPROVED', 'POLICY_AUTHORIZED')
       AND scope_expires_at > clock_timestamp() AND authority_snapshot = snapshot;
    SELECT id INTO capability FROM platform.platform_capability
     WHERE platform_code = snapshot ->> 'platformCode' AND capability_code = 'price-change'
       AND read_write_class = 'WRITE' AND verification_state = 'VERIFIED'
       AND status = 'ACTIVE' AND deprecated_at IS NULL;
    fulfillment_mode := snapshot #>> '{economics,fulfillmentModeCode}';
    IF a.id IS NULL OR capability IS NULL OR snapshot #>> '{prior,id}' IS NULL
       OR snapshot #>> '{mapping,id}' IS NULL OR snapshot #>> '{policy,id}' IS NULL
       OR fulfillment_mode IS NULL OR fulfillment_mode = 'UNKNOWN'
       OR snapshot ->> 'currentEntityDigest' IS DISTINCT FROM r.entity_version_digest
       OR NOT ops.r2_price_authority_is_current(snapshot, statement_timestamp()) THEN
        RAISE EXCEPTION 'current authorization or canonical facts missing'
            USING ERRCODE = 'MO032';
    END IF;
    target := (r.proposed_parameters ->> 'targetPrice')::numeric;
    command_id := gen_random_uuid();
    INSERT INTO ops.price_command (
        id, organization_id, recommendation_id, approval_decision_id,
        store_id, platform_listing_variant_id, platform_code, capability_id,
        idempotency_key, currency_code, prior_price, target_price,
        prior_price_observation_id, entity_version_digest, fulfillment_mode_code,
        state, retry_budget_remaining, next_attempt_at, created_at, updated_at)
    VALUES (command_id, r.organization_id, r.id, a.id, r.store_id, r.subject_id,
        snapshot ->> 'platformCode', capability, 'pc-' || r.id::text,
        snapshot #>> '{prior,currency}', (snapshot #>> '{prior,price}')::numeric, target,
        (snapshot #>> '{prior,id}')::uuid, r.entity_version_digest, fulfillment_mode,
        'PENDING', 3, clock_timestamp(), clock_timestamp(), clock_timestamp());
    IF NOT ops.price_command_authority_matches(command_id) THEN
        RAISE EXCEPTION 'command inputs do not match approved authority' USING ERRCODE = 'MO032';
    END IF;
    INSERT INTO ops.metadata_audit_event (
        id,actor_type,actor_id,source_domain,action,entity_type,entity_id,
        entity_code,change_summary,correlation_id)
    VALUES (gen_random_uuid(),'OPERATOR',p_actor_id::text,'marketplaceintegration',
        'COMMAND_TRANSITION','price-command',command_id,'pc-'||r.id::text,
        jsonb_build_object('recommendationId',jsonb_build_object(
            'oldValue',NULL,'newValue',r.id::text)),p_correlation_id);
    RETURN command_id;
END;
$$;
