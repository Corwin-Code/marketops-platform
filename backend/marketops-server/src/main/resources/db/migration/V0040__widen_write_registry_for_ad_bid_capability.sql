-- One write registry, two capabilities.
--
-- The price write path already owns a complete, reviewed model of how a
-- controlled write is shaped: an API profile, auth headers, endpoints, a
-- capability, and a per-operation call shape whose verified rows the
-- application role can never write. The Contract requires exactly one execution
-- authority, so `AD_BID_CHANGE` joins that registry rather than growing a
-- parallel one.
--
-- Joining it means widening four closed vocabularies, and each widening is
-- narrower in effect than it looks, because the trigger that validates a write
-- shape dispatches on the capability: it admits `price-change` and
-- `ad-bid-change`, and requires each to carry its own placeholders and refuse
-- the other's. A price operation that mentions {targetBid} is impossible
-- because the trigger refuses it by name, not because a vocabulary happens to
-- omit the token.
--
-- `capability_operation_template_ck` is dropped because that trigger asks a
-- strictly stronger question. A table constraint can only ask "does the
-- template contain {targetPrice}" without knowing which capability the row
-- belongs to; the trigger asks it per capability and additionally refuses the
-- wrong capability's placeholders.
--
-- Nothing here makes any advertising Provider path reachable. No capability,
-- endpoint, profile, header or operation row is created. The registry gains the
-- ability to describe an advertising bid write; describing one and being allowed
-- to perform one remain different things, separated by verification state, the
-- write gate, an account-bound verification case, a feature flag and a Gate.

-- ---------------------------------------------------------------------------
-- Endpoint operation functions
-- ---------------------------------------------------------------------------

ALTER TABLE platform.platform_endpoint
    DROP CONSTRAINT platform_endpoint_function_ck;
ALTER TABLE platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_function_ck
    CHECK (operation_function IN (
        'UNDECLARED', 'READ_DATA',
        'PRICE_APPLY', 'PRICE_STATUS', 'PRICE_READBACK', 'PRICE_RESTORE',
        'AD_BID_APPLY', 'AD_BID_STATUS', 'AD_BID_READBACK', 'AD_BID_RESTORE'));

-- ---------------------------------------------------------------------------
-- Request template vocabulary
-- ---------------------------------------------------------------------------

-- The write vocabulary becomes the union of the two capabilities' placeholders.
-- The union alone would let a price template mention a bid, so the trigger below
-- forbids cross-capability tokens by name; this function's job is only to prove
-- that every token is one the renderer knows how to substitute and that the
-- rendered result is still a well-formed request.
CREATE OR REPLACE FUNCTION platform.request_template_is_well_formed(
    p_template text, p_is_body boolean, p_is_write boolean)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE SET search_path = pg_catalog, pg_temp
AS $$
DECLARE rendered text := p_template; token text[]; allowed text[];
BEGIN
    IF p_template IS NULL THEN RETURN true; END IF;
    IF length(p_template) > 4096 THEN RETURN false; END IF;
    allowed := CASE WHEN p_is_write THEN ARRAY[
            'nativeListingKey', 'nativeVariantKey', 'targetPrice', 'currencyCode',
            'idempotencyKey', 'nativeTaskKey',
            'nativeCampaignKey', 'nativeObjectKey', 'targetBid', 'bidUnitCode']
        ELSE ARRAY['cursor', 'limit', 'accountKey', 'endpointCode'] END;
    FOR token IN SELECT regexp_matches(p_template, '\{([a-zA-Z][a-zA-Z0-9]{0,31})\}', 'g') LOOP
        IF NOT token[1] = ANY(allowed) THEN RETURN false; END IF;
        rendered := replace(rendered, '{' || token[1] || '}',
            CASE WHEN token[1] IN ('targetPrice', 'targetBid', 'limit', 'cursor')
                 THEN '1' ELSE 'fixture' END);
    END LOOP;
    IF p_is_body THEN RETURN rendered IS JSON OBJECT WITH UNIQUE KEYS; END IF;
    RETURN rendered !~ '[{}[:cntrl:]]';
END;
$$;

-- ---------------------------------------------------------------------------
-- Capability-aware write shape validation
-- ---------------------------------------------------------------------------

-- The row-level template check is superseded by the trigger below, which knows
-- which capability the operation belongs to and can therefore require the right
-- placeholder and refuse the wrong one. Dropping it without that replacement
-- would be a weakening; with it the same rule is enforced more precisely.
ALTER TABLE platform.capability_operation
    DROP CONSTRAINT capability_operation_template_ck;

