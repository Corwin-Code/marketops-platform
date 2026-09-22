-- SLICE-V1-004 Promotion & Listing Conversion: the Shared Spine learns the new
-- vocabulary before any Slice table exists.
--
-- Nothing here creates a table. The audit journal gains a source domain, the
-- role matrix gains the listing actions and who may hold them, the workflow
-- vocabularies admit a listing-scoped recommendation, the write registry can
-- describe a description write and refuse to perform one, and the one-use
-- control invocation grant learns the listing purposes. Describing a write and
-- being permitted to perform it stay separate: no profile, endpoint, header,
-- operation or capability row is created, every new switch defaults to absent,
-- which is off, and production_write_enabled is not touched.
--
-- Forward-only: every constraint is replaced by a wider one every existing row
-- already satisfies, and every function replacement keeps its privileges.

-- ---------------------------------------------------------------------------
-- The listing conversion module becomes an audit source domain
-- ---------------------------------------------------------------------------

ALTER TABLE ops.metadata_audit_event
    DROP CONSTRAINT metadata_audit_event_source_domain_ck;
ALTER TABLE ops.metadata_audit_event
    ADD CONSTRAINT metadata_audit_event_source_domain_ck
    CHECK (source_domain IN (
        'organizationaccount', 'identityaccess', 'marketplaceintegration',
        'adminobservability', 'productlisting', 'operatingfacts',
        'analyticsdecision', 'aicopilot', 'operationsworkflow',
        'availabilityrisk', 'advertisingefficiency', 'listingconversion'));

-- ---------------------------------------------------------------------------
-- Business actions and the reviewed role matrix
-- ---------------------------------------------------------------------------

-- Every action whose consequence is external, financial or a change to what a
-- customer reads requires step-up. Reading never does. A technical
-- administrator holds a stop and an attestation, never a business approval.
INSERT INTO iam.action_scope (code, display_name, description, requires_step_up, ordinal) VALUES
    ('LISTING_CONVERSION_VIEW', 'View listing conversion',
        'Read Listing Health, retained-visit conversion, candidates, actions and outcomes.', false, 30),
    ('LISTING_ACTION_PREPARE', 'Prepare listing action',
        'Author a candidate comparison or an exact listing action for review.', false, 31),
    ('LISTING_ACTION_REVIEW', 'Review listing action',
        'Attest the exact Russian text and the facts of a listing action as a person other than its author.', true, 32),
    ('LISTING_ACTION_APPROVE_ORDINARY', 'Approve ordinary listing action',
        'Give the final approval for a listing action below both material triggers.', true, 33),
    ('LISTING_ACTION_APPROVE_MATERIAL', 'Approve material listing action',
        'Give the final approval for a listing action that crosses a material trigger.', true, 34),
    ('LISTING_ACTION_LAUNCH', 'Launch listing action',
        'Launch an approved listing action and acquire its exposure allowance.', true, 35),
    ('LISTING_MANUAL_EXECUTE', 'Execute listing manual packet',
        'Report the execution of a governed manual listing packet.', true, 36),
    ('LISTING_MANUAL_VERIFY', 'Verify listing manual execution',
        'Verify management-side match and display evidence independently of the executor.', true, 37),
    ('LISTING_CONTAINMENT_STOP', 'Stop listing scope',
        'Record a technical or business stop at an exact listing scope.', true, 38),
    ('LISTING_CONTAINMENT_ATTEST', 'Attest listing repair',
        'Attest, as the cause owner, that a contained cause is repaired.', true, 39),
    ('LISTING_CONTAINMENT_CONSENT', 'Consent to listing reenablement',
        'Consent, as the business owner, to reenabling a contained listing scope.', true, 40),
    ('LISTING_PROMOTION_MANAGE', 'Manage promotion engagement',
        'Adopt, exit and release simple promotion engagements on the governed manual path.', true, 41);

