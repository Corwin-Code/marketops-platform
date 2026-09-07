package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Canonical row changes, not hand-enqueued requests, prove the declared trigger families. */
@SpringBootTest @ActiveProfiles("ci")
class AdvertisingTargetedTriggerIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DATABASE::getJdbcUrl);r.add("spring.datasource.username",TestDatabase::applicationRole);
        r.add("spring.datasource.password",TestDatabase::applicationPassword);r.add("spring.flyway.user",TestDatabase::migrationRole);
        r.add("spring.flyway.password",TestDatabase::migrationPassword);
    }
    @ParameterizedTest
    @ValueSource(strings={"NATIVE_CONFIGURATION","PRODUCT_MAPPING","AVAILABILITY","COMPLETED_SALES","ALLOWABLE_CPA",
            "PRIORITY_POLICY","BUNDLE_REVOCATION","QUARANTINE","PROVIDER_INCIDENT","CRITICAL_SALES_UNIT"})
    void acceptedCanonicalChangesWakeOnlyTheirDependants(String family) throws Exception {
        var ds=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        var graph=AdvertisingR1Fixture.seed(ds);var other=AdvertisingR1Fixture.seedManual(ds);var db=JdbcClient.create(ds);
        db.sql("UPDATE ops.ad_recalculation_request SET state='COMPLETED',completed_at=now(),lease_owner=NULL,leased_until=NULL WHERE organization_id IN(:org,:other)")
                .param("org",graph.id("organization")).param("other",other.id("organization")).update();
        String trigger;
        Instant before=db.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
        switch(family) {
            case "NATIVE_CONFIGURATION" -> {
                db.sql("INSERT INTO core.ad_object_configuration_observation SELECT clone.* FROM core.ad_object_configuration_observation old CROSS JOIN LATERAL jsonb_populate_record(NULL::core.ad_object_configuration_observation,to_jsonb(old)||jsonb_build_object('id',gen_random_uuid(),'observed_at',clock_timestamp(),'source_time',clock_timestamp())) clone WHERE old.id=:id")
                        .param("id",graph.id("configuration")).update();trigger="AD_CONFIGURATION";
            }
            case "PRODUCT_MAPPING" -> {
                db.sql("UPDATE core.listing_mapping SET effective_to=clock_timestamp() WHERE organization_id=:org")
                        .param("org",graph.id("organization")).update();trigger="PRODUCT_MAPPING_OR_AFFECTED_SET";
            }
            case "AVAILABILITY" -> {
                // A listing observation reaches the object through its canonical affected membership.
                db.sql("INSERT INTO core.ad_object_relationship(id,organization_id,parent_object_id,platform_listing_variant_id,relationship_kind,provenance_id,observed_at,status,created_at) VALUES(gen_random_uuid(),:org,:object,:listing,'PROMOTES_LISTING_VARIANT',:provenance,now(),'ACTIVE',now())")
                        .param("org",graph.id("organization")).param("object",graph.id("object")).param("listing",graph.id("listingVariant")).param("provenance",graph.id("provenance")).update();
                db.sql("UPDATE ops.ad_recalculation_request SET state='COMPLETED',completed_at=now() WHERE organization_id=:org AND state='PENDING'").param("org",graph.id("organization")).update();
                db.sql("INSERT INTO core.listing_stock_observation(id,organization_id,provenance_id,platform_listing_variant_id,fulfillment_mode_code,source_fact_key,observed_at,available_quantity) VALUES(gen_random_uuid(),:org,:provenance,:listing,'SELLER_FULFILLED',:key,now(),0)")
                        .param("org",graph.id("organization")).param("provenance",graph.id("provenance")).param("listing",graph.id("listingVariant")).param("key","stock-"+UUID.randomUUID()).update();trigger="SELLABILITY_OR_AVAILABILITY";
            }
            case "COMPLETED_SALES" -> {
                db.sql("INSERT INTO ledger.sales_fact SELECT clone.* FROM ledger.sales_fact old CROSS JOIN LATERAL jsonb_populate_record(NULL::ledger.sales_fact,to_jsonb(old)||jsonb_build_object('id',gen_random_uuid(),'source_fact_key','completion-'||gen_random_uuid(),'sale_stage','COMPLETED','retention_window_days',NULL,'occurred_at',clock_timestamp())) clone WHERE old.organization_id=:org")
                        .param("org",graph.id("organization")).update();trigger="COMPANY_SALES_OR_RETURNS";
            }
            case "ALLOWABLE_CPA" -> {db.sql("UPDATE core.ad_allowable_cpa_definition SET status='RETIRED' WHERE id=:id").param("id",graph.id("allowableCpa")).update();trigger="CONVERSION_OR_ALLOWABLE_CPA";}
            case "PRIORITY_POLICY" -> {db.sql("UPDATE core.ad_priority_policy SET status='RETIRED' WHERE id=:id").param("id",graph.id("priority")).update();trigger="PRIORITY_OR_SLO_POLICY";}
            case "BUNDLE_REVOCATION" -> {db.sql("UPDATE ops.ad_decision_policy_bundle SET status='REVOKED' WHERE id=:id").param("id",graph.id("bundle")).update();trigger="POLICY_BUNDLE_LIFECYCLE";}
            case "QUARANTINE" -> {
                db.sql("INSERT INTO ops.ad_containment(id,organization_id,containment_kind,scope_kind,ad_native_object_id,cause_class,reason,evidence_reference,activated_by_trigger,activated_at,state,correlation_id,created_at,updated_at) VALUES(gen_random_uuid(),:org,'ACTION_OUTCOME_QUARANTINE','ENTITY',:object,'OUTCOME_REGRESSION','Known fictional regression','fixture://trigger/regression','FIXTURE_REGRESSION',now(),'ACTIVE','trigger-test',now(),now())")
                        .param("org",graph.id("organization")).param("object",graph.id("object")).update();trigger="EXCEPTION_HOLD_KILL_OR_QUARANTINE";
            }
            case "PROVIDER_INCIDENT" -> {
                db.sql("INSERT INTO platform.ad_provider_incident VALUES(gen_random_uuid(),:org,:platform,:store,:provenance,true,now(),now()+interval '5 minutes','fixture://trigger/incident')")
                        .param("org",graph.id("organization")).param("platform",graph.platform()).param("store",graph.id("store")).param("provenance",graph.id("provenance")).update();trigger="PROVIDER_READBACK_OR_UNKNOWN";
            }
            case "CRITICAL_SALES_UNIT" -> {
                db.sql("INSERT INTO core.ad_outcome_critical_unit_rule VALUES(gen_random_uuid(),:policy,:org,:product,:store,'Protected canonical unit','fixture://trigger/critical-unit')")
                        .param("policy",graph.id("outcome")).param("org",graph.id("organization")).param("product",graph.id("productVariant")).param("store",graph.id("store")).update();trigger="CRITICAL_SALES_OR_CONFOUNDER";
            }
            default -> throw new IllegalArgumentException(family);
        }
        var rows=db.sql("SELECT ad_native_object_id,trigger_class,fact_accepted_at FROM ops.ad_recalculation_request WHERE state='PENDING' AND organization_id=:org")
                .param("org",graph.id("organization")).query((rs,n)->List.of(rs.getObject(1,UUID.class),rs.getString(2),rs.getTimestamp(3).toInstant())).list();
        assertThat(rows).as(family).hasSize(1);assertThat(rows.getFirst().get(0)).isEqualTo(graph.id("object"));
        assertThat(rows.getFirst().get(1)).isEqualTo(trigger);assertThat((Instant)rows.getFirst().get(2)).isAfterOrEqualTo(before);
        assertThat(db.sql("SELECT count(*) FROM ops.ad_recalculation_request WHERE state='PENDING' AND organization_id=:org").param("org",other.id("organization")).query(Integer.class).single()).isZero();
    }
    @Test
    void crossStoreCompanyFactsFollowProductMembershipAndPartialSetsRemainVisible() throws Exception {
        var ds=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        var graph=AdvertisingR1Fixture.seedManual(ds);var foreign=AdvertisingR1Fixture.seedManual(ds);var db=JdbcClient.create(ds);
        UUID org=graph.id("organization"),peerStore=UUID.randomUUID(),peerObject=UUID.randomUUID(),unrelatedObject=UUID.randomUUID(),unrelatedProduct=UUID.randomUUID();
        db.sql("INSERT INTO core.store SELECT clone.* FROM core.store old CROSS JOIN LATERAL jsonb_populate_record(NULL::core.store,to_jsonb(old)||jsonb_build_object('id',:id,'code','other-channel','display_name','Other fictional sales channel')) clone WHERE old.id=:base")
                .param("id",peerStore).param("base",graph.id("store")).update();
        db.sql("INSERT INTO core.product_variant SELECT clone.* FROM core.product_variant old CROSS JOIN LATERAL jsonb_populate_record(NULL::core.product_variant,to_jsonb(old)||jsonb_build_object('id',:id,'sku_code','unrelated-product')) clone WHERE old.id=:base")
                .param("id",unrelatedProduct).param("base",graph.id("productVariant")).update();
        for(UUID object:List.of(peerObject,unrelatedObject)) {
            UUID store=object.equals(peerObject)?peerStore:graph.id("store");UUID product=object.equals(peerObject)?graph.id("productVariant"):unrelatedProduct;
            db.sql("INSERT INTO core.ad_native_object SELECT clone.* FROM core.ad_native_object old CROSS JOIN LATERAL jsonb_populate_record(NULL::core.ad_native_object,to_jsonb(old)||jsonb_build_object('id',:id,'store_id',:store,'native_object_key',:key,'lineage_key',:key)) clone WHERE old.id=:base")
                    .param("id",object).param("store",store).param("key",object.toString()).param("base",graph.id("object")).update();
            db.sql("INSERT INTO core.ad_affected_set SELECT clone.* FROM core.ad_affected_set old CROSS JOIN LATERAL jsonb_populate_record(NULL::core.ad_affected_set,to_jsonb(old)||jsonb_build_object('id',gen_random_uuid(),'ad_native_object_id',:object,'product_variant_ids',ARRAY[:product]::uuid[],'affected_set_digest',:digest,'resolved_at',clock_timestamp())) clone WHERE old.id=:base")
                    .param("object",object).param("product",product).param("digest",com.mimococo.marketops.shared.Digest.ofComponents(List.of(product.toString()))).param("base",graph.id("affectedSet")).update();
        }
        clear(db,org,foreign.id("organization"));
        sales(db,org);
        assertThat(pendingObjects(db,org)).containsExactlyInAnyOrder(graph.id("object"),peerObject);
        assertThat(pendingObjects(db,foreign.id("organization"))).isEmpty();
        clear(db,org,foreign.id("organization"));
        db.sql("INSERT INTO core.ad_object_configuration_observation SELECT clone.* FROM core.ad_object_configuration_observation old CROSS JOIN LATERAL jsonb_populate_record(NULL::core.ad_object_configuration_observation,to_jsonb(old)||jsonb_build_object('id',gen_random_uuid(),'observed_at',clock_timestamp(),'source_time',clock_timestamp())) clone WHERE old.id=:id")
                .param("id",graph.id("configuration")).update();
        assertThat(pendingObjects(db,org)).containsExactly(graph.id("object"));
        // A partial affected set does not disappear from wake-up dependency resolution.
        db.sql("INSERT INTO core.ad_affected_set SELECT clone.* FROM core.ad_affected_set old CROSS JOIN LATERAL jsonb_populate_record(NULL::core.ad_affected_set,to_jsonb(old)||jsonb_build_object('id',gen_random_uuid(),'ad_native_object_id',:object,'resolution_state','INCOMPLETE','unresolved_reason_codes',ARRAY['LISTING_MAPPING_UNRESOLVED'],'resolved_at',clock_timestamp())) clone WHERE old.id=:base")
                .param("object",unrelatedObject).param("base",graph.id("affectedSet")).update();
        clear(db,org,foreign.id("organization"));sales(db,org);
        assertThat(pendingObjects(db,org)).containsExactlyInAnyOrder(graph.id("object"),peerObject,unrelatedObject);
        assertThat(pendingObjects(db,foreign.id("organization"))).isEmpty();
    }
    private static void clear(JdbcClient db,UUID first,UUID second) {
        db.sql("UPDATE ops.ad_recalculation_request SET state='COMPLETED',completed_at=now(),lease_owner=NULL,leased_until=NULL WHERE organization_id IN(:first,:second) AND state IN('PENDING','LEASED')")
                .param("first",first).param("second",second).update();
    }
    private static void sales(JdbcClient db,UUID org) {
        db.sql("INSERT INTO ledger.sales_fact SELECT clone.* FROM ledger.sales_fact old CROSS JOIN LATERAL jsonb_populate_record(NULL::ledger.sales_fact,to_jsonb(old)||jsonb_build_object('id',gen_random_uuid(),'source_fact_key','cross-channel-'||gen_random_uuid(),'sale_stage','COMPLETED','retention_window_days',NULL,'occurred_at',clock_timestamp())) clone WHERE old.organization_id=:org AND old.sale_stage='RETAINED'")
                .param("org",org).update();
    }
    private static List<UUID> pendingObjects(JdbcClient db,UUID org) {
        return db.sql("SELECT ad_native_object_id FROM ops.ad_recalculation_request WHERE organization_id=:org AND state='PENDING'").param("org",org).query(UUID.class).list();
    }

}