CREATE OR REPLACE FUNCTION platform.capability_operation_matches_write_model()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    model text;
    capability platform.platform_capability%ROWTYPE;
    endpoint platform.platform_endpoint%ROWTYPE;
    expected_function text;
    all_templates text;
    required_value_token text;
    forbidden_value_token text;
    required_object_tokens text[];
BEGIN
    IF cardinality(NEW.task_pending_values) <> (SELECT count(DISTINCT value) FROM unnest(NEW.task_pending_values) value)
        OR EXISTS (SELECT 1 FROM unnest(NEW.task_pending_values) value
                    WHERE length(value) NOT BETWEEN 1 AND 256 OR value ~ '[[:cntrl:]]') THEN
        RAISE EXCEPTION 'task pending states must be distinct bounded values' USING ERRCODE = 'MO036';
    END IF;

    SELECT * INTO capability FROM platform.platform_capability WHERE id = NEW.capability_id;
    SELECT * INTO endpoint FROM platform.platform_endpoint WHERE id = NEW.endpoint_id;
    model := capability.write_result_model;

    -- The two capabilities this registry describes, and the vocabulary each
    -- owns. A capability outside this list has no write shape at all, which is
    -- how a third controlled write stays impossible until somebody adds it here
    -- deliberately.
    IF capability.capability_code = 'price-change' THEN
        expected_function := CASE NEW.operation
            WHEN 'APPLY' THEN 'PRICE_APPLY' WHEN 'RESTORE' THEN 'PRICE_RESTORE'
            WHEN 'READBACK' THEN 'PRICE_READBACK' WHEN 'STATUS_ENQUIRY' THEN 'PRICE_STATUS' END;
        required_value_token := '%{targetPrice}%';
        forbidden_value_token := '%{targetBid}%';
        required_object_tokens := ARRAY['%{nativeListingKey}%', '%{nativeVariantKey}%'];
    ELSIF capability.capability_code = 'ad-bid-change' THEN
        expected_function := CASE NEW.operation
            WHEN 'APPLY' THEN 'AD_BID_APPLY' WHEN 'RESTORE' THEN 'AD_BID_RESTORE'
            WHEN 'READBACK' THEN 'AD_BID_READBACK' WHEN 'STATUS_ENQUIRY' THEN 'AD_BID_STATUS' END;
        required_value_token := '%{targetBid}%';
        forbidden_value_token := '%{targetPrice}%';
        required_object_tokens := ARRAY['%{nativeCampaignKey}%', '%{nativeObjectKey}%'];
    ELSE
        RAISE EXCEPTION 'no write shape is defined for this capability' USING ERRCODE = 'MO036';
    END IF;

    -- The template rule the dropped constraint used to carry, now capability
    -- aware in both directions.
    IF NEW.operation IN ('APPLY', 'RESTORE') THEN
        IF NEW.request_template NOT LIKE required_value_token THEN
            RAISE EXCEPTION 'a mutating operation must carry its own target placeholder'
                USING ERRCODE = 'MO036';
        END IF;
    END IF;
    all_templates := coalesce(endpoint.path_template, '') || coalesce(endpoint.query_template, '')
        || coalesce(NEW.request_template, '');
    IF all_templates LIKE forbidden_value_token THEN
        RAISE EXCEPTION 'an operation may not carry another capability''s target placeholder'
            USING ERRCODE = 'MO036';
    END IF;

    IF capability.read_write_class <> 'WRITE'
        OR endpoint.capability_id IS DISTINCT FROM NEW.capability_id
        OR endpoint.platform_code IS DISTINCT FROM NEW.platform_code
        OR capability.platform_code IS DISTINCT FROM NEW.platform_code
        OR (NEW.operation IN ('APPLY', 'RESTORE') AND
            (endpoint.read_write_class <> 'WRITE' OR endpoint.http_method NOT IN ('POST','PUT','PATCH')))
        OR (NEW.operation IN ('READBACK', 'STATUS_ENQUIRY') AND
            (endpoint.read_write_class <> 'READ' OR endpoint.http_method NOT IN ('GET','POST')))
        OR (endpoint.operation_function <> 'UNDECLARED'
            AND endpoint.operation_function <> expected_function) THEN
        RAISE EXCEPTION 'operation is incompatible with capability or endpoint semantics'
            USING ERRCODE = 'MO036';
    END IF;

    IF NEW.verification_state = 'VERIFIED' THEN
        IF endpoint.operation_function <> expected_function OR endpoint.http_method IS NULL
            OR endpoint.body_template IS NOT NULL
            OR (endpoint.http_method = 'GET' AND NEW.request_template <> '')
            OR (NEW.operation <> 'STATUS_ENQUIRY'
                AND all_templates NOT LIKE required_object_tokens[1]
                AND all_templates NOT LIKE required_object_tokens[2])
            OR (NEW.operation IN ('APPLY', 'RESTORE')
                AND capability.capability_code = 'price-change'
                AND all_templates NOT LIKE '%{currencyCode}%')
            -- A bid without its unit is a number whose meaning depends on which
            -- marketplace read it. Roubles and kopecks differ by a factor of a
            -- hundred, and the mistake is silent in both directions.
            OR (NEW.operation IN ('APPLY', 'RESTORE')
                AND capability.capability_code = 'ad-bid-change'
                AND (all_templates NOT LIKE '%{currencyCode}%'
                     OR all_templates NOT LIKE '%{bidUnitCode}%'))
            OR (NEW.operation = 'STATUS_ENQUIRY' AND all_templates NOT LIKE '%{nativeTaskKey}%') THEN
            RAISE EXCEPTION 'verified operation has incomplete request semantics'
                USING ERRCODE = 'MO036';
        END IF;
        IF NOT platform.request_template_is_well_formed(endpoint.path_template, false, true)
            OR NOT platform.request_template_is_well_formed(endpoint.query_template, false, true)
            OR (endpoint.http_method <> 'GET'
                AND NOT platform.request_template_is_well_formed(NEW.request_template, true, true)) THEN
            RAISE EXCEPTION 'verified operation request template is invalid' USING ERRCODE = 'MO036';
        END IF;
    END IF;

    IF model = 'ASYNCHRONOUS_TASK' AND NEW.operation IN ('APPLY', 'RESTORE')
        AND NEW.task_key_pointer IS NULL THEN
        RAISE EXCEPTION 'an asynchronous apply must record where the platform task key lives'
            USING ERRCODE = 'MO036';
    END IF;

    IF model = 'SYNCHRONOUS' AND NEW.operation = 'STATUS_ENQUIRY' THEN
        RAISE EXCEPTION 'a synchronous capability has no asynchronous task to enquire about'
            USING ERRCODE = 'MO036';
    END IF;

    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------
