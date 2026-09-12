package com.mimococo.marketops;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A complete fictional listing graph: two listings on the fictional platform,
 * an Owner-published calibration and allowance, a described and verified
 * description capability, and two actions approved up to the point where the
 * launch function is the only way forward. No production platform, credential
 * or listing is named; every write beyond seeding goes through the functions
 * the application role may call.
 */
public final class ListingConversionFixture {

    private static final Map<String, String> TEMPLATE = Map.ofEntries(
            Map.entry("organization", "8689c119-8fa0-50b7-8ba2-f9bf3039d336"),
            Map.entry("legalEntity", "8f17abcd-c8f2-5dbb-af7d-e0dd234dc59a"),
            Map.entry("account", "2be0ab6f-af56-56cf-b332-700dd591a96e"),
            Map.entry("store", "f5eced9a-7d0a-5d65-8942-8d1efeabf41a"),
            Map.entry("product", "40c77853-4a6b-5909-b79c-fdeaadfddad6"),
            Map.entry("productVariant", "1484c926-777f-5205-8893-941965dbb38a"),
            Map.entry("listing", "aa14dd95-b455-5db2-924c-8a3972e6f9d2"),
            Map.entry("listingVariant", "7d693f80-2ad3-570d-8f47-e589af7b5598"),
            Map.entry("executorUser", "0998716b-6f78-56da-bbea-554b20cfd093"),
            Map.entry("verifierUser", "8ec704dd-3aa5-529c-93db-def4bbf39260"),
            Map.entry("ownerUser", "9264ceb0-c29a-5837-9339-c84bfe73a444"),
            Map.entry("provider", "bdd07a92-b359-552a-81c9-46e654657965"),
            Map.entry("provenance", "0e994c7c-409d-506f-a310-f256f77d0920"),
            Map.entry("calculationRun", "4d57d2d4-daa5-519a-8c7b-1a00cfa924ba"),
            Map.entry("calibrationPackage", "5c000000-0000-5000-8000-000000000001"),
            Map.entry("allowanceConcurrent", "5c000000-0000-5000-8000-000000000002"),
            Map.entry("allowanceVariants", "5c000000-0000-5000-8000-000000000003"),
            Map.entry("gateAuthority", "5c000000-0000-5000-8000-000000000004"),
            Map.entry("observationOne", "5c000000-0000-5000-8000-000000000005"),
            Map.entry("affectedSetOne", "5c000000-0000-5000-8000-000000000006"),
            Map.entry("healthOne", "5c000000-0000-5000-8000-000000000007"),
            Map.entry("candidateOne", "5c000000-0000-5000-8000-000000000008"),
            Map.entry("recommendationOne", "5c000000-0000-5000-8000-000000000009"),
            Map.entry("actionOne", "5c000000-0000-5000-8000-00000000000a"),
            Map.entry("guardrailOne", "5c000000-0000-5000-8000-00000000000c"),
            Map.entry("approvalOne", "5c000000-0000-5000-8000-00000000000d"),
            Map.entry("bindingOne", "5c000000-0000-5000-8000-00000000000e"),
            Map.entry("planOne", "5c000000-0000-5000-8000-00000000000f"),
            Map.entry("capability", "5c000000-0000-5000-8000-000000000010"),
            Map.entry("endpointApply", "5c000000-0000-5000-8000-000000000011"),
            Map.entry("endpointReadback", "5c000000-0000-5000-8000-000000000012"),
            Map.entry("endpointRestore", "5c000000-0000-5000-8000-000000000013"),
            Map.entry("credential", "5c000000-0000-5000-8000-000000000014"),
            Map.entry("executionGuardrailOne", "5c000000-0000-5000-8000-00000000001c"),
            Map.entry("productTwo", "5c000000-0000-5000-8000-000000000020"),
            Map.entry("productVariantTwo", "5c000000-0000-5000-8000-000000000021"),
            Map.entry("listingTwo", "5c000000-0000-5000-8000-000000000022"),
            Map.entry("listingVariantTwo", "5c000000-0000-5000-8000-000000000023"),
            Map.entry("observationTwo", "5c000000-0000-5000-8000-000000000025"),
            Map.entry("affectedSetTwo", "5c000000-0000-5000-8000-000000000026"),
            Map.entry("healthTwo", "5c000000-0000-5000-8000-000000000027"),
            Map.entry("candidateTwo", "5c000000-0000-5000-8000-000000000028"),
            Map.entry("recommendationTwo", "5c000000-0000-5000-8000-000000000029"),
            Map.entry("actionTwo", "5c000000-0000-5000-8000-00000000002a"),
            Map.entry("guardrailTwo", "5c000000-0000-5000-8000-00000000002c"),
            Map.entry("approvalTwo", "5c000000-0000-5000-8000-00000000002d"),
            Map.entry("bindingTwo", "5c000000-0000-5000-8000-00000000002e"),
            Map.entry("planTwo", "5c000000-0000-5000-8000-00000000002f"));

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PRIOR_TEXT_ONE = "Прежнее описание товара для покупателя";
    static final String TARGET_TEXT_ONE = "Новое описание товара для покупателя с точными характеристиками";

