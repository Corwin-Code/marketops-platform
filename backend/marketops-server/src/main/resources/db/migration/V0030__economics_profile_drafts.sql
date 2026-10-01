-- V0030: economics projection profiles from the marketplace's own tariffs, verified by two Owners
-- (Owner decisions 2026-10-01).
--
-- The price guardrail projects a proposed price through exactly one current, verified economics
-- projection profile per store and fulfilment mode, and nothing could create one: the application
-- role may only read core.economics_projection_profile and its families and components.
--
-- One Owner submits a draft the platform generated from the store's newest price observations: for
-- each fee, the highest value any listing of the store states (the Owner's rule of 2026-09-29 takes
-- the highest logistics tier, and the store-wide profile takes the highest of every fee). A second,
-- different Owner approves it; ops.publish_economics_profile then writes the profile, its eight
-- families and its components in one transaction, retires the scope's previous profile and records
-- who approved. The tariffs come from the real account, so the profile is REAL_ACCOUNT_VERIFIED, and
-- its verification expires (30 days unless stated), because the marketplace changes its tariffs.
CREATE TABLE ops.economics_profile_draft (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    platform_code text NOT NULL,
    marketplace_account_id uuid NOT NULL,
    fulfillment_mode_code text NOT NULL,
    currency_code text NOT NULL,
    payload jsonb NOT NULL,
    evidence_reference text NOT NULL,
    submitted_by_user_id uuid NOT NULL,
    submitted_at timestamp with time zone NOT NULL,
    state text NOT NULL,
    reviewed_by_user_id uuid,
    reviewed_at timestamp with time zone,
    review_note text,
    profile_id uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT economics_profile_draft_state_ck CHECK ((state = ANY (ARRAY['SUBMITTED'::text, 'APPROVED'::text, 'REJECTED'::text, 'SUPERSEDED'::text]))),
    CONSTRAINT economics_profile_draft_currency_ck CHECK ((currency_code ~ '^[A-Z]{3}$'::text)),
    CONSTRAINT economics_profile_draft_payload_ck CHECK ((jsonb_typeof(payload) = 'object'::text)),
    CONSTRAINT economics_profile_draft_evidence_ck CHECK (((length(btrim(evidence_reference)) >= 1) AND (length(evidence_reference) <= 400))),
    CONSTRAINT economics_profile_draft_review_ck CHECK ((((state = ANY (ARRAY['APPROVED'::text, 'REJECTED'::text])) = (reviewed_by_user_id IS NOT NULL)) AND ((reviewed_by_user_id IS NULL) = (reviewed_at IS NULL)) AND ((reviewed_by_user_id IS NULL) OR (reviewed_by_user_id <> submitted_by_user_id)))),
    CONSTRAINT economics_profile_draft_profile_ck CHECK (((state = 'APPROVED'::text) = (profile_id IS NOT NULL))),
    CONSTRAINT economics_profile_draft_note_ck CHECK (((review_note IS NULL) OR ((length(btrim(review_note)) >= 1) AND (length(review_note) <= 500)))),
    CONSTRAINT economics_profile_draft_version_ck CHECK ((version >= 0))
);

ALTER TABLE ONLY ops.economics_profile_draft
    ADD CONSTRAINT economics_profile_draft_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.economics_profile_draft
    ADD CONSTRAINT economics_profile_draft_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.economics_profile_draft
    ADD CONSTRAINT economics_profile_draft_profile_fk FOREIGN KEY (profile_id) REFERENCES core.economics_projection_profile(id);

ALTER TABLE ONLY ops.economics_profile_draft
    ADD CONSTRAINT economics_profile_draft_mode_fk FOREIGN KEY (fulfillment_mode_code) REFERENCES core.fulfillment_mode(code);

CREATE INDEX economics_profile_draft_store_ix ON ops.economics_profile_draft USING btree (store_id, submitted_at DESC);

-- At most one draft of a scope waits for review.
CREATE UNIQUE INDEX economics_profile_draft_open_uq ON ops.economics_profile_draft USING btree (store_id, fulfillment_mode_code) WHERE (state = 'SUBMITTED'::text);

GRANT SELECT,INSERT ON TABLE ops.economics_profile_draft TO marketops_app;

-- A draft is rejected or superseded by the application; approval goes through the function below.
GRANT UPDATE (state, reviewed_by_user_id, reviewed_at, review_note, version) ON TABLE ops.economics_profile_draft TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'economics_profile_draft', 'NO_ROUTE', NULL, 'an economics projection profile waiting for a second Owner''s review; approval writes core.economics_projection_profile through ops.publish_economics_profile');

-- Publish a submitted draft as the scope's economics projection profile. The reviewer must differ
-- from the submitter; the application has checked both people's authority before calling.
CREATE FUNCTION ops.publish_economics_profile(p_draft_id uuid, p_reviewer uuid, p_expected_version bigint,
                                              p_note text, p_verification_days integer) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE
    draft        ops.economics_profile_draft%ROWTYPE;
    now_at       timestamp with time zone := clock_timestamp();
    profile_uuid uuid := gen_random_uuid();
    next_version integer;
    family_row   record;
    component_row record;
