-- V0035: W2 — writing a listing's title and description to Ozon (Owner decisions 2026-10-02).
--
-- The Owner wants the fewest approvals: an Owner settles the final title and description (a
-- Qwen draft or the current text as the starting point) and confirms once, with a fresh sign-in.
-- That confirmation is the approval. The write itself still follows the whole chain: deterministic
-- checks, an idempotent command, a read of the card just before writing (the text it replaces
-- must still be the text the Owner saw), the write, the platform task, a readback of both fields,
-- an append-only record of every call, and the kill switch and allowlist the price write uses.
-- Every content change counts as material: only an Owner confirms one.
--
-- Ozon facts (official OpenAPI "Документация Ozon Seller API 2.1" 3.0.0, saved 2026-10-01):
--   POST /v1/product/attributes/update  items[{offer_id, attributes[{id, complex_id,
--        values[{value}]}]}], up to 100 items; changes only the attributes named; answers {task_id};
--        per-minute and per-day operation limits, 429 with Item-Retry-After (minutes).
--   POST /v1/product/import/info        {task_id} -> result.items[{offer_id, product_id, status
--        pending|imported|failed|skipped (no change in the request), errors[]}].
--   POST /v1/product/info/description   {offer_id} -> result{id, offer_id, name, description}.
--   The title is attribute 4180 and the description attribute 4191 (the catalog facts carry both
--   for every pilot listing). The title is at most 255 characters (error name_too_long); the
--   reference states no description limit, so 6000 characters is this product's own bound.
--
-- The capability is `listing-content-change`, registered and verified like the price write: the
-- same registry, the same same-value probe, the same two-Owner case. No column is added to any
-- registry table: the configuration snapshot a verified case keeps covers whole rows, and a new
-- column would make the verified price capability's evidence stale. The readback names the title
-- pointer in `description_response_binding` ({"titlePointer": ...}) and the description pointer in
-- `description_observed_text_pointer`; the apply is acknowledged by its task key.
--
-- Slice 004's governed description path (calibration, independent review, allowance) stays as it
-- is and is not used by the pilot.

-- 1. Registry: the content write capability ------------------------------------------------

ALTER TABLE platform.platform_endpoint
    DROP CONSTRAINT platform_endpoint_function_ck;

ALTER TABLE platform.platform_endpoint
    ADD CONSTRAINT platform_endpoint_function_ck CHECK ((operation_function = ANY (ARRAY['UNDECLARED'::text, 'READ_DATA'::text, 'PRICE_APPLY'::text, 'PRICE_STATUS'::text, 'PRICE_READBACK'::text, 'PRICE_RESTORE'::text, 'AD_BID_APPLY'::text, 'AD_BID_STATUS'::text, 'AD_BID_READBACK'::text, 'AD_BID_RESTORE'::text, 'DESCRIPTION_APPLY'::text, 'DESCRIPTION_STATUS'::text, 'DESCRIPTION_READBACK'::text, 'DESCRIPTION_RESTORE'::text, 'CONTENT_APPLY'::text, 'CONTENT_STATUS'::text, 'CONTENT_READBACK'::text])));

-- A verified apply is acknowledged either by a constant the platform answers, or — the task form,
-- which the write-model trigger allows only for content — by the task the platform opens for it.
ALTER TABLE platform.capability_operation
    DROP CONSTRAINT capability_operation_acceptance_ck;

ALTER TABLE platform.capability_operation
    ADD CONSTRAINT capability_operation_acceptance_ck CHECK ((((accepted_value IS NULL) OR (jsonb_typeof(accepted_value) = ANY (ARRAY['string'::text, 'boolean'::text, 'number'::text]))) AND ((verification_state <> 'VERIFIED'::text) OR (operation <> ALL (ARRAY['APPLY'::text, 'RESTORE'::text])) OR ((accepted_pointer IS NOT NULL) AND (accepted_value IS NOT NULL)) OR ((accepted_pointer IS NULL) AND (accepted_value IS NULL) AND (task_key_pointer IS NOT NULL)))));

CREATE OR REPLACE FUNCTION platform.capability_credential_purpose(p_capability_code text, p_read_write_class text) RETURNS text
    LANGUAGE sql IMMUTABLE
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
    SELECT CASE
        WHEN p_read_write_class <> 'WRITE' THEN 'READ'
        WHEN p_capability_code = 'ad-bid-change' THEN 'ADS_WRITE'
        WHEN p_capability_code = 'listing-description-change' THEN 'CONTENT_WRITE'
        WHEN p_capability_code = 'listing-content-change' THEN 'CONTENT_WRITE'
        ELSE 'PRICE_WRITE'
    END
$$;

CREATE OR REPLACE FUNCTION platform.request_template_is_well_formed(p_template text, p_is_body boolean, p_is_write boolean) RETURNS boolean
    LANGUAGE plpgsql IMMUTABLE
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE rendered text := p_template; token text[]; allowed text[];
BEGIN
    IF p_template IS NULL THEN RETURN true; END IF;
    IF length(p_template) > 4096 THEN RETURN false; END IF;
    allowed := CASE WHEN p_is_write THEN ARRAY[
            'nativeListingKey', 'nativeVariantKey', 'targetPrice', 'currencyCode',
            'idempotencyKey', 'nativeTaskKey',
            'nativeCampaignKey', 'nativeObjectKey', 'targetBid', 'bidUnitCode',
            'descriptionText', 'descriptionAttributeKey', 'offerKey', 'titleText']
        ELSE ARRAY['cursor', 'limit', 'accountKey', 'endpointCode', 'offset', 'page',
            'windowFrom', 'windowTo', 'windowStartUtcDate', 'windowEndUtcDate',
            'listingKeyBatch', 'itemKeyBatch'] END;
    FOR token IN SELECT regexp_matches(p_template, '\{([a-zA-Z][a-zA-Z0-9]{0,31})\}', 'g') LOOP
        IF NOT token[1] = ANY(allowed) THEN RETURN false; END IF;
        rendered := replace(rendered, '{' || token[1] || '}',
            CASE WHEN token[1] IN ('targetPrice', 'targetBid', 'limit', 'cursor', 'offset', 'page')
                 THEN '1' WHEN token[1] IN ('listingKeyBatch', 'itemKeyBatch') THEN '[]'
                 ELSE 'fixture' END);
    END LOOP;
    IF p_is_body THEN RETURN rendered IS JSON OBJECT WITH UNIQUE KEYS; END IF;
    RETURN rendered !~ '[{}[:cntrl:]]';
END;
$$;