    final DataSource migration;
    final DataSource application;
    final DataSource admin;
    final JdbcClient seed;
    final JdbcClient app;
    final AdvertisingR1Fixture.Graph graph;

    static final String MEANING_ORDINARY="""
            {"model":"LC_MEANING_CONDITIONS_1","description":[{"code":"LIMITED_CLARIFICATION","condition":"Ограниченное уточнение без изменения свойств, применения, ограничений или предупреждений."}],"promotion":[{"code":"LIMITED_SIMPLE_PROMOTION","condition":"Простая акция в принятой ограниченной коммерческой области без нового существенного обязательства."}]}
            """.strip();
    static final String MEANING_MATERIAL="""
            {"model":"LC_MEANING_CONDITIONS_1","description":[{"code":"SAFETY_OR_PRODUCT_FACT_CHANGE","condition":"Изменены отрицание, ограничения, безопасность, существенные свойства или назначение товара."}],"promotion":[{"code":"MATERIAL_COMMERCIAL_CHANGE","condition":"Существенно изменены цена, плательщик расходов, заморозка, участие, сочетание, срок или остаточные обязательства."}]}
            """.strip();

    public ListingConversionFixture(DataSource migration, DataSource application, DataSource admin) throws Exception {
        this(migration,application,admin,false);
    }

    ListingConversionFixture(DataSource migration,DataSource application,DataSource admin,boolean emptyPrior) throws Exception {
        this(migration,application,admin,emptyPrior,1);
    }

    ListingConversionFixture(DataSource migration,DataSource application,DataSource admin,
                             boolean emptyPrior,int nativeVariantsPerListing) throws Exception {
        this(migration,application,admin,emptyPrior,nativeVariantsPerListing,null);
    }

