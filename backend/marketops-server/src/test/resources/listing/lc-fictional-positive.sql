-- Listing conversion fixture on top of the fictional advertising graph.
-- Every identifier here is a template value the fixture class replaces, and
-- SYNTHETIC_AD is the fictional platform code. No production platform,
-- credential or listing is named anywhere in this file.

-- The fictional identity provider is active for listing work.
UPDATE iam.identity_provider SET status = 'ACTIVE' WHERE id = 'bdd07a92-b359-552a-81c9-46e654657965';

-- Listing scopes for the three fixture people, at organization scope.
INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,status,effective_from,reason,created_at,updated_at)
SELECT gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','9264ceb0-c29a-5837-9339-c84bfe73a444',code,'8689c119-8fa0-50b7-8ba2-f9bf3039d336','ACTIVE',now()-interval '1 day','synthetic owner listing scope',now(),now()
  FROM unnest(ARRAY['LISTING_CONVERSION_VIEW','LISTING_ACTION_PREPARE','LISTING_ACTION_REVIEW','LISTING_ACTION_APPROVE_ORDINARY',
                    'LISTING_ACTION_APPROVE_MATERIAL','LISTING_ACTION_LAUNCH','LISTING_MANUAL_EXECUTE','LISTING_MANUAL_VERIFY',
                    'LISTING_CONTAINMENT_STOP','LISTING_CONTAINMENT_ATTEST','LISTING_CONTAINMENT_CONSENT','LISTING_PROMOTION_MANAGE',
                    'LISTING_DECISION_EVIDENCE_VIEW']) AS code;
INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,status,effective_from,reason,created_at,updated_at)
SELECT gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','8ec704dd-3aa5-529c-93db-def4bbf39260',code,'8689c119-8fa0-50b7-8ba2-f9bf3039d336','ACTIVE',now()-interval '1 day','synthetic lead listing scope',now(),now()
  FROM unnest(ARRAY['LISTING_CONVERSION_VIEW','LISTING_ACTION_PREPARE','LISTING_ACTION_REVIEW','LISTING_ACTION_APPROVE_ORDINARY',
                    'LISTING_ACTION_LAUNCH','LISTING_MANUAL_EXECUTE','LISTING_MANUAL_VERIFY','LISTING_CONTAINMENT_STOP',
                    'LISTING_CONTAINMENT_CONSENT','LISTING_PROMOTION_MANAGE','LISTING_DECISION_EVIDENCE_VIEW']) AS code;
INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,status,effective_from,reason,created_at,updated_at)
SELECT gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','0998716b-6f78-56da-bbea-554b20cfd093',code,'8689c119-8fa0-50b7-8ba2-f9bf3039d336','ACTIVE',now()-interval '1 day','synthetic operator listing scope',now(),now()
  FROM unnest(ARRAY['LISTING_CONVERSION_VIEW','LISTING_ACTION_PREPARE','LISTING_MANUAL_EXECUTE']) AS code;