CREATE OR REPLACE FUNCTION platform.capability_operation_matches_write_model() RETURNS trigger
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
        forbidden_value_tokens := ARRAY['%{targetBid}%', '%{descriptionText}%', '%{kizMarkedDeclared}%'];
        required_object_tokens := ARRAY['%{nativeListingKey}%', '%{nativeVariantKey}%'];
    ELSIF capability.capability_code = 'ad-bid-change' THEN
        expected_function := CASE NEW.operation
            WHEN 'APPLY' THEN 'AD_BID_APPLY' WHEN 'RESTORE' THEN 'AD_BID_RESTORE'
            WHEN 'READBACK' THEN 'AD_BID_READBACK' WHEN 'STATUS_ENQUIRY' THEN 'AD_BID_STATUS' END;
        required_value_token := '%{targetBid}%';
        forbidden_value_tokens := ARRAY['%{targetPrice}%', '%{descriptionText}%', '%{kizMarkedDeclared}%'];
        required_object_tokens := ARRAY['%{nativeCampaignKey}%', '%{nativeObjectKey}%'];
    ELSIF capability.capability_code = 'listing-description-change' THEN
        expected_function := CASE NEW.operation
            WHEN 'APPLY' THEN 'DESCRIPTION_APPLY' WHEN 'RESTORE' THEN 'DESCRIPTION_RESTORE'
            WHEN 'READBACK' THEN 'DESCRIPTION_READBACK' WHEN 'STATUS_ENQUIRY' THEN 'DESCRIPTION_STATUS' END;
        required_value_token := '%{descriptionText}%';
        forbidden_value_tokens := ARRAY['%{targetPrice}%', '%{targetBid}%'];
        required_object_tokens := ARRAY['%{nativeListingKey}%', '%{nativeVariantKey}%'];
    ELSIF capability.capability_code = 'listing-content-change' THEN
        -- W2: a listing's title and description, written together by the seller article and
        -- answered by a platform task. A change is put back by another approved change.
        IF NEW.operation = 'RESTORE' THEN
            RAISE EXCEPTION 'a content change is put back by a new approved change, not by a restore'
                USING ERRCODE = 'MO036';
        END IF;
        expected_function := CASE NEW.operation
            WHEN 'APPLY' THEN 'CONTENT_APPLY' WHEN 'READBACK' THEN 'CONTENT_READBACK'
            WHEN 'STATUS_ENQUIRY' THEN 'CONTENT_STATUS' END;
        required_value_token := '%{descriptionText}%';
        forbidden_value_tokens := ARRAY['%{targetPrice}%', '%{targetBid}%', '%{descriptionAttributeKey}%',
            '%{kizMarkedDeclared}%'];
        required_object_tokens := ARRAY['%{offerKey}%', '%{offerKey}%'];
    ELSE
        RAISE EXCEPTION 'no write shape is defined for this capability' USING ERRCODE = 'MO036';
    END IF;

    IF capability.capability_code = 'listing-content-change' AND NEW.operation = 'APPLY'
        AND NEW.request_template NOT LIKE '%{titleText}%' THEN
        RAISE EXCEPTION 'a content apply carries the title and the description together'
            USING ERRCODE = 'MO036';
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
            -- A content change is acknowledged by the task the platform opens for it; every other
            -- write is acknowledged by a constant the platform answers (the relaxed acceptance check
            -- of V0035 is only for the task form).
            OR (NEW.operation IN ('APPLY', 'RESTORE')
                AND capability.capability_code <> 'listing-content-change'
                AND (NEW.accepted_pointer IS NULL OR NEW.accepted_value IS NULL))
            OR (NEW.operation = 'APPLY'
                AND capability.capability_code = 'listing-content-change'
                AND (NEW.task_key_pointer IS NULL OR NEW.accepted_pointer IS NOT NULL
                     OR NEW.accepted_value IS NOT NULL))
            -- A content readback names where the title and the description live in the answer.
            OR (NEW.operation = 'READBACK'
                AND capability.capability_code = 'listing-content-change'
                AND (NEW.description_observed_text_pointer IS NULL
                     OR coalesce(NEW.description_response_binding->>'titlePointer', '')
                        !~ '^(/[^/~]*(~[01][^/~]*)*)+$'))
            OR (NEW.operation = 'STATUS_ENQUIRY' AND all_templates NOT LIKE '%{nativeTaskKey}%') THEN
            RAISE EXCEPTION 'verified operation has incomplete request semantics'
                USING ERRCODE = 'MO036';
        END IF;
        IF NOT platform.request_template_is_well_formed(endpoint.path_template, false, true)
            OR NOT platform.request_template_is_well_formed(endpoint.query_template, false, true)
            OR (endpoint.http_method <> 'GET'
                AND CASE WHEN capability.capability_code='listing-description-change'
                    THEN NOT platform.lc_description_template_is_well_formed(NEW.request_template)
                    ELSE NOT platform.request_template_is_well_formed(NEW.request_template, true, true) END) THEN
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

CREATE OR REPLACE FUNCTION platform.review_registry_verification(p_case uuid, p_actor uuid, p_expected_version bigint, p_approve boolean, p_correlation text) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE evidence platform.registry_verification_case%ROWTYPE; capability platform.platform_capability%ROWTYPE;
    expected_purpose text;