    ListingConversionFixture(DataSource migration,DataSource application,DataSource admin,
                             boolean emptyPrior,int nativeVariantsPerListing,String allowanceAxes) throws Exception {
        if (nativeVariantsPerListing<1 || nativeVariantsPerListing>4096) throw new IllegalArgumentException("finite fixture scope");
        this.migration = migration;
        this.application = application;
        this.admin = admin;
        this.seed = JdbcClient.create(migration);
        this.app = JdbcClient.create(application);
        AdvertisingR1Fixture.Graph base = AdvertisingR1Fixture.seedManual(migration);
        Map<String, UUID> named = new HashMap<>(base.ids());
        Map<String, String> replacement = new HashMap<>();
        TEMPLATE.forEach((name, template) -> {
            UUID actual = named.computeIfAbsent(name, ignored -> UUID.randomUUID());
            replacement.put(template, actual.toString());
        });
        String source = new ClassPathResource("listing/lc-fictional-positive.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        boolean structuredMeaning=Boolean.TRUE.equals(seed.sql("SELECT to_regprocedure('core.lc_meaning_catalog(uuid,text)') IS NOT NULL")
                .query(Boolean.class).single());
        if (structuredMeaning) {
            source=source.replace("'ORDINARY_TRIGGER_CONTENT',0.100000,NULL,NULL,'RATIO'",
                    "'ORDINARY_TRIGGER_CONTENT',NULL,NULL,'"+MEANING_ORDINARY+"','CONDITIONS'")
                .replace("'MATERIAL_TRIGGER_CONTENT',0.400000,NULL,NULL,'RATIO'",
                    "'MATERIAL_TRIGGER_CONTENT',NULL,NULL,'"+MEANING_MATERIAL+"','CONDITIONS'");
        }
        if (emptyPrior) source=source.replace(PRIOR_TEXT_ONE,"");
        if (allowanceAxes!=null) source=source.replace(
                "'[\"CONCURRENT_LISTINGS\", \"AFFECTED_VARIANTS\"]'",
                "'"+allowanceAxes.replace("'","''")+"'");
        if (nativeVariantsPerListing>1) {
            // Expand real native identities and mappings BEFORE exact affected sets, plans and approvals are frozen.
            String expansion="""
                INSERT INTO core.platform_listing_variant(id,organization_id,platform_listing_id,native_variant_key,
                  first_seen_at,last_seen_at,status,created_at,updated_at,version)
                SELECT md5(v.id::text||':'||n)::uuid,v.organization_id,v.platform_listing_id,
                  'fixture-expanded-'||n,now()-interval '1 day',now(),'OBSERVED',now()-interval '1 day',now(),0
                FROM core.platform_listing_variant v CROSS JOIN generate_series(2,%d) n
                WHERE v.id IN ('7d693f80-2ad3-570d-8f47-e589af7b5598','5c000000-0000-5000-8000-000000000023');
                INSERT INTO core.listing_mapping(id,organization_id,platform_listing_variant_id,product_variant_id,
                  effective_from,status,confirmed_by_user_id,reason,created_at,updated_at,version)
                SELECT gen_random_uuid(),m.organization_id,md5(m.platform_listing_variant_id::text||':'||n)::uuid,
                  m.product_variant_id,now()-interval '1 day','ACTIVE',m.confirmed_by_user_id,
                  'Synthetic native-member allowance fixture',now()-interval '1 day',now(),0
                FROM core.listing_mapping m CROSS JOIN generate_series(2,%d) n
                WHERE m.platform_listing_variant_id IN ('7d693f80-2ad3-570d-8f47-e589af7b5598','5c000000-0000-5000-8000-000000000023')
                  AND m.status='ACTIVE';
                """.formatted(nativeVariantsPerListing,nativeVariantsPerListing);
            source=source.replace("INSERT INTO core.lc_affected_set(",expansion+"INSERT INTO core.lc_affected_set(");
            source=source.replace("ARRAY['7d693f80-2ad3-570d-8f47-e589af7b5598']::uuid[]",
                    "(SELECT array_agg(id ORDER BY id) FROM core.platform_listing_variant WHERE platform_listing_id='aa14dd95-b455-5db2-924c-8a3972e6f9d2')");
            source=source.replace("ARRAY['5c000000-0000-5000-8000-000000000023']::uuid[]",
                    "(SELECT array_agg(id ORDER BY id) FROM core.platform_listing_variant WHERE platform_listing_id='5c000000-0000-5000-8000-000000000022')");
        }
        if (Boolean.TRUE.equals(seed.sql("SELECT to_regclass('core.platform_listing_scope_observation') IS NOT NULL")
                .query(Boolean.class).single())) {
            source=source.replace("UPDATE core.lc_calibration_package SET status = 'ACTIVE'", """
                UPDATE core.lc_calibration_value SET value_json=
                 '{"nativeScope":{"MANUAL_ENTRY":{"maximumAgeSeconds":3600},"MARKETPLACE_RAW":{"maximumAgeSeconds":3600}},"materialityExposure":{"maximumVerificationAgeSeconds":3600,"maximumPeriodEndAgeSeconds":86400}}'
                 WHERE package_id='5c000000-0000-5000-8000-000000000001' AND category_code='FRESHNESS_RULE';
                UPDATE core.lc_calibration_value SET window_days=30
                 WHERE package_id='5c000000-0000-5000-8000-000000000001'
                   AND category_code IN ('ORDINARY_TRIGGER_EXPOSURE','MATERIAL_TRIGGER_EXPOSURE');
                UPDATE core.lc_calibration_package SET status = 'ACTIVE'
                """);
            source=source.replace("INSERT INTO core.lc_affected_set(","""
                INSERT INTO ops.lc_calibration_governance(package_id,rationale,impact,differences,
                  drafted_by_user_id,drafted_at,draft_digest,validated_by_user_id,validated_at,validated_digest,
                  validation_reference,accepted_by_user_id,accepted_at,accepted_digest,acceptance_reference)
                SELECT id,'Synthetic fixture only','Synthetic scope','Synthetic initial version',
                  '0998716b-6f78-56da-bbea-554b20cfd093',published_at,ops.lc_calibration_digest(id),
                  '8ec704dd-3aa5-529c-93db-def4bbf39260',published_at,ops.lc_calibration_digest(id),'fixture://synthetic/professional',
                  '9264ceb0-c29a-5837-9339-c84bfe73a444',activated_at,ops.lc_calibration_digest(id),'fixture://synthetic/owner'
                 FROM core.lc_calibration_package WHERE id='5c000000-0000-5000-8000-000000000001';
                -- The canonical digest includes the governance rationale, which exists only after INSERT.
                UPDATE ops.lc_calibration_governance SET
                  draft_digest=ops.lc_calibration_digest(package_id),
                  validated_digest=ops.lc_calibration_digest(package_id),accepted_digest=ops.lc_calibration_digest(package_id)
                 WHERE package_id='5c000000-0000-5000-8000-000000000001';
                WITH native_scope_source AS MATERIALIZED (
                 SELECT l.*,gen_random_uuid() AS provenance_id FROM core.platform_listing l
                 WHERE l.id IN ('aa14dd95-b455-5db2-924c-8a3972e6f9d2','5c000000-0000-5000-8000-000000000022')),
                scope_provenance AS (
                 INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note)
                 SELECT provenance_id,organization_id,'MANUAL_ENTRY',now(),now(),
                   '8ec704dd-3aa5-529c-93db-def4bbf39260','Synthetic native enumeration for unrelated launch fixtures'
                 FROM native_scope_source RETURNING id)
                INSERT INTO core.platform_listing_scope_observation(id,organization_id,platform_listing_id,provenance_id,
                  scope_kind,native_scope_key,native_variant_keys,coverage_state,expected_member_count,
                  source_reference,scope_basis_reference,observed_at,recorded_at,verification_expires_at)
                SELECT gen_random_uuid(),s.organization_id,s.id,p.id,'WHOLE_LISTING',s.native_listing_key,
                  ARRAY(SELECT v.native_variant_key FROM core.platform_listing_variant v WHERE v.platform_listing_id=s.id AND v.status='OBSERVED' ORDER BY v.native_variant_key),
                  'COMPLETE',(SELECT count(*)::integer FROM core.platform_listing_variant v WHERE v.platform_listing_id=s.id AND v.status='OBSERVED'),
                  'fixture://synthetic/native-enumeration','fixture://synthetic/whole-listing-scope',now(),now(),now()+interval '1 day'
                FROM native_scope_source s JOIN scope_provenance p ON p.id=s.provenance_id;
                INSERT INTO core.lc_affected_set(
                """);
        }
        if (structuredMeaning) {
            source=source.replace("facts_digest,verdict,reason,reviewed_at)",
                    "facts_digest,verdict,reason,reviewed_at,meaning_assessment,exposure_evidence,content_axis_material,exposure_axis_material,materiality_route)");
            source=source.replace("'ATTESTED','synthetic independent review',now()", """
                    'ATTESTED','synthetic independent review',now(),
                    jsonb_build_object('model','LC_MEANING_REVIEW_1','basisDigest',ops.lc_meaning_review_basis_digest(a.id),
                      'complete',true,'evidenceReference','fixture://synthetic/independent-meaning',
                      'answers',(SELECT jsonb_agg(jsonb_build_object('code',r->>'code','state',
                        CASE WHEN (r->>'axis'='MATERIAL')=a.content_axis_material THEN 'APPLIES' ELSE 'DOES_NOT_APPLY' END,
                        'reason','Synthetic exact-action professional assessment'))
                        FROM jsonb_array_elements(core.lc_meaning_catalog(a.calibration_package_id,a.action_kind)) r)),
                    jsonb_build_object('state','QUALIFIED','fixture','Synthetic authority for unrelated launch and worker fixtures'),
                    a.content_axis_material,a.exposure_axis_material,a.materiality_route
                    """);
        }
        var uuid = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(source);
        String sql = uuid.replaceAll(match -> replacement.computeIfAbsent(match.group(),
                        ignored -> UUID.randomUUID().toString()))
                .replace("SYNTHETIC_AD", base.platform())
                .replace("secret-ref://fictional/never-resolve-content",
                        "secret-ref://fictional/content/" + named.get("credential"));
        try (Connection connection = migration.getConnection()) {
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            connection.commit();
        }
        this.graph = new AdvertisingR1Fixture.Graph(Map.copyOf(named), base.platform());
        // Synthetic preexisting authority for unrelated action/worker fixtures.
        // The calibration lifecycle itself is tested through signed HTTP, never this seed.
        seed.sql("""
                INSERT INTO ops.lc_calibration_governance(package_id,rationale,impact,differences,
                  drafted_by_user_id,drafted_at,draft_digest)
                SELECT id,'Synthetic fixture only','Synthetic scope','Synthetic initial version',
                  :drafter,published_at,repeat('0',64) FROM core.lc_calibration_package WHERE id=:id
                ON CONFLICT (package_id) DO NOTHING
                """).param("id",id("calibrationPackage")).param("drafter",id("executorUser")).update();
        seed.sql("""
                UPDATE ops.lc_calibration_governance g SET draft_digest=ops.lc_calibration_digest(g.package_id),
                  validated_digest=ops.lc_calibration_digest(g.package_id),accepted_digest=ops.lc_calibration_digest(g.package_id),
                  validated_by_user_id=:verifier,accepted_by_user_id=:owner,
                  validated_at=p.published_at,accepted_at=p.activated_at,
                  validation_reference='fixture://synthetic/professional',acceptance_reference='fixture://synthetic/owner'
                FROM core.lc_calibration_package p WHERE p.id=g.package_id AND g.package_id=:id
                """).param("id",id("calibrationPackage")).param("verifier",id("verifierUser")).param("owner",id("ownerUser")).update();
        if (Boolean.TRUE.equals(seed.sql("SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='ops' AND table_name='lc_action' AND column_name='materiality_evidence')")
                .query(Boolean.class).single())) {
            seedRetainedSalesExposure(new java.math.BigDecimal("100"),new java.math.BigDecimal("1000000"));
        }
    }

