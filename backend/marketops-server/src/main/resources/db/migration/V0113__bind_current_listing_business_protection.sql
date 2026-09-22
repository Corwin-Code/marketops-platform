-- Root 007/011: bind only the exact affected products' accepted supply scenarios.
-- This extends the existing calibration dependency projection, with no new Policy values or grants.
ALTER TABLE core.lc_calibration_value DROP CONSTRAINT lc_calibration_value_present_ck;
ALTER TABLE core.lc_calibration_value ADD CONSTRAINT lc_calibration_value_present_ck CHECK (
  num_nonnulls(value_numeric,value_text,value_json)=1
  OR (category_code='NON_WORSENING_PROFIT_BOUND' AND value_numeric IS NOT NULL
      AND value_text IS NULL AND value_json IS NOT NULL));

CREATE OR REPLACE FUNCTION core.lc_action_calibration_dependencies(p_package uuid,p_kind text) RETURNS jsonb
LANGUAGE sql STABLE SET search_path=pg_catalog SET timezone='UTC' SET datestyle='ISO, YMD' AS $$
 SELECT jsonb_build_object('model','LC_ACTION_RULE_DEPENDENCIES_1','purpose',p.purpose_code,
   'actionKind',p_kind,'values',
   (SELECT jsonb_object_agg(v.category_code,jsonb_build_object('numeric',v.value_numeric,'text',v.value_text,
      'json',CASE WHEN v.category_code='DEMAND_SCENARIO_SET' THEN v.value_json-'supplyScenarios'-'economicScenarioBases' WHEN v.category_code='CRITICAL_GROUP_RULE' THEN v.value_json-'protectionScopeBases' WHEN v.category_code='NON_WORSENING_PROFIT_BOUND' THEN v.value_json-'currentAccountingComparisons' ELSE v.value_json END,'unit',v.unit_code,'windowDays',v.window_days,'evidenceReference',v.evidence_reference)
       ORDER BY v.category_code)
    FROM core.lc_calibration_value v WHERE v.package_id=p.id
      -- A versioned, closed consumer inventory: future categories cannot silently enlarge old dependencies.
      AND v.category_code IN ('MATERIAL_IMPROVEMENT_BOUND','NON_WORSENING_PROFIT_BOUND',
        'NON_WORSENING_RETURN_BOUND','CRITICAL_GROUP_RULE','DEMAND_SCENARIO_SET','FRESHNESS_RULE',
        'RESPONSIBILITY_SLO','RESPONSIBILITY_COVERAGE','ORDINARY_TRIGGER_CONTENT','MATERIAL_TRIGGER_CONTENT',
        'ORDINARY_TRIGGER_EXPOSURE','MATERIAL_TRIGGER_EXPOSURE','APPROVAL_VALIDITY',
        'REPRESENTATION_EQUIVALENCE_RULE','ALLOWANCE_AXES','ALLOWANCE_RESERVE','FORMAL_NODES','STOP_RULE',
        'CROSS_PERIOD_WINDOW','DESCRIPTION_LENGTH_RULE')
      -- Description actions never consume promotion demand scenarios. Promotion actions never
      -- serialize description text or use the description readback representation rule.
      AND NOT (p_kind='LISTING_DESCRIPTION_CHANGE' AND v.category_code='DEMAND_SCENARIO_SET')
      AND NOT (p_kind='LISTING_PROMOTION_ACTION' AND v.category_code IN
           ('DESCRIPTION_LENGTH_RULE','REPRESENTATION_EQUIVALENCE_RULE'))))
 FROM core.lc_calibration_package p WHERE p.id=p_package
  AND p_kind IN ('LISTING_DESCRIPTION_CHANGE','LISTING_PROMOTION_ACTION')
$$;

