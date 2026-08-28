-- SYNTHETIC_PERFORMANCE_DATASET_V1. Run only in RepresentativePerformanceIT's
-- disposable PostgreSQL container. No real account, provider, buyer or secret.
-- Materialized metric/finding values are benchmark fixtures, not calculation evidence.
-- Fixed profile: 5,000 SKUs; 3 stores/accounts (80/15/5%); 360,000 orders over
-- 180 days; COMPLETED and SETTLED facts; 26 metrics and 9 rules in D7/D14/D30;
-- two versions per subject, sixty versions for ten hot subjects; 30,000 recommendations.

CREATE TEMP TABLE perf_config AS SELECT
    5000 AS sku_count,360000 AS order_count,timestamptz '2026-08-01T12:00:00Z' AS base_time,
    md5('performance-v1/org')::uuid AS org,md5('performance-v1/legal')::uuid AS legal,
    md5('performance-v1/provider')::uuid AS provider,md5('performance-v1/user')::uuid AS actor;

INSERT INTO core.organization(id,code,display_name,status,created_at,updated_at)
SELECT org,'performance-v1','Synthetic performance organization','ACTIVE',base_time,base_time FROM perf_config;
INSERT INTO core.legal_entity(id,organization_id,code,display_name,status,created_at,updated_at)
SELECT legal,org,'performance-v1','Synthetic entity','ACTIVE',base_time,base_time FROM perf_config;
INSERT INTO iam.identity_provider(id,code,display_name,issuer,mfa_claim_name,mfa_claim_value,
    max_auth_age_seconds,verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
SELECT provider,'performance-v1','Synthetic test identity','https://performance.fixture.invalid','amr','mfa',900,
    'VERIFIED',base_time,'evidence://fixture/performance','Synthetic test principal','fixture','ACTIVE',base_time,base_time FROM perf_config;
INSERT INTO iam.user_account(id,organization_id,identity_provider_id,external_subject,display_name,status,
    credentials_valid_from,created_at,updated_at)
SELECT actor,org,provider,'performance-v1','Synthetic performance operator','ACTIVE',base_time,base_time,base_time FROM perf_config;
INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,effective_from,status,created_at,updated_at)
SELECT md5('performance-v1/role')::uuid,org,actor,'OWNER',base_time,'ACTIVE',base_time,base_time FROM perf_config;
INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,effective_from,status,created_at,updated_at)
SELECT md5('performance-v1/scope/'||action)::uuid,org,actor,action,org,base_time,'ACTIVE',base_time,base_time
FROM perf_config CROSS JOIN unnest(ARRAY['DIAGNOSTIC_VIEW','EVIDENCE_VIEW']) action;

CREATE TEMP TABLE perf_store AS
SELECT n,md5('performance-v1/account/'||n)::uuid AS account,md5('performance-v1/store/'||n)::uuid AS store
FROM generate_series(1,3) n;
INSERT INTO core.marketplace_account(id,organization_id,legal_entity_id,platform_code,code,display_name,status,created_at,updated_at)
SELECT account,org,legal,'OZON','performance-v1-'||n,'Synthetic account','ACTIVE',base_time,base_time FROM perf_store CROSS JOIN perf_config;
INSERT INTO core.store(id,organization_id,marketplace_account_id,code,display_name,status,created_at,updated_at)
SELECT store,org,account,'performance-v1-'||n,'Synthetic store','ACTIVE',base_time,base_time FROM perf_store CROSS JOIN perf_config;

CREATE TEMP TABLE perf_sku AS
SELECT n,md5('performance-v1/product/'||n)::uuid AS product,
    md5('performance-v1/sku/'||n)::uuid AS sku,md5('performance-v1/listing/'||n)::uuid AS listing,
    md5('performance-v1/variant/'||n)::uuid AS variant,
    CASE WHEN n<=sku_count*0.8 THEN 1 WHEN n<=sku_count*0.95 THEN 2 ELSE 3 END AS store_no
FROM perf_config CROSS JOIN generate_series(1,sku_count) n;
CREATE UNIQUE INDEX ON perf_sku(n);
INSERT INTO core.product(id,organization_id,code,display_name,status,created_at,updated_at)
SELECT product,org,'performance-product-'||n,'Synthetic product','ACTIVE',base_time,base_time FROM perf_sku CROSS JOIN perf_config;
INSERT INTO core.product_variant(id,organization_id,product_id,sku_code,display_name,status,created_at,updated_at)
SELECT sku,org,product,'performance-sku-'||n,'Synthetic SKU','ACTIVE',base_time,base_time FROM perf_sku CROSS JOIN perf_config;
INSERT INTO core.platform_listing(id,organization_id,store_id,marketplace_account_id,platform_code,native_listing_key,title,
    first_seen_at,last_seen_at,status,created_at,updated_at)