    /** Published canonical fixtures for the actual exposure consumer; not a caller-supplied classification. */
    void seedRetainedSalesExposure(java.math.BigDecimal memberSales,java.math.BigDecimal storeSales) {
        seed.sql("""
                WITH basis AS MATERIALIZED (
                 SELECT gen_random_uuid() AS run_id,clock_timestamp() AS at,
                   date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS period_end),
                run AS (
                 INSERT INTO mart.calculation_run(id,organization_id,trigger_kind,scope_kind,store_ref_id,
                  window_code,period_start,period_end,definition_set_digest,state,subject_count,value_count,
                  correlation_id,started_at,completed_at,requested_by_user_id)
                 SELECT run_id,:org,'MANUAL','STORE',:store,'D30',period_end-interval '30 days',period_end,
                   repeat('c',64),'SUCCEEDED',1,1,'synthetic-materiality-exposure',at,at,:requester FROM basis RETURNING id),
                subjects AS (
                 SELECT 'STORE'::text AS kind,:store::uuid AS id,:storeSales::numeric AS amount
                 UNION ALL
                 SELECT 'PLATFORM_LISTING_VARIANT',v.id,:memberSales::numeric FROM core.platform_listing_variant v
                   JOIN core.platform_listing l ON l.id=v.platform_listing_id WHERE l.store_id=:store),
                observed_values AS (
                 INSERT INTO mart.metric_value(id,organization_id,calculation_run_id,metric_code,definition_version,
                  subject_kind,subject_id,window_code,period_start,period_end,value_state,numeric_value,currency_code,
                  confidence_state,estimated,oldest_source_time,freshness_seconds,input_digest,computed_at)
                 SELECT gen_random_uuid(),:org,run.id,'RETAINED_NET_SALES',
                   (SELECT max(definition_version) FROM mart.metric_definition WHERE metric_code='RETAINED_NET_SALES'),
                   s.kind,s.id,'D30',b.period_end-interval '30 days',b.period_end,'AVAILABLE',s.amount,'RUB',
                   'CANONICAL_CONFIRMED',false,b.at,0,
                   encode(sha256(convert_to(s.kind||':'||s.id::text||':'||s.amount::text,'UTF8')),'hex'),b.at
                   FROM basis b CROSS JOIN run CROSS JOIN subjects s
                 ON CONFLICT (metric_code,definition_version,subject_kind,subject_id,window_code,period_start,period_end,input_digest)
                   DO NOTHING
                 RETURNING id)
                INSERT INTO mart.metric_input_reference(id,metric_value_id,reference_kind,reference_id)
                 SELECT gen_random_uuid(),id,'FACT_PROVENANCE',:provenance FROM observed_values
                """).param("org",id("organization")).param("store",id("store"))
                .param("memberSales",memberSales).param("storeSales",storeSales).param("provenance",id("provenance"))
                .param("requester",id("executorUser")).update();
    }