CREATE FUNCTION core.lc_action_calibration_dependencies(p_package uuid,p_kind text,p_affected_set uuid) RETURNS jsonb
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT core.lc_action_calibration_dependencies(p_package,p_kind)||
  CASE WHEN s.items IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('supplyScenarios',s.items) END||
  coalesce((SELECT jsonb_build_object('currentAccountingComparison',v.value_json->'currentAccountingComparisons'->a.platform_listing_id::text)
   FROM core.lc_calibration_value v JOIN core.lc_affected_set a ON a.id=p_affected_set
   WHERE v.package_id=p_package AND v.category_code='NON_WORSENING_PROFIT_BOUND'
    AND v.value_json->'currentAccountingComparisons'->a.platform_listing_id::text IS NOT NULL),'{}'::jsonb)||
  coalesce((SELECT jsonb_build_object('economicScenarioBasis',v.value_json->'economicScenarioBases'->a.platform_listing_id::text)
   FROM core.lc_calibration_value v JOIN core.lc_affected_set a ON a.id=p_affected_set
   WHERE v.package_id=p_package AND v.category_code='DEMAND_SCENARIO_SET' AND p_kind='LISTING_PROMOTION_ACTION'
    AND v.value_json->'economicScenarioBases'->a.platform_listing_id::text IS NOT NULL),'{}'::jsonb)||
  coalesce((SELECT jsonb_build_object('protectionScopeBasis',v.value_json->'protectionScopeBases'->a.platform_listing_id::text)
   FROM core.lc_calibration_value v JOIN core.lc_affected_set a ON a.id=p_affected_set
   WHERE v.package_id=p_package AND v.category_code='CRITICAL_GROUP_RULE'
    AND v.value_json->'protectionScopeBases'->a.platform_listing_id::text IS NOT NULL),'{}'::jsonb)
 FROM (SELECT jsonb_agg(item ORDER BY item->>'productVariantId',item->>'code') AS items
  FROM core.lc_calibration_value v JOIN core.lc_affected_set a ON a.id=p_affected_set
  CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(v.value_json->'supplyScenarios')='array'
    THEN v.value_json->'supplyScenarios' ELSE '[]'::jsonb END) item
  WHERE v.package_id=p_package AND v.category_code='DEMAND_SCENARIO_SET'
    AND item->>'productVariantId'=ANY(SELECT id::text FROM unnest(a.product_variant_ids) id)) s