-- Owner-published calibration: drafted, completed, activated.
INSERT INTO core.lc_calibration_package(id,organization_id,package_code,package_version,scope_kind,status,published_by_user_id,published_at,evidence_reference,effective_from)
VALUES ('5c000000-0000-5000-8000-000000000001','8689c119-8fa0-50b7-8ba2-f9bf3039d336','fictional-listing-calibration',1,'ORGANIZATION','DRAFT','9264ceb0-c29a-5837-9339-c84bfe73a444',now(),'fixture://listing-calibration',now()-interval '1 day');
INSERT INTO core.lc_calibration_value(id,package_id,category_code,value_numeric,value_text,value_json,unit_code,scope_note,window_days,evidence_reference) VALUES
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','MATERIAL_IMPROVEMENT_BOUND',0.050000,NULL,NULL,'RATIO','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','NON_WORSENING_PROFIT_BOUND',0.000000,NULL,NULL,'RATIO','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','NON_WORSENING_RETURN_BOUND',0.020000,NULL,NULL,'RATIO','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','CRITICAL_GROUP_RULE',NULL,NULL,'{"groups": []}','RULE','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','DEMAND_SCENARIO_SET',NULL,NULL,'{"scenarios": [{"code": "BASE", "quantity": "10", "necessary": true, "conservative": false}]}','RULE','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','FRESHNESS_RULE',NULL,NULL,'{"maxAgeHours": 72}','RULE','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','RESPONSIBILITY_SLO',NULL,NULL,'{"acknowledgementMinutes": 120, "actionMinutes": 180, "outcomeMaturityDays": 30, "maximumDeferMinutes": 1440, "necessaryRisk": {"acknowledgementMinutes": 15, "actionMinutes": 60, "outcomeMaturityDays": 30, "maximumDeferMinutes": 1440}}','RULE','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','RESPONSIBILITY_COVERAGE',NULL,NULL,'{"roles": ["OPS_LEAD"], "timezone":"Europe/Moscow", "days":[1,2,3,4,5], "startMinute":540, "endMinute":1080}','RULE','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','ORDINARY_TRIGGER_CONTENT',0.100000,NULL,NULL,'RATIO','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','MATERIAL_TRIGGER_CONTENT',0.400000,NULL,NULL,'RATIO','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','ORDINARY_TRIGGER_EXPOSURE',0.050000,NULL,NULL,'RATIO','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','MATERIAL_TRIGGER_EXPOSURE',0.200000,NULL,NULL,'RATIO','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','APPROVAL_VALIDITY',48.000000,NULL,NULL,'HOURS','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','REPRESENTATION_EQUIVALENCE_RULE',NULL,'EXACT',NULL,'RULE','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','ALLOWANCE_AXES',NULL,NULL,'["CONCURRENT_LISTINGS", "AFFECTED_VARIANTS"]','RULE','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','ALLOWANCE_RESERVE',NULL,NULL,'{"CONCURRENT_LISTINGS": "0", "AFFECTED_VARIANTS": "0"}','RULE','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','FORMAL_NODES',NULL,NULL,'[{"nodeCode":"D14","maturityDays":14,"method":"EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1","threshold":"0.050000","methodParameters":{"familyAlpha":"0.05","nodeAlpha":"0.05","samplingModel":"INDEPENDENT_BERNOULLI_VISITS","qualificationRef":"fixture://exact-fixed-traffic"},"schedule":{"windowStartOffsetDays":1,"windowEndOffsetDays":15,"notBeforeOffsetDays":29,"lastOffsetDays":43}}]','RULE','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','STOP_RULE',NULL,NULL,'{}','RULE','fixture',NULL,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','CROSS_PERIOD_WINDOW',30.000000,NULL,NULL,'DAYS','fixture',30,'fixture://calibration'),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000001','DESCRIPTION_LENGTH_RULE',NULL,NULL,'{"min": 10, "max": 6000}','RULE','fixture',NULL,'fixture://calibration');
UPDATE core.lc_calibration_package SET status = 'ACTIVE', activated_at = now() WHERE id = '5c000000-0000-5000-8000-000000000001';

-- Owner-published exposure allowance: one listing at a time, ten variants.
INSERT INTO ops.lc_exposure_allowance(id,organization_id,allowance_version,scope_kind,axis_code,limit_value,reserve_value,unit_code,published_by_user_id,published_at,evidence_reference,effective_from,status) VALUES
 ('5c000000-0000-5000-8000-000000000002','8689c119-8fa0-50b7-8ba2-f9bf3039d336',1,'ORGANIZATION','CONCURRENT_LISTINGS',1,0,'COUNT','9264ceb0-c29a-5837-9339-c84bfe73a444',now(),'fixture://allowance',now()-interval '1 day','ACTIVE'),
 ('5c000000-0000-5000-8000-000000000003','8689c119-8fa0-50b7-8ba2-f9bf3039d336',1,'ORGANIZATION','AFFECTED_VARIANTS',10,0,'COUNT','9264ceb0-c29a-5837-9339-c84bfe73a444',now(),'fixture://allowance',now()-interval '1 day','ACTIVE');

-- The description write capability, described and verified for the fictional platform.
INSERT INTO platform.platform_capability (id, platform_code, capability_code, display_name, applies_to, read_write_class, subscription_required,
        verification_state, last_verified_at, evidence_ref, verified_source_title, owner_label, contract_test_status, status, write_result_model, created_at, updated_at)
VALUES ('5c000000-0000-5000-8000-000000000010', 'SYNTHETIC_AD', 'listing-description-change', 'Listing description change', 'STORE', 'WRITE', 'UNKNOWN',
        'VERIFIED', now(), 'fixture://protocol', 'synthetic protocol', 'fixture', 'PASSING', 'ACTIVE', 'SYNCHRONOUS', now(), now());