SELECT listing,org,store,account,'OZON','SYNTHETIC-LISTING-'||s.n,'Synthetic listing',base_time,base_time,'OBSERVED',base_time,base_time
FROM perf_sku s JOIN perf_store st ON st.n=s.store_no CROSS JOIN perf_config;
INSERT INTO core.platform_listing_variant(id,organization_id,platform_listing_id,native_variant_key,native_sku_key,
    first_seen_at,last_seen_at,status,created_at,updated_at)
SELECT variant,org,listing,'SYNTHETIC-VARIANT-'||n,'SYNTHETIC-SKU-'||n,base_time,base_time,'OBSERVED',base_time,base_time FROM perf_sku CROSS JOIN perf_config;
INSERT INTO core.listing_mapping(id,organization_id,platform_listing_variant_id,product_variant_id,effective_from,status,
    confirmed_by_user_id,reason,created_at,updated_at)
SELECT md5('performance-v1/mapping/'||n)::uuid,org,variant,sku,base_time,'ACTIVE',actor,'Synthetic performance mapping',base_time,base_time
FROM perf_sku CROSS JOIN perf_config;

CREATE TEMP TABLE perf_order AS
SELECT n,((n-1)%sku_count)+1 AS sku_no,md5('performance-v1/provenance/'||n)::uuid AS provenance,
    base_time-((n-1)%180)*interval '1 day' AS occurred_at
FROM perf_config CROSS JOIN generate_series(1,order_count) n;
INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note)
SELECT provenance,org,'MANUAL_ENTRY',occurred_at,base_time,actor,'SYNTHETIC_PERFORMANCE_DATASET_V1; not a marketplace assertion'
FROM perf_order CROSS JOIN perf_config;
INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,sale_stage,
    source_fact_key,native_order_key,native_line_key,native_status,occurred_at,quantity,currency_code,gross_amount,discount_amount,net_amount)
SELECT md5('performance-v1/sale/'||o.n||'/'||stage)::uuid,org,provenance,variant,store,stage,
    'performance-v1/'||o.n||'/'||stage,'SYNTHETIC-ORDER-'||o.n,'SYNTHETIC-LINE-1','synthetic',occurred_at,1,'RUB',110,10,100
FROM perf_order o JOIN perf_sku s ON s.n=o.sku_no JOIN perf_store st ON st.n=s.store_no
CROSS JOIN perf_config CROSS JOIN unnest(ARRAY['COMPLETED','SETTLED']) stage;

CREATE TEMP TABLE perf_snapshot AS
SELECT s.*,w AS days,iteration,base_time-interval '1 day'*w AS period_start,
    base_time AS period_end,base_time+iteration*interval '1 minute' AS computed_at,
    md5('performance-v1/run/'||w||'/'||iteration)::uuid AS run
FROM perf_sku s CROSS JOIN perf_config CROSS JOIN unnest(ARRAY[7,14,30]) w
CROSS JOIN LATERAL generate_series(1,CASE WHEN s.n<=10 THEN 60 ELSE 2 END) iteration;
INSERT INTO mart.calculation_run(id,organization_id,trigger_kind,scope_kind,window_code,period_start,period_end,
    definition_set_digest,state,subject_count,value_count,started_at,completed_at,correlation_id)
SELECT run,org,'BACKFILL','ORGANIZATION','D'||days,period_start,period_end,
    repeat('1',64),'SUCCEEDED',count(*),count(*)*26,computed_at,computed_at,'SYNTHETIC_PERFORMANCE_DATASET_V1'
FROM perf_snapshot CROSS JOIN perf_config GROUP BY run,org,days,period_start,period_end,computed_at;

CREATE TEMP TABLE perf_metric AS
SELECT s.*,d.metric_code,d.definition_version,
    md5('performance-v1/metric/'||n||'/'||days||'/'||iteration||'/'||d.metric_code)::uuid AS metric_id
FROM perf_snapshot s CROSS JOIN mart.metric_definition d WHERE d.status='ACTIVE';
INSERT INTO mart.metric_value(id,organization_id,calculation_run_id,metric_code,definition_version,subject_kind,subject_id,
    window_code,period_start,period_end,value_state,numeric_value,currency_code,confidence_state,estimated,
    oldest_source_time,freshness_seconds,input_digest,computed_at)