$$;
REVOKE ALL ON FUNCTION core.lc_action_calibration_dependencies(uuid,text,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lc_action_calibration_dependencies(uuid,text,uuid) TO marketops_app;

CREATE OR REPLACE FUNCTION ops.capture_lc_action_calibration_dependencies() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='UPDATE' THEN
   IF NEW.calibration_dependencies IS DISTINCT FROM OLD.calibration_dependencies
     OR NEW.calibration_package_id IS DISTINCT FROM OLD.calibration_package_id
     OR NEW.calibration_version IS DISTINCT FROM OLD.calibration_version THEN
     RAISE EXCEPTION 'prepared rule dependencies and authority are immutable' USING ERRCODE='MO090';
   END IF;
 ELSE
   NEW.calibration_dependencies:=core.lc_action_calibration_dependencies(NEW.calibration_package_id,NEW.action_kind,NEW.affected_set_id);
 END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION ops.lc_action_calibration_recheck(p_action uuid,p_at timestamptz) RETURNS jsonb
LANGUAGE plpgsql STABLE SET search_path=pg_catalog AS $$
DECLARE action ops.lc_action; listing core.platform_listing; bound core.lc_calibration_package;
 current_id uuid; current_version integer; resolution text; projection jsonb; state text;
BEGIN
 SELECT * INTO action FROM ops.lc_action WHERE id=p_action;
 SELECT * INTO listing FROM core.platform_listing WHERE id=action.platform_listing_id;
 SELECT * INTO bound FROM core.lc_calibration_package WHERE id=action.calibration_package_id
  AND organization_id=action.organization_id AND package_version=action.calibration_version
  AND (scope_kind='ORGANIZATION' OR (scope_kind='PLATFORM' AND platform_code=listing.platform_code)
       OR (scope_kind='STORE' AND store_ref_id=action.store_id));
 IF bound.id IS NULL THEN RETURN jsonb_build_object('state','CALIBRATION_UNRESOLVED'); END IF;
 SELECT r.package_id,r.package_version,r.resolution_state INTO current_id,current_version,resolution
  FROM core.lc_resolve_calibration_for(action.organization_id,listing.platform_code,action.store_id,p_at,bound.purpose_code) r;
 state:='CALIBRATION_UNRESOLVED';
 IF resolution='RESOLVED' THEN
   projection:=core.lc_action_calibration_dependencies(current_id,action.action_kind,action.affected_set_id);
   IF current_id=bound.id THEN state:='CURRENT';
   ELSIF action.calibration_dependencies IS NULL THEN state:='CALIBRATION_DEPENDENCIES_UNPROVEN';
   ELSIF NOT EXISTS(SELECT 1 FROM ops.lc_calibration_governance g WHERE g.package_id=bound.id
       AND g.accepted_at<=action.created_at AND g.accepted_digest=ops.lc_calibration_digest(bound.id))
       OR bound.activated_at IS NULL OR bound.activated_at>action.created_at
       OR (bound.retired_at IS NOT NULL AND bound.retired_at<=action.created_at)
       OR bound.effective_from>action.created_at OR (bound.effective_to IS NOT NULL AND bound.effective_to<=action.created_at)
       OR action.calibration_dependencies IS DISTINCT FROM core.lc_action_calibration_dependencies(bound.id,action.action_kind,action.affected_set_id)
       THEN state:='CALIBRATION_DEPENDENCIES_UNPROVEN';
   ELSIF projection IS DISTINCT FROM action.calibration_dependencies THEN state:='CALIBRATION_DEPENDENCIES_CHANGED';
   ELSE state:='UNCHANGED_DEPENDENCIES'; END IF;
 END IF;
 RETURN jsonb_build_object('state',state,'purpose',bound.purpose_code,'boundPackageId',bound.id,
   'currentPackageId',current_id,'currentVersion',current_version,
   'dependencyDigest',CASE WHEN projection IS NULL THEN NULL ELSE encode(sha256(convert_to(projection::text,'UTF8')),'hex') END);
END $$;

-- Existing independent acceptance validates every supplied scenario; no frontend necessary/conservative flag is authoritative.
ALTER FUNCTION ops.lc_calibration_combination_failures(uuid) RENAME TO lc_calibration_combination_failures_v0107;
CREATE FUNCTION ops.lc_calibration_combination_failures(p_id uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SET search_path=pg_catalog AS $$
DECLARE failures text[]; nodes jsonb; node jsonb; comparison jsonb; reference_from timestamptz; reference_to timestamptz; scenarios jsonb; item jsonb; financial_freshness jsonb; scope_bases jsonb; scope_item record; linked jsonb; member jsonb; codes text[]; member_ids text[]; age_key text; organization uuid; identities text[]:='{}'; identity text;
BEGIN
 failures:=ops.lc_calibration_combination_failures_v0107(p_id);
 SELECT organization_id INTO organization FROM core.lc_calibration_package WHERE id=p_id;
 -- Validate supplied current-financial freshness limits in the existing accepted package.
 -- Absence remains unresolved at the consumer; it does not invent a production duration.
 SELECT value_json->'businessProtection' INTO financial_freshness FROM core.lc_calibration_value
  WHERE package_id=p_id AND category_code='FRESHNESS_RULE';
 IF financial_freshness IS NOT NULL THEN
   IF jsonb_typeof(financial_freshness) IS DISTINCT FROM 'object' THEN
     failures:=array_append(failures,'CURRENT_FINANCIAL_FRESHNESS_INVALID');
   ELSE
     FOREACH age_key IN ARRAY ARRAY['maximumVerificationAgeSeconds','maximumPeriodEndAgeSeconds'] LOOP
       IF jsonb_typeof(financial_freshness->age_key) IS DISTINCT FROM 'number' THEN
         failures:=array_append(failures,'CURRENT_FINANCIAL_FRESHNESS_INVALID');
       ELSIF (financial_freshness->>age_key)::numeric<=0
           OR (financial_freshness->>age_key)::numeric>9223372036854775807
           OR trunc((financial_freshness->>age_key)::numeric)<>(financial_freshness->>age_key)::numeric THEN
         failures:=array_append(failures,'CURRENT_FINANCIAL_FRESHNESS_INVALID');
       END IF;
     END LOOP;
   END IF;
 END IF;
 SELECT value_json->'protectionScopeBases' INTO scope_bases FROM core.lc_calibration_value
  WHERE package_id=p_id AND category_code='CRITICAL_GROUP_RULE';
 IF scope_bases IS NOT NULL THEN
   IF jsonb_typeof(scope_bases) IS DISTINCT FROM 'object' THEN
     failures:=array_append(failures,'PROTECTION_SCOPE_BASES_INVALID');
   ELSE
     FOR scope_item IN SELECT key,value FROM jsonb_each(scope_bases) LOOP
       IF NOT EXISTS(SELECT 1 FROM core.platform_listing l WHERE l.id::text=scope_item.key AND l.organization_id=organization)
          OR jsonb_typeof(scope_item.value) IS DISTINCT FROM 'object'
          OR jsonb_typeof(scope_item.value->'evidenceReference') IS DISTINCT FROM 'string'
          OR length(btrim(scope_item.value->>'evidenceReference')) NOT BETWEEN 1 AND 512
          OR jsonb_typeof(scope_item.value->'linkedProfitScopes') IS DISTINCT FROM 'array'
          OR jsonb_typeof(scope_item.value->'criticalReturnVariantIds') IS DISTINCT FROM 'array' THEN
         failures:=array_append(failures,'PROTECTION_SCOPE_BASIS_INVALID'); CONTINUE;
       END IF;
       codes:='{}';
       FOR linked IN SELECT value FROM jsonb_array_elements(scope_item.value->'linkedProfitScopes') LOOP
         IF jsonb_typeof(linked) IS DISTINCT FROM 'object'
            OR jsonb_typeof(linked->'code') IS DISTINCT FROM 'string'
            OR (linked->>'code') !~ '^[A-Z][A-Z0-9_]{0,63}$'
            OR (linked->>'code')=ANY(codes)
            OR jsonb_typeof(linked->'listingVariantIds') IS DISTINCT FROM 'array'
            OR jsonb_typeof(linked->'evidenceReference') IS DISTINCT FROM 'string'
            OR length(btrim(linked->>'evidenceReference')) NOT BETWEEN 1 AND 512 THEN
           failures:=array_append(failures,'LINKED_PROFIT_SCOPE_INVALID'); CONTINUE;
         END IF;
         codes:=array_append(codes,linked->>'code'); member_ids:='{}';
         IF jsonb_array_length(linked->'listingVariantIds')=0 THEN
           failures:=array_append(failures,'LINKED_PROFIT_SCOPE_EMPTY');
         END IF;
         FOR member IN SELECT value FROM jsonb_array_elements(linked->'listingVariantIds') LOOP
           IF jsonb_typeof(member) IS DISTINCT FROM 'string' OR (member#>>'{}')=ANY(member_ids)
              OR NOT EXISTS(SELECT 1 FROM core.platform_listing_variant v JOIN core.platform_listing l ON l.id=v.platform_listing_id
                WHERE v.id::text=member#>>'{}' AND v.organization_id=organization AND l.store_id::text=linked->>'storeId') THEN
             failures:=array_append(failures,'LINKED_PROFIT_MEMBER_INVALID');
           END IF;
           member_ids:=array_append(member_ids,member#>>'{}');
         END LOOP;
       END LOOP;
       member_ids:='{}';
       FOR member IN SELECT value FROM jsonb_array_elements(scope_item.value->'criticalReturnVariantIds') LOOP
         IF jsonb_typeof(member) IS DISTINCT FROM 'string' OR (member#>>'{}')=ANY(member_ids)
            OR NOT EXISTS(SELECT 1 FROM core.platform_listing_variant v WHERE v.id::text=member#>>'{}'
                AND v.organization_id=organization AND v.platform_listing_id::text=scope_item.key) THEN
           failures:=array_append(failures,'CRITICAL_RETURN_MEMBER_INVALID');
         END IF;
         member_ids:=array_append(member_ids,member#>>'{}');
       END LOOP;
     END LOOP;
   END IF;
 END IF;
 SELECT value_json INTO nodes FROM core.lc_calibration_value WHERE package_id=p_id AND category_code='FORMAL_NODES';
 IF jsonb_typeof(nodes)='array' THEN
   FOR node IN SELECT value FROM jsonb_array_elements(nodes) LOOP
     comparison:=node->'protectionComparison';
     IF comparison IS NULL THEN CONTINUE; END IF;
     IF jsonb_typeof(comparison) IS DISTINCT FROM 'object'
        OR comparison->>'method' IS DISTINCT FROM 'CANONICAL_ACCOUNTING_CHANGE_V1'
        OR jsonb_typeof(comparison->'qualificationRef') IS DISTINCT FROM 'string'
        OR length(btrim(comparison->>'qualificationRef')) NOT BETWEEN 1 AND 512
        OR jsonb_typeof(comparison->'referencePeriodStart') IS DISTINCT FROM 'string'
        OR jsonb_typeof(comparison->'referencePeriodEnd') IS DISTINCT FROM 'string'
        OR (comparison->>'referencePeriodStart') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T.*(Z|[+-][0-9]{2}:[0-9]{2})$'
        OR (comparison->>'referencePeriodEnd') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T.*(Z|[+-][0-9]{2}:[0-9]{2})$' THEN
       failures:=array_append(failures,'ACCOUNTING_COMPARISON_METHOD_INVALID'); CONTINUE;
     END IF;
     BEGIN
       reference_from:=(comparison->>'referencePeriodStart')::timestamptz;
       reference_to:=(comparison->>'referencePeriodEnd')::timestamptz;
       IF reference_from>=reference_to OR reference_to>statement_timestamp()
          OR jsonb_typeof(node#>'{schedule,windowStartOffsetDays}') IS DISTINCT FROM 'number'
          OR jsonb_typeof(node#>'{schedule,windowEndOffsetDays}') IS DISTINCT FROM 'number' THEN
         failures:=array_append(failures,'ACCOUNTING_COMPARISON_PERIOD_INVALID');
       ELSIF extract(epoch FROM reference_to-reference_from) IS DISTINCT FROM
          ((node#>>'{schedule,windowEndOffsetDays}')::numeric-(node#>>'{schedule,windowStartOffsetDays}')::numeric)*86400 THEN
         failures:=array_append(failures,'ACCOUNTING_COMPARISON_PERIOD_LENGTH_MISMATCH');
       END IF;
     EXCEPTION WHEN invalid_datetime_format OR datetime_field_overflow THEN
       failures:=array_append(failures,'ACCOUNTING_COMPARISON_PERIOD_INVALID');
     END;
     IF (SELECT count(*) FROM core.lc_calibration_value v WHERE v.package_id=p_id
         AND v.category_code IN ('NON_WORSENING_PROFIT_BOUND','NON_WORSENING_RETURN_BOUND')
         AND v.unit_code='RATIO' AND v.value_numeric BETWEEN 0 AND 1)<>2 THEN
       failures:=array_append(failures,'ACCOUNTING_COMPARISON_BOUNDS_INVALID');
     END IF;
   END LOOP;
 END IF;
 -- Exact accepted accounting reference; no automatic previous-period selection.
 SELECT value_json->'currentAccountingComparisons' INTO scope_bases FROM core.lc_calibration_value
  WHERE package_id=p_id AND category_code='NON_WORSENING_PROFIT_BOUND';
 IF scope_bases IS NOT NULL THEN
  IF jsonb_typeof(scope_bases) IS DISTINCT FROM 'object' THEN
   failures:=array_append(failures,'CURRENT_ACCOUNTING_REFERENCES_INVALID');
  ELSE
   FOR scope_item IN SELECT key,value FROM jsonb_each(scope_bases) LOOP
    IF NOT EXISTS(SELECT 1 FROM core.platform_listing l WHERE l.organization_id=organization AND l.id::text=scope_item.key)
      OR jsonb_typeof(scope_item.value) IS DISTINCT FROM 'object'
      OR jsonb_typeof(scope_item.value->'evidenceReference') IS DISTINCT FROM 'string'
      OR length(btrim(scope_item.value->>'evidenceReference')) NOT BETWEEN 1 AND 512
      OR jsonb_typeof(scope_item.value->'periodStart') IS DISTINCT FROM 'string'
      OR jsonb_typeof(scope_item.value->'periodEnd') IS DISTINCT FROM 'string' THEN
     failures:=array_append(failures,'CURRENT_ACCOUNTING_REFERENCE_INVALID'); CONTINUE;
    END IF;
    IF (scope_item.value->>'periodStart') !~ 'T.*(Z|[+-][0-9]{2}:[0-9]{2})$'
      OR (scope_item.value->>'periodEnd') !~ 'T.*(Z|[+-][0-9]{2}:[0-9]{2})$' THEN
     failures:=array_append(failures,'CURRENT_ACCOUNTING_REFERENCE_PERIOD_INVALID'); CONTINUE;
    END IF;
    BEGIN
     reference_from:=(scope_item.value->>'periodStart')::timestamptz;
     reference_to:=(scope_item.value->>'periodEnd')::timestamptz;
     IF NOT isfinite(reference_from) OR NOT isfinite(reference_to) OR reference_from>=reference_to THEN
      failures:=array_append(failures,'CURRENT_ACCOUNTING_REFERENCE_PERIOD_INVALID');
     END IF;
    EXCEPTION WHEN invalid_datetime_format OR datetime_field_overflow THEN
     failures:=array_append(failures,'CURRENT_ACCOUNTING_REFERENCE_PERIOD_INVALID');
    END;
   END LOOP;
   IF (SELECT count(*) FROM core.lc_calibration_value v WHERE v.package_id=p_id
       AND v.category_code IN ('NON_WORSENING_PROFIT_BOUND','NON_WORSENING_RETURN_BOUND')
       AND v.unit_code='RATIO' AND v.value_numeric BETWEEN 0 AND 1)<>2
      OR (SELECT count(DISTINCT window_days) FROM core.lc_calibration_value WHERE package_id=p_id
       AND category_code IN ('NON_WORSENING_PROFIT_BOUND','NON_WORSENING_RETURN_BOUND'))<>1 THEN
    failures:=array_append(failures,'CURRENT_ACCOUNTING_REFERENCE_BOUNDS_INVALID');
   END IF;
  END IF;
 END IF;
 -- Listing-specific accepted downside scenarios; supplied caller flags are not authority.
 SELECT value_json->'economicScenarioBases' INTO scope_bases FROM core.lc_calibration_value
  WHERE package_id=p_id AND category_code='DEMAND_SCENARIO_SET';
 IF scope_bases IS NOT NULL THEN
  IF jsonb_typeof(scope_bases) IS DISTINCT FROM 'object' THEN
   failures:=array_append(failures,'ECONOMIC_SCENARIO_BASES_INVALID');
  ELSE
   FOR scope_item IN SELECT key,value FROM jsonb_each(scope_bases) LOOP
    IF NOT EXISTS(SELECT 1 FROM core.platform_listing l WHERE l.organization_id=organization AND l.id::text=scope_item.key)
      OR jsonb_typeof(scope_item.value) IS DISTINCT FROM 'object'
      OR jsonb_typeof(scope_item.value->'evidenceReference') IS DISTINCT FROM 'string'
      OR length(btrim(scope_item.value->>'evidenceReference')) NOT BETWEEN 1 AND 512
      OR jsonb_typeof(scope_item.value->'minimumContributionProfit') IS DISTINCT FROM 'number'
      OR (scope_item.value->>'minimumContributionProfit')::numeric NOT BETWEEN 0 AND 99999999999999
      OR jsonb_typeof(scope_item.value->'currencyCode') IS DISTINCT FROM 'string'
      OR (scope_item.value->>'currencyCode') !~ '^[A-Z]{3}$'
      OR jsonb_typeof(scope_item.value->'profitEvidenceReference') IS DISTINCT FROM 'string'
      OR length(btrim(scope_item.value->>'profitEvidenceReference')) NOT BETWEEN 1 AND 512
      OR jsonb_typeof(scope_item.value->'necessaryScenarios') IS DISTINCT FROM 'array' THEN
     failures:=array_append(failures,'ECONOMIC_SCENARIO_BASIS_INVALID'); CONTINUE;
    END IF;
    BEGIN
     IF coalesce(scope_item.value->>'periodStart','') !~ '^\d{4}-\d{2}-\d{2}T.*(Z|[+-]\d{2}:\d{2})$'
       OR coalesce(scope_item.value->>'periodEnd','') !~ '^\d{4}-\d{2}-\d{2}T.*(Z|[+-]\d{2}:\d{2})$' THEN
      failures:=array_append(failures,'ECONOMIC_SCENARIO_PERIOD_INVALID'); CONTINUE;
     END IF;
     reference_from:=(scope_item.value->>'periodStart')::timestamptz;
     reference_to:=(scope_item.value->>'periodEnd')::timestamptz;
     -- Activation reruns this package-wide validator. Clock expiry belongs to the exact consumer.
     IF reference_from>=reference_to THEN
      failures:=array_append(failures,'ECONOMIC_SCENARIO_PERIOD_INVALID');
     END IF;
    EXCEPTION WHEN invalid_datetime_format OR datetime_field_overflow THEN
     failures:=array_append(failures,'ECONOMIC_SCENARIO_PERIOD_INVALID');
    END;
    scenarios:=scope_item.value->'necessaryScenarios'; codes:='{}';
    IF jsonb_array_length(scenarios) NOT BETWEEN 1 AND 64 THEN
     failures:=array_append(failures,'NECESSARY_DEMAND_SCENARIOS_INVALID');
    END IF;
    FOR item IN SELECT value FROM jsonb_array_elements(scenarios) LOOP
     IF jsonb_typeof(item) IS DISTINCT FROM 'object' OR jsonb_typeof(item->'code') IS DISTINCT FROM 'string'
       OR length(btrim(item->>'code')) NOT BETWEEN 1 AND 64 OR (item->>'code')=ANY(codes)
       OR item->'conservative' IS DISTINCT FROM 'true'::jsonb
       OR jsonb_typeof(item->'evidenceReference') IS DISTINCT FROM 'string'
       OR length(btrim(item->>'evidenceReference')) NOT BETWEEN 1 AND 512
       OR jsonb_typeof(item->'quantity') IS DISTINCT FROM 'number' THEN
      failures:=array_append(failures,'NECESSARY_DEMAND_SCENARIO_INVALID'); CONTINUE;
     END IF;
     codes:=array_append(codes,item->>'code');
     IF (item->>'quantity')::numeric NOT BETWEEN 0 AND 99999999999999
       OR trunc((item->>'quantity')::numeric)<>(item->>'quantity')::numeric THEN
      failures:=array_append(failures,'NECESSARY_DEMAND_QUANTITY_INVALID');
     END IF;
    END LOOP;
   END LOOP;
  END IF;
 END IF;
 SELECT value_json->'supplyScenarios' INTO scenarios FROM core.lc_calibration_value
  WHERE package_id=p_id AND category_code='DEMAND_SCENARIO_SET';
 IF scenarios IS NULL THEN RETURN failures; END IF;
 IF jsonb_typeof(scenarios)<>'array' THEN RETURN array_append(failures,'SUPPLY_SCENARIOS_INVALID'); END IF;
 IF jsonb_array_length(scenarios)=0 THEN RETURN array_append(failures,'SUPPLY_SCENARIOS_EMPTY'); END IF;
 FOR item IN SELECT value FROM jsonb_array_elements(scenarios) LOOP
  IF jsonb_typeof(item)<>'object' OR jsonb_typeof(item->'code') IS DISTINCT FROM 'string'
     OR length(btrim(item->>'code')) NOT BETWEEN 1 AND 512
     OR jsonb_typeof(item->'productVariantId') IS DISTINCT FROM 'string'
     OR NOT EXISTS(SELECT 1 FROM core.product_variant v WHERE v.organization_id=organization AND v.id::text=item->>'productVariantId')
     OR jsonb_typeof(item->'evidenceReference') IS DISTINCT FROM 'string'
     OR length(btrim(item->>'evidenceReference')) NOT BETWEEN 1 AND 512
     OR jsonb_typeof(item->'companyDailyFulfillmentUnits') IS DISTINCT FROM 'number'
     OR jsonb_typeof(item->'coverageDays') IS DISTINCT FROM 'number' THEN
   failures:=array_append(failures,'SUPPLY_SCENARIO_INVALID');CONTINUE;
  END IF;
  IF (item->>'companyDailyFulfillmentUnits')::numeric<0
     OR (item->>'coverageDays')::numeric<=0 OR (item->>'coverageDays')::numeric>2147483647
     OR trunc((item->>'coverageDays')::numeric)<>(item->>'coverageDays')::numeric THEN
   failures:=array_append(failures,'SUPPLY_SCENARIO_UNITS_OR_HORIZON_INVALID');
  END IF;
  identity:=jsonb_build_array(item->>'productVariantId',item->>'code')::text;
  IF identity=ANY(identities) THEN failures:=array_append(failures,'SUPPLY_SCENARIO_DUPLICATE'); END IF;
  identities:=array_append(identities,identity);
 END LOOP;
 RETURN ARRAY(SELECT DISTINCT reason FROM unnest(failures) reason ORDER BY reason);
END $$;
REVOKE ALL ON FUNCTION ops.lc_calibration_combination_failures(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_calibration_combination_failures(uuid) TO marketops_app;

-- The existing recommendation carries the chosen simulation identity into review/approval.
-- A matching conditional calculation is a bound input, not commercial qualification.
CREATE FUNCTION ops.lc_action_binds_selected_simulation() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE parameters jsonb;
BEGIN
 SELECT proposed_parameters INTO parameters FROM ops.recommendation
  WHERE id=NEW.recommendation_id AND organization_id=NEW.organization_id;
 IF parameters ? 'simulationId' OR parameters ? 'simulationInputsDigest' THEN
  IF NEW.action_kind<>'LISTING_PROMOTION_ACTION' OR NOT EXISTS(
   SELECT 1 FROM ops.lc_simulation s WHERE s.id::text=parameters->>'simulationId'
    AND s.inputs_digest=parameters->>'simulationInputsDigest'
    AND s.organization_id=NEW.organization_id AND s.candidate_id=NEW.candidate_id AND s.computed_at<=NEW.created_at
    AND s.input_snapshot->>'nativeIdentityDigest'=NEW.affected_set_digest
    AND s.input_snapshot->>'promotionTermsDigest'=ops.lc_promotion_terms_digest(NEW.promotion_terms)
    AND s.input_snapshot->>'promotionTermsDigest'=ops.lc_promotion_terms_digest(s.input_snapshot->'context'->'commercialDeclaration')
    AND s.inputs_digest=encode(sha256(convert_to(s.input_snapshot::text,'UTF8')),'hex')) THEN
   RAISE EXCEPTION 'selected simulation must bind exact candidate, scope, terms and immutable inputs' USING ERRCODE='MO092';
  END IF;
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_action_binds_selected_simulation() FROM PUBLIC;
CREATE TRIGGER lc_action_selected_simulation BEFORE INSERT OR UPDATE ON ops.lc_action
 FOR EACH ROW EXECUTE FUNCTION ops.lc_action_binds_selected_simulation();

ALTER FUNCTION ops.lc_meaning_review_basis_digest(uuid) RENAME TO lc_meaning_review_basis_digest_v0112;
CREATE FUNCTION ops.lc_meaning_review_basis_digest(p_action uuid) RETURNS text
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT CASE WHEN NOT (r.proposed_parameters ? 'simulationId') THEN ops.lc_meaning_review_basis_digest_v0112(p_action)
  ELSE encode(sha256(convert_to(jsonb_build_array('LC_MEANING_SIMULATION_BASIS_1',
    ops.lc_meaning_review_basis_digest_v0112(p_action),r.proposed_parameters->>'simulationId',
    r.proposed_parameters->>'simulationInputsDigest')::text,'UTF8')),'hex') END
 FROM ops.lc_action a JOIN ops.recommendation r ON r.id=a.recommendation_id AND r.organization_id=a.organization_id
 WHERE a.id=p_action
$$;
REVOKE ALL ON FUNCTION ops.lc_meaning_review_basis_digest(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_meaning_review_basis_digest(uuid) TO marketops_app;

-- Review material selection is part of the prepared proposal, not a mutable pointer.
CREATE FUNCTION ops.lc_freeze_selected_simulation_reference() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF (OLD.action_kind IN ('LISTING_DESCRIPTION_CHANGE','LISTING_PROMOTION_ACTION')
      OR NEW.action_kind IN ('LISTING_DESCRIPTION_CHANGE','LISTING_PROMOTION_ACTION'))
   AND (NEW.proposed_parameters->'simulationId' IS DISTINCT FROM OLD.proposed_parameters->'simulationId'
     OR NEW.proposed_parameters->'simulationInputsDigest' IS DISTINCT FROM OLD.proposed_parameters->'simulationInputsDigest') THEN
  RAISE EXCEPTION 'prepared simulation reference is immutable; prepare a new proposal' USING ERRCODE='MO092';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_freeze_selected_simulation_reference() FROM PUBLIC;
CREATE TRIGGER lc_recommendation_selected_simulation BEFORE UPDATE ON ops.recommendation
 FOR EACH ROW EXECUTE FUNCTION ops.lc_freeze_selected_simulation_reference();
