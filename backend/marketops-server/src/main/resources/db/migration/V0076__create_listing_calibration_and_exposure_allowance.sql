-- SLICE-V1-004: Owner-published calibration packages and exposure allowances,
-- and the third authority a guardrail verdict may name.
--
-- Every threshold this Slice consumes is a published value with a unit, a
-- scope, a window and an evidence reference. Consumption resolves the one
-- active package for a scope and purpose; a missing or conflicting package
-- blocks only its consumers and produces CALIBRATION_UNRESOLVED, never a code
-- default. A package becomes ACTIVE only when every consumed category is
-- present, and an active package cannot overlap another at the same scope.
--
-- The exposure allowance is the cumulative bound on what may be exposed at
-- once. Each axis is independent: surplus on one never offsets another, and
-- the disposal reserve on each axis is never occupied by a launch.
--
-- No Java writes any table in this migration. The sole-authority scan refuses
-- one, exactly as for advertising policy.

-- ---------------------------------------------------------------------------
-- Calibration packages
-- ---------------------------------------------------------------------------

CREATE TABLE core.lc_calibration_package (
    id                   uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    package_code         text        NOT NULL,
    package_version      integer     NOT NULL,
    scope_kind           text        NOT NULL,
    platform_code        text,
    store_ref_id         uuid,
    scope_key            text GENERATED ALWAYS AS (
        scope_kind || ':' || coalesce(platform_code, '') || ':'
            || coalesce(CAST(store_ref_id AS text), '')) STORED,
    status               text        NOT NULL,
    published_by_user_id uuid        NOT NULL,
    published_at         timestamptz NOT NULL,
    evidence_reference   text        NOT NULL,
    effective_from       timestamptz NOT NULL,
    effective_to         timestamptz,
    activated_at         timestamptz,
    retired_at           timestamptz,
    CONSTRAINT lc_calibration_package_pk PRIMARY KEY (id),
    CONSTRAINT lc_calibration_package_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_calibration_package_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT lc_calibration_package_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT lc_calibration_package_store_fk
        FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_calibration_package_publisher_fk
        FOREIGN KEY (published_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_calibration_package_version_uq
        UNIQUE (organization_id, package_code, package_version),
    CONSTRAINT lc_calibration_package_code_ck CHECK (package_code ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT lc_calibration_package_version_ck CHECK (package_version >= 1),
    CONSTRAINT lc_calibration_package_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE')),
    CONSTRAINT lc_calibration_package_scope_shape_ck
        CHECK ((scope_kind = 'ORGANIZATION' AND platform_code IS NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'PLATFORM' AND platform_code IS NOT NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'STORE' AND platform_code IS NULL AND store_ref_id IS NOT NULL)),
    CONSTRAINT lc_calibration_package_status_ck CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT lc_calibration_package_activation_ck
        CHECK ((status = 'ACTIVE') = (activated_at IS NOT NULL AND retired_at IS NULL)),
    CONSTRAINT lc_calibration_package_retirement_ck
        CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL)),
    CONSTRAINT lc_calibration_package_range_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT lc_calibration_package_reference_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT lc_calibration_package_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            scope_key WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE INDEX lc_calibration_package_scope_ix
    ON core.lc_calibration_package (organization_id, scope_kind, status);

-- Every category a consumer reads. A package missing one cannot activate;
-- a consumer never substitutes a default for a category that is absent.
CREATE TABLE core.lc_calibration_category (
    code        text    NOT NULL,
    display_name text   NOT NULL,
    value_shape text    NOT NULL,
    ordinal     integer NOT NULL,
    CONSTRAINT lc_calibration_category_pk PRIMARY KEY (code),
    CONSTRAINT lc_calibration_category_code_ck CHECK (code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    CONSTRAINT lc_calibration_category_shape_ck
        CHECK (value_shape IN ('NUMERIC', 'TEXT', 'JSON')),
    CONSTRAINT lc_calibration_category_ordinal_uq UNIQUE (ordinal)
);

INSERT INTO core.lc_calibration_category (code, display_name, value_shape, ordinal) VALUES
    ('MATERIAL_IMPROVEMENT_BOUND', 'Material improvement bound', 'NUMERIC', 1),
    ('NON_WORSENING_PROFIT_BOUND', 'Non-worsening profit bound', 'NUMERIC', 2),
    ('NON_WORSENING_RETURN_BOUND', 'Non-worsening return rate bound', 'NUMERIC', 3),
    ('CRITICAL_GROUP_RULE', 'Critical group rule', 'JSON', 4),
    ('DEMAND_SCENARIO_SET', 'Demand scenario set', 'JSON', 5),
    ('FRESHNESS_RULE', 'Freshness rule', 'JSON', 6),
    ('RESPONSIBILITY_SLO', 'Responsibility service level', 'JSON', 7),
    ('RESPONSIBILITY_COVERAGE', 'Responsibility coverage', 'JSON', 8),
    ('ORDINARY_TRIGGER_CONTENT', 'Ordinary trigger on the content axis', 'NUMERIC', 9),
    ('MATERIAL_TRIGGER_CONTENT', 'Material trigger on the content axis', 'NUMERIC', 10),
    ('ORDINARY_TRIGGER_EXPOSURE', 'Ordinary trigger on the exposure axis', 'NUMERIC', 11),
    ('MATERIAL_TRIGGER_EXPOSURE', 'Material trigger on the exposure axis', 'NUMERIC', 12),
    ('APPROVAL_VALIDITY', 'Approval validity', 'NUMERIC', 13),
    ('REPRESENTATION_EQUIVALENCE_RULE', 'Representation equivalence rule', 'TEXT', 14),
    ('ALLOWANCE_AXES', 'Allowance axes', 'JSON', 15),
    ('ALLOWANCE_RESERVE', 'Allowance disposal reserve', 'JSON', 16),
    ('FORMAL_NODES', 'Formal evaluation nodes', 'JSON', 17),
    ('STOP_RULE', 'Effect shortfall stop rule', 'JSON', 18),
    ('CROSS_PERIOD_WINDOW', 'Cross-period profit window', 'NUMERIC', 19),
    ('DESCRIPTION_LENGTH_RULE', 'Verified description length bound', 'JSON', 20);

CREATE TABLE core.lc_calibration_value (
    id                 uuid           NOT NULL,
    package_id         uuid           NOT NULL,
    category_code      text           NOT NULL,
    value_numeric      numeric(18, 6),
    value_text         text,
    value_json         jsonb,
    unit_code          text           NOT NULL,
    scope_note         text           NOT NULL,
    window_days        integer,
    evidence_reference text           NOT NULL,
    CONSTRAINT lc_calibration_value_pk PRIMARY KEY (id),
    CONSTRAINT lc_calibration_value_package_fk
        FOREIGN KEY (package_id) REFERENCES core.lc_calibration_package (id),
    CONSTRAINT lc_calibration_value_category_fk
        FOREIGN KEY (category_code) REFERENCES core.lc_calibration_category (code),
    CONSTRAINT lc_calibration_value_uq UNIQUE (package_id, category_code),
    CONSTRAINT lc_calibration_value_present_ck
        CHECK (num_nonnulls(value_numeric, value_text, value_json) = 1),
    CONSTRAINT lc_calibration_value_unit_ck CHECK (unit_code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    CONSTRAINT lc_calibration_value_note_ck CHECK (length(btrim(scope_note)) BETWEEN 1 AND 512),
    CONSTRAINT lc_calibration_value_window_ck CHECK (window_days IS NULL OR window_days BETWEEN 1 AND 3660),
    CONSTRAINT lc_calibration_value_reference_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    -- The representation equivalence rule is one of two words, because a rule
    -- nobody can state is not a rule the readback can apply.
    CONSTRAINT lc_calibration_value_equivalence_ck
        CHECK (category_code <> 'REPRESENTATION_EQUIVALENCE_RULE'
            OR value_text IN ('EXACT', 'WHITESPACE_NORMALIZED'))
);

-- The value shape a category demands must be the shape the row carries.
CREATE FUNCTION core.lc_calibration_value_matches_shape()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, core, pg_temp
AS $$
DECLARE shape text;
BEGIN
    SELECT value_shape INTO shape FROM core.lc_calibration_category WHERE code = NEW.category_code;
    IF (shape = 'NUMERIC' AND NEW.value_numeric IS NULL)
        OR (shape = 'TEXT' AND NEW.value_text IS NULL)
        OR (shape = 'JSON' AND NEW.value_json IS NULL) THEN
        RAISE EXCEPTION 'calibration value % must carry a % value', NEW.category_code, shape
            USING ERRCODE = 'MO036';
    END IF;
    IF EXISTS (SELECT 1 FROM core.lc_calibration_package p
                WHERE p.id = NEW.package_id AND p.status <> 'DRAFT') THEN
        RAISE EXCEPTION 'a calibration value may only be written while its package is a draft'
            USING ERRCODE = 'MO036';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION core.lc_calibration_value_matches_shape() FROM PUBLIC;
CREATE TRIGGER lc_calibration_value_matches_shape
    BEFORE INSERT OR UPDATE ON core.lc_calibration_value
    FOR EACH ROW EXECUTE FUNCTION core.lc_calibration_value_matches_shape();

-- Every category that an active package lacks.
CREATE FUNCTION core.lc_calibration_package_failures(p_package uuid)
RETURNS text[]
LANGUAGE sql STABLE
SET search_path = pg_catalog, core, pg_temp
AS $$
    SELECT coalesce(array_agg(c.code ORDER BY c.ordinal), '{}')
      FROM core.lc_calibration_category c
     WHERE NOT EXISTS (SELECT 1 FROM core.lc_calibration_value v
                        WHERE v.package_id = p_package AND v.category_code = c.code)
$$;
REVOKE ALL ON FUNCTION core.lc_calibration_package_failures(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lc_calibration_package_failures(uuid) TO marketops_app;

-- Complete-combination activation. A package that lacks a category cannot be
-- ACTIVE however it was written, and an activated package cannot change scope.
CREATE FUNCTION core.lc_calibration_package_activates_complete()
RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, core, pg_temp
AS $$
DECLARE missing text[];
BEGIN
    IF NEW.status = 'ACTIVE' AND (TG_OP = 'INSERT' OR OLD.status <> 'ACTIVE') THEN
        missing := core.lc_calibration_package_failures(NEW.id);
        IF cardinality(missing) > 0 THEN
            RAISE EXCEPTION 'calibration package is incomplete: %', array_to_string(missing, ',')
                USING ERRCODE = 'MO036';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status <> 'DRAFT'
        AND (NEW.scope_key <> OLD.scope_key OR NEW.package_code <> OLD.package_code
             OR NEW.package_version <> OLD.package_version OR NEW.effective_from <> OLD.effective_from) THEN
        RAISE EXCEPTION 'an activated calibration package keeps its identity and scope'
            USING ERRCODE = 'MO036';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED' THEN
        RAISE EXCEPTION 'a retired calibration package is not revived' USING ERRCODE = 'MO036';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION core.lc_calibration_package_activates_complete() FROM PUBLIC;
CREATE TRIGGER lc_calibration_package_activates_complete
    BEFORE INSERT OR UPDATE ON core.lc_calibration_package
    FOR EACH ROW EXECUTE FUNCTION core.lc_calibration_package_activates_complete();

-- The one active package for a scope at an instant. The most specific scope
-- wins; two packages at the same precedence are a conflict, and no package is
-- unresolved. Neither is a default.
CREATE FUNCTION core.lc_resolve_calibration(
    p_org uuid, p_platform text, p_store uuid, p_at timestamptz)
RETURNS TABLE (package_id uuid, package_version integer, resolution_state text)
LANGUAGE sql STABLE
SET search_path = pg_catalog, core, pg_temp
AS $$
    WITH candidates AS (
        SELECT p.id, p.package_version,
               CASE p.scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END AS precedence
          FROM core.lc_calibration_package p
         WHERE p.organization_id = p_org
           AND p.status = 'ACTIVE'
           AND p.effective_from <= p_at
           AND (p.effective_to IS NULL OR p.effective_to > p_at)
           AND (p.scope_kind = 'ORGANIZATION'
                OR (p.scope_kind = 'PLATFORM' AND p.platform_code = p_platform)
                OR (p.scope_kind = 'STORE' AND p.store_ref_id = p_store))),
    best AS (SELECT * FROM candidates WHERE precedence = (SELECT min(precedence) FROM candidates))
    SELECT CASE WHEN (SELECT count(*) FROM best) = 1 THEN (SELECT id FROM best) END,
           CASE WHEN (SELECT count(*) FROM best) = 1 THEN (SELECT package_version FROM best) END,
           CASE (SELECT count(*) FROM best)
               WHEN 0 THEN 'CALIBRATION_UNRESOLVED'
               WHEN 1 THEN 'RESOLVED'
               ELSE 'CALIBRATION_CONFLICTED' END
$$;
REVOKE ALL ON FUNCTION core.lc_resolve_calibration(uuid, text, uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lc_resolve_calibration(uuid, text, uuid, timestamptz) TO marketops_app;

-- ---------------------------------------------------------------------------
-- Exposure allowances
-- ---------------------------------------------------------------------------

CREATE TABLE ops.lc_exposure_allowance (
    id                   uuid           NOT NULL,
    organization_id      uuid           NOT NULL,
    allowance_version    integer        NOT NULL,
    scope_kind           text           NOT NULL,
    platform_code        text,
    store_ref_id         uuid,
    scope_key            text GENERATED ALWAYS AS (
        scope_kind || ':' || coalesce(platform_code, '') || ':'
            || coalesce(CAST(store_ref_id AS text), '')) STORED,
    axis_code            text           NOT NULL,
    limit_value          numeric(18, 4) NOT NULL,
    reserve_value        numeric(18, 4) NOT NULL,
    unit_code            text           NOT NULL,
    published_by_user_id uuid           NOT NULL,
    published_at         timestamptz    NOT NULL,
    evidence_reference   text           NOT NULL,
    effective_from       timestamptz    NOT NULL,
    effective_to         timestamptz,
    status               text           NOT NULL,
    CONSTRAINT lc_exposure_allowance_pk PRIMARY KEY (id),
    CONSTRAINT lc_exposure_allowance_id_org_uq UNIQUE (id, organization_id),
    CONSTRAINT lc_exposure_allowance_organization_fk
        FOREIGN KEY (organization_id) REFERENCES core.organization (id),
    CONSTRAINT lc_exposure_allowance_platform_fk
        FOREIGN KEY (platform_code) REFERENCES core.marketplace_platform (code),
    CONSTRAINT lc_exposure_allowance_store_fk
        FOREIGN KEY (store_ref_id, organization_id) REFERENCES core.store (id, organization_id),
    CONSTRAINT lc_exposure_allowance_publisher_fk
        FOREIGN KEY (published_by_user_id, organization_id)
        REFERENCES iam.user_account (id, organization_id),
    CONSTRAINT lc_exposure_allowance_version_ck CHECK (allowance_version >= 1),
    CONSTRAINT lc_exposure_allowance_scope_ck
        CHECK (scope_kind IN ('ORGANIZATION', 'PLATFORM', 'STORE')),
    CONSTRAINT lc_exposure_allowance_scope_shape_ck
        CHECK ((scope_kind = 'ORGANIZATION' AND platform_code IS NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'PLATFORM' AND platform_code IS NOT NULL AND store_ref_id IS NULL)
            OR (scope_kind = 'STORE' AND platform_code IS NULL AND store_ref_id IS NOT NULL)),
    -- Four independent axes. A launch that fits on three and not the fourth
    -- does not launch.
    CONSTRAINT lc_exposure_allowance_axis_ck
        CHECK (axis_code IN ('CONCURRENT_LISTINGS', 'AFFECTED_VARIANTS',
                             'REVENUE_EXPOSURE', 'CATEGORY_SHARE')),
    CONSTRAINT lc_exposure_allowance_bounds_ck
        CHECK (limit_value > 0 AND reserve_value >= 0 AND reserve_value < limit_value),
    CONSTRAINT lc_exposure_allowance_unit_ck CHECK (unit_code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    CONSTRAINT lc_exposure_allowance_reference_ck
        CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 512),
    CONSTRAINT lc_exposure_allowance_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT lc_exposure_allowance_range_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT lc_exposure_allowance_no_overlap
        EXCLUDE USING gist (
            organization_id WITH =,
            scope_key WITH =,
            axis_code WITH =,
            tstzrange(effective_from, effective_to, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE INDEX lc_exposure_allowance_scope_ix
    ON ops.lc_exposure_allowance (organization_id, scope_kind, axis_code, status);

-- ---------------------------------------------------------------------------
-- A guardrail verdict may name a calibration package as its authority
-- ---------------------------------------------------------------------------

ALTER TABLE ops.guardrail_evaluation ADD COLUMN lc_calibration_package_id uuid;
ALTER TABLE ops.guardrail_evaluation ADD COLUMN lc_calibration_version integer;

ALTER TABLE ops.guardrail_evaluation
    ADD CONSTRAINT guardrail_evaluation_lc_calibration_fk
    FOREIGN KEY (lc_calibration_package_id, organization_id)
    REFERENCES core.lc_calibration_package (id, organization_id);

ALTER TABLE ops.guardrail_evaluation
    ADD CONSTRAINT guardrail_evaluation_lc_calibration_shape_ck
    CHECK ((lc_calibration_package_id IS NULL) = (lc_calibration_version IS NULL));

-- Exactly one authority on a PASS: a commercial policy, a decision bundle or a
-- calibration package. A verdict two authorities could each claim is a verdict
-- neither owns.
ALTER TABLE ops.guardrail_evaluation DROP CONSTRAINT guardrail_evaluation_policy_presence_ck;
ALTER TABLE ops.guardrail_evaluation
    ADD CONSTRAINT guardrail_evaluation_policy_presence_ck
    CHECK (outcome = 'BLOCK'
        OR ((policy_id IS NOT NULL AND policy_version IS NOT NULL)::integer
            + (ad_decision_bundle_id IS NOT NULL AND ad_bundle_version IS NOT NULL)::integer
            + (lc_calibration_package_id IS NOT NULL AND lc_calibration_version IS NOT NULL)::integer
            = 1));

CREATE INDEX guardrail_evaluation_lc_calibration_ix
    ON ops.guardrail_evaluation (lc_calibration_package_id, evaluated_at DESC)
    WHERE lc_calibration_package_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Route inventory and privileges
-- ---------------------------------------------------------------------------

INSERT INTO platform.control_route_inventory
    (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('core', 'lc_calibration_package', 'NO_ROUTE', NULL,
        'owner-published, versioned, scoped calibration package; no Java writer'),
    ('core', 'lc_calibration_category', 'NO_ROUTE', NULL,
        'the closed list of categories a complete package carries'),
    ('core', 'lc_calibration_value', 'NO_ROUTE', NULL,
        'one published value with unit, scope, window and evidence; no Java writer'),
    ('ops', 'lc_exposure_allowance', 'NO_ROUTE', NULL,
        'owner-published cumulative exposure bound per axis with disposal reserve; no Java writer');

GRANT SELECT ON core.lc_calibration_package TO marketops_app;
GRANT SELECT ON core.lc_calibration_category TO marketops_app;
GRANT SELECT ON core.lc_calibration_value TO marketops_app;
GRANT SELECT ON ops.lc_exposure_allowance TO marketops_app;
