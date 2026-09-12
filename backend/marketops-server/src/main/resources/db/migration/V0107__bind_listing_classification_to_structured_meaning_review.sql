-- Typed, finite meaning conditions replace numeric character-ratio configuration for new drafts.
-- Existing numeric values and their acceptance digests are retained; unrelated consumers still resolve them.
UPDATE core.lc_calibration_category SET value_shape='JSON'
 WHERE code IN ('ORDINARY_TRIGGER_CONTENT','MATERIAL_TRIGGER_CONTENT');

CREATE FUNCTION core.lc_meaning_rule_document_valid(doc jsonb) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE kind text; rules jsonb; rule jsonb; codes text[];
BEGIN
 IF jsonb_typeof(doc) IS DISTINCT FROM 'object' THEN RETURN false; END IF;
 IF doc->>'model' IS DISTINCT FROM 'LC_MEANING_CONDITIONS_1'
    OR EXISTS(SELECT 1 FROM jsonb_object_keys(doc) k WHERE k NOT IN ('model','description','promotion'))
    OR NOT (doc ? 'description' OR doc ? 'promotion') THEN RETURN false; END IF;
 FOREACH kind IN ARRAY ARRAY['description','promotion'] LOOP
  IF NOT doc ? kind THEN CONTINUE; END IF;
  rules:=doc->kind; codes:='{}';
  IF jsonb_typeof(rules) IS DISTINCT FROM 'array' THEN RETURN false; END IF;
  IF jsonb_array_length(rules)>16 THEN RETURN false; END IF;
  FOR rule IN SELECT value FROM jsonb_array_elements(rules) LOOP
   IF jsonb_typeof(rule) IS DISTINCT FROM 'object' THEN RETURN false; END IF;
   IF EXISTS(SELECT 1 FROM jsonb_object_keys(rule) k WHERE k NOT IN ('code','condition'))
      OR coalesce(rule->>'code','') !~ '^[A-Z][A-Z0-9_]{1,63}$'
      OR jsonb_typeof(rule->'condition') IS DISTINCT FROM 'string'
      OR length(btrim(rule->>'condition')) NOT BETWEEN 1 AND 2000
      OR (rule->>'code')=ANY(codes) THEN RETURN false; END IF;
   codes:=array_append(codes,rule->>'code');
  END LOOP;
 END LOOP;
 RETURN true;
END $$;
REVOKE ALL ON FUNCTION core.lc_meaning_rule_document_valid(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lc_meaning_rule_document_valid(jsonb) TO marketops_app;

CREATE FUNCTION core.lc_meaning_catalog(p_package uuid,p_kind text) RETURNS jsonb
LANGUAGE plpgsql STABLE SET search_path=pg_catalog AS $$
DECLARE ordinary jsonb; material jsonb; key text; result jsonb; ordinary_unit text; material_unit text;
BEGIN
 key:=CASE p_kind WHEN 'LISTING_DESCRIPTION_CHANGE' THEN 'description' WHEN 'LISTING_PROMOTION_ACTION' THEN 'promotion' END;
 IF key IS NULL THEN RETURN NULL; END IF;
 SELECT value_json,unit_code INTO ordinary,ordinary_unit FROM core.lc_calibration_value
  WHERE package_id=p_package AND category_code='ORDINARY_TRIGGER_CONTENT';
 SELECT value_json,unit_code INTO material,material_unit FROM core.lc_calibration_value
  WHERE package_id=p_package AND category_code='MATERIAL_TRIGGER_CONTENT';
 IF NOT core.lc_meaning_rule_document_valid(ordinary) OR NOT core.lc_meaning_rule_document_valid(material)
    OR ordinary_unit IS DISTINCT FROM 'CONDITIONS' OR material_unit IS DISTINCT FROM 'CONDITIONS' THEN RETURN NULL; END IF;
 SELECT coalesce(jsonb_agg(rule ORDER BY rule->>'code'),'[]'::jsonb) INTO result FROM (
  SELECT value||jsonb_build_object('axis','ORDINARY') AS rule FROM jsonb_array_elements(coalesce(ordinary->key,'[]'::jsonb))
  UNION ALL
  SELECT value||jsonb_build_object('axis','MATERIAL') FROM jsonb_array_elements(coalesce(material->key,'[]'::jsonb))) selected;
 IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(result) r WHERE r->>'axis'='ORDINARY')
    OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(result) r WHERE r->>'axis'='MATERIAL') OR EXISTS(SELECT 1 FROM jsonb_array_elements(result) r
   GROUP BY r->>'code' HAVING count(*)>1) THEN RETURN NULL; END IF;
 RETURN result;
