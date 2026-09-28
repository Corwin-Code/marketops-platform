-- V0011: name a listing variant by the marketplace's item identifier, and take a fact's period
-- from the acquisition run's window.
--
-- Ozon analytics (POST /v1/analytics/data, 2026-09-29) groups its rows by SKU, the storefront
-- item identifier, while listings and variants are keyed by the product id the catalog, price
-- and stock answers use. The catalog answer carries both, so:
--   * a variant records the item identifier the catalog states (native_item_key), and a dataset
--     may name a variant by it (canonical field nativeItemKey) instead of by the listing and
--     variant keys. Normalization resolves it through what the catalog recorded; an item nobody
--     recorded resolves to nothing and produces no fact, it never creates a listing;
--   * the analytics answer does not state the period it covers: the run asks for one window and
--     the answer is about that window, so a field may take the run's window start or end
--     (WINDOW_START, WINDOW_END; INSTANT fields only). A run without a window gives no value, and
--     a required period field that stays absent rejects the record.

ALTER TABLE core.platform_listing_variant
    ADD COLUMN native_item_key text;

ALTER TABLE core.platform_listing_variant
    ADD CONSTRAINT platform_listing_variant_native_item_ck CHECK (((native_item_key IS NULL) OR ((length(btrim(native_item_key)) >= 1) AND (length(btrim(native_item_key)) <= 128))));

CREATE INDEX platform_listing_variant_item_key_ix ON core.platform_listing_variant USING btree (organization_id, native_item_key) WHERE (native_item_key IS NOT NULL);

INSERT INTO staging.canonical_field (dataset_kind, field_name, value_kind, requirement, description, ordinal) VALUES
    ('LISTING', 'nativeItemKey', 'TEXT', 'OPTIONAL', 'The marketplace item identifier other datasets may name the variant by.', 10),
    ('TRAFFIC', 'nativeItemKey', 'TEXT', 'OPTIONAL', 'The marketplace item identifier, when the source names the variant by it instead of the listing and variant keys.', 10);

ALTER TABLE staging.normalization_field
    DROP CONSTRAINT normalization_field_source_kind_ck,
    DROP CONSTRAINT normalization_field_source_shape_ck;

ALTER TABLE staging.normalization_field
    ADD CONSTRAINT normalization_field_source_kind_ck CHECK ((source_kind = ANY (ARRAY['POINTER'::text, 'PARENT_POINTER'::text, 'OBSERVATION_TIME'::text, 'CONSTANT'::text, 'WINDOW_START'::text, 'WINDOW_END'::text]))),
    ADD CONSTRAINT normalization_field_source_shape_ck CHECK ((((source_kind = ANY (ARRAY['POINTER'::text, 'PARENT_POINTER'::text])) AND (source_pointer IS NOT NULL) AND (constant_value IS NULL))
        OR ((source_kind = ANY (ARRAY['OBSERVATION_TIME'::text, 'WINDOW_START'::text, 'WINDOW_END'::text])) AND (source_pointer IS NULL) AND (constant_value IS NULL) AND (value_map IS NULL))
        OR ((source_kind = 'CONSTANT'::text) AND (source_pointer IS NULL) AND (constant_value IS NOT NULL) AND (length(constant_value) >= 1) AND (length(constant_value) <= 256) AND (value_map IS NULL))));

-- The analytics request names its window and its offset, so a read template may carry the
-- run's window (windowFrom, windowTo as instants; windowStartUtcDate, windowEndUtcDate as the
-- UTC calendar days of the first and the last instant inside it) and a computed position
-- (offset, page; numeric, like limit). The set stays closed; everything else is unchanged.
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
            'descriptionText', 'descriptionAttributeKey']
        ELSE ARRAY['cursor', 'limit', 'accountKey', 'endpointCode', 'offset', 'page',
            'windowFrom', 'windowTo', 'windowStartUtcDate', 'windowEndUtcDate'] END;
    FOR token IN SELECT regexp_matches(p_template, '\{([a-zA-Z][a-zA-Z0-9]{0,31})\}', 'g') LOOP
        IF NOT token[1] = ANY(allowed) THEN RETURN false; END IF;
        rendered := replace(rendered, '{' || token[1] || '}',
            CASE WHEN token[1] IN ('targetPrice', 'targetBid', 'limit', 'cursor', 'offset', 'page')
                 THEN '1' ELSE 'fixture' END);
    END LOOP;
    IF p_is_body THEN RETURN rendered IS JSON OBJECT WITH UNIQUE KEYS; END IF;
    RETURN rendered !~ '[{}[:cntrl:]]';
END;
$$;

-- A paginated endpoint had to say where the source puts its continuation. The analytics answer
-- carries none: the next offset is the last one plus the page size, and a page shorter than
-- the page size is the last. An OFFSET or PAGE endpoint with a short-page end rule therefore
-- has complete paging semantics without a continuation pointer. The function is otherwise
-- exactly the V0001 definition.
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
    expected_purpose:=CASE WHEN capability.capability_code='listing-description-change' AND capability.read_write_class='WRITE' THEN 'CONTENT_WRITE'
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
                                  AND e.continuation_end_rule IN ('SHORT_PAGE','SHORT_PAGE_OR_NOT_FOUND')))
                     OR (capability.read_write_class='READ' AND
                         (e.read_write_class<>'READ' OR e.operation_function<>'READ_DATA' OR e.http_method NOT IN ('GET','POST')
                          OR NOT platform.request_template_is_well_formed(e.path_template,false,false)
                          OR NOT platform.request_template_is_well_formed(e.query_template,false,false)
                          OR NOT platform.request_template_is_well_formed(e.body_template,true,false))))) THEN
            RAISE EXCEPTION 'real-account evidence or complete protocol semantics missing' USING ERRCODE='MO039';
        END IF;
        IF capability.read_write_class='WRITE' THEN
            IF capability.capability_code NOT IN ('price-change','listing-description-change') OR capability.write_result_model='UNKNOWN'
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