    public UUID id(String name) {
        UUID found = graph.id(name);
        if (found == null) {
            throw new IllegalArgumentException("no fixture identifier named " + name);
        }
        return found;
    }

    Connection transaction() throws SQLException {
        Connection connection = application.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    /** One-use proof for exactly this transaction, this person, this target and this version. */
    String proof(Connection transaction, UUID actor, String purpose, UUID target, UUID version) throws Exception {
        return AdvertisingR1Fixture.proof(admin, transaction, graph, actor, purpose, target, version);
    }

    /** Launch one action as one person inside one committed transaction; the database's answer comes back. */
    JsonNode launch(UUID launchId, String actionName, UUID actor) throws Exception {
        return launch(launchId,actionName,actor,"{}");
    }

    JsonNode launch(UUID launchId,String actionName,UUID actor,String requested) throws Exception {
        String suffix = actionName.endsWith("Two") ? "Two" : "One";
        UUID action = id(actionName);
        UUID recommendation = id("recommendation" + suffix);
        UUID approval = id("approval" + suffix);
        try (Connection connection = transaction()) {
            String proof = proof(connection, actor, "LISTING_ACTION_LAUNCH", recommendation, approval);
            try (var query = connection.prepareStatement(
                    "SELECT ops.acquire_lc_launch_allowance(?, ?, ?, ?, ?::jsonb)::text")) {
                query.setObject(1, launchId);
                query.setObject(2, action);
                query.setObject(3, actor);
                query.setString(4, proof);
                query.setString(5, requested);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    JsonNode answer = JSON.readTree(rows.getString(1));
                    connection.commit();
                    return answer;
                }
            } catch (SQLException refused) {
                connection.rollback();
                throw refused;
            }
        }
    }