INSERT INTO platform.capability_subject_status(id,organization_id,platform_code,capability_id,store_id,availability,last_verified_at,evidence_ref,verified_source_title,created_at,updated_at)
VALUES (gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','SYNTHETIC_AD','5c000000-0000-5000-8000-000000000010','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','AVAILABLE',now(),'fixture://protocol','synthetic protocol',now(),now());
INSERT INTO platform.platform_endpoint(id,platform_code,endpoint_code,api_version,http_method,path_template,operation_function,capability_id,read_write_class,pagination_model,idempotency_support,verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,contract_test_status,status,created_at,updated_at) VALUES
 ('5c000000-0000-5000-8000-000000000011','SYNTHETIC_AD','synthetic.description.apply','v1','POST','/fixture/descriptions','DESCRIPTION_APPLY','5c000000-0000-5000-8000-000000000010','WRITE','NONE','YES','VERIFIED',now(),'fixture://protocol','synthetic protocol','synthetic','PASSING','ACTIVE',now(),now()),
 ('5c000000-0000-5000-8000-000000000012','SYNTHETIC_AD','synthetic.description.readback','v1','GET','/fixture/descriptions/{nativeListingKey}','DESCRIPTION_READBACK','5c000000-0000-5000-8000-000000000010','READ','NONE','YES','VERIFIED',now(),'fixture://protocol','synthetic protocol','synthetic','PASSING','ACTIVE',now(),now()),
 ('5c000000-0000-5000-8000-000000000013','SYNTHETIC_AD','synthetic.description.restore','v1','POST','/fixture/descriptions','DESCRIPTION_RESTORE','5c000000-0000-5000-8000-000000000010','WRITE','NONE','YES','VERIFIED',now(),'fixture://protocol','synthetic protocol','synthetic','PASSING','ACTIVE',now(),now());
INSERT INTO platform.capability_operation(id,capability_id,platform_code,operation,endpoint_id,request_template,accepted_pointer,accepted_value,
        description_observed_text_pointer,description_kiz_marked_pointer,description_attribute_key,
        verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at) VALUES
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000010','SYNTHETIC_AD','APPLY','5c000000-0000-5000-8000-000000000011',
  '{"offer_id":"{nativeListingKey}","attributes":[{"id":"{descriptionAttributeKey}","values":[{"value":"{descriptionText}"}]}],"kizMarked":false}',
  '/accepted','true','/description','/kizMarked','4191','VERIFIED',now(),'fixture://protocol','synthetic protocol','synthetic','ACTIVE',now(),now()),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000010','SYNTHETIC_AD','READBACK','5c000000-0000-5000-8000-000000000012',
  '','/accepted','true','/description','/kizMarked','4191','VERIFIED',now(),'fixture://protocol','synthetic protocol','synthetic','ACTIVE',now(),now()),
 (gen_random_uuid(),'5c000000-0000-5000-8000-000000000010','SYNTHETIC_AD','RESTORE','5c000000-0000-5000-8000-000000000013',
  '{"offer_id":"{nativeListingKey}","attributes":[{"id":"{descriptionAttributeKey}","values":[{"value":"{descriptionText}"}]}],"kizMarked":false}',
  '/accepted','true','/description','/kizMarked','4191','VERIFIED',now(),'fixture://protocol','synthetic protocol','synthetic','ACTIVE',now(),now());
INSERT INTO platform.credential_metadata(id,organization_id,marketplace_account_id,code,display_name,purpose_code,scope_mode,secret_reference,effective_from,expires_at,status,custodian_label,verification_state,created_at,updated_at)
VALUES ('5c000000-0000-5000-8000-000000000014','8689c119-8fa0-50b7-8ba2-f9bf3039d336','2be0ab6f-af56-56cf-b332-700dd591a96e','fictional-content','Fictional content metadata','CONTENT_WRITE','ACCOUNT',
        'secret-ref://fictional/never-resolve-content',now()-interval '1 hour',now()+interval '1 day','ACTIVE','synthetic','UNVERIFIED',now(),now());

-- Switches on, entity allowlisted, and still no production write: the gate
-- authority the Owner publishes says so.
INSERT INTO platform.feature_flag(id,flag_code,flag_kind,scope_kind,state,status,reason,created_at,updated_at)
SELECT gen_random_uuid(),'listing-description-write','WRITE_CAPABILITY','GLOBAL','ENABLED','ACTIVE','synthetic protocol only',now(),now()
 WHERE NOT EXISTS (SELECT 1 FROM platform.feature_flag WHERE flag_code='listing-description-write' AND scope_kind='GLOBAL');