BEGIN
    SELECT * INTO evidence FROM platform.registry_verification_case WHERE id=p_case FOR UPDATE;
    IF evidence.id IS NULL OR evidence.state<>'SUBMITTED' OR evidence.version IS DISTINCT FROM p_expected_version
        OR evidence.submitted_by_user_id=p_actor OR p_approve IS NULL
        OR NOT platform.registry_operator_allowed(p_actor,evidence.marketplace_account_id)
        OR NOT platform.registry_operator_allowed(evidence.submitted_by_user_id,evidence.marketplace_account_id) THEN
        RAISE EXCEPTION 'independent verification reviewer authority denied' USING ERRCODE='MO039';
    END IF;
    SELECT * INTO capability FROM platform.platform_capability WHERE id=evidence.capability_id FOR UPDATE;
    expected_purpose:=CASE WHEN capability.capability_code IN ('listing-description-change','listing-content-change')
            AND capability.read_write_class='WRITE' THEN 'CONTENT_WRITE'
        WHEN capability.read_write_class='WRITE' THEN 'PRICE_WRITE' ELSE 'READ' END;
    PERFORM 1 FROM platform.platform_api_profile WHERE platform_code=capability.platform_code FOR UPDATE;
    PERFORM 1 FROM platform.platform_auth_header WHERE platform_code=capability.platform_code ORDER BY id FOR UPDATE;
    PERFORM 1 FROM platform.platform_endpoint WHERE capability_id=capability.id ORDER BY id FOR UPDATE;
    PERFORM 1 FROM platform.capability_operation WHERE capability_id=capability.id ORDER BY id FOR UPDATE;
    IF p_approve THEN
        IF evidence.evidence_class<>'REAL_ACCOUNT' OR evidence.valid_until<=clock_timestamp()
            OR evidence.configuration_snapshot IS DISTINCT FROM platform.registry_configuration_snapshot(capability.id)
            OR evidence.configuration_snapshot->'profile'='null'::jsonb
            OR NOT EXISTS (SELECT 1 FROM platform.platform_auth_header h WHERE h.id=ANY(evidence.auth_header_ids)
                AND h.value_source='RESOLVED_SECRET' AND h.credential_purpose=expected_purpose)
            OR EXISTS (SELECT 1 FROM platform.platform_auth_header h WHERE h.id=ANY(evidence.auth_header_ids)
                AND h.credential_purpose<>expected_purpose)
            OR EXISTS (SELECT 1 FROM platform.platform_endpoint e WHERE e.id=ANY(evidence.endpoint_ids)
                AND (e.http_method IS NULL OR e.path_template IS NULL OR e.response_content_type IS NULL
                     OR e.rate_limit_per_minute IS NULL OR e.rate_limit_per_minute NOT BETWEEN 1 AND 60000
                     OR e.pagination_model='UNKNOWN' OR (e.pagination_model<>'NONE' AND e.continuation_pointer IS NULL
                         AND NOT (e.pagination_model IN ('OFFSET','PAGE')
                                  AND e.continuation_end_rule IN ('SHORT_PAGE','SHORT_PAGE_OR_NOT_FOUND'))
                         AND NOT (e.pagination_model='OFFSET' AND e.continuation_end_rule='KEYS_EXHAUSTED'))
                     OR (capability.read_write_class='READ' AND
                         (e.read_write_class<>'READ' OR e.operation_function<>'READ_DATA' OR e.http_method NOT IN ('GET','POST')
                          OR NOT platform.request_template_is_well_formed(e.path_template,false,false)
                          OR NOT platform.request_template_is_well_formed(e.query_template,false,false)
                          OR NOT platform.request_template_is_well_formed(e.body_template,true,false))))) THEN
            RAISE EXCEPTION 'real-account evidence or complete protocol semantics missing' USING ERRCODE='MO039';
        END IF;
        IF capability.read_write_class='WRITE' THEN
            IF capability.capability_code NOT IN ('price-change','listing-description-change','listing-content-change')
                OR capability.write_result_model='UNKNOWN'
                OR NOT EXISTS (SELECT 1 FROM platform.capability_operation WHERE capability_id=capability.id AND operation='APPLY')
                OR NOT EXISTS (SELECT 1 FROM platform.capability_operation WHERE capability_id=capability.id AND operation='READBACK')
                OR (capability.write_result_model='ASYNCHRONOUS_TASK' AND NOT EXISTS
                    (SELECT 1 FROM platform.capability_operation WHERE capability_id=capability.id AND operation='STATUS_ENQUIRY'))
                OR EXISTS (SELECT 1 FROM platform.capability_operation WHERE capability_id=capability.id
                    AND NOT endpoint_id=ANY(evidence.endpoint_ids))
                OR EXISTS (SELECT 1 FROM platform.capability_operation restore WHERE restore.capability_id=capability.id AND restore.operation='RESTORE'
                    AND (restore.conditional_write_header IS NULL OR NOT EXISTS (SELECT 1 FROM platform.capability_operation readback
                        WHERE readback.capability_id=capability.id AND readback.operation='READBACK' AND readback.version_token_header IS NOT NULL))) THEN
                RAISE EXCEPTION 'write protocol is incomplete' USING ERRCODE='MO039';
            END IF;
        END IF;
        -- W2: a content write is a platform task with a title and a description to read back.
        IF capability.capability_code='listing-content-change' AND (capability.write_result_model<>'ASYNCHRONOUS_TASK'
            OR EXISTS (SELECT 1 FROM platform.capability_operation operation WHERE operation.capability_id=capability.id
                AND (operation.operation='RESTORE'
                    OR (operation.operation='APPLY' AND operation.task_key_pointer IS NULL)
                    OR (operation.operation='READBACK' AND (operation.description_observed_text_pointer IS NULL
                        OR operation.description_response_binding->>'titlePointer' IS NULL))))) THEN
            RAISE EXCEPTION 'content write protocol is incomplete' USING ERRCODE='MO039';
        END IF;
        IF capability.capability_code='listing-description-change' AND EXISTS (
            SELECT 1 FROM platform.capability_operation operation WHERE operation.capability_id=capability.id
                AND (NOT platform.lc_description_response_descriptor_valid(operation.description_response_binding,
                        operation.operation,capability.write_result_model)
                    OR operation.description_response_binding->>'evidenceRef' IS DISTINCT FROM
                        CASE WHEN operation.verification_state='VERIFIED' AND operation.status='ACTIVE'
                            THEN operation.evidence_ref ELSE evidence.account_evidence_ref END
                    OR (operation.operation='READBACK' AND operation.description_observed_text_pointer IS NULL)
                    OR (operation.operation IN ('APPLY','RESTORE') AND operation.description_attribute_key IS NULL))) THEN
            RAISE EXCEPTION 'Description response or request semantics incomplete' USING ERRCODE='MO039';
        END IF;
        IF capability.capability_code='listing-description-change' AND EXISTS (
            SELECT 1 FROM platform.capability_operation operation WHERE operation.capability_id=capability.id
                AND operation.operation IN ('APPLY','RESTORE')
                AND (NOT platform.lc_description_request_guard_valid(operation.description_request_guard,operation.description_attribute_key)
                    OR operation.description_request_guard->>'evidenceRef' IS DISTINCT FROM
                        CASE WHEN operation.verification_state='VERIFIED' AND operation.status='ACTIVE'
                            THEN operation.evidence_ref ELSE evidence.account_evidence_ref END)) THEN
            RAISE EXCEPTION 'Description exact request schema is incomplete or unsupported' USING ERRCODE='MO039';
        END IF;
        IF capability.capability_code='listing-description-change' AND EXISTS (
            SELECT 1 FROM platform.capability_operation operation
              JOIN platform.platform_endpoint endpoint ON endpoint.id=operation.endpoint_id
             WHERE operation.capability_id=capability.id
               AND NOT platform.lc_description_task_query_shape_valid(to_jsonb(operation.*),to_jsonb(endpoint.*))) THEN
            RAISE EXCEPTION 'Description exact task query is unsupported or does not bind its declared task field' USING ERRCODE='MO039';
        END IF;
        UPDATE platform.platform_capability SET verification_state='VERIFIED',last_verified_at=evidence.tested_at,
            evidence_ref=evidence.account_evidence_ref,verified_source_title=evidence.official_source_url,
            contract_test_status='PASSING',status='ACTIVE',updated_at=clock_timestamp(),version=version+1 WHERE id=capability.id
            AND (verification_state<>'VERIFIED' OR status<>'ACTIVE' OR contract_test_status<>'PASSING');
        UPDATE platform.platform_api_profile SET verification_state='VERIFIED',last_verified_at=evidence.tested_at,
            evidence_ref=evidence.account_evidence_ref,verified_source_title=evidence.official_source_url,
            status='ACTIVE',updated_at=clock_timestamp(),version=version+1 WHERE platform_code=capability.platform_code
            AND (verification_state<>'VERIFIED' OR status<>'ACTIVE');
        UPDATE platform.platform_auth_header SET status='RETIRED',updated_at=clock_timestamp(),version=version+1
            WHERE platform_code=capability.platform_code AND credential_purpose=expected_purpose
              AND status='ACTIVE' AND NOT id=ANY(evidence.auth_header_ids);
        UPDATE platform.platform_auth_header SET verification_state='VERIFIED',last_verified_at=evidence.tested_at,
            evidence_ref=evidence.account_evidence_ref,verified_source_title=evidence.official_source_url,
            status='ACTIVE',updated_at=clock_timestamp(),version=version+1 WHERE id=ANY(evidence.auth_header_ids)
            AND (verification_state<>'VERIFIED' OR status<>'ACTIVE');
        UPDATE platform.platform_endpoint SET verification_state='VERIFIED',last_verified_at=evidence.tested_at,
            evidence_ref=evidence.account_evidence_ref,verified_source_title=evidence.official_source_url,
            contract_test_status='PASSING',status='ACTIVE',updated_at=clock_timestamp(),version=version+1 WHERE id=ANY(evidence.endpoint_ids)
            AND (verification_state<>'VERIFIED' OR status<>'ACTIVE' OR contract_test_status<>'PASSING');
        UPDATE platform.capability_operation SET verification_state='VERIFIED',last_verified_at=evidence.tested_at,
            evidence_ref=evidence.account_evidence_ref,verified_source_title=evidence.official_source_url,
            status='ACTIVE',updated_at=clock_timestamp(),version=version+1 WHERE capability_id=capability.id
            AND (verification_state<>'VERIFIED' OR status<>'ACTIVE');
    END IF;
    UPDATE platform.registry_verification_case SET state=CASE WHEN p_approve THEN 'APPROVED' ELSE 'REJECTED' END,
        reviewed_by_user_id=p_actor,reviewed_at=clock_timestamp(),version=version+1,
        configuration_snapshot=CASE WHEN p_approve THEN platform.registry_configuration_snapshot(capability.id) ELSE configuration_snapshot END
        WHERE id=p_case;
    PERFORM platform.audit_registry_verification(p_actor,p_case,'SUBMITTED',CASE WHEN p_approve THEN 'APPROVED' ELSE 'REJECTED' END,
        evidence.account_evidence_ref,p_correlation);