INSERT INTO iam.business_role_action_scope (role_code, action_code)
SELECT role_code, action_code
  FROM (VALUES
    ('OWNER', 'LISTING_CONVERSION_VIEW'),
    ('OPERATIONS', 'LISTING_CONVERSION_VIEW'),
    ('MARKETPLACE_OPERATOR', 'LISTING_CONVERSION_VIEW'),
    ('OPS_LEAD', 'LISTING_CONVERSION_VIEW'),
    ('RISK_AUTHORITY', 'LISTING_CONVERSION_VIEW'),
    ('TECH_DATA', 'LISTING_CONVERSION_VIEW'),
    ('AUDITOR', 'LISTING_CONVERSION_VIEW'),
    ('OWNER', 'LISTING_ACTION_PREPARE'),
    ('OPERATIONS', 'LISTING_ACTION_PREPARE'),
    ('MARKETPLACE_OPERATOR', 'LISTING_ACTION_PREPARE'),
    ('OPS_LEAD', 'LISTING_ACTION_PREPARE'),
    ('OWNER', 'LISTING_ACTION_REVIEW'),
    ('OPERATIONS', 'LISTING_ACTION_REVIEW'),
    ('OPS_LEAD', 'LISTING_ACTION_REVIEW'),
    ('OWNER', 'LISTING_ACTION_APPROVE_ORDINARY'),
    ('OPS_LEAD', 'LISTING_ACTION_APPROVE_ORDINARY'),
    ('OWNER', 'LISTING_ACTION_APPROVE_MATERIAL'),
    ('OWNER', 'LISTING_ACTION_LAUNCH'),
    ('OPS_LEAD', 'LISTING_ACTION_LAUNCH'),
    ('OWNER', 'LISTING_MANUAL_EXECUTE'),
    ('OPERATIONS', 'LISTING_MANUAL_EXECUTE'),
    ('MARKETPLACE_OPERATOR', 'LISTING_MANUAL_EXECUTE'),
    ('OPS_LEAD', 'LISTING_MANUAL_EXECUTE'),
    ('OWNER', 'LISTING_MANUAL_VERIFY'),
    ('OPS_LEAD', 'LISTING_MANUAL_VERIFY'),
    ('RISK_AUTHORITY', 'LISTING_MANUAL_VERIFY'),
    ('OWNER', 'LISTING_CONTAINMENT_STOP'),
    ('OPS_LEAD', 'LISTING_CONTAINMENT_STOP'),
    ('RISK_AUTHORITY', 'LISTING_CONTAINMENT_STOP'),
    ('TECH_DATA', 'LISTING_CONTAINMENT_STOP'),
    ('OWNER', 'LISTING_CONTAINMENT_ATTEST'),
    ('TECH_DATA', 'LISTING_CONTAINMENT_ATTEST'),
    ('OWNER', 'LISTING_CONTAINMENT_CONSENT'),
    ('OPS_LEAD', 'LISTING_CONTAINMENT_CONSENT'),
    ('OWNER', 'LISTING_PROMOTION_MANAGE'),
    ('OPS_LEAD', 'LISTING_PROMOTION_MANAGE')
  ) AS matrix(role_code, action_code);

-- ---------------------------------------------------------------------------
-- Workflow vocabularies
-- ---------------------------------------------------------------------------

-- A listing action is about a platform listing: the native listing key and its
-- complete variant set, never one variant a person happened to click. The
-- description change is the only new controlled write; the promotion action is
-- governed manual work that raises no command and holds no write capability.
ALTER TABLE ops.recommendation DROP CONSTRAINT recommendation_action_subject_ck;
ALTER TABLE ops.recommendation DROP CONSTRAINT recommendation_action_ck;
ALTER TABLE ops.recommendation
    ADD CONSTRAINT recommendation_action_ck
    CHECK (action_kind IN (
        'PRICE_CHANGE', 'AD_BID_CHANGE', 'RESOLVE_MAPPING', 'RESTOCK_REVIEW',
        'LISTING_CONTENT_REVIEW', 'ADVERTISING_REVIEW', 'COST_DATA_REVIEW',
        'LISTING_DESCRIPTION_CHANGE', 'LISTING_PROMOTION_ACTION'));