INSERT INTO platform.feature_flag(id,flag_code,flag_kind,scope_kind,capability_id,state,status,reason,created_at,updated_at)
VALUES (gen_random_uuid(),'listing-description-write','WRITE_CAPABILITY','CAPABILITY','5c000000-0000-5000-8000-000000000010','ENABLED','ACTIVE','synthetic protocol only',now(),now());
INSERT INTO ops.pilot_allowlist_entry(id,organization_id,action_kind,platform_code,store_id,platform_listing_id,valid_from,valid_until,status,granted_by_user_id,reason,created_at,updated_at)
VALUES (gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','LISTING_DESCRIPTION_CHANGE','SYNTHETIC_AD','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','aa14dd95-b455-5db2-924c-8a3972e6f9d2',
        now()-interval '1 hour',now()+interval '1 day','ACTIVE','9264ceb0-c29a-5837-9339-c84bfe73a444','synthetic exact listing',now(),now());
INSERT INTO ops.lc_gate_authority(id,organization_id,gate_kind,platform_code,store_id,capability_code,platform_listing_ids,exact_head_sha,exact_tree_sha,owner_user_id,approved_at,valid_from,valid_until,max_commands,evidence_reference,controller_verdict_reference,security_attestation_reference,restoration_plan_reference,production_write_enabled,status)
VALUES ('5c000000-0000-5000-8000-000000000004','8689c119-8fa0-50b7-8ba2-f9bf3039d336','GATE_EV','SYNTHETIC_AD','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','listing-description-change',
        ARRAY['aa14dd95-b455-5db2-924c-8a3972e6f9d2']::uuid[],repeat('a',40),repeat('b',40),'9264ceb0-c29a-5837-9339-c84bfe73a444',now(),now()-interval '1 hour',now()+interval '1 day',1,
        'fixture://gate-ev','fixture://controller-verdict','fixture://security-attestation','fixture://restoration-plan',false,'ACTIVE');

-- A second listing so two actions can compete for one concurrent-listing allowance.
INSERT INTO core.product (id, organization_id, code, display_name, status, created_at, updated_at)
VALUES ('5c000000-0000-5000-8000-000000000020','8689c119-8fa0-50b7-8ba2-f9bf3039d336','fictional-product-two','Fixture product two','ACTIVE',now(),now());
INSERT INTO core.product_variant (id, organization_id, product_id, sku_code, display_name, status, created_at, updated_at)
VALUES ('5c000000-0000-5000-8000-000000000021','8689c119-8fa0-50b7-8ba2-f9bf3039d336','5c000000-0000-5000-8000-000000000020','fictional-sku-two','Fixture variant two','ACTIVE',now(),now());
INSERT INTO core.platform_listing(id,organization_id,store_id,marketplace_account_id,platform_code,native_listing_key,first_seen_at,last_seen_at,status,created_at,updated_at)
VALUES ('5c000000-0000-5000-8000-000000000022','8689c119-8fa0-50b7-8ba2-f9bf3039d336','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','2be0ab6f-af56-56cf-b332-700dd591a96e','SYNTHETIC_AD','fictional-listing-two',now(),now(),'OBSERVED',now(),now());
INSERT INTO core.platform_listing_variant(id,organization_id,platform_listing_id,native_variant_key,first_seen_at,last_seen_at,status,created_at,updated_at)
VALUES ('5c000000-0000-5000-8000-000000000023','8689c119-8fa0-50b7-8ba2-f9bf3039d336','5c000000-0000-5000-8000-000000000022','fictional-child-two',now(),now(),'OBSERVED',now(),now());
INSERT INTO core.listing_mapping(id,organization_id,platform_listing_variant_id,product_variant_id,effective_from,status,confirmed_by_user_id,reason,created_at,updated_at)
VALUES (gen_random_uuid(),'8689c119-8fa0-50b7-8ba2-f9bf3039d336','5c000000-0000-5000-8000-000000000023','5c000000-0000-5000-8000-000000000021',now()-interval '1 day','ACTIVE','0998716b-6f78-56da-bbea-554b20cfd093','fictional mapping two',now(),now());