END;
$$;

CREATE OR REPLACE FUNCTION platform.registry_configuration_snapshot(p_capability uuid) RETURNS jsonb
    LANGUAGE sql STABLE
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
    SELECT jsonb_build_object('capability',to_jsonb(c),
        'profile',(SELECT to_jsonb(p) FROM platform.platform_api_profile p WHERE p.platform_code=c.platform_code),
        'headers',(SELECT coalesce(jsonb_agg(to_jsonb(h) ORDER BY h.id),'[]'::jsonb)
            FROM platform.platform_auth_header h WHERE h.platform_code=c.platform_code
                AND h.credential_purpose=CASE WHEN c.capability_code IN ('listing-description-change','listing-content-change')
                        AND c.read_write_class='WRITE' THEN 'CONTENT_WRITE'
                    WHEN c.read_write_class='WRITE' THEN 'PRICE_WRITE' ELSE 'READ' END),
        'endpoints',(SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY e.id),'[]'::jsonb)
            FROM platform.platform_endpoint e WHERE e.capability_id=c.id),
        'operations',(SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.id),'[]'::jsonb)
            FROM platform.capability_operation o WHERE o.capability_id=c.id),
        'permissionRequirements',(SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]'::jsonb)
            FROM platform.platform_permission_requirement r WHERE r.capability_id=c.id
                OR r.endpoint_id IN (SELECT id FROM platform.platform_endpoint WHERE capability_id=c.id)))
    FROM platform.platform_capability c WHERE c.id=p_capability
$$;

CREATE OR REPLACE FUNCTION platform.configure_registry_draft(p_account uuid, p_capability uuid, p_actor uuid, p_kind text, p_id uuid, p_expected_version bigint, p_definition jsonb, p_correlation text) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE capability platform.platform_capability%ROWTYPE; entity uuid:=coalesce(p_id,gen_random_uuid());
    keys text[]; changed integer;
    profile platform.platform_api_profile%ROWTYPE; header platform.platform_auth_header%ROWTYPE;
    endpoint platform.platform_endpoint%ROWTYPE; operation platform.capability_operation%ROWTYPE;