ALTER TABLE ops.recommendation DROP CONSTRAINT recommendation_subject_ck;
ALTER TABLE ops.recommendation
    ADD CONSTRAINT recommendation_subject_ck
    CHECK (subject_kind IN (
        'PRODUCT_VARIANT', 'PLATFORM_LISTING_VARIANT', 'STORE', 'AD_NATIVE_OBJECT',
        'PLATFORM_LISTING'));

ALTER TABLE ops.recommendation
    ADD CONSTRAINT recommendation_action_subject_ck
    CHECK ((action_kind <> 'AD_BID_CHANGE' OR subject_kind = 'AD_NATIVE_OBJECT')
       AND (action_kind <> 'PRICE_CHANGE' OR subject_kind = 'PLATFORM_LISTING_VARIANT')
       AND (subject_kind <> 'AD_NATIVE_OBJECT'
            OR action_kind IN ('AD_BID_CHANGE', 'ADVERTISING_REVIEW'))
       AND (action_kind NOT IN ('LISTING_DESCRIPTION_CHANGE', 'LISTING_PROMOTION_ACTION')
            OR subject_kind = 'PLATFORM_LISTING')
       AND (subject_kind <> 'PLATFORM_LISTING'
            OR action_kind IN ('LISTING_DESCRIPTION_CHANGE', 'LISTING_PROMOTION_ACTION')));

-- The pilot allowlist may name a listing for the description write. It keys the
-- entity by listing, not by variant, because a description belongs to the
-- listing and reaches every variant at once.
ALTER TABLE ops.pilot_allowlist_entry DROP CONSTRAINT pilot_allowlist_entry_action_kind_ck;
ALTER TABLE ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_action_kind_ck
    CHECK (action_kind IN ('PRICE_CHANGE', 'AD_BID_CHANGE', 'LISTING_DESCRIPTION_CHANGE'));

ALTER TABLE ops.pilot_allowlist_entry
    ADD COLUMN platform_listing_id uuid;
ALTER TABLE ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_listing_fk
    FOREIGN KEY (platform_listing_id, organization_id)
    REFERENCES core.platform_listing (id, organization_id);
ALTER TABLE ops.pilot_allowlist_entry DROP CONSTRAINT pilot_allowlist_entry_entity_shape_ck;
ALTER TABLE ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_entity_shape_ck
    CHECK ((action_kind <> 'AD_BID_CHANGE'
                OR (ad_native_object_id IS NOT NULL AND platform_listing_variant_id IS NULL
                    AND platform_listing_id IS NULL))
       AND (action_kind <> 'PRICE_CHANGE'
                OR (ad_native_object_id IS NULL AND platform_listing_id IS NULL))
       AND (action_kind <> 'LISTING_DESCRIPTION_CHANGE'
                OR (platform_listing_id IS NOT NULL AND platform_listing_variant_id IS NULL
                    AND ad_native_object_id IS NULL)));

CREATE INDEX pilot_allowlist_entry_listing_ix
    ON ops.pilot_allowlist_entry (platform_listing_id, action_kind)
    WHERE platform_listing_id IS NOT NULL;

-- The structured actions a responsibility Task journal may record for listing
-- work. Each is an act with evidence and a person; none of them is a view.
ALTER TABLE ops.work_task_event DROP CONSTRAINT work_task_event_action_kind_ck;
ALTER TABLE ops.work_task_event
    ADD CONSTRAINT work_task_event_action_kind_ck
    CHECK (action_kind IS NULL OR action_kind IN (
        'DECISION_SUBMITTED_FOR_APPROVAL', 'DECISION_ENDORSED',
        'DECISION_APPROVED', 'DECISION_REJECTED', 'MANUAL_PACKET_ISSUED',
        'MANUAL_EXECUTION_VERIFIED', 'DATA_OR_MAPPING_REPAIR',
        'EXCEPTION_REQUESTED', 'COMPENSATION_REQUESTED',
        'ACTION_LAUNCHED', 'MANUAL_EXECUTION_REPORTED', 'CONTAINMENT_RECORDED',
        'LATE_ASSOCIATION_RECORDED', 'COLLABORATION_HANDOVER', 'EVIDENCE_RETURNED'));