-- Facts: the current description of each listing, its frozen affected set and a passing health row.
INSERT INTO core.lc_description_observation(id,organization_id,provenance_id,platform_listing_id,source_fact_key,observed_at,acquired_at,description_text,text_digest,language_code,kiz_marked_declared)
VALUES ('5c000000-0000-5000-8000-000000000005','8689c119-8fa0-50b7-8ba2-f9bf3039d336','0e994c7c-409d-506f-a310-f256f77d0920','aa14dd95-b455-5db2-924c-8a3972e6f9d2','fictional-description-one',now()-interval '2 hours',now()-interval '2 hours',
        'Прежнее описание товара для покупателя',encode(sha256(convert_to('Прежнее описание товара для покупателя','UTF8')),'hex'),'ru',false),
       ('5c000000-0000-5000-8000-000000000025','8689c119-8fa0-50b7-8ba2-f9bf3039d336','0e994c7c-409d-506f-a310-f256f77d0920','5c000000-0000-5000-8000-000000000022','fictional-description-two',now()-interval '2 hours',now()-interval '2 hours',
        'Прежнее описание второго товара',encode(sha256(convert_to('Прежнее описание второго товара','UTF8')),'hex'),'ru',false);
INSERT INTO core.lc_affected_set(id,organization_id,platform_listing_id,affected_set_digest,platform_listing_variant_ids,product_variant_ids,resolution_state,unresolved_reason_codes,resolved_at,created_at)
VALUES ('5c000000-0000-5000-8000-000000000006','8689c119-8fa0-50b7-8ba2-f9bf3039d336','aa14dd95-b455-5db2-924c-8a3972e6f9d2',core.lc_listing_affected_set_digest('aa14dd95-b455-5db2-924c-8a3972e6f9d2'),
        ARRAY['7d693f80-2ad3-570d-8f47-e589af7b5598']::uuid[],ARRAY['1484c926-777f-5205-8893-941965dbb38a']::uuid[],'COMPLETE','{}',now(),now()),
       ('5c000000-0000-5000-8000-000000000026','8689c119-8fa0-50b7-8ba2-f9bf3039d336','5c000000-0000-5000-8000-000000000022',core.lc_listing_affected_set_digest('5c000000-0000-5000-8000-000000000022'),
        ARRAY['5c000000-0000-5000-8000-000000000023']::uuid[],ARRAY['5c000000-0000-5000-8000-000000000021']::uuid[],'COMPLETE','{}',now(),now());
INSERT INTO mart.lc_listing_health(id,organization_id,store_id,platform_listing_id,calculation_run_id,affected_set_id,health_version,necessary_conditions,necessary_state,eligibility,opportunities,definition_digest,source_time,acquisition_time,computed_at)
VALUES ('5c000000-0000-5000-8000-000000000007','8689c119-8fa0-50b7-8ba2-f9bf3039d336','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','aa14dd95-b455-5db2-924c-8a3972e6f9d2','4d57d2d4-daa5-519a-8c7b-1a00cfa924ba','5c000000-0000-5000-8000-000000000006',1,
        '[{"code":"AFFECTED_SET_COMPLETE","state":"PASS","evidenceReference":"core.lc_affected_set"},{"code":"MAPPING_RESOLVED","state":"PASS","evidenceReference":"core.listing_mapping"},{"code":"DESCRIPTION_OBSERVED","state":"PASS","evidenceReference":"core.lc_description_observation"},{"code":"NOT_CONTAINED","state":"PASS","evidenceReference":"ops.lc_containment"},{"code":"CALIBRATION_RESOLVED","state":"PASS","evidenceReference":"core.lc_calibration_package"}]',
        'PASS','{"MEASUREMENT":"UNKNOWN","PROTECTION":"ELIGIBLE","EVALUATION":"UNKNOWN"}','[]',repeat('c',64),now(),now(),now()),
       ('5c000000-0000-5000-8000-000000000027','8689c119-8fa0-50b7-8ba2-f9bf3039d336','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','5c000000-0000-5000-8000-000000000022','4d57d2d4-daa5-519a-8c7b-1a00cfa924ba','5c000000-0000-5000-8000-000000000026',1,
        '[{"code":"AFFECTED_SET_COMPLETE","state":"PASS","evidenceReference":"core.lc_affected_set"},{"code":"MAPPING_RESOLVED","state":"PASS","evidenceReference":"core.listing_mapping"},{"code":"DESCRIPTION_OBSERVED","state":"PASS","evidenceReference":"core.lc_description_observation"},{"code":"NOT_CONTAINED","state":"PASS","evidenceReference":"ops.lc_containment"},{"code":"CALIBRATION_RESOLVED","state":"PASS","evidenceReference":"core.lc_calibration_package"}]',
        'PASS','{"MEASUREMENT":"UNKNOWN","PROTECTION":"ELIGIBLE","EVALUATION":"UNKNOWN"}','[]',repeat('c',64),now(),now(),now());