-- Credential purpose per capability
-- ---------------------------------------------------------------------------

-- A price write authenticates with a price-write credential and an advertising
-- write with an advertising one. The purposes exist as reference data; this
-- function is how the registry snapshot picks the right one, so that no caller
-- has to name a purpose it cannot check.
CREATE FUNCTION platform.capability_credential_purpose(
    p_capability_code text, p_read_write_class text)
RETURNS text
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT CASE
        WHEN p_read_write_class <> 'WRITE' THEN 'READ'
        WHEN p_capability_code = 'ad-bid-change' THEN 'ADS_WRITE'
        ELSE 'PRICE_WRITE'
    END
$$;
REVOKE ALL ON FUNCTION platform.capability_credential_purpose(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.capability_credential_purpose(text, text) TO marketops_app;

-- The account-bound verification snapshot now selects headers by the
-- capability's own purpose. Without this an advertising verification case would
-- freeze the price-write headers and then compare against them for ever, which
-- would make the verification meaningless in a way nothing would surface.
CREATE OR REPLACE FUNCTION platform.registry_configuration_snapshot(p_capability uuid)
RETURNS jsonb LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp
AS $$
    SELECT jsonb_build_object('capability', to_jsonb(c),
        'profile', (SELECT to_jsonb(p) FROM platform.platform_api_profile p
                     WHERE p.platform_code = c.platform_code),
        'headers', (SELECT coalesce(jsonb_agg(to_jsonb(h) ORDER BY h.id), '[]'::jsonb)
            FROM platform.platform_auth_header h
            WHERE h.platform_code = c.platform_code
              AND h.credential_purpose =
                  platform.capability_credential_purpose(c.capability_code, c.read_write_class)),
        'endpoints', (SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY e.id), '[]'::jsonb)
            FROM platform.platform_endpoint e WHERE e.capability_id = c.id),
        'operations', (SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.id), '[]'::jsonb)
            FROM platform.capability_operation o WHERE o.capability_id = c.id),
        'permissionRequirements', (SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id), '[]'::jsonb)
            FROM platform.platform_permission_requirement r
            WHERE r.capability_id = c.id
               OR r.endpoint_id IN (SELECT id FROM platform.platform_endpoint WHERE capability_id = c.id)))
    FROM platform.platform_capability c WHERE c.id = p_capability