-- ---------------------------------------------------------------------------
-- Write registry: the description write can be described
-- ---------------------------------------------------------------------------

INSERT INTO platform.credential_purpose (code, display_name) VALUES
    ('CONTENT_WRITE', 'Content write');

ALTER TABLE platform.platform_endpoint
    DROP CONSTRAINT platform_endpoint_function_ck;
ALTER TABLE platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_function_ck
    CHECK (operation_function IN (
        'UNDECLARED', 'READ_DATA',
        'PRICE_APPLY', 'PRICE_STATUS', 'PRICE_READBACK', 'PRICE_RESTORE',
        'AD_BID_APPLY', 'AD_BID_STATUS', 'AD_BID_READBACK', 'AD_BID_RESTORE',
        'DESCRIPTION_APPLY', 'DESCRIPTION_STATUS', 'DESCRIPTION_READBACK',
        'DESCRIPTION_RESTORE'));

-- A readback of a description observes the management-side text and the
-- marking declaration the platform holds. Both are pointers into the recorded
-- response shape, so the adapter reads what the verified registry says and
-- never guesses a field name.
ALTER TABLE platform.capability_operation
    ADD COLUMN description_observed_text_pointer text,
    ADD COLUMN description_kiz_marked_pointer text,
    ADD COLUMN description_attribute_key text;
ALTER TABLE platform.capability_operation ADD CONSTRAINT description_operation_pointer_shape_ck
    CHECK ((description_observed_text_pointer IS NULL OR description_observed_text_pointer LIKE '/%')
       AND (description_kiz_marked_pointer IS NULL OR description_kiz_marked_pointer LIKE '/%')
       AND (description_attribute_key IS NULL
            OR (length(btrim(description_attribute_key)) BETWEEN 1 AND 128
                AND description_attribute_key !~ '[[:cntrl:]{}]')));

-- A description readback observes a text, not a price. The readback shape
-- rule admits either the price/currency pair or the observed text pointer.
ALTER TABLE platform.capability_operation DROP CONSTRAINT capability_operation_readback_shape_ck;
ALTER TABLE platform.capability_operation ADD CONSTRAINT capability_operation_readback_shape_ck
    CHECK (operation <> 'READBACK'
        OR (observed_price_pointer IS NOT NULL AND observed_currency_pointer IS NOT NULL)
        OR description_observed_text_pointer IS NOT NULL);

-- The template vocabulary becomes the union of three capabilities' placeholders.
-- The trigger below still forbids cross-capability tokens by name; this function
-- proves every token is one the renderer substitutes and that the rendered
-- result is a well-formed request.
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
            'nativeCampaignKey', 'nativeObjectKey', 'targetBid', 'bidUnitCode',
            'descriptionText', 'descriptionAttributeKey']
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