BEGIN
    SELECT * INTO capability FROM platform.platform_capability WHERE id=p_capability FOR SHARE;
    IF NOT platform.registry_operator_allowed(p_actor,p_account) OR NOT EXISTS
        (SELECT 1 FROM core.marketplace_account WHERE id=p_account AND platform_code=capability.platform_code) THEN
        RAISE EXCEPTION 'configuration account authority denied' USING ERRCODE='MO039';
    END IF;
    keys:=CASE p_kind
        WHEN 'PROFILE' THEN ARRAY['base_url','request_timeout_ms','max_response_bytes','owner_label']
        WHEN 'HEADER' THEN ARRAY['header_name','value_source','value_template','credential_purpose','ordinal','owner_label']
        WHEN 'ENDPOINT' THEN ARRAY['http_method','path_template','operation_function','query_template','body_template',
            'response_content_type','continuation_pointer','pagination_model','rate_limit_per_minute',
            'continuation_end_rule','records_pointer']
        WHEN 'CAPABILITY' THEN ARRAY['write_result_model']
        WHEN 'OPERATION' THEN ARRAY['operation','endpoint_id','request_template','accepted_pointer','accepted_value','task_key_pointer',
            'task_status_pointer','task_success_value','task_failure_value','task_pending_values','observed_price_pointer',
            'observed_currency_pointer','conditional_write_header','version_token_header','owner_label'] END;
    IF p_kind='OPERATION' AND capability.capability_code='listing-description-change' THEN
        keys:=keys||ARRAY['description_observed_text_pointer','description_kiz_marked_pointer',
            'description_attribute_key','description_response_binding','ad_not_applied_pointer','ad_not_applied_value','description_request_guard'];
    END IF;
    IF p_kind='OPERATION' AND capability.capability_code='listing-content-change' THEN
        keys:=keys||ARRAY['description_observed_text_pointer','description_response_binding'];
    END IF;
    IF keys IS NULL OR jsonb_typeof(p_definition) IS DISTINCT FROM 'object'
        OR (p_definition-keys)<>'{}'::jsonb OR octet_length(p_definition::text)>16384 THEN
        RAISE EXCEPTION 'unknown or unbounded draft fields' USING ERRCODE='MO039';
    END IF;
    IF p_kind='PROFILE' THEN
        SELECT * INTO profile FROM jsonb_populate_record(NULL::platform.platform_api_profile,p_definition);
        IF profile.request_timeout_ms NOT BETWEEN 1000 AND 60000 OR profile.max_response_bytes NOT BETWEEN 1024 AND 8388608 THEN
            RAISE EXCEPTION 'profile exceeds transport limits' USING ERRCODE='MO039';
        END IF;
        INSERT INTO platform.platform_api_profile(platform_code,base_url,request_timeout_ms,max_response_bytes,
            verification_state,owner_label,status,created_at,updated_at,version)
        SELECT capability.platform_code,profile.base_url,profile.request_timeout_ms,profile.max_response_bytes,
            'UNVERIFIED',profile.owner_label,'RETIRED',clock_timestamp(),clock_timestamp(),0
        WHERE p_expected_version=-1 OR EXISTS (SELECT 1 FROM platform.platform_api_profile
            WHERE platform_code=capability.platform_code AND version=p_expected_version AND verification_state<>'VERIFIED')
        ON CONFLICT (platform_code) DO UPDATE SET base_url=excluded.base_url,request_timeout_ms=excluded.request_timeout_ms,
            max_response_bytes=excluded.max_response_bytes,owner_label=excluded.owner_label,updated_at=clock_timestamp(),
            version=platform.platform_api_profile.version+1
        WHERE platform.platform_api_profile.version=p_expected_version AND platform.platform_api_profile.verification_state<>'VERIFIED';
        GET DIAGNOSTICS changed=ROW_COUNT;
        entity:=capability.id;
    ELSIF p_kind='HEADER' THEN
        SELECT * INTO header FROM jsonb_populate_record(NULL::platform.platform_auth_header,p_definition);
        IF lower(header.header_name) IN ('host','connection','content-length','transfer-encoding','cookie','forwarded','proxy-authorization')
            OR lower(header.header_name) LIKE 'x-forwarded-%' OR lower(header.header_name) LIKE 'proxy-%'
            OR header.value_template ~ '[[:cntrl:]]' OR length(header.value_template)>256 THEN
            RAISE EXCEPTION 'unsafe authentication header shape' USING ERRCODE='MO039';
        END IF;
        INSERT INTO platform.platform_auth_header(id,platform_code,header_name,value_source,value_template,credential_purpose,
            ordinal,verification_state,owner_label,status,created_at,updated_at)
        SELECT entity,capability.platform_code,header.header_name,header.value_source,header.value_template,header.credential_purpose,
            header.ordinal,'UNVERIFIED',header.owner_label,'RETIRED',clock_timestamp(),clock_timestamp()
        WHERE p_expected_version=-1 OR EXISTS (SELECT 1 FROM platform.platform_auth_header
            WHERE id=entity AND version=p_expected_version AND platform_code=capability.platform_code AND verification_state<>'VERIFIED')
        ON CONFLICT (id) DO UPDATE SET header_name=excluded.header_name,value_source=excluded.value_source,value_template=excluded.value_template,
            credential_purpose=excluded.credential_purpose,ordinal=excluded.ordinal,owner_label=excluded.owner_label,
            updated_at=clock_timestamp(),version=platform.platform_auth_header.version+1
        WHERE platform.platform_auth_header.version=p_expected_version AND platform.platform_auth_header.platform_code=capability.platform_code
            AND platform.platform_auth_header.verification_state<>'VERIFIED';
        GET DIAGNOSTICS changed=ROW_COUNT;
    ELSIF p_kind='ENDPOINT' THEN
        SELECT * INTO endpoint FROM jsonb_populate_record(NULL::platform.platform_endpoint,p_definition);
        UPDATE platform.platform_endpoint SET http_method=endpoint.http_method,path_template=endpoint.path_template,
            operation_function=endpoint.operation_function,query_template=endpoint.query_template,body_template=endpoint.body_template,
            response_content_type=endpoint.response_content_type,continuation_pointer=endpoint.continuation_pointer,
            pagination_model=endpoint.pagination_model,rate_limit_per_minute=endpoint.rate_limit_per_minute,
            continuation_end_rule=coalesce(endpoint.continuation_end_rule,'JSON_NULL'),records_pointer=endpoint.records_pointer,
            updated_at=clock_timestamp(),version=version+1
        WHERE id=entity AND capability_id=capability.id AND version=p_expected_version AND verification_state<>'VERIFIED';
        GET DIAGNOSTICS changed=ROW_COUNT;
    ELSIF p_kind='CAPABILITY' THEN
        UPDATE platform.platform_capability SET write_result_model=p_definition->>'write_result_model',updated_at=clock_timestamp(),version=version+1
        WHERE id=capability.id AND version=p_expected_version AND verification_state<>'VERIFIED';
        GET DIAGNOSTICS changed=ROW_COUNT;
        entity:=capability.id;
    ELSE
        SELECT * INTO operation FROM jsonb_populate_record(NULL::platform.capability_operation,p_definition);
        INSERT INTO platform.capability_operation(id,capability_id,platform_code,operation,endpoint_id,request_template,accepted_pointer,
            accepted_value,task_key_pointer,task_status_pointer,task_success_value,task_failure_value,task_pending_values,
            observed_price_pointer,observed_currency_pointer,conditional_write_header,version_token_header,
            description_observed_text_pointer,description_kiz_marked_pointer,description_attribute_key,description_response_binding,ad_not_applied_pointer,ad_not_applied_value,description_request_guard,
            verification_state,owner_label,status,created_at,updated_at)
        SELECT entity,capability.id,capability.platform_code,operation.operation,operation.endpoint_id,operation.request_template,
            operation.accepted_pointer,operation.accepted_value,operation.task_key_pointer,operation.task_status_pointer,
            operation.task_success_value,operation.task_failure_value,coalesce(operation.task_pending_values,'{}'::text[]),
            operation.observed_price_pointer,operation.observed_currency_pointer,operation.conditional_write_header,operation.version_token_header,
            operation.description_observed_text_pointer,operation.description_kiz_marked_pointer,operation.description_attribute_key,operation.description_response_binding,operation.ad_not_applied_pointer,operation.ad_not_applied_value,operation.description_request_guard,
            'UNVERIFIED',operation.owner_label,'RETIRED',clock_timestamp(),clock_timestamp()
        WHERE p_expected_version=-1 OR EXISTS (SELECT 1 FROM platform.capability_operation
            WHERE id=entity AND capability_id=capability.id AND version=p_expected_version AND verification_state<>'VERIFIED')
        ON CONFLICT (id) DO UPDATE SET endpoint_id=excluded.endpoint_id,request_template=excluded.request_template,
            accepted_pointer=excluded.accepted_pointer,accepted_value=excluded.accepted_value,task_key_pointer=excluded.task_key_pointer,
            task_status_pointer=excluded.task_status_pointer,task_success_value=excluded.task_success_value,task_failure_value=excluded.task_failure_value,
            task_pending_values=excluded.task_pending_values,observed_price_pointer=excluded.observed_price_pointer,
            observed_currency_pointer=excluded.observed_currency_pointer,conditional_write_header=excluded.conditional_write_header,
            version_token_header=excluded.version_token_header,
            description_observed_text_pointer=CASE WHEN capability.capability_code IN ('listing-description-change','listing-content-change') THEN excluded.description_observed_text_pointer ELSE platform.capability_operation.description_observed_text_pointer END,
            description_kiz_marked_pointer=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.description_kiz_marked_pointer ELSE platform.capability_operation.description_kiz_marked_pointer END,
            description_attribute_key=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.description_attribute_key ELSE platform.capability_operation.description_attribute_key END,
            description_response_binding=CASE WHEN capability.capability_code IN ('listing-description-change','listing-content-change') THEN excluded.description_response_binding ELSE platform.capability_operation.description_response_binding END,
            ad_not_applied_pointer=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.ad_not_applied_pointer ELSE platform.capability_operation.ad_not_applied_pointer END,
            ad_not_applied_value=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.ad_not_applied_value ELSE platform.capability_operation.ad_not_applied_value END,
            description_request_guard=CASE WHEN capability.capability_code='listing-description-change' THEN excluded.description_request_guard ELSE platform.capability_operation.description_request_guard END,
            owner_label=excluded.owner_label,updated_at=clock_timestamp(),
            version=platform.capability_operation.version+1
        WHERE platform.capability_operation.version=p_expected_version AND platform.capability_operation.capability_id=capability.id
            AND platform.capability_operation.operation=excluded.operation AND platform.capability_operation.verification_state<>'VERIFIED';
        GET DIAGNOSTICS changed=ROW_COUNT;
    END IF;
    IF changed<>1 THEN RAISE EXCEPTION 'draft version changed or verified revision not opened' USING ERRCODE='MO039'; END IF;
    INSERT INTO ops.metadata_audit_event(id,actor_type,actor_id,source_domain,action,entity_type,entity_id,change_summary,correlation_id)
    VALUES (gen_random_uuid(),'OPERATOR',p_actor::text,'marketplaceintegration','UPDATE','registry-draft',entity,
        jsonb_build_object('kind',p_kind,'definitionSha256',encode(sha256(convert_to(p_definition::text,'UTF8')),'hex')),p_correlation);
    RETURN entity;