END $$;
REVOKE ALL ON FUNCTION core.lc_meaning_catalog(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.lc_meaning_catalog(uuid,text) TO marketops_app;

CREATE FUNCTION ops.lc_meaning_review_basis_digest(p_action uuid) RETURNS text
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT encode(sha256(convert_to(jsonb_build_object('model','LC_MEANING_BASIS_1','actionId',a.id,
  'currentTextDigest',a.current_text_digest,'targetTextDigest',a.target_text_digest,
  'promotionTermsDigest',a.promotion_terms_digest,'affectedSetDigest',a.affected_set_digest,
  'calibrationDigest',ops.lc_calibration_digest(a.calibration_package_id),
  'conditions',core.lc_meaning_catalog(a.calibration_package_id,a.action_kind))::text,'UTF8')),'hex')
 FROM ops.lc_action a WHERE a.id=p_action
$$;
REVOKE ALL ON FUNCTION ops.lc_meaning_review_basis_digest(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_meaning_review_basis_digest(uuid) TO marketops_app;

CREATE FUNCTION ops.lc_review_meaning_axis(p_action uuid,assessment jsonb) RETURNS boolean
LANGUAGE plpgsql STABLE SET search_path=pg_catalog AS $$
DECLARE catalog jsonb; answer jsonb; ordinary boolean:=false; material boolean:=false; unknown boolean:=false;
 code text; codes text[]:='{}'; category text;
BEGIN
 SELECT core.lc_meaning_catalog(a.calibration_package_id,a.action_kind) INTO catalog FROM ops.lc_action a WHERE a.id=p_action;
 IF catalog IS NULL OR jsonb_typeof(assessment) IS DISTINCT FROM 'object' THEN RETURN NULL; END IF;
 IF assessment->>'model' IS DISTINCT FROM 'LC_MEANING_REVIEW_1'
    OR assessment->'complete' IS DISTINCT FROM 'true'::jsonb
    OR assessment->>'basisDigest' IS DISTINCT FROM ops.lc_meaning_review_basis_digest(p_action)
    OR jsonb_typeof(assessment->'evidenceReference') IS DISTINCT FROM 'string'
    OR length(btrim(assessment->>'evidenceReference')) NOT BETWEEN 1 AND 512
    OR jsonb_typeof(assessment->'answers') IS DISTINCT FROM 'array'
    OR EXISTS(SELECT 1 FROM jsonb_object_keys(assessment) k WHERE k NOT IN
        ('model','complete','basisDigest','evidenceReference','answers')) THEN RETURN NULL; END IF;
 IF jsonb_array_length(assessment->'answers')<>jsonb_array_length(catalog) THEN RETURN NULL; END IF;
 FOR answer IN SELECT value FROM jsonb_array_elements(assessment->'answers') LOOP
  IF jsonb_typeof(answer) IS DISTINCT FROM 'object' THEN RETURN NULL; END IF;
  IF EXISTS(SELECT 1 FROM jsonb_object_keys(answer) k WHERE k NOT IN ('code','state','reason'))
     OR coalesce(answer->>'state','') NOT IN ('APPLIES','DOES_NOT_APPLY','UNKNOWN')
     OR jsonb_typeof(answer->'reason') IS DISTINCT FROM 'string'
     OR length(btrim(answer->>'reason')) NOT BETWEEN 1 AND 2000 THEN RETURN NULL; END IF;
  code:=answer->>'code';
  IF code IS NULL OR code=ANY(codes) THEN RETURN NULL; END IF;
  SELECT r->>'axis' INTO category FROM jsonb_array_elements(catalog) r WHERE r->>'code'=code;
  IF NOT FOUND THEN RETURN NULL; END IF;
  codes:=array_append(codes,code);
  IF answer->>'state'='UNKNOWN' THEN unknown:=true; END IF;
  IF answer->>'state'='APPLIES' THEN
   IF category='MATERIAL' THEN material:=true; ELSE ordinary:=true; END IF;
  END IF;
 END LOOP;
 IF unknown THEN RETURN NULL; END IF;
 IF material THEN RETURN true; END IF;
 IF ordinary THEN RETURN false; END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION ops.lc_review_meaning_axis(uuid,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_review_meaning_axis(uuid,jsonb) TO marketops_app;

ALTER TABLE ops.lc_action_review ADD COLUMN meaning_assessment jsonb,
 ADD COLUMN exposure_evidence jsonb, ADD COLUMN content_axis_material boolean,
 ADD COLUMN exposure_axis_material boolean, ADD COLUMN materiality_route text;

-- Known materiality on either axis is material; ordinary needs both axes known and ordinary.
-- Missing axes cannot be treated as false. Professional review separately requires complete evidence.
ALTER TABLE ops.lc_action DROP CONSTRAINT lc_action_materiality_axes_ck;
ALTER TABLE ops.lc_action ADD CONSTRAINT lc_action_materiality_axes_ck CHECK (
 (calibration_package_id IS NULL)=(calibration_version IS NULL)
 AND ((materiality_route='MATERIAL_IMPACT' AND calibration_package_id IS NOT NULL
       AND (content_axis_material IS TRUE OR exposure_axis_material IS TRUE))
   OR (materiality_route='ORDINARY_IMPACT' AND calibration_package_id IS NOT NULL
       AND content_axis_material IS FALSE AND exposure_axis_material IS FALSE)
   OR (materiality_route='MATERIALITY_UNRESOLVED' AND content_axis_material IS NOT TRUE
       AND exposure_axis_material IS NOT TRUE AND (content_axis_material IS NULL OR exposure_axis_material IS NULL))));

CREATE FUNCTION ops.lc_review_classification_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE a ops.lc_action%ROWTYPE; meaning boolean;
BEGIN
 IF NEW.verdict<>'ATTESTED' THEN RETURN NEW; END IF;
 SELECT * INTO a FROM ops.lc_action WHERE id=NEW.action_id;
 meaning:=ops.lc_review_meaning_axis(a.id,NEW.meaning_assessment);
 IF meaning IS NULL OR NEW.content_axis_material IS DISTINCT FROM meaning OR NEW.exposure_axis_material IS NULL
    OR NEW.content_axis_material IS DISTINCT FROM a.content_axis_material
    OR NEW.exposure_axis_material IS DISTINCT FROM a.exposure_axis_material
    OR NEW.materiality_route IS DISTINCT FROM a.materiality_route
    OR NEW.materiality_route IS DISTINCT FROM (CASE WHEN meaning OR NEW.exposure_axis_material
        THEN 'MATERIAL_IMPACT' ELSE 'ORDINARY_IMPACT' END)
    OR NEW.exposure_evidence->>'state' IS DISTINCT FROM 'QUALIFIED' THEN
  RAISE EXCEPTION 'review requires exact structured meaning and qualified exposure' USING ERRCODE='MO107';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER lc_review_classification_guard BEFORE INSERT ON ops.lc_action_review
 FOR EACH ROW EXECUTE FUNCTION ops.lc_review_classification_guard();

CREATE FUNCTION ops.lc_reviewed_classification_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF OLD.state<>'DRAFT' AND (NEW.content_axis_material IS DISTINCT FROM OLD.content_axis_material
    OR NEW.exposure_axis_material IS DISTINCT FROM OLD.exposure_axis_material
    OR NEW.materiality_route IS DISTINCT FROM OLD.materiality_route) THEN
  RAISE EXCEPTION 'reviewed classification is immutable' USING ERRCODE='MO107';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER lc_reviewed_classification_immutable BEFORE UPDATE ON ops.lc_action
 FOR EACH ROW EXECUTE FUNCTION ops.lc_reviewed_classification_immutable();

CREATE OR REPLACE FUNCTION ops.lc_calibration_combination_failures(p_id uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SET search_path=pg_catalog AS $$
DECLARE failures text[]:=core.lc_calibration_package_failures(p_id); v record;
 ordinary numeric; material numeric; node jsonb; codes text[]:='{}';
BEGIN
 FOR v IN SELECT * FROM core.lc_calibration_value WHERE package_id=p_id LOOP
  IF v.category_code IN ('MATERIAL_IMPROVEMENT_BOUND','NON_WORSENING_RETURN_BOUND',
       'ORDINARY_TRIGGER_EXPOSURE','MATERIAL_TRIGGER_EXPOSURE')
     AND (v.unit_code<>'RATIO' OR v.value_numeric<0 OR v.value_numeric>1) THEN
   failures:=array_append(failures,v.category_code||'_RATIO_INVALID');
  END IF;
  IF v.category_code='NON_WORSENING_PROFIT_BOUND' AND v.value_numeric<0 THEN
   failures:=array_append(failures,'NON_WORSENING_PROFIT_BOUND_INVALID');
  END IF;
  IF v.category_code='APPROVAL_VALIDITY' AND (v.value_numeric<=0 OR v.value_numeric<>trunc(v.value_numeric)
       OR v.unit_code NOT IN ('MINUTES','HOURS','DAYS')) THEN
   failures:=array_append(failures,'APPROVAL_VALIDITY_INVALID');
  END IF;
  IF v.category_code='CROSS_PERIOD_WINDOW' AND (v.value_numeric<0 OR v.value_numeric>3660
       OR v.value_numeric<>trunc(v.value_numeric) OR v.unit_code<>'DAYS') THEN
   failures:=array_append(failures,'CROSS_PERIOD_WINDOW_INVALID');
  END IF;
  IF v.category_code='STOP_RULE' AND jsonb_typeof(v.value_json)<>'object' THEN
   failures:=array_append(failures,'STOP_RULE_INVALID');
  END IF;
  IF v.category_code='FORMAL_NODES' THEN
   IF jsonb_typeof(v.value_json)<>'array' THEN
    failures:=array_append(failures,'FORMAL_NODES_INVALID');
   ELSE
    IF jsonb_array_length(v.value_json) NOT BETWEEN 1 AND 8 THEN failures:=array_append(failures,'FORMAL_NODE_COUNT_INVALID'); END IF;
    FOR node IN SELECT value FROM jsonb_array_elements(v.value_json) LOOP
     IF coalesce(node->>'nodeCode','') !~ '^[A-Z][A-Z0-9_]{1,62}$' OR (node->>'nodeCode')=ANY(codes)
       OR coalesce(node->>'maturityDays','') NOT IN ('7','14','30')
       OR length(btrim(coalesce(node->>'method','')))=0
       OR coalesce(node->>'threshold','') !~ '^[0-9]+([.][0-9]+)?$' THEN
      failures:=array_append(failures,'FORMAL_NODE_INVALID');
     END IF;
     codes:=array_append(codes,node->>'nodeCode');
    END LOOP;
   END IF;
  END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM core.lc_calibration_value WHERE package_id=p_id
    AND category_code IN ('ORDINARY_TRIGGER_CONTENT','MATERIAL_TRIGGER_CONTENT')
    AND (unit_code<>'CONDITIONS' OR NOT core.lc_meaning_rule_document_valid(value_json))) THEN
  failures:=array_append(failures,'MEANING_RULE_DOCUMENT_INVALID');
 END IF;
 IF core.lc_meaning_catalog(p_id,CASE (SELECT purpose_code FROM core.lc_calibration_package WHERE id=p_id)
      WHEN 'PROMOTION' THEN 'LISTING_PROMOTION_ACTION' ELSE 'LISTING_DESCRIPTION_CHANGE' END) IS NULL THEN
  failures:=array_append(failures,'MEANING_RULE_COMBINATION_UNRESOLVED');
 END IF;
 SELECT value_numeric INTO ordinary FROM core.lc_calibration_value WHERE package_id=p_id AND category_code='ORDINARY_TRIGGER_EXPOSURE';
 SELECT value_numeric INTO material FROM core.lc_calibration_value WHERE package_id=p_id AND category_code='MATERIAL_TRIGGER_EXPOSURE';
 IF ordinary>material THEN failures:=array_append(failures,'EXPOSURE_TRIGGER_ORDER_INVALID'); END IF;
 RETURN failures;
END $$;

REVOKE ALL ON FUNCTION ops.lc_review_classification_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ops.lc_reviewed_classification_immutable() FROM PUBLIC;

CREATE FUNCTION ops.lc_action_has_meaning_review(p_action uuid,p_at timestamptz) RETURNS boolean
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
 SELECT EXISTS(SELECT 1 FROM ops.lc_action a JOIN ops.lc_action_review r ON r.action_id=a.id
  WHERE a.id=p_action AND r.verdict='ATTESTED' AND r.reviewed_at<=p_at
    AND r.content_axis_material=ops.lc_review_meaning_axis(a.id,r.meaning_assessment)
    AND r.content_axis_material=a.content_axis_material AND r.exposure_axis_material=a.exposure_axis_material
    AND r.materiality_route=a.materiality_route AND r.exposure_evidence->>'state'='QUALIFIED'
    AND r.attested_current_text_digest IS NOT DISTINCT FROM a.current_text_digest
    AND r.attested_target_text_digest IS NOT DISTINCT FROM a.target_text_digest
    AND r.attested_affected_set_digest=a.affected_set_digest
    AND r.reviewer_user_id<>a.author_user_id)
$$;
REVOKE ALL ON FUNCTION ops.lc_action_has_meaning_review(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.lc_action_has_meaning_review(uuid,timestamptz) TO marketops_app;

CREATE FUNCTION ops.lc_binding_requires_meaning_review() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NOT ops.lc_action_has_meaning_review(NEW.action_id,NEW.bound_at) THEN
  RAISE EXCEPTION 'binding requires exact qualified meaning review' USING ERRCODE='MO107';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ops.lc_binding_requires_meaning_review() FROM PUBLIC;
CREATE TRIGGER lc_binding_requires_meaning_review BEFORE INSERT ON ops.lc_action_binding
 FOR EACH ROW EXECUTE FUNCTION ops.lc_binding_requires_meaning_review();

CREATE OR REPLACE FUNCTION ops.lc_binding_gaps(p_action uuid)
RETURNS text[]
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, ops, core, pg_temp
AS $$
DECLARE
    action  ops.lc_action%ROWTYPE;
    binding ops.lc_action_binding%ROWTYPE;
    reasons text[] := '{}';
    latest_digest text;
BEGIN
    SELECT * INTO action FROM ops.lc_action WHERE id = p_action;
    IF NOT FOUND THEN RETURN ARRAY['ACTION_NOT_FOUND']; END IF;
    SELECT * INTO binding FROM ops.lc_action_binding WHERE action_id = p_action;
    IF NOT FOUND THEN RETURN ARRAY['BINDING_MISSING']; END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.lc_evaluation_plan p
        WHERE p.action_id=action.id AND p.organization_id=action.organization_id
          AND p.plan_digest=binding.evaluation_plan_digest AND p.frozen_at<=binding.bound_at) THEN
        reasons:=array_append(reasons,'EVALUATION_PLAN_BINDING_MISSING_OR_CHANGED');
    END IF;
    IF NOT ops.lc_action_has_meaning_review(action.id,binding.bound_at) THEN
        reasons:=array_append(reasons,'MEANING_REVIEW_UNQUALIFIED');
    END IF;
    IF binding.state <> 'BOUND' THEN
        reasons := array_append(reasons, 'BINDING_INAPPLICABLE');
    END IF;
    IF binding.expires_at <= statement_timestamp() THEN
        reasons := array_append(reasons, 'BINDING_EXPIRED');
    END IF;
    IF core.lc_listing_affected_set_digest(action.platform_listing_id) <> binding.affected_set_digest THEN
        reasons := array_append(reasons, 'AFFECTED_SET_DIGEST_CHANGED');
    END IF;
    IF action.action_kind = 'LISTING_DESCRIPTION_CHANGE' THEN
        SELECT o.text_digest INTO latest_digest
          FROM core.lc_description_observation o
         WHERE o.platform_listing_id = action.platform_listing_id
         ORDER BY o.observed_at DESC, o.acquired_at DESC LIMIT 1;
        IF latest_digest IS DISTINCT FROM binding.current_text_digest THEN
            reasons := array_append(reasons, 'CURRENT_TEXT_MOVED');
        END IF;
    END IF;
    IF (ops.lc_action_calibration_recheck(action.id,statement_timestamp())->>'state')
         NOT IN ('CURRENT','UNCHANGED_DEPENDENCIES') THEN
        reasons := array_append(reasons, 'CALIBRATION_NOT_CURRENT');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.approval_decision a
                    WHERE a.id = binding.approval_decision_id
                      AND a.decision = 'APPROVED'
                      AND a.scope_expires_at > statement_timestamp()) THEN
        reasons := array_append(reasons, 'AUTHORIZATION_INVALID_OR_EXPIRED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.recommendation r
                    WHERE r.id = action.recommendation_id
                      AND r.state IN ('APPROVED', 'COMMAND_CREATED', 'EXECUTION_TRACKING')
                      AND r.valid_until > statement_timestamp()) THEN
        reasons := array_append(reasons, 'RECOMMENDATION_STALE');
    END IF;
    RETURN reasons;
END;
$$;