-- Three capabilities, three vocabularies. A description operation must carry
-- the description text and the attribute it targets, and may carry neither a
-- price nor a bid; a price or bid operation may not carry a description.
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
    forbidden_value_tokens text[];
    forbidden_token text;
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

    IF capability.capability_code = 'price-change' THEN
        expected_function := CASE NEW.operation
            WHEN 'APPLY' THEN 'PRICE_APPLY' WHEN 'RESTORE' THEN 'PRICE_RESTORE'
            WHEN 'READBACK' THEN 'PRICE_READBACK' WHEN 'STATUS_ENQUIRY' THEN 'PRICE_STATUS' END;
        required_value_token := '%{targetPrice}%';
        forbidden_value_tokens := ARRAY['%{targetBid}%', '%{descriptionText}%'];
        required_object_tokens := ARRAY['%{nativeListingKey}%', '%{nativeVariantKey}%'];
    ELSIF capability.capability_code = 'ad-bid-change' THEN
        expected_function := CASE NEW.operation
            WHEN 'APPLY' THEN 'AD_BID_APPLY' WHEN 'RESTORE' THEN 'AD_BID_RESTORE'
            WHEN 'READBACK' THEN 'AD_BID_READBACK' WHEN 'STATUS_ENQUIRY' THEN 'AD_BID_STATUS' END;
        required_value_token := '%{targetBid}%';
        forbidden_value_tokens := ARRAY['%{targetPrice}%', '%{descriptionText}%'];
        required_object_tokens := ARRAY['%{nativeCampaignKey}%', '%{nativeObjectKey}%'];
    ELSIF capability.capability_code = 'listing-description-change' THEN
        expected_function := CASE NEW.operation
            WHEN 'APPLY' THEN 'DESCRIPTION_APPLY' WHEN 'RESTORE' THEN 'DESCRIPTION_RESTORE'
            WHEN 'READBACK' THEN 'DESCRIPTION_READBACK' WHEN 'STATUS_ENQUIRY' THEN 'DESCRIPTION_STATUS' END;
        required_value_token := '%{descriptionText}%';
        forbidden_value_tokens := ARRAY['%{targetPrice}%', '%{targetBid}%'];
        required_object_tokens := ARRAY['%{nativeListingKey}%', '%{nativeVariantKey}%'];
    ELSE
        RAISE EXCEPTION 'no write shape is defined for this capability' USING ERRCODE = 'MO036';
    END IF;

    IF NEW.operation IN ('APPLY', 'RESTORE') THEN
        IF NEW.request_template NOT LIKE required_value_token THEN
            RAISE EXCEPTION 'a mutating operation must carry its own target placeholder'
                USING ERRCODE = 'MO036';
        END IF;
    END IF;
    all_templates := coalesce(endpoint.path_template, '') || coalesce(endpoint.query_template, '')
        || coalesce(NEW.request_template, '');
    FOREACH forbidden_token IN ARRAY forbidden_value_tokens LOOP
        IF all_templates LIKE forbidden_token THEN
            RAISE EXCEPTION 'an operation may not carry another capability''s target placeholder'
                USING ERRCODE = 'MO036';
        END IF;
    END LOOP;

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
            OR (NEW.operation IN ('APPLY', 'RESTORE')
                AND capability.capability_code = 'ad-bid-change'
                AND (all_templates NOT LIKE '%{currencyCode}%'
                     OR all_templates NOT LIKE '%{bidUnitCode}%'))
            -- A description write names the attribute it changes. Without the
            -- attribute key the request could be a whole-card import, and a
            -- whole-card import is exactly the write this product does not make.
            OR (NEW.operation IN ('APPLY', 'RESTORE')
                AND capability.capability_code = 'listing-description-change'
                AND (all_templates NOT LIKE '%{descriptionAttributeKey}%'
                     OR NEW.description_attribute_key IS NULL))
            OR (NEW.operation = 'READBACK'
                AND capability.capability_code = 'listing-description-change'
                AND NEW.description_observed_text_pointer IS NULL)
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

-- A description write authenticates with a content-write credential. The
-- registry snapshot and the adapter both pick the purpose through this
-- function, so no caller names one it cannot check.
CREATE OR REPLACE FUNCTION platform.capability_credential_purpose(
    p_capability_code text, p_read_write_class text)
RETURNS text
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT CASE
        WHEN p_read_write_class <> 'WRITE' THEN 'READ'
        WHEN p_capability_code = 'ad-bid-change' THEN 'ADS_WRITE'
        WHEN p_capability_code = 'listing-description-change' THEN 'CONTENT_WRITE'
        ELSE 'PRICE_WRITE'
    END
$$;

-- ---------------------------------------------------------------------------
-- One-use control invocation purposes for listing work
-- ---------------------------------------------------------------------------