END;
$$;

-- 2. The approved change, the command that carries it out, and every call it made ---------------

-- One approved content change: what the Owner confirmed, and the card as the catalog facts showed
-- it at that moment. Written once; nothing about it changes afterwards.
CREATE TABLE ops.content_change (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    platform_listing_variant_id uuid NOT NULL,
    native_listing_key text NOT NULL,
    offer_key text NOT NULL,
    prior_title text,
    prior_description text,
    prior_observed_at timestamp with time zone,
    target_title text NOT NULL,
    target_description text NOT NULL,
    title_changed boolean NOT NULL,
    description_changed boolean NOT NULL,
    source_invocation_id uuid,
    reason text NOT NULL,
    approved_by_user_id uuid NOT NULL,
    approved_at timestamp with time zone NOT NULL,
    approval_expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT content_change_title_ck CHECK (((char_length(target_title) >= 1) AND (char_length(target_title) <= 255) AND (target_title !~ '[[:cntrl:]]'::text) AND (btrim(target_title) = target_title))),
    CONSTRAINT content_change_description_ck CHECK (((char_length(target_description) >= 1) AND (char_length(target_description) <= 6000) AND (target_description !~ '[\x01-\x08\x0b\x0c\x0e-\x1f\x7f]'::text) AND (length(btrim(target_description)) >= 1))),
    CONSTRAINT content_change_some_change_ck CHECK ((title_changed OR description_changed)),
    CONSTRAINT content_change_reason_ck CHECK (((length(btrim(reason)) >= 1) AND (length(btrim(reason)) <= 1024))),
    CONSTRAINT content_change_keys_ck CHECK (((length(btrim(native_listing_key)) >= 1) AND (length(btrim(native_listing_key)) <= 128) AND (length(btrim(offer_key)) >= 1) AND (length(btrim(offer_key)) <= 50))),
    CONSTRAINT content_change_expiry_ck CHECK ((approval_expires_at > approved_at))
);

ALTER TABLE ONLY ops.content_change
    ADD CONSTRAINT content_change_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.content_change
    ADD CONSTRAINT content_change_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.content_change
    ADD CONSTRAINT content_change_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.content_change
    ADD CONSTRAINT content_change_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY ops.content_change
    ADD CONSTRAINT content_change_approver_fk FOREIGN KEY (approved_by_user_id, organization_id) REFERENCES iam.user_account(id, organization_id);

ALTER TABLE ONLY ops.content_change
    ADD CONSTRAINT content_change_invocation_fk FOREIGN KEY (source_invocation_id) REFERENCES ops.ai_invocation(id);

CREATE INDEX content_change_store_ix ON ops.content_change USING btree (organization_id, store_id, approved_at DESC);

-- The command that carries one change out. It moves only forward; a lease names the worker
-- acting on it, and the fence increases with every lease so a stale worker cannot act.
CREATE TABLE ops.content_command (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    change_id uuid NOT NULL,
    store_id uuid NOT NULL,
    platform_listing_variant_id uuid NOT NULL,
    idempotency_key text NOT NULL,
    state text NOT NULL,
    gate_reasons text[] DEFAULT '{}'::text[] NOT NULL,
    capability_id uuid,
    credential_id uuid,
    lease_owner text,
    lease_expires_at timestamp with time zone,
    fence bigint DEFAULT 0 NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    next_action_at timestamp with time zone NOT NULL,
    native_task_key text,
    task_polls integer DEFAULT 0 NOT NULL,
    readbacks integer DEFAULT 0 NOT NULL,
    outcome_code text,
    outcome_detail text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    terminal_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT content_command_state_ck CHECK ((state = ANY (ARRAY['PENDING'::text, 'AWAITING_TASK'::text, 'AWAITING_READBACK'::text, 'UNKNOWN_REQUIRES_READBACK'::text, 'READBACK_MISMATCH'::text, 'SUCCEEDED'::text, 'FAILED_BEFORE_WRITE'::text, 'FAILED'::text, 'CANCELLED'::text, 'CLOSED'::text]))),
    CONSTRAINT content_command_terminal_ck CHECK (((state = ANY (ARRAY['SUCCEEDED'::text, 'FAILED_BEFORE_WRITE'::text, 'FAILED'::text, 'CANCELLED'::text, 'CLOSED'::text])) = (terminal_at IS NOT NULL))),
    CONSTRAINT content_command_lease_ck CHECK (((lease_owner IS NULL) = (lease_expires_at IS NULL))),
    CONSTRAINT content_command_counts_ck CHECK (((fence >= 0) AND (retry_count >= 0) AND (retry_count <= 10) AND (task_polls >= 0) AND (readbacks >= 0))),
    CONSTRAINT content_command_reasons_ck CHECK (((cardinality(gate_reasons) <= 16) AND (array_position(gate_reasons, NULL::text) IS NULL))),
    CONSTRAINT content_command_task_ck CHECK (((native_task_key IS NULL) OR ((length(native_task_key) >= 1) AND (length(native_task_key) <= 64) AND (native_task_key !~ '[[:cntrl:]]'::text)))),
    CONSTRAINT content_command_idempotency_ck CHECK ((idempotency_key ~ '^cc-[0-9a-f-]{36}$'::text))
);