-- Two candidates, two recommendations, two drafted actions: one on the API path, one manual.
INSERT INTO ops.lc_candidate(id,organization_id,store_id,platform_listing_id,calculation_run_id,health_id,candidate_kind,comparison_round_key,evidence_references,expected_effect,prepared_by_user_id,prepared_at,state,updated_at,version) VALUES
 ('5c000000-0000-5000-8000-000000000008','8689c119-8fa0-50b7-8ba2-f9bf3039d336','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','aa14dd95-b455-5db2-924c-8a3972e6f9d2','4d57d2d4-daa5-519a-8c7b-1a00cfa924ba','5c000000-0000-5000-8000-000000000007','CONTENT_DESCRIPTION','round-1','["fixture://evidence/one"]','{"conversion":"improve"}','0998716b-6f78-56da-bbea-554b20cfd093',now(),'SELECTED',now(),1),
 ('5c000000-0000-5000-8000-000000000028','8689c119-8fa0-50b7-8ba2-f9bf3039d336','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','5c000000-0000-5000-8000-000000000022','4d57d2d4-daa5-519a-8c7b-1a00cfa924ba','5c000000-0000-5000-8000-000000000027','CONTENT_DESCRIPTION','round-1','["fixture://evidence/two"]','{"conversion":"improve"}','0998716b-6f78-56da-bbea-554b20cfd093',now(),'SELECTED',now(),1);
INSERT INTO ops.recommendation (id, organization_id, store_id, subject_kind, subject_id, action_kind, origin, calculation_run_id, window_code, state, priority_score, proposed_parameters, expected_effect, risk_label, validation_horizon_days, entity_version_digest, valid_until, created_at, updated_at) VALUES
 ('5c000000-0000-5000-8000-000000000009','8689c119-8fa0-50b7-8ba2-f9bf3039d336','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','PLATFORM_LISTING','aa14dd95-b455-5db2-924c-8a3972e6f9d2','LISTING_DESCRIPTION_CHANGE','DETERMINISTIC','4d57d2d4-daa5-519a-8c7b-1a00cfa924ba','D30','READY_FOR_REVIEW',500,
  '{"actionId":"5c000000-0000-5000-8000-00000000000a","executionPath":"API"}','{}','LOW',14,repeat('d',64),now()+interval '3 days',now(),now()),
 ('5c000000-0000-5000-8000-000000000029','8689c119-8fa0-50b7-8ba2-f9bf3039d336','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','PLATFORM_LISTING','5c000000-0000-5000-8000-000000000022','LISTING_DESCRIPTION_CHANGE','DETERMINISTIC','4d57d2d4-daa5-519a-8c7b-1a00cfa924ba','D30','READY_FOR_REVIEW',500,
  '{"actionId":"5c000000-0000-5000-8000-00000000002a","executionPath":"MANUAL"}','{}','LOW',14,repeat('e',64),now()+interval '3 days',now(),now());