    UUID createCommand(UUID action, UUID actor) {
        long version = app.sql("SELECT version FROM ops.lc_action WHERE id = :id").param("id", action)
                .query(Long.class).single();
        return app.sql("SELECT ops.create_lc_description_command(:action, :actor, :version, 'listing-fixture')")
                .param("action", action).param("actor", actor).param("version", version)
                .query(UUID.class).single();
    }

    List<String> gateReasons(UUID command) {
        return app.sql("SELECT unnest(ops.evaluate_lc_description_write_gate(:id))").param("id", command)
                .query(String.class).list();
    }

    String actionState(UUID action) {
        return app.sql("SELECT state FROM ops.lc_action WHERE id = :id").param("id", action)
                .query(String.class).single();
    }

    UUID contain(UUID containmentId, UUID actor, UUID listing) throws Exception {
        try (Connection connection = transaction()) {
            String proof = proof(connection, actor, "LISTING_CONTAINMENT_STOP", containmentId, containmentId);
            try (var query = connection.prepareStatement("""
                    SELECT ops.record_lc_containment(?, ?, ?, ?, 'LISTING', ?, NULL, NULL, NULL,
                        'SAFETY_FAILURE', 'OWNER', 'fixture stop', 'fixture://stop')
                    """)) {
                query.setObject(1, containmentId);
                query.setObject(2, actor);
                query.setObject(3, id("organization"));
                query.setString(4, proof);
                query.setObject(5, listing);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    UUID recorded = rows.getObject(1, UUID.class);
                    connection.commit();
                    return recorded;
                }
            } catch (SQLException refused) {
                connection.rollback();
                throw refused;
            }
        }
    }

    UUID attest(UUID attestationId, UUID containmentId, UUID actor, String kind) throws Exception {
        try (Connection connection = transaction()) {
            String purpose = "REPAIR_ATTESTATION".equals(kind) ? "LISTING_CONTAINMENT_ATTEST" : "LISTING_CONTAINMENT_CONSENT";
            String proof = proof(connection, actor, purpose, containmentId, containmentId);
            try (var query = connection.prepareStatement(
                    "SELECT ops.attest_lc_containment(?, ?, ?, ?, ?, 'fixture://attestation')")) {
                query.setObject(1, attestationId);
                query.setObject(2, containmentId);
                query.setObject(3, actor);
                query.setString(4, proof);
                query.setString(5, kind);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    UUID recorded = rows.getObject(1, UUID.class);
                    connection.commit();
                    return recorded;
                }
            } catch (SQLException refused) {
                connection.rollback();
                throw refused;
            }
        }
    }

    /** A display observation of the target text, so a STOP_EVIDENCE release has something to rest on. */
    UUID displayObservation(String listingName, String text, UUID observer) {
        UUID observation = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.lc_display_observation(id,organization_id,provenance_id,platform_listing_id,source_fact_key,
                    observed_at,acquired_at,evidence_grade,observer_user_id,display_state,displayed_text,displayed_text_digest,
                    evidence_reference)
                VALUES (:id,:org,:provenance,:listing,:key,now(),now(),'INDEPENDENT_HUMAN',:observer,'DISPLAYED',:text,
                    encode(sha256(convert_to(:text,'UTF8')),'hex'),'fixture://display')
                """).param("id", observation).param("org", id("organization")).param("provenance", id("provenance"))
                .param("listing", id(listingName)).param("key", "fictional-display-" + observation)
                .param("observer", observer).param("text", text).update();
        return observation;
    }

    void release(UUID occupation, UUID actor, UUID evidence) throws Exception {
        release(occupation,actor,evidence,"STOP_EVIDENCE");
    }

    void release(UUID occupation,UUID actor,UUID evidence,String basis) throws Exception {
        try (Connection connection = transaction()) {
            String proof = proof(connection, actor, "LISTING_OCCUPATION_RELEASE", occupation, occupation);
            try (var query = connection.prepareStatement(
                    "SELECT ops.release_lc_occupation(?, ?, ?, ?, ?, 'fixture://release-proof')")) {
                query.setObject(1, occupation);
                query.setObject(2, actor);
                query.setString(3, proof);
                query.setString(4, basis);
                query.setObject(5, evidence);
                query.execute();
                connection.commit();
            } catch (SQLException refused) {
                connection.rollback();
                throw refused;
            }
        }
    }

    /** The SQLSTATE at the root of a failure, or null when there is none. */
    static String sqlState(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