ALTER TABLE ONLY ops.content_command
    ADD CONSTRAINT content_command_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.content_command
    ADD CONSTRAINT content_command_id_org_uq UNIQUE (id, organization_id);

ALTER TABLE ONLY ops.content_command
    ADD CONSTRAINT content_command_change_uq UNIQUE (change_id);

ALTER TABLE ONLY ops.content_command
    ADD CONSTRAINT content_command_idempotency_uq UNIQUE (idempotency_key);

ALTER TABLE ONLY ops.content_command
    ADD CONSTRAINT content_command_change_fk FOREIGN KEY (change_id, organization_id) REFERENCES ops.content_change(id, organization_id);

ALTER TABLE ONLY ops.content_command
    ADD CONSTRAINT content_command_store_fk FOREIGN KEY (store_id, organization_id) REFERENCES core.store(id, organization_id);

ALTER TABLE ONLY ops.content_command
    ADD CONSTRAINT content_command_variant_fk FOREIGN KEY (platform_listing_variant_id, organization_id) REFERENCES core.platform_listing_variant(id, organization_id);

ALTER TABLE ONLY ops.content_command
    ADD CONSTRAINT content_command_capability_fk FOREIGN KEY (capability_id) REFERENCES platform.platform_capability(id);

ALTER TABLE ONLY ops.content_command
    ADD CONSTRAINT content_command_credential_fk FOREIGN KEY (credential_id) REFERENCES platform.credential_metadata(id);

CREATE INDEX content_command_due_ix ON ops.content_command USING btree (next_action_at) WHERE (terminal_at IS NULL);

CREATE INDEX content_command_store_ix ON ops.content_command USING btree (organization_id, store_id, created_at DESC);

-- At most one live command per listing: two writes to one card would race each other's readback.
CREATE UNIQUE INDEX content_command_one_live_per_listing_uq ON ops.content_command USING btree (platform_listing_variant_id) WHERE (terminal_at IS NULL);

-- Every call the command made and every move it took, in order. An apply is recorded as started
-- before the call and as answered after it, so a call whose answer was lost with its worker
-- still left proof that it began.
CREATE TABLE ops.content_command_event (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    command_id uuid NOT NULL,
    sequence integer NOT NULL,
    kind text NOT NULL,
    fence bigint,
    state_after text,
    http_status integer,
    outcome text,
    native_task_key text,
    task_status text,
    observed_title text,
    observed_description text,
    title_match text,
    description_match text,
    detail text,
    raw_observation_id uuid,
    actor_user_id uuid,
    recorded_at timestamp with time zone NOT NULL,
    CONSTRAINT content_command_event_kind_ck CHECK ((kind = ANY (ARRAY['CREATED'::text, 'GATE_CLOSED'::text, 'PRE_READ'::text, 'APPLY_STARTED'::text, 'APPLY'::text, 'STATUS'::text, 'READBACK'::text, 'STATE'::text, 'RESOLUTION'::text]))),
    CONSTRAINT content_command_event_match_ck CHECK ((((title_match IS NULL) OR (title_match = ANY (ARRAY['MATCHES_TARGET'::text, 'MATCHES_PRIOR'::text, 'DIFFERENT'::text]))) AND ((description_match IS NULL) OR (description_match = ANY (ARRAY['MATCHES_TARGET'::text, 'MATCHES_PRIOR'::text, 'DIFFERENT'::text]))))),
    CONSTRAINT content_command_event_sequence_ck CHECK ((sequence >= 1)),
    CONSTRAINT content_command_event_http_ck CHECK (((http_status IS NULL) OR ((http_status >= 100) AND (http_status <= 599)))),
    CONSTRAINT content_command_event_detail_ck CHECK (((detail IS NULL) OR (length(detail) <= 4000)))
);

ALTER TABLE ONLY ops.content_command_event
    ADD CONSTRAINT content_command_event_pk PRIMARY KEY (id);

ALTER TABLE ONLY ops.content_command_event
    ADD CONSTRAINT content_command_event_sequence_uq UNIQUE (command_id, sequence);

ALTER TABLE ONLY ops.content_command_event
    ADD CONSTRAINT content_command_event_command_fk FOREIGN KEY (command_id, organization_id) REFERENCES ops.content_command(id, organization_id);

GRANT SELECT,INSERT ON TABLE ops.content_change TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.content_command TO marketops_app;

GRANT UPDATE (state, gate_reasons, capability_id, credential_id, lease_owner, lease_expires_at, fence, retry_count, next_action_at, native_task_key, task_polls, readbacks, outcome_code, outcome_detail, updated_at, terminal_at, version) ON TABLE ops.content_command TO marketops_app;

GRANT SELECT,INSERT ON TABLE ops.content_command_event TO marketops_app;

INSERT INTO platform.control_route_inventory (schema_name, table_name, route_kind, scope_kind, routing_note) VALUES
    ('ops', 'content_change', 'NO_ROUTE', NULL, 'approved listing title and description changes (W2); written once, carried out by content_command'),
    ('ops', 'content_command', 'NO_ROUTE', NULL, 'content write execution state (W2); guarded by its gate before every write'),
    ('ops', 'content_command_event', 'NO_ROUTE', NULL, 'append-only record of every call and move of a content command (W2)');

-- 3. Kill switch scope and allowlist ----------------------------------------------------------------

ALTER TABLE ops.pilot_allowlist_entry
    DROP CONSTRAINT pilot_allowlist_entry_action_kind_ck;

ALTER TABLE ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_action_kind_ck CHECK ((action_kind = ANY (ARRAY['PRICE_CHANGE'::text, 'AD_BID_CHANGE'::text, 'LISTING_DESCRIPTION_CHANGE'::text, 'LISTING_CONTENT_CHANGE'::text])));

-- A content allowlist entry names the whole store or one listing variant, like a price entry.
ALTER TABLE ops.pilot_allowlist_entry
    DROP CONSTRAINT pilot_allowlist_entry_entity_shape_ck;

