-- Attach the epoch triggers that make control-plane advancement structural.
--
-- The control epoch is only a guard if every table that can change a control
-- fact advances it. Application code cannot be that guarantee: a worker that
-- forgets one table produces no error, only a grant that survives a revocation.
-- The guarantee therefore lives in the database, on the tables themselves, and
-- an arbitrary SQL client cannot get around it.
--
-- Three properties decide the shape of what follows.
--
-- 1. PostgreSQL 18 refuses to create one trigger that covers several events and
--    also requests transition relations ("transition tables cannot be specified
--    for triggers with more than one event"). OLD TABLE is legal only for
--    DELETE and UPDATE, NEW TABLE only for INSERT and UPDATE. Each table
--    therefore gets exactly three triggers, one per event, each declaring only
--    the relations its event actually has.
--
-- 2. A row-level trigger fires once per row in whatever order the plan produced
--    them and cannot see the rest of the statement. Ordering the epoch writes
--    -- which is what keeps two bulk statements from deadlocking against each
--    other -- is only expressible when the whole set is visible at once, so
--    every trigger here is FOR EACH STATEMENT over a transition relation.
--
-- 3. A statement that matches no row still fires its statement trigger, with an
--    empty transition relation. Empty in, empty out: nothing changed, so no
--    epoch advances and no in-flight grant is invalidated. That falls out of
--    set semantics rather than needing a special case.
--
-- The triggers are generated from one routing map instead of being written out
-- twenty-two times. The map is the only place a table's scope is stated in
-- executable form; V0007's route inventory states the same fact as a contract,
-- and an architecture test compares the two. A hand-copied routing expression
-- is exactly the kind of error that only appears at runtime, as an undefined
-- column on the first write to a table nobody exercised.

-- ---------------------------------------------------------------------------
-- Backfill
-- ---------------------------------------------------------------------------
-- Scopes that already have rows need an epoch before the first acquisition can
-- lock one; a missing row is a fail-closed condition, not a default of zero.
-- Job scopes are not backfilled here because V0007's job trigger creates them,
-- and no job exists yet.
INSERT INTO platform.control_epoch (scope_kind, scope_id, epoch)
SELECT 'ORGANIZATION', id, 1 FROM core.organization
UNION ALL
SELECT 'MARKETPLACE_ACCOUNT', id, 1 FROM core.marketplace_account
UNION ALL
SELECT 'SERVICE_ACCOUNT', id, 1 FROM iam.service_account
ON CONFLICT (scope_kind, scope_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Trigger generation
-- ---------------------------------------------------------------------------

DO $generate$
DECLARE
    -- schema, table, route kind, and the query that maps the transition
    -- relation `rel` onto the scopes that statement reached.
    route record;
    event  record;
    guard_block text;
    body        text;
BEGIN
    FOR route IN
        SELECT * FROM (VALUES
            -- ---- ownership chain -------------------------------------------
            ('core', 'organization', 'SCOPE',
             'SELECT ROW(''ORGANIZATION'', rel.id)::platform.control_scope FROM rel'),
            ('core', 'legal_entity', 'SCOPE',
             'SELECT ROW(''ORGANIZATION'', rel.organization_id)::platform.control_scope FROM rel'),
            ('core', 'warehouse', 'SCOPE',
             'SELECT ROW(''ORGANIZATION'', rel.organization_id)::platform.control_scope FROM rel'),
            ('core', 'marketplace_account', 'SCOPE',
             'SELECT ROW(''MARKETPLACE_ACCOUNT'', rel.id)::platform.control_scope FROM rel'),
            ('core', 'store', 'SCOPE',
             'SELECT ROW(''MARKETPLACE_ACCOUNT'', rel.marketplace_account_id)::platform.control_scope FROM rel'),
            -- These two carry organization_id and store_id but no account
            -- column. The account is resolved through core.store; naming a
            -- column the table does not have would fail only at run time, on
            -- the first write, which is why the generated bodies are compiled
            -- against the real relations below.
            ('core', 'store_warehouse_link', 'SCOPE',
             'SELECT ROW(''MARKETPLACE_ACCOUNT'', store.marketplace_account_id)::platform.control_scope'
             || ' FROM rel JOIN core.store AS store ON store.id = rel.store_id'),
            ('core', 'store_fulfillment_declaration', 'SCOPE',
             'SELECT ROW(''MARKETPLACE_ACCOUNT'', store.marketplace_account_id)::platform.control_scope'
             || ' FROM rel JOIN core.store AS store ON store.id = rel.store_id'),

            -- ---- execution subject -----------------------------------------
            ('iam', 'service_account', 'SCOPE',
             'SELECT ROW(''SERVICE_ACCOUNT'', rel.id)::platform.control_scope FROM rel'),
            ('iam', 'service_account_scope_grant', 'SCOPE',
             'SELECT ROW(''SERVICE_ACCOUNT'', rel.service_account_id)::platform.control_scope FROM rel'),
            ('iam', 'service_account_allowed_source', 'SCOPE',
             'SELECT ROW(''SERVICE_ACCOUNT'', rel.service_account_id)::platform.control_scope FROM rel'),

            -- ---- credential identity and selection -------------------------
            ('platform', 'credential_metadata', 'SCOPE',
             'SELECT ROW(''MARKETPLACE_ACCOUNT'', rel.marketplace_account_id)::platform.control_scope FROM rel'),
            ('platform', 'credential_store_scope', 'SCOPE',
             'SELECT ROW(''MARKETPLACE_ACCOUNT'', rel.marketplace_account_id)::platform.control_scope FROM rel'),
            -- Exactly one of the two subject columns is set, enforced by the
            -- table's own check constraint.
            ('platform', 'capability_subject_status', 'SCOPE',
             'SELECT ROW(''MARKETPLACE_ACCOUNT'', COALESCE(rel.marketplace_account_id,'
             || ' (SELECT store.marketplace_account_id FROM core.store AS store'
             || '   WHERE store.id = rel.store_id)))::platform.control_scope FROM rel'),

            -- ---- the job itself --------------------------------------------
            ('platform', 'ingestion_job', 'SCOPE',
             'SELECT ROW(''JOB'', rel.id)::platform.control_scope FROM rel'),

            -- ---- platform-wide registry facts ------------------------------
            ('platform', 'platform_capability', 'PLATFORM_FANOUT',
             'SELECT ROW(''JOB'', job.id)::platform.control_scope FROM rel'
             || ' JOIN platform.ingestion_job AS job ON job.platform_code = rel.platform_code'),
            ('platform', 'platform_endpoint', 'PLATFORM_FANOUT',
             'SELECT ROW(''JOB'', job.id)::platform.control_scope FROM rel'
             || ' JOIN platform.ingestion_job AS job ON job.platform_code = rel.platform_code'),
            ('platform', 'platform_permission_requirement', 'PLATFORM_FANOUT',
             'SELECT ROW(''JOB'', job.id)::platform.control_scope FROM rel'
             || ' JOIN platform.ingestion_job AS job ON job.platform_code = rel.platform_code'),

            -- ---- reference vocabularies every job may consume --------------
            ('core', 'marketplace_platform', 'GLOBAL_FANOUT',
             'SELECT ROW(''JOB'', job.id)::platform.control_scope'
             || ' FROM rel CROSS JOIN platform.ingestion_job AS job'),
            ('core', 'fulfillment_mode', 'GLOBAL_FANOUT',
             'SELECT ROW(''JOB'', job.id)::platform.control_scope'
             || ' FROM rel CROSS JOIN platform.ingestion_job AS job'),
            ('iam', 'permission_kind', 'GLOBAL_FANOUT',
             'SELECT ROW(''JOB'', job.id)::platform.control_scope'
             || ' FROM rel CROSS JOIN platform.ingestion_job AS job'),
            ('platform', 'credential_purpose', 'GLOBAL_FANOUT',
             'SELECT ROW(''JOB'', job.id)::platform.control_scope'
             || ' FROM rel CROSS JOIN platform.ingestion_job AS job'),
            -- A flag's scope_kind decides which jobs it reaches. GLOBAL reaches
            -- every job, which is why the whole platform set is guarded.
            ('platform', 'feature_flag', 'GLOBAL_FANOUT',
             'SELECT ROW(''JOB'', job.id)::platform.control_scope FROM rel'
             || ' JOIN platform.ingestion_job AS job ON ('
             || '     rel.scope_kind = ''GLOBAL'''
             || '  OR (rel.scope_kind = ''PLATFORM'' AND job.platform_code = rel.platform_code)'
             || '  OR (rel.scope_kind = ''MARKETPLACE_ACCOUNT'''
             || '      AND job.marketplace_account_id = rel.marketplace_account_id)'
             || '  OR (rel.scope_kind = ''STORE'' AND job.marketplace_account_id ='
             || '      (SELECT store.marketplace_account_id FROM core.store AS store'
             || '        WHERE store.id = rel.store_id))'
             || '  OR (rel.scope_kind = ''CAPABILITY'' AND job.platform_code ='
             || '      (SELECT capability.platform_code FROM platform.platform_capability AS capability'
             || '        WHERE capability.id = rel.capability_id)))')
        ) AS mapping(schema_name, table_name, route_kind, scope_sql)
        ORDER BY schema_name, table_name
    LOOP
        -- A fan-out enumerates the job set, so it must first take the guard
        -- that serialises it against job creation. Reading the set without the
        -- guard is the phantom this whole mechanism exists to prevent, so the
        -- acquisition is emitted here rather than left to the routing author.
        --
        -- A scope route touches no membership set and takes no guard: adding an
        -- exclusive lock to those paths would serialise ordinary metadata
        -- administration for no gain.
        guard_block := CASE route.route_kind
            WHEN 'PLATFORM_FANOUT' THEN
                'WITH rel AS (%1$s) SELECT array_agg(DISTINCT rel.platform_code)'
                || ' INTO guarded FROM rel;'
                || ' IF guarded IS NOT NULL THEN'
                || '   PERFORM platform.acquire_platform_job_set_guard(guarded);'
                || ' END IF;'
            WHEN 'GLOBAL_FANOUT' THEN
                'IF EXISTS (SELECT 1 FROM %2$s) THEN'
                || '   SELECT array_agg(code) INTO guarded FROM core.marketplace_platform;'
                || '   PERFORM platform.acquire_platform_job_set_guard(guarded);'
                || ' END IF;'
            ELSE ''
        END;

        FOR event IN
            SELECT * FROM (VALUES
                ('insert', 'ai', 'AFTER INSERT', 'REFERENCING NEW TABLE AS n',
                 'SELECT * FROM n', 'n'),
                ('update', 'au', 'AFTER UPDATE', 'REFERENCING OLD TABLE AS o NEW TABLE AS n',
                 'SELECT * FROM n UNION ALL SELECT * FROM o', 'n'),
                ('delete', 'ad', 'AFTER DELETE', 'REFERENCING OLD TABLE AS o',
                 'SELECT * FROM o', 'o')
            ) AS events(suffix, trigger_suffix, timing, referencing, rel_sql, exists_rel)
        LOOP
            -- An update may move a row from one scope to another, so the update
            -- wrapper reads both transition relations: advancing only the new
            -- scope would leave a live grant on the old one untouched.
            body := format(
                $body$
                DECLARE
                    guarded text[];
                    scopes  platform.control_scope[];
                BEGIN
                    %3$s
                    WITH rel AS (%1$s)
                    SELECT array_agg(DISTINCT mapped.scope) INTO scopes
                      FROM (%4$s) AS mapped(scope);
                    IF scopes IS NOT NULL THEN
                        PERFORM platform.advance_control_epochs(scopes);
                    END IF;
                    RETURN NULL;
                END;
                $body$,
                event.rel_sql,
                event.exists_rel,
                format(guard_block, event.rel_sql, event.exists_rel),
                route.scope_sql);

            EXECUTE format(
                'CREATE FUNCTION platform.%I() RETURNS trigger LANGUAGE plpgsql AS %L',
                route.table_name || '_advance_control_epoch_' || event.suffix,
                body);

            EXECUTE format(
                'CREATE TRIGGER %I %s ON %I.%I %s FOR EACH STATEMENT'
                || ' EXECUTE FUNCTION platform.%I()',
                route.table_name || '_control_epoch_' || event.trigger_suffix,
                event.timing,
                route.schema_name,
                route.table_name,
                event.referencing,
                route.table_name || '_advance_control_epoch_' || event.suffix);
        END LOOP;
    END LOOP;
END;
$generate$;

-- ---------------------------------------------------------------------------
-- As-built self-check
-- ---------------------------------------------------------------------------
-- The migration proves its own completeness before it commits, so a routing
-- entry that was added to one of the two maps and not the other cannot reach a
-- deployed database. The same comparison is repeated as an architecture test,
-- because a check that only ever runs at migration time stops protecting a
-- database that was migrated before the check existed.
DO $verify$
DECLARE
    routed_tables   bigint;
    routed_triggers bigint;
    mismatch        text;
BEGIN
    SELECT count(*) INTO routed_tables
      FROM platform.control_route_inventory
     WHERE route_kind <> 'NO_ROUTE';

    SELECT count(*) INTO routed_triggers
      FROM pg_trigger
     WHERE NOT tgisinternal
       AND tgname LIKE '%\_control\_epoch\_a%';

    IF routed_triggers <> routed_tables * 3 THEN
        RAISE EXCEPTION
            'expected 3 epoch triggers for each of % routed tables, found %',
            routed_tables, routed_triggers
            USING ERRCODE = 'MO004';
    END IF;

    SELECT string_agg(schema_name || '.' || table_name, ', ')
      INTO mismatch
      FROM platform.control_route_inventory AS inventory
     WHERE inventory.route_kind <> 'NO_ROUTE'
       AND NOT EXISTS (
               SELECT 1
                 FROM pg_trigger AS trg
                 JOIN pg_class AS rel ON rel.oid = trg.tgrelid
                 JOIN pg_namespace AS nsp ON nsp.oid = rel.relnamespace
                WHERE NOT trg.tgisinternal
                  AND nsp.nspname = inventory.schema_name
                  AND rel.relname = inventory.table_name
                  AND trg.tgname = inventory.table_name || '_control_epoch_ai');

    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'routed tables without epoch triggers: %', mismatch
            USING ERRCODE = 'MO004';
    END IF;
END;
$verify$;