BEGIN
    SELECT * INTO draft FROM ops.economics_profile_draft WHERE id = p_draft_id FOR UPDATE;
    IF draft.id IS NULL THEN
        RAISE EXCEPTION 'draft % does not exist', p_draft_id USING ERRCODE = 'MO039';
    END IF;
    IF draft.version <> p_expected_version THEN
        RAISE EXCEPTION 'draft % changed since it was read', p_draft_id USING ERRCODE = 'MO061';
    END IF;
    IF draft.state <> 'SUBMITTED' THEN
        RAISE EXCEPTION 'draft % is not waiting for review', p_draft_id USING ERRCODE = 'MO091';
    END IF;
    IF p_reviewer IS NULL OR p_reviewer = draft.submitted_by_user_id THEN
        RAISE EXCEPTION 'the reviewer must differ from the submitter' USING ERRCODE = 'MO092';
    END IF;
    IF p_verification_days IS NULL OR p_verification_days NOT BETWEEN 1 AND 90 THEN
        RAISE EXCEPTION 'verification lasts 1 to 90 days' USING ERRCODE = 'MO039';
    END IF;
    -- Exactly the eight families, each either required with components or not applicable without.
    IF (SELECT count(DISTINCT family.value ->> 'familyCode') FROM jsonb_array_elements(draft.payload -> 'families') AS family) <> 8
       OR jsonb_array_length(draft.payload -> 'families') <> 8
       OR EXISTS (SELECT 1 FROM jsonb_array_elements(draft.payload -> 'families') AS family
                   WHERE (family.value ->> 'applicability') = 'REQUIRED'
                     AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(draft.payload -> 'components') AS component
                                      WHERE component.value ->> 'familyCode' = family.value ->> 'familyCode'))
       OR EXISTS (SELECT 1 FROM jsonb_array_elements(draft.payload -> 'components') AS component
                   WHERE NOT EXISTS (SELECT 1 FROM jsonb_array_elements(draft.payload -> 'families') AS family
                                      WHERE family.value ->> 'familyCode' = component.value ->> 'familyCode'
                                        AND family.value ->> 'applicability' = 'REQUIRED')) THEN
        RAISE EXCEPTION 'draft % does not describe eight families with their components', p_draft_id
            USING ERRCODE = 'MO039';
    END IF;

    SELECT coalesce(max(profile.profile_version), 0) + 1 INTO next_version
      FROM core.economics_projection_profile AS profile
     WHERE profile.organization_id = draft.organization_id AND profile.platform_code = draft.platform_code
       AND profile.marketplace_account_id = draft.marketplace_account_id AND profile.store_id = draft.store_id
       AND profile.fulfillment_mode_code = draft.fulfillment_mode_code;

    -- The scope's previous profile ends where this one begins.
    UPDATE core.economics_projection_profile AS profile
       SET status = 'RETIRED',
           effective_to = CASE WHEN profile.effective_from < now_at THEN now_at ELSE profile.effective_to END
     WHERE profile.organization_id = draft.organization_id AND profile.platform_code = draft.platform_code
       AND profile.marketplace_account_id = draft.marketplace_account_id AND profile.store_id = draft.store_id
       AND profile.fulfillment_mode_code = draft.fulfillment_mode_code AND profile.status = 'ACTIVE';

    INSERT INTO core.economics_projection_profile (
        id, profile_version, organization_id, platform_code, marketplace_account_id, store_id,
        fulfillment_mode_code, currency_code, effective_from, effective_to, verification_state, verified_at,
        verification_expires_at, evidence_reference, minimum_supported_price, maximum_supported_price, status,
        created_at)
    VALUES (profile_uuid, next_version, draft.organization_id, draft.platform_code, draft.marketplace_account_id,
        draft.store_id, draft.fulfillment_mode_code, draft.currency_code, now_at, NULL, 'REAL_ACCOUNT_VERIFIED',
        now_at, now_at + make_interval(days => p_verification_days),
        left(draft.evidence_reference || '; draft ' || draft.id || ' approved by ' || p_reviewer, 512),
        (draft.payload ->> 'minimumSupportedPrice')::numeric, (draft.payload ->> 'maximumSupportedPrice')::numeric,
        'ACTIVE', now_at);

    FOR family_row IN
        SELECT family.value ->> 'familyCode' AS family_code, family.value ->> 'applicability' AS applicability,
               family.value ->> 'evidence' AS evidence
          FROM jsonb_array_elements(draft.payload -> 'families') AS family
    LOOP
        INSERT INTO core.economics_projection_family (profile_id, family_code, applicability_state, evidence_reference)
        VALUES (profile_uuid, family_row.family_code, family_row.applicability, family_row.evidence);
    END LOOP;

    FOR component_row IN
        SELECT component.value ->> 'componentCode' AS component_code, component.value ->> 'familyCode' AS family_code,
               component.value ->> 'kind' AS kind, (component.value ->> 'fixedAmount')::numeric AS fixed_amount,
               (component.value ->> 'rateValue')::numeric AS rate_value, component.value ->> 'evidence' AS evidence
          FROM jsonb_array_elements(draft.payload -> 'components') AS component
    LOOP
        INSERT INTO core.economics_projection_component (
            id, profile_id, component_code, family_code, component_kind, fixed_amount, rate_value,
            lower_price_inclusive, upper_price_exclusive, evidence_reference, price_basis)
        VALUES (gen_random_uuid(), profile_uuid, component_row.component_code, component_row.family_code,
            component_row.kind, component_row.fixed_amount, component_row.rate_value, NULL, NULL,
            component_row.evidence, 'PROPOSED_PRICE');
    END LOOP;

    UPDATE ops.economics_profile_draft
       SET state = 'APPROVED', reviewed_by_user_id = p_reviewer, reviewed_at = now_at, review_note = p_note,
           profile_id = profile_uuid, version = version + 1
     WHERE id = p_draft_id;

    RETURN profile_uuid;
END;
$$;

REVOKE ALL ON FUNCTION ops.publish_economics_profile(p_draft_id uuid, p_reviewer uuid, p_expected_version bigint, p_note text, p_verification_days integer) FROM PUBLIC;

GRANT ALL ON FUNCTION ops.publish_economics_profile(p_draft_id uuid, p_reviewer uuid, p_expected_version bigint, p_note text, p_verification_days integer) TO marketops_app;
