-- Diagnostic exports are derived artifacts, never a second fact/Raw writer.
-- All mutations are fenced functions. Snapshot rows are canonical UTF-8 NDJSON
-- records; explicit field lists prevent later schema additions leaking data.
CREATE TABLE ops.diagnostic_export (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES core.organization(id),
    store_id uuid NOT NULL REFERENCES core.store(id),
    requester_id uuid NOT NULL REFERENCES iam.user_account(id),
    window_code text NOT NULL CHECK (window_code IN ('D7','D14','D30')),
    request_key_hash text NOT NULL CHECK (request_key_hash ~ '^[0-9a-f]{64}$'),
    state text NOT NULL CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','EXPIRED')),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    deadline_at timestamptz NOT NULL DEFAULT statement_timestamp()+interval '15 minutes',
    next_attempt_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    snapshot_at timestamptz,
    expires_at timestamptz,
    lease_token uuid,
    lease_until timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    row_count integer NOT NULL DEFAULT 0 CHECK (row_count BETWEEN 0 AND 1000000),
    byte_length bigint NOT NULL DEFAULT 0 CHECK (byte_length BETWEEN 0 AND 268435456),
    manifest jsonb,
    manifest_sha256 text CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    failure_code text CHECK (failure_code IN ('AUTHORIZATION_REVOKED','LIMIT_EXCEEDED',
        'STORAGE_UNAVAILABLE','DATABASE_UNAVAILABLE','INVALID_SNAPSHOT','DEADLINE_EXCEEDED','RETRY_EXHAUSTED')),
    UNIQUE (requester_id,request_key_hash),
    CHECK ((state='RUNNING')=(lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((state IN ('SUCCEEDED','EXPIRED'))=(manifest IS NOT NULL AND manifest_sha256 IS NOT NULL AND expires_at IS NOT NULL))
);
CREATE INDEX diagnostic_export_queue_ix ON ops.diagnostic_export(state,next_attempt_at,created_at);
CREATE INDEX diagnostic_export_org_ix ON ops.diagnostic_export(organization_id,created_at);

CREATE TABLE mart.diagnostic_export_row (
    export_id uuid NOT NULL REFERENCES ops.diagnostic_export(id),
    ordinal integer NOT NULL CHECK (ordinal BETWEEN 1 AND 1000000),
    payload text NOT NULL,
    byte_length integer GENERATED ALWAYS AS (octet_length(payload)+1) STORED,
    PRIMARY KEY(export_id,ordinal),
    CHECK (octet_length(payload)+1<=65536)
);
CREATE TABLE ops.diagnostic_export_part (
    export_id uuid NOT NULL REFERENCES ops.diagnostic_export(id),
    part_number integer NOT NULL CHECK (part_number BETWEEN 1 AND 64),
    first_ordinal integer NOT NULL CHECK (first_ordinal>0),
    last_ordinal integer NOT NULL CHECK (last_ordinal>=first_ordinal),
    row_count integer NOT NULL CHECK (row_count=last_ordinal-first_ordinal+1),
    byte_length integer NOT NULL CHECK (byte_length BETWEEN 1 AND 4194304),
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    content_id uuid NOT NULL REFERENCES raw.raw_content(id),
    PRIMARY KEY(export_id,part_number),
    UNIQUE(export_id,first_ordinal)
);
GRANT SELECT ON ops.diagnostic_export,ops.diagnostic_export_part,mart.diagnostic_export_row TO marketops_app;
INSERT INTO platform.control_route_inventory(schema_name,table_name,route_kind,scope_kind,routing_note) VALUES
 ('ops','diagnostic_export','NO_ROUTE',NULL,'Derived diagnostic export; fenced functions are its only writer.'),
 ('ops','diagnostic_export_part','NO_ROUTE',NULL,'Immutable contiguous custody parts, checked against snapshot bytes.'),
 ('mart','diagnostic_export_row','NO_ROUTE',NULL,'Immutable bounded snapshot; no metadata maintenance route.');

-- Same live role/action/grant matrix as BusinessAuthorization, with active
-- ownership and provider checks. No token, claimed organization or supplied role
-- grants authority to an asynchronous worker.
CREATE FUNCTION iam.diagnostic_export_allowed(p_actor uuid,p_store uuid)
RETURNS boolean LANGUAGE sql STABLE SET search_path=pg_catalog,pg_temp AS $$
 SELECT EXISTS (
   SELECT 1 FROM core.store s JOIN core.marketplace_account a ON a.id=s.marketplace_account_id
   JOIN core.legal_entity e ON e.id=a.legal_entity_id JOIN core.organization o ON o.id=s.organization_id
   JOIN iam.user_account u ON u.organization_id=o.id
   JOIN iam.identity_provider ip ON ip.id=u.identity_provider_id
   WHERE s.id=p_store AND u.id=p_actor AND u.status='ACTIVE' AND ip.status='ACTIVE'
     AND ip.verification_state='VERIFIED' AND s.status='ACTIVE' AND a.status='ACTIVE'
     AND e.status='ACTIVE' AND o.status='ACTIVE'
     AND NOT EXISTS (SELECT 1 FROM (VALUES ('DIAGNOSTIC_VIEW'),('EVIDENCE_VIEW')) actions(code)
       WHERE NOT EXISTS (SELECT 1 FROM iam.user_role_assignment r
         JOIN iam.business_role_action_scope m ON m.role_code=r.role_code
         WHERE r.user_id=u.id AND m.action_code=actions.code AND r.status='ACTIVE'
           AND r.effective_from<=statement_timestamp() AND (r.effective_to IS NULL OR r.effective_to>statement_timestamp()))
       OR NOT EXISTS (SELECT 1 FROM iam.user_scope_grant g WHERE g.user_id=u.id
         AND g.action_code=actions.code AND g.status='ACTIVE'
         AND g.effective_from<=statement_timestamp() AND (g.effective_to IS NULL OR g.effective_to>statement_timestamp())
         AND (g.organization_ref_id=o.id OR g.legal_entity_ref_id=e.id
           OR g.marketplace_account_ref_id=a.id OR g.store_ref_id=s.id))))
$$;
REVOKE ALL ON FUNCTION iam.diagnostic_export_allowed(uuid,uuid) FROM PUBLIC;

CREATE FUNCTION ops.audit_diagnostic_export(p_job ops.diagnostic_export,p_event text)
RETURNS void LANGUAGE sql SET search_path=pg_catalog,pg_temp AS $$
 INSERT INTO ops.metadata_audit_event(id,actor_type,actor_id,source_domain,action,entity_type,entity_id,change_summary,correlation_id)
 VALUES(gen_random_uuid(),'SYSTEM','diagnostic-export','analyticsdecision','STATUS_CHANGE','diagnostic-export',p_job.id,
   jsonb_build_object('event',p_event,'requesterId',p_job.requester_id,'state',p_job.state,
     'rowCount',p_job.row_count,'byteLength',p_job.byte_length,'failureCode',p_job.failure_code),p_job.id::text)
$$;
REVOKE ALL ON FUNCTION ops.audit_diagnostic_export(ops.diagnostic_export,text) FROM PUBLIC;

CREATE FUNCTION ops.submit_diagnostic_export(p_actor uuid,p_store uuid,p_window text,p_key text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE j ops.diagnostic_export; org uuid;
BEGIN
 IF p_window IS NULL OR p_window NOT IN ('D7','D14','D30') OR p_key IS NULL OR p_key !~ '^[0-9a-f]{64}$' THEN
   RAISE EXCEPTION 'invalid export request' USING ERRCODE='MO039'; END IF;
 IF NOT iam.diagnostic_export_allowed(p_actor,p_store) THEN
   RAISE EXCEPTION 'export scope denied' USING ERRCODE='MO064'; END IF;
 SELECT organization_id INTO STRICT org FROM core.store WHERE id=p_store;
 PERFORM pg_advisory_xact_lock(hashtextextended('diagnostic-export:'||org::text,0));
 SELECT * INTO j FROM ops.diagnostic_export WHERE requester_id=p_actor AND request_key_hash=p_key;
 IF FOUND THEN
   IF j.store_id<>p_store OR j.window_code<>p_window THEN
     RAISE EXCEPTION 'export key conflict' USING ERRCODE='MO061'; END IF;
   RETURN j.id;
 END IF;
 IF (SELECT count(*) FROM ops.diagnostic_export WHERE organization_id=org AND state IN ('QUEUED','RUNNING'))>=2
   OR (SELECT count(*) FROM ops.diagnostic_export WHERE organization_id=org AND created_at>statement_timestamp()-interval '1 hour')>=10 THEN
   RAISE EXCEPTION 'export queue full' USING ERRCODE='MO080'; END IF;
 INSERT INTO ops.diagnostic_export(id,organization_id,store_id,requester_id,window_code,request_key_hash,state)
 VALUES(gen_random_uuid(),org,p_store,p_actor,p_window,p_key,'QUEUED') RETURNING * INTO j;
 PERFORM ops.audit_diagnostic_export(j,'SUBMITTED'); RETURN j.id;
END $$;
REVOKE ALL ON FUNCTION ops.submit_diagnostic_export(uuid,uuid,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.submit_diagnostic_export(uuid,uuid,text,text) TO marketops_app;

-- Bounded housekeeping shares the scheduler. Expiry ends access; it does not
-- delete custody or historical evidence. Stalled jobs cannot fill a queue forever.
CREATE FUNCTION ops.expire_diagnostic_exports()
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE j ops.diagnostic_export;
BEGIN
 FOR j IN SELECT * FROM ops.diagnostic_export WHERE
   (state='SUCCEEDED' AND expires_at<=statement_timestamp()) OR
   (state IN ('QUEUED','RUNNING') AND (deadline_at<=statement_timestamp() OR
     (attempt_count>=5 AND (lease_until IS NULL OR lease_until<=statement_timestamp()))))
   ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 100 LOOP
   UPDATE ops.diagnostic_export SET state=CASE WHEN j.state='SUCCEEDED' THEN 'EXPIRED' ELSE 'FAILED' END,
     failure_code=CASE WHEN j.state='SUCCEEDED' THEN NULL WHEN j.deadline_at<=statement_timestamp()
       THEN 'DEADLINE_EXCEEDED' ELSE 'RETRY_EXHAUSTED' END,lease_token=NULL,lease_until=NULL
     WHERE id=j.id RETURNING * INTO j;
   PERFORM ops.audit_diagnostic_export(j,j.state);
 END LOOP;
END $$;
REVOKE ALL ON FUNCTION ops.expire_diagnostic_exports() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.expire_diagnostic_exports() TO marketops_app;

CREATE FUNCTION ops.claim_diagnostic_export()
RETURNS SETOF ops.diagnostic_export LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE j ops.diagnostic_export;
BEGIN
 SELECT * INTO j FROM ops.diagnostic_export WHERE
   (state='QUEUED' OR (state='RUNNING' AND lease_until<=statement_timestamp()))
   AND next_attempt_at<=statement_timestamp() AND deadline_at>statement_timestamp() AND attempt_count<5
   ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1;
 IF NOT FOUND THEN RETURN; END IF;
 IF NOT iam.diagnostic_export_allowed(j.requester_id,j.store_id) THEN
   UPDATE ops.diagnostic_export SET state='FAILED',failure_code='AUTHORIZATION_REVOKED',lease_token=NULL,lease_until=NULL
     WHERE id=j.id RETURNING * INTO j;
   PERFORM ops.audit_diagnostic_export(j,'FAILED'); RETURN;
 END IF;
 UPDATE ops.diagnostic_export SET state='RUNNING',lease_token=gen_random_uuid(),
   lease_until=least(deadline_at,statement_timestamp()+interval '2 minutes'),attempt_count=attempt_count+1,failure_code=NULL
   WHERE id=j.id RETURNING * INTO j;
 PERFORM ops.audit_diagnostic_export(j,'CLAIMED'); RETURN NEXT j;
END $$;
REVOKE ALL ON FUNCTION ops.claim_diagnostic_export() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.claim_diagnostic_export() TO marketops_app;

CREATE FUNCTION ops.require_diagnostic_export_lease(p_id uuid,p_token uuid)
RETURNS ops.diagnostic_export LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
DECLARE j ops.diagnostic_export;
BEGIN
 SELECT * INTO j FROM ops.diagnostic_export WHERE id=p_id FOR UPDATE;
 IF NOT FOUND OR j.state<>'RUNNING' OR p_token IS NULL OR j.lease_token IS DISTINCT FROM p_token
   OR j.lease_until<=clock_timestamp() OR j.deadline_at<=clock_timestamp() THEN
   RAISE EXCEPTION 'export lease lost' USING ERRCODE='MO081'; END IF;
 IF NOT iam.diagnostic_export_allowed(j.requester_id,j.store_id) THEN
   RAISE EXCEPTION 'export scope denied' USING ERRCODE='MO064'; END IF;
 RETURN j;
END $$;
REVOKE ALL ON FUNCTION ops.require_diagnostic_export_lease(uuid,uuid) FROM PUBLIC;

CREATE FUNCTION ops.snapshot_diagnostic_export(p_id uuid,p_token uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET timezone='UTC'
 SET enable_nestloop=off SET jit=off AS $$
DECLARE j ops.diagnostic_export; n integer; bytes bigint; taken_at timestamptz;
BEGIN
 j:=ops.require_diagnostic_export_lease(p_id,p_token);
 IF j.snapshot_at IS NOT NULL THEN RETURN; END IF;
 taken_at:=clock_timestamp();
 -- This bulk query discourages nested loops only within this function: fresh
 -- small-table statistics otherwise caused millions of repeated CTE probes.
 -- JIT is off here to avoid compilation overhead for bounded background jobs.
 -- All four record families are read by this one MVCC statement. A finding's
 -- referenced metric versions are included even when they are no longer latest.
 WITH subjects AS MATERIALIZED (
   SELECT 'STORE'::text kind,j.store_id id UNION ALL
   SELECT 'PLATFORM_LISTING_VARIANT',v.id FROM core.platform_listing_variant v
     JOIN core.platform_listing l ON l.id=v.platform_listing_id
     WHERE l.store_id=j.store_id AND v.organization_id=j.organization_id
 ), findings AS MATERIALIZED (
   SELECT DISTINCT ON (f.subject_kind,f.subject_id,f.rule_code) f.* FROM mart.diagnosis_finding f
     JOIN subjects s ON s.kind=f.subject_kind AND s.id=f.subject_id
     WHERE f.organization_id=j.organization_id AND f.window_code=j.window_code
     ORDER BY f.subject_kind,f.subject_id,f.rule_code,f.evaluated_at DESC,f.id DESC
 ), latest_metrics AS MATERIALIZED (
   SELECT DISTINCT ON (m.subject_kind,m.subject_id,m.metric_code) m.id FROM mart.metric_value m
     JOIN subjects s ON s.kind=m.subject_kind AND s.id=m.subject_id
     WHERE m.organization_id=j.organization_id AND m.window_code=j.window_code
     ORDER BY m.subject_kind,m.subject_id,m.metric_code,m.computed_at DESC,m.id DESC
 ), metric_ids AS MATERIALIZED (
   SELECT id,bool_or(is_current) is_current FROM (
     SELECT id,true is_current FROM latest_metrics UNION ALL
     SELECT i.metric_value_id,false FROM mart.diagnosis_finding_input i JOIN findings f ON f.id=i.finding_id
   ) selected GROUP BY id
 ), metrics AS MATERIALIZED (
   SELECT m.*,ids.is_current FROM mart.metric_value m JOIN metric_ids ids ON ids.id=m.id
     JOIN subjects s ON s.kind=m.subject_kind AND s.id=m.subject_id
     WHERE m.organization_id=j.organization_id
 ), records AS (
   SELECT 1 family,m.id id,jsonb_build_object('schemaVersion',1,'recordType','METRIC','metricValueId',m.id,
     'metricCode',m.metric_code,'definitionVersion',m.definition_version,'subjectKind',m.subject_kind,
     'subjectId',m.subject_id,'window',m.window_code,'periodStart',m.period_start,'periodEnd',m.period_end,
     'valueState',m.value_state,'numericValue',m.numeric_value::text,'currencyCode',m.currency_code,
     'confidenceState',m.confidence_state,'estimated',m.estimated,'oldestSourceTime',m.oldest_source_time,
     'freshnessSeconds',m.freshness_seconds,'inputDigest',m.input_digest,'computedAt',m.computed_at,
     'current',m.is_current) payload FROM metrics m
   UNION ALL SELECT 2,i.id,jsonb_build_object('schemaVersion',1,'recordType','METRIC_INPUT',
     'metricValueId',i.metric_value_id,'referenceKind',i.reference_kind,'referenceId',i.reference_id)
     FROM mart.metric_input_reference i JOIN metrics m ON m.id=i.metric_value_id
   UNION ALL SELECT 3,f.id,jsonb_build_object('schemaVersion',1,'recordType','FINDING','findingId',f.id,
     'ruleCode',f.rule_code,'ruleVersion',f.rule_version,'subjectKind',f.subject_kind,'subjectId',f.subject_id,
     'window',f.window_code,'periodStart',f.period_start,'periodEnd',f.period_end,'outcome',f.outcome,
     'severity',f.severity,'declineReason',f.decline_reason,'inputDigest',f.input_digest,
     'blocksExecution',r.blocks_execution,'evaluatedAt',f.evaluated_at)
     FROM findings f JOIN mart.diagnosis_rule r ON r.rule_code=f.rule_code AND r.rule_version=f.rule_version
   UNION ALL SELECT 4,i.id,jsonb_build_object('schemaVersion',1,'recordType','FINDING_INPUT',
     'findingId',i.finding_id,'metricValueId',i.metric_value_id,'role',i.role)
     FROM mart.diagnosis_finding_input i JOIN findings f ON f.id=i.finding_id
     JOIN metrics m ON m.id=i.metric_value_id
   -- Refuse a finding with an input outside this authorized snapshot instead
   -- of silently dropping an input and publishing incomplete evidence.
   UNION ALL SELECT 0,j.id,NULL::jsonb WHERE EXISTS (
     SELECT 1 FROM mart.diagnosis_finding_input i JOIN findings f ON f.id=i.finding_id
       WHERE NOT EXISTS (SELECT 1 FROM metrics m WHERE m.id=i.metric_value_id))
 ), bounded AS (SELECT * FROM records ORDER BY family,id LIMIT 1000001)
 INSERT INTO mart.diagnostic_export_row(export_id,ordinal,payload)
 SELECT p_id,row_number() OVER (ORDER BY family,id),payload::text FROM bounded;
 SELECT count(*),coalesce(sum(byte_length),0) INTO n,bytes FROM mart.diagnostic_export_row WHERE export_id=p_id;
 IF n>1000000 OR bytes>268435456 THEN RAISE EXCEPTION 'export limit exceeded' USING ERRCODE='MO082'; END IF;
 UPDATE ops.diagnostic_export SET snapshot_at=taken_at,row_count=n,byte_length=bytes WHERE id=p_id RETURNING * INTO j;
 PERFORM ops.audit_diagnostic_export(j,'SNAPSHOTTED');
END $$;
REVOKE ALL ON FUNCTION ops.snapshot_diagnostic_export(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.snapshot_diagnostic_export(uuid,uuid) TO marketops_app;

CREATE FUNCTION ops.renew_diagnostic_export(p_id uuid,p_token uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 PERFORM ops.require_diagnostic_export_lease(p_id,p_token);
 UPDATE ops.diagnostic_export SET lease_until=least(deadline_at,statement_timestamp()+interval '2 minutes') WHERE id=p_id;
END $$;
REVOKE ALL ON FUNCTION ops.renew_diagnostic_export(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.renew_diagnostic_export(uuid,uuid) TO marketops_app;

CREATE FUNCTION ops.record_diagnostic_export_part(p_id uuid,p_token uuid,p_first integer,p_last integer,p_content uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE j ops.diagnostic_export; c raw.raw_content; prior ops.diagnostic_export_part;
 next_first integer; next_part integer; n integer; bytes bigint; body text;
BEGIN
 j:=ops.require_diagnostic_export_lease(p_id,p_token);
 IF j.snapshot_at IS NULL OR p_first IS NULL OR p_last IS NULL OR p_first<1 OR p_last<p_first OR p_last>j.row_count THEN
   RAISE EXCEPTION 'invalid export range' USING ERRCODE='MO039'; END IF;
 SELECT * INTO c FROM raw.raw_content WHERE id=p_content;
 IF NOT FOUND OR c.byte_length>4194304 THEN RAISE EXCEPTION 'invalid export custody' USING ERRCODE='MO082'; END IF;
 SELECT * INTO prior FROM ops.diagnostic_export_part WHERE export_id=p_id AND first_ordinal=p_first;
 IF FOUND THEN
   IF prior.last_ordinal=p_last AND prior.content_id=p_content THEN RETURN; END IF;
   RAISE EXCEPTION 'export part conflict' USING ERRCODE='MO061';
 END IF;
 SELECT coalesce(max(last_ordinal),0)+1,coalesce(max(part_number),0)+1 INTO next_first,next_part
   FROM ops.diagnostic_export_part WHERE export_id=p_id;
 IF p_first<>next_first OR next_part>64 THEN RAISE EXCEPTION 'invalid export sequence' USING ERRCODE='MO082'; END IF;
 SELECT count(*),sum(byte_length) INTO n,bytes FROM mart.diagnostic_export_row
   WHERE export_id=p_id AND ordinal BETWEEN p_first AND p_last;
 IF n<>p_last-p_first+1 OR bytes IS NULL OR bytes>4194304 OR bytes<>c.byte_length THEN
   RAISE EXCEPTION 'invalid export bytes' USING ERRCODE='MO082'; END IF;
 SELECT string_agg(payload||chr(10),'' ORDER BY ordinal) INTO body FROM mart.diagnostic_export_row
   WHERE export_id=p_id AND ordinal BETWEEN p_first AND p_last;
 IF encode(sha256(convert_to(body,'UTF8')),'hex')<>c.hash_value OR c.hash_algorithm<>'SHA256' THEN
   RAISE EXCEPTION 'export custody mismatch' USING ERRCODE='MO083'; END IF;
 INSERT INTO ops.diagnostic_export_part VALUES(p_id,next_part,p_first,p_last,n,bytes,c.hash_value,c.id);
 PERFORM ops.audit_diagnostic_export(j,'PART_RECORDED');
END $$;
REVOKE ALL ON FUNCTION ops.record_diagnostic_export_part(uuid,uuid,integer,integer,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.record_diagnostic_export_part(uuid,uuid,integer,integer,uuid) TO marketops_app;

CREATE FUNCTION ops.complete_diagnostic_export(p_id uuid,p_token uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET timezone='UTC' AS $$
DECLARE j ops.diagnostic_export; document jsonb; n bigint; bytes bigint;
BEGIN
 j:=ops.require_diagnostic_export_lease(p_id,p_token);
 SELECT coalesce(sum(row_count),0),coalesce(sum(byte_length),0) INTO n,bytes FROM ops.diagnostic_export_part WHERE export_id=p_id;
 IF j.snapshot_at IS NULL OR n<>j.row_count OR bytes<>j.byte_length THEN
   RAISE EXCEPTION 'incomplete export' USING ERRCODE='MO083'; END IF;
 SELECT jsonb_build_object('schemaVersion',1,'format','marketops-diagnostic-ndjson-v1','exportId',j.id,
   'storeId',j.store_id,'window',j.window_code,'snapshotAt',j.snapshot_at,'rowCount',j.row_count,'byteLength',j.byte_length,
   'parts',coalesce(jsonb_agg(jsonb_build_object('partNumber',part_number,'firstOrdinal',first_ordinal,
     'lastOrdinal',last_ordinal,'rowCount',row_count,'byteLength',byte_length,'sha256',sha256) ORDER BY part_number),'[]'::jsonb))
   INTO document FROM ops.diagnostic_export_part WHERE export_id=p_id;
 UPDATE ops.diagnostic_export SET state='SUCCEEDED',manifest=document,manifest_sha256=encode(sha256(convert_to(document::text,'UTF8')),'hex'),
   expires_at=statement_timestamp()+interval '1 hour',lease_token=NULL,lease_until=NULL,failure_code=NULL WHERE id=p_id RETURNING * INTO j;
 PERFORM ops.audit_diagnostic_export(j,'COMPLETED');
END $$;
REVOKE ALL ON FUNCTION ops.complete_diagnostic_export(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.complete_diagnostic_export(uuid,uuid) TO marketops_app;

CREATE FUNCTION ops.fail_diagnostic_export(p_id uuid,p_token uuid,p_code text,p_retry boolean)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE j ops.diagnostic_export;
BEGIN
 SELECT * INTO j FROM ops.diagnostic_export WHERE id=p_id FOR UPDATE;
 IF NOT FOUND OR j.state<>'RUNNING' OR p_token IS NULL OR j.lease_token IS DISTINCT FROM p_token
   OR j.lease_until<=clock_timestamp() THEN RAISE EXCEPTION 'export lease lost' USING ERRCODE='MO081'; END IF;
 IF p_code IS NULL OR p_code NOT IN ('AUTHORIZATION_REVOKED','LIMIT_EXCEEDED','STORAGE_UNAVAILABLE','DATABASE_UNAVAILABLE','INVALID_SNAPSHOT')
   OR p_retry IS NULL OR (p_retry AND p_code NOT IN ('STORAGE_UNAVAILABLE','DATABASE_UNAVAILABLE')) THEN
   RAISE EXCEPTION 'invalid export failure' USING ERRCODE='MO039'; END IF;
 UPDATE ops.diagnostic_export SET state=CASE WHEN p_retry AND attempt_count<5 AND deadline_at>statement_timestamp() THEN 'QUEUED' ELSE 'FAILED' END,
   failure_code=p_code,lease_token=NULL,lease_until=NULL,next_attempt_at=statement_timestamp()+interval '5 seconds'
   WHERE id=p_id RETURNING * INTO j;
 PERFORM ops.audit_diagnostic_export(j,'ATTEMPT_FAILED');
END $$;
REVOKE ALL ON FUNCTION ops.fail_diagnostic_export(uuid,uuid,text,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.fail_diagnostic_export(uuid,uuid,text,boolean) TO marketops_app;

-- The event says authorized/verified, never that the browser saved a file.
CREATE FUNCTION ops.authorize_diagnostic_export_read(p_id uuid,p_actor uuid,p_part integer,p_verified boolean)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE j ops.diagnostic_export;
BEGIN
 SELECT * INTO j FROM ops.diagnostic_export WHERE id=p_id;
 IF NOT FOUND OR j.requester_id IS DISTINCT FROM p_actor OR NOT iam.diagnostic_export_allowed(p_actor,j.store_id) THEN
   RAISE EXCEPTION 'export scope denied' USING ERRCODE='MO064'; END IF;
 IF j.state<>'SUCCEEDED' OR j.expires_at<=statement_timestamp() THEN
   RAISE EXCEPTION 'export unavailable' USING ERRCODE='MO084'; END IF;
 IF p_part IS NULL OR p_part<0 OR p_verified IS NULL OR (p_part<>0 AND NOT EXISTS
   (SELECT 1 FROM ops.diagnostic_export_part WHERE export_id=p_id AND part_number=p_part)) THEN
   RAISE EXCEPTION 'invalid export part' USING ERRCODE='MO039'; END IF;
 PERFORM ops.audit_diagnostic_export(j,CASE WHEN p_part=0 THEN 'MANIFEST_AUTHORIZED'
   WHEN p_verified THEN 'PART_VERIFIED' ELSE 'PART_AUTHORIZED' END);
END $$;
REVOKE ALL ON FUNCTION ops.authorize_diagnostic_export_read(uuid,uuid,integer,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.authorize_diagnostic_export_read(uuid,uuid,integer,boolean) TO marketops_app;