ALTER TABLE ops.pilot_allowlist_entry
    ADD CONSTRAINT pilot_allowlist_entry_entity_shape_ck CHECK ((((action_kind <> 'AD_BID_CHANGE'::text) OR ((ad_native_object_id IS NOT NULL) AND (platform_listing_variant_id IS NULL) AND (platform_listing_id IS NULL))) AND ((action_kind <> 'PRICE_CHANGE'::text) OR ((ad_native_object_id IS NULL) AND (platform_listing_id IS NULL))) AND ((action_kind <> 'LISTING_DESCRIPTION_CHANGE'::text) OR ((platform_listing_id IS NOT NULL) AND (platform_listing_variant_id IS NULL) AND (ad_native_object_id IS NULL))) AND ((action_kind <> 'LISTING_CONTENT_CHANGE'::text) OR ((ad_native_object_id IS NULL) AND (platform_listing_id IS NULL)))));

-- 4. Effect review (P10): a content change is followed like a price change ------------------------

ALTER TABLE ops.action_outcome
    DROP CONSTRAINT action_outcome_source_ck;

ALTER TABLE ops.action_outcome
    ADD CONSTRAINT action_outcome_source_ck CHECK ((action_source = ANY (ARRAY['PRICE_COMMAND'::text, 'PRICE_DECISION'::text, 'PROMOTION_DECISION'::text, 'CONTENT_COMMAND'::text])));

ALTER TABLE ops.action_outcome
    DROP CONSTRAINT action_outcome_kind_ck;

ALTER TABLE ops.action_outcome
    ADD CONSTRAINT action_outcome_kind_ck CHECK ((action_kind = ANY (ARRAY['PRICE_CHANGE'::text, 'PROMOTION_JOINED'::text, 'PROMOTION_LEFT'::text, 'CONTENT_CHANGE'::text])));

ALTER TABLE ops.action_outcome
    ADD CONSTRAINT action_outcome_content_source_ck CHECK (((action_kind = 'CONTENT_CHANGE'::text) = (action_source = 'CONTENT_COMMAND'::text)));

-- 5. The content write gate ----------------------------------------------------------------------

-- Why a content command may not write now; empty when it may. Asked when a worker takes the
-- command and again just before the write leaves, so a switch turned off in between still stops
-- it. A missing switch is off, so an unconfigured scope blocks.
CREATE FUNCTION ops.evaluate_content_write_gate(p_command_id uuid) RETURNS text[]
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'pg_catalog', 'pg_temp'
    AS $$
DECLARE
    reasons text[] := '{}';
    now_instant timestamp with time zone := statement_timestamp();
    command_row record;
    capability_row platform.platform_capability%ROWTYPE;
BEGIN
    SELECT command.id, command.organization_id, command.store_id, command.platform_listing_variant_id,
           change.approval_expires_at, store.marketplace_account_id, account.platform_code
      INTO command_row
      FROM ops.content_command AS command
      JOIN ops.content_change AS change ON change.id = command.change_id
      JOIN core.store AS store ON store.id = command.store_id
      JOIN core.marketplace_account AS account ON account.id = store.marketplace_account_id
     WHERE command.id = p_command_id;
    IF command_row.id IS NULL THEN
        RETURN ARRAY['COMMAND_NOT_FOUND'];
    END IF;

    SELECT * INTO capability_row
      FROM platform.platform_capability AS capability
     WHERE capability.platform_code = command_row.platform_code
       AND capability.capability_code = 'listing-content-change'
       AND capability.read_write_class = 'WRITE';
    IF capability_row.id IS NULL THEN
        reasons := array_append(reasons, 'CAPABILITY_NOT_REGISTERED');
    ELSE
        IF capability_row.status <> 'ACTIVE' OR capability_row.deprecated_at IS NOT NULL
            OR capability_row.verification_state <> 'VERIFIED' THEN
            reasons := array_append(reasons, 'CAPABILITY_NOT_VERIFIED');
        ELSIF NOT platform.capability_evidence_current(command_row.marketplace_account_id, capability_row.id, NULL) THEN
            reasons := array_append(reasons, 'CAPABILITY_EVIDENCE_NOT_CURRENT');
        END IF;
        PERFORM 1 FROM platform.capability_subject_status AS subject
         WHERE subject.capability_id = capability_row.id AND subject.store_id = command_row.store_id
           AND subject.availability = 'AVAILABLE';
        IF NOT FOUND THEN
            reasons := array_append(reasons, 'CAPABILITY_NOT_AVAILABLE_FOR_STORE');
        END IF;
        PERFORM 1 FROM platform.feature_flag AS flag
         WHERE flag.flag_code = 'listing-content-write' AND flag.scope_kind = 'CAPABILITY'
           AND flag.capability_id = capability_row.id AND flag.status = 'ACTIVE' AND flag.state = 'ENABLED';
        IF NOT FOUND THEN
            reasons := array_append(reasons, 'CAPABILITY_SWITCH_DISABLED');
        END IF;
    END IF;

    PERFORM 1 FROM platform.feature_flag AS flag
     WHERE flag.flag_code = 'listing-content-write' AND flag.scope_kind = 'GLOBAL'
       AND flag.status = 'ACTIVE' AND flag.state = 'ENABLED';
    IF NOT FOUND THEN
        reasons := array_append(reasons, 'GLOBAL_SWITCH_DISABLED');
    END IF;

    IF EXISTS (SELECT 1 FROM platform.feature_flag AS flag
                WHERE flag.flag_code = 'listing-content-write' AND flag.status = 'ACTIVE' AND flag.state = 'DISABLED'
                  AND ((flag.scope_kind = 'PLATFORM' AND flag.platform_code = command_row.platform_code)
                    OR (flag.scope_kind = 'MARKETPLACE_ACCOUNT' AND flag.marketplace_account_id = command_row.marketplace_account_id)
                    OR (flag.scope_kind = 'STORE' AND flag.store_id = command_row.store_id))) THEN
        reasons := array_append(reasons, 'SCOPED_SWITCH_DISABLED');
    END IF;

    PERFORM 1 FROM ops.pilot_allowlist_entry AS entry
     WHERE entry.action_kind = 'LISTING_CONTENT_CHANGE' AND entry.status = 'ACTIVE'
       AND entry.store_id = command_row.store_id
       AND (entry.platform_listing_variant_id IS NULL
            OR entry.platform_listing_variant_id = command_row.platform_listing_variant_id)
       AND entry.valid_from <= now_instant AND entry.valid_until > now_instant;
    IF NOT FOUND THEN
        reasons := array_append(reasons, 'ENTITY_NOT_ALLOWLISTED');
    END IF;

    IF command_row.approval_expires_at <= now_instant THEN
        reasons := array_append(reasons, 'APPROVAL_EXPIRED');
    END IF;
    RETURN reasons;
END;
$$;

REVOKE ALL ON FUNCTION ops.evaluate_content_write_gate(p_command_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION ops.evaluate_content_write_gate(p_command_id uuid) TO marketops_app;