SELECT metric_id,org,run,metric_code,definition_version,'PLATFORM_LISTING_VARIANT',variant,'D'||days,
    period_start,period_end,CASE WHEN n%11=0 THEN 'NOT_AVAILABLE' ELSE 'AVAILABLE' END,
    CASE WHEN n%11=0 THEN NULL ELSE (n%1000+1)*100 END,'RUB',
    CASE WHEN n%11=0 THEN 'INCOMPLETE' WHEN n%7=0 THEN 'STALE' ELSE 'CANONICAL_CONFIRMED' END,false,
    period_start,3600,encode(sha256(convert_to(metric_id::text,'UTF8')),'hex'),computed_at
FROM perf_metric CROSS JOIN perf_config;
INSERT INTO mart.metric_input_reference(id,metric_value_id,reference_kind,reference_id)
SELECT md5('performance-v1/metric-input/'||metric_id||'/'||ref)::uuid,metric_id,'FACT_PROVENANCE',
    md5('performance-v1/provenance/'||(n+(ref-1)*sku_count))::uuid
FROM perf_metric CROSS JOIN perf_config CROSS JOIN generate_series(1,3) ref;

CREATE TEMP TABLE perf_finding AS
SELECT s.*,r.rule_code,r.rule_version,r.ordinal,
    md5('performance-v1/finding/'||n||'/'||days||'/'||iteration||'/'||r.rule_code)::uuid AS finding_id
FROM perf_snapshot s CROSS JOIN mart.diagnosis_rule r WHERE r.status='ACTIVE';
INSERT INTO mart.diagnosis_finding(id,organization_id,calculation_run_id,rule_code,rule_version,subject_kind,subject_id,
    window_code,period_start,period_end,outcome,severity,decline_reason,detail,input_digest,evaluated_at)
SELECT finding_id,org,run,rule_code,rule_version,'PLATFORM_LISTING_VARIANT',variant,'D'||days,period_start,period_end,
    CASE WHEN (n+ordinal+iteration)%3=0 THEN 'TRIGGERED' WHEN (n+ordinal+iteration)%3=1 THEN 'CLEAR' ELSE 'DECLINED' END,
    CASE WHEN (n+ordinal+iteration)%3=0 THEN CASE WHEN ordinal<=3 THEN 'CRITICAL' ELSE 'WARNING' END END,
    CASE WHEN (n+ordinal+iteration)%3=2 THEN 'REQUIRED_METRIC_UNAVAILABLE' END,
    '{"classification":"SYNTHETIC_PERFORMANCE_DATASET_V1"}'::jsonb,
    encode(sha256(convert_to(finding_id::text,'UTF8')),'hex'),computed_at
FROM perf_finding CROSS JOIN perf_config;
INSERT INTO mart.diagnosis_finding_input(id,finding_id,metric_value_id,role)
SELECT md5('performance-v1/finding-input/'||finding_id)::uuid,finding_id,
    md5('performance-v1/metric/'||n||'/'||days||'/'||iteration||'/COMPLETED_NET_SALES')::uuid,'SUPPORTING'
FROM perf_finding;

INSERT INTO ops.recommendation(id,organization_id,store_id,subject_kind,subject_id,action_kind,origin,calculation_run_id,
    window_code,state,priority_score,proposed_parameters,expected_effect,risk_label,validation_horizon_days,entity_version_digest,
    valid_until,created_at,updated_at)
SELECT md5('performance-v1/recommendation/'||s.n||'/'||action||'/'||generation)::uuid,org,store,
    'PLATFORM_LISTING_VARIANT',variant,action,'DETERMINISTIC',md5('performance-v1/run/30/2')::uuid,
    'D30',CASE WHEN generation=1 THEN 'CLOSED' ELSE 'TASK_ONLY' END,s.n%1000,'{}'::jsonb,'{}'::jsonb,'LOW',30,
    repeat('2',64),base_time+interval '1 day',base_time,base_time
FROM perf_sku s JOIN perf_store st ON st.n=s.store_no CROSS JOIN perf_config
CROSS JOIN unnest(ARRAY['RESTOCK_REVIEW','COST_DATA_REVIEW','ADVERTISING_REVIEW']) action
CROSS JOIN generate_series(1,2) generation;

ANALYZE core.platform_listing;
ANALYZE core.platform_listing_variant;
ANALYZE core.fact_provenance;
ANALYZE ledger.sales_fact;
ANALYZE mart.metric_value;
ANALYZE mart.metric_input_reference;
ANALYZE mart.diagnosis_finding;
ANALYZE mart.diagnosis_finding_input;
ANALYZE ops.recommendation;