$$;

-- ---------------------------------------------------------------------------
-- Workflow vocabularies
-- ---------------------------------------------------------------------------

-- An advertising recommendation is about an advertising object, and its action
-- kind is the new controlled write. ADVERTISING_REVIEW stays: it is the
-- task-only kind for advertising work that raises no command.
ALTER TABLE ops.recommendation DROP CONSTRAINT recommendation_action_ck;
ALTER TABLE ops.recommendation
    ADD CONSTRAINT recommendation_action_ck
    CHECK (action_kind IN (
        'PRICE_CHANGE', 'AD_BID_CHANGE', 'RESOLVE_MAPPING', 'RESTOCK_REVIEW',
        'LISTING_CONTENT_REVIEW', 'ADVERTISING_REVIEW', 'COST_DATA_REVIEW'));

ALTER TABLE ops.recommendation DROP CONSTRAINT recommendation_subject_ck;
ALTER TABLE ops.recommendation
    ADD CONSTRAINT recommendation_subject_ck
    CHECK (subject_kind IN (
        'PRODUCT_VARIANT', 'PLATFORM_LISTING_VARIANT', 'STORE', 'AD_NATIVE_OBJECT'));

-- An advertising bid change is about an advertising object and nothing else,
-- and a price change is still about a listing variant. Pairing the two closes
-- the gap the widened vocabularies would otherwise open.
ALTER TABLE ops.recommendation
    ADD CONSTRAINT recommendation_action_subject_ck
    CHECK ((action_kind <> 'AD_BID_CHANGE' OR subject_kind = 'AD_NATIVE_OBJECT')
       AND (action_kind <> 'PRICE_CHANGE' OR subject_kind = 'PLATFORM_LISTING_VARIANT')
       AND (subject_kind <> 'AD_NATIVE_OBJECT'
            OR action_kind IN ('AD_BID_CHANGE', 'ADVERTISING_REVIEW')));

-- A bounded policy authorization and a pilot allowlist entry may now name the
-- advertising action. Neither grants anything by existing; both are consumed by
-- a gate that also demands verification, a flag and a Gate scope.
ALTER TABLE ops.policy_authorization DROP CONSTRAINT policy_authorization_action_ck;
ALTER TABLE ops.policy_authorization
    ADD CONSTRAINT policy_authorization_action_ck
    CHECK (action_kind IN ('PRICE_CHANGE', 'AD_BID_CHANGE'));

ALTER TABLE ops.pilot_allowlist_entry DROP CONSTRAINT pilot_allowlist_entry_action_kind_ck;
ALTER TABLE ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_action_kind_ck
    CHECK (action_kind IN ('PRICE_CHANGE', 'AD_BID_CHANGE'));

-- The allowlist keys an entity by listing variant, which an advertising object
-- is not. A nullable advertising column plus a shape check keeps one allowlist
-- rather than two, and refuses an entry that names the wrong entity for its
-- action.
ALTER TABLE ops.pilot_allowlist_entry
    ADD COLUMN ad_native_object_id uuid;
ALTER TABLE ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_ad_object_fk
    FOREIGN KEY (ad_native_object_id, organization_id)
    REFERENCES core.ad_native_object (id, organization_id);
ALTER TABLE ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_entity_shape_ck
    CHECK ((action_kind <> 'AD_BID_CHANGE'
                OR (ad_native_object_id IS NOT NULL AND platform_listing_variant_id IS NULL))
       AND (action_kind <> 'PRICE_CHANGE' OR ad_native_object_id IS NULL));

CREATE INDEX pilot_allowlist_entry_ad_object_ix
    ON ops.pilot_allowlist_entry (ad_native_object_id, action_kind)
    WHERE ad_native_object_id IS NOT NULL;

-- Advertising observations have their own unit and explicit NOT_APPLIED semantics.
-- Neither an HTTP status nor an ETag header describes a bid unit or proves absence.
ALTER TABLE platform.capability_operation
    ADD COLUMN ad_observed_unit_pointer text,
    ADD COLUMN ad_not_applied_pointer text,
    ADD COLUMN ad_not_applied_value jsonb;
ALTER TABLE platform.capability_operation ADD CONSTRAINT ad_operation_pointer_shape_ck
    CHECK ((ad_observed_unit_pointer IS NULL OR ad_observed_unit_pointer LIKE '/%')
       AND ((ad_not_applied_pointer IS NULL AND ad_not_applied_value IS NULL)
         OR (ad_not_applied_pointer LIKE '/%' AND ad_not_applied_value IS NOT NULL)));