-- The grant mechanism is the identity module's and stays one. The closed
-- purpose list widens by the listing purposes; the target is the
-- recommendation and the version is the approval decision, exactly as for an
-- advertising control invocation.
CREATE OR REPLACE FUNCTION iam.issue_ad_control_invocation_grant(p_purpose text,p_proof_hash text,p_actor uuid,p_org uuid,
 p_provider uuid,p_subject text,p_session text,p_authenticated timestamptz,p_step_up_until timestamptz,
 p_target uuid,p_version uuid,p_backend integer,p_transaction bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,iam,pg_temp AS $$ BEGIN
 IF p_purpose NOT IN ('COMPENSATION_PREVIEW','COMPENSATION_ENDORSE','COMPENSATION_APPROVE',
 'BUNDLE_DRAFT','BUNDLE_ENDORSE','BUNDLE_APPROVE','CONTAINMENT_STOP','AUTHORITY_VERSION_STOP','CONTAINMENT_REENABLE',
 'CONTAINMENT_ATTEST','CONTAINMENT_ENDORSE','MANUAL_POLICY_PUBLISH','MANUAL_PACKET_SELECT',
 'MANUAL_PACKET_ENDORSE','MANUAL_PACKET_APPROVE','MANUAL_EXECUTION_REPORT','MANUAL_EXECUTION_START','MANUAL_INDEPENDENT_VERIFY',
 'LISTING_ACTION_LAUNCH','LISTING_ACTION_REVIEW','LISTING_ACTION_APPROVE','LISTING_MANUAL_VERIFY',
 'LISTING_OCCUPATION_RELEASE','LISTING_CONTAINMENT_STOP','LISTING_CONTAINMENT_ATTEST',
 'LISTING_CONTAINMENT_CONSENT','LISTING_PROMOTION_EXIT') THEN
  RAISE EXCEPTION 'unknown control invocation purpose' USING ERRCODE='MO092'; END IF;
 PERFORM iam.issue_ad_invocation_grant(p_proof_hash,p_actor,p_org,p_provider,p_subject,p_session,
 p_authenticated,p_step_up_until,p_target,p_version,p_backend,p_transaction);
 UPDATE iam.ad_invocation_grant SET purpose=p_purpose WHERE proof_hash=p_proof_hash;
END $$;

-- Whether one person holds one listing action at a scope covering one store,
-- through any role the reviewed matrix gives that action to. Used by the
-- SECURITY DEFINER functions that must not trust the caller's claim about
-- who is calling.
CREATE FUNCTION ops.lc_actor_holds_action(p_actor uuid, p_org uuid, p_store uuid, p_action text)
RETURNS boolean LANGUAGE sql STABLE SET search_path = pg_catalog, iam, core, pg_temp AS $$
 SELECT EXISTS (
   SELECT 1
     FROM iam.user_role_assignment r
     JOIN iam.business_role_action_scope m ON m.role_code = r.role_code
     JOIN iam.user_scope_grant s ON s.user_id = r.user_id AND s.action_code = m.action_code
     JOIN core.store st ON st.id = p_store
     JOIN core.marketplace_account account ON account.id = st.marketplace_account_id
    WHERE r.user_id = p_actor AND r.organization_id = p_org AND s.organization_id = p_org
      AND st.organization_id = p_org
      AND EXISTS (SELECT 1 FROM iam.user_account actor
                    JOIN iam.identity_provider provider ON provider.id = actor.identity_provider_id
                   WHERE actor.id = p_actor AND actor.organization_id = p_org
                     AND actor.status = 'ACTIVE' AND provider.status = 'ACTIVE')
      AND r.status = 'ACTIVE' AND m.action_code = p_action
      AND r.effective_from <= statement_timestamp()
      AND (r.effective_to IS NULL OR r.effective_to > statement_timestamp())
      AND s.status = 'ACTIVE' AND s.effective_from <= statement_timestamp()
      AND (s.effective_to IS NULL OR s.effective_to > statement_timestamp())
      AND (s.organization_ref_id = p_org OR s.store_ref_id = p_store
           OR s.marketplace_account_ref_id = account.id
           OR s.legal_entity_ref_id = account.legal_entity_id))
$$;
REVOKE ALL ON FUNCTION ops.lc_actor_holds_action(uuid, uuid, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_actor_holds_action(uuid, uuid, uuid, text) TO marketops_app;