INSERT INTO ops.lc_action(id,organization_id,store_id,platform_listing_id,candidate_id,recommendation_id,affected_set_id,affected_set_digest,action_kind,execution_path,current_description_observation_id,current_text_digest,target_text,target_text_digest,target_language_code,kiz_marked_declared,content_axis_material,exposure_axis_material,materiality_route,calibration_package_id,calibration_version,author_user_id,state,created_at,updated_at,version) VALUES
 ('5c000000-0000-5000-8000-00000000000a','8689c119-8fa0-50b7-8ba2-f9bf3039d336','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','aa14dd95-b455-5db2-924c-8a3972e6f9d2','5c000000-0000-5000-8000-000000000008','5c000000-0000-5000-8000-000000000009','5c000000-0000-5000-8000-000000000006',core.lc_listing_affected_set_digest('aa14dd95-b455-5db2-924c-8a3972e6f9d2'),
  'LISTING_DESCRIPTION_CHANGE','API','5c000000-0000-5000-8000-000000000005',encode(sha256(convert_to('Прежнее описание товара для покупателя','UTF8')),'hex'),
  'Новое описание товара для покупателя с точными характеристиками',encode(sha256(convert_to('Новое описание товара для покупателя с точными характеристиками','UTF8')),'hex'),'ru',false,false,false,'ORDINARY_IMPACT','5c000000-0000-5000-8000-000000000001',1,'0998716b-6f78-56da-bbea-554b20cfd093','DRAFT',now(),now(),1),
 ('5c000000-0000-5000-8000-00000000002a','8689c119-8fa0-50b7-8ba2-f9bf3039d336','f5eced9a-7d0a-5d65-8942-8d1efeabf41a','5c000000-0000-5000-8000-000000000022','5c000000-0000-5000-8000-000000000028','5c000000-0000-5000-8000-000000000029','5c000000-0000-5000-8000-000000000026',core.lc_listing_affected_set_digest('5c000000-0000-5000-8000-000000000022'),
  'LISTING_DESCRIPTION_CHANGE','MANUAL','5c000000-0000-5000-8000-000000000025',encode(sha256(convert_to('Прежнее описание второго товара','UTF8')),'hex'),
  'Новое описание второго товара для покупателя',encode(sha256(convert_to('Новое описание второго товара для покупателя','UTF8')),'hex'),'ru',false,false,false,'ORDINARY_IMPACT','5c000000-0000-5000-8000-000000000001',1,'0998716b-6f78-56da-bbea-554b20cfd093','DRAFT',now(),now(),1);

-- Synthetic plan is frozen before independent review and approval.
INSERT INTO ops.lc_evaluation_plan(id,organization_id,action_id,calibration_package_id,calibration_version,version_coverage,transition_handling,latest_boundary,formal_nodes,stop_rule,critical_groups,comparison_basis,cross_period_window_days,plan_digest,frozen_at) VALUES
 ('5c000000-0000-5000-8000-00000000000f','8689c119-8fa0-50b7-8ba2-f9bf3039d336','5c000000-0000-5000-8000-00000000000a','5c000000-0000-5000-8000-000000000001',1,'{"targetVersion":"one"}','EXCLUDE_TRANSITION_DAYS',now()+interval '30 days',
  '[{"nodeCode":"D14","maturityDays":14,"method":"WILSON_LOWER_BOUND","threshold":"0.050000"}]','{"nodeCode":"D14"}','[]','PRIOR_VERSION_WINDOW',30,repeat('6',64),now()),
 ('5c000000-0000-5000-8000-00000000002f','8689c119-8fa0-50b7-8ba2-f9bf3039d336','5c000000-0000-5000-8000-00000000002a','5c000000-0000-5000-8000-000000000001',1,'{"targetVersion":"two"}','EXCLUDE_TRANSITION_DAYS',now()+interval '30 days',
  '[{"nodeCode":"D14","maturityDays":14,"method":"WILSON_LOWER_BOUND","threshold":"0.050000"}]','{"nodeCode":"D14"}','[]','PRIOR_VERSION_WINDOW',30,repeat('7',64),now());

-- Independent review, then approval with a PASS naming the calibration package, then its exact plan binding.
INSERT INTO ops.lc_action_review(id,organization_id,action_id,reviewer_user_id,attested_target_text_digest,attested_current_text_digest,attested_affected_set_digest,facts_digest,verdict,reason,reviewed_at)
SELECT gen_random_uuid(),a.organization_id,a.id,'8ec704dd-3aa5-529c-93db-def4bbf39260',a.target_text_digest,a.current_text_digest,a.affected_set_digest,repeat('f',64),'ATTESTED','synthetic independent review',now()
  FROM ops.lc_action a WHERE a.id IN ('5c000000-0000-5000-8000-00000000000a','5c000000-0000-5000-8000-00000000002a');
UPDATE ops.lc_action SET state='REVIEWED', updated_at=now(), version=version+1 WHERE id IN ('5c000000-0000-5000-8000-00000000000a','5c000000-0000-5000-8000-00000000002a');
INSERT INTO ops.guardrail_evaluation (id, organization_id, recommendation_id, purpose, outcome, reason_codes, detail, input_digest, evaluated_at, correlation_id, authority_snapshot, lc_calibration_package_id, lc_calibration_version) VALUES
 ('5c000000-0000-5000-8000-00000000000c','8689c119-8fa0-50b7-8ba2-f9bf3039d336','5c000000-0000-5000-8000-000000000009','APPROVAL','PASS','{}','{}',repeat('1',64),now(),'listing-fixture',ops.lc_authority_snapshot('5c000000-0000-5000-8000-000000000009'),'5c000000-0000-5000-8000-000000000001',1),
 ('5c000000-0000-5000-8000-00000000002c','8689c119-8fa0-50b7-8ba2-f9bf3039d336','5c000000-0000-5000-8000-000000000029','APPROVAL','PASS','{}','{}',repeat('2',64),now(),'listing-fixture',ops.lc_authority_snapshot('5c000000-0000-5000-8000-000000000029'),'5c000000-0000-5000-8000-000000000001',1),
 ('5c000000-0000-5000-8000-00000000001c','8689c119-8fa0-50b7-8ba2-f9bf3039d336','5c000000-0000-5000-8000-000000000009','EXECUTION','PASS','{}','{}',repeat('3',64),now(),'listing-fixture',ops.lc_authority_snapshot('5c000000-0000-5000-8000-000000000009'),'5c000000-0000-5000-8000-000000000001',1);
INSERT INTO ops.approval_decision (id, organization_id, recommendation_id, decision, decided_by_user_id, step_up_satisfied, authenticated_at, entity_version_digest, scope_expires_at, reason, decided_at, correlation_id, authority_snapshot) VALUES
 ('5c000000-0000-5000-8000-00000000000d','8689c119-8fa0-50b7-8ba2-f9bf3039d336','5c000000-0000-5000-8000-000000000009','APPROVED','9264ceb0-c29a-5837-9339-c84bfe73a444',true,now(),repeat('d',64),now()+interval '2 hours','synthetic listing approval',now(),'listing-fixture','{}'),
 ('5c000000-0000-5000-8000-00000000002d','8689c119-8fa0-50b7-8ba2-f9bf3039d336','5c000000-0000-5000-8000-000000000029','APPROVED','9264ceb0-c29a-5837-9339-c84bfe73a444',true,now(),repeat('e',64),now()+interval '2 hours','synthetic listing approval',now(),'listing-fixture','{}');
UPDATE ops.recommendation SET state='APPROVED', updated_at=now() WHERE id IN ('5c000000-0000-5000-8000-000000000009','5c000000-0000-5000-8000-000000000029');
INSERT INTO ops.lc_action_binding(id,organization_id,action_id,approval_decision_id,guardrail_evaluation_id,target_text_digest,current_text_digest,affected_set_digest,execution_path,evidence_versions,rule_versions,calibration_package_id,calibration_version,binding_digest,bound_at,expires_at,state)
SELECT '5c000000-0000-5000-8000-00000000000e',a.organization_id,a.id,'5c000000-0000-5000-8000-00000000000d','5c000000-0000-5000-8000-00000000000c',a.target_text_digest,a.current_text_digest,a.affected_set_digest,a.execution_path,
       jsonb_build_object('descriptionObservation','5c000000-0000-5000-8000-000000000005'),jsonb_build_object('calibration','1'),a.calibration_package_id,a.calibration_version,repeat('4',64),now(),now()+interval '1 hour','BOUND'
  FROM ops.lc_action a WHERE a.id='5c000000-0000-5000-8000-00000000000a';
INSERT INTO ops.lc_action_binding(id,organization_id,action_id,approval_decision_id,guardrail_evaluation_id,target_text_digest,current_text_digest,affected_set_digest,execution_path,evidence_versions,rule_versions,calibration_package_id,calibration_version,binding_digest,bound_at,expires_at,state)
SELECT '5c000000-0000-5000-8000-00000000002e',a.organization_id,a.id,'5c000000-0000-5000-8000-00000000002d','5c000000-0000-5000-8000-00000000002c',a.target_text_digest,a.current_text_digest,a.affected_set_digest,a.execution_path,
       jsonb_build_object('descriptionObservation','5c000000-0000-5000-8000-000000000025'),jsonb_build_object('calibration','1'),a.calibration_package_id,a.calibration_version,repeat('5',64),now(),now()+interval '1 hour','BOUND'
  FROM ops.lc_action a WHERE a.id='5c000000-0000-5000-8000-00000000002a';
UPDATE ops.lc_action SET state='APPROVED', updated_at=now(), version=version+1 WHERE id IN ('5c000000-0000-5000-8000-00000000000a','5c000000-0000-5000-8000-00000000002a');
