package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static com.mimococo.marketops.AdvertisingRetryProofIT.assertSqlState;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exact current Gate scope and demonstrated predecessor scope are tested at their separate real sinks. */
class AdvertisingGateScopeMatrixIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration,application,admin;
    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    private AdvertisingControlProofFixture fixture() throws Exception {return new AdvertisingControlProofFixture(migration,application,admin,false);}
    @Test void exactSyntheticGateAdmitsOnlyItsCapturedObjectValuesWithoutProductionEnablement() throws Exception {
        var f=fixture();assertThat(f.reasons()).isEmpty();f.state("EXECUTING");assertThat(f.open("APPLY")).isNotNull();
        assertThat(f.app.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",f.graph.id("gate")).query(Boolean.class).single()).isFalse();
    }
    @Test void aMonotonicGateEWithExactDemonstratedScopeCanBePublishedAsSyntheticOwnerInput() throws Exception {
        var f=fixture();UUID next=UUID.randomUUID();insertGateE(f,next,"{}");
        assertThat(f.app.sql("SELECT gate_kind FROM ops.ad_gate_authority WHERE id=:id").param("id",next).query(String.class).single()).isEqualTo("GATE_E");
        assertThat(f.app.sql("SELECT predecessor_gate_ev_id FROM ops.ad_gate_authority WHERE id=:id").param("id",next).query(UUID.class).single()).isEqualTo(f.graph.id("gate"));
        // Publication alone does not replace the old Bundle's selected Gate or create an approval.
        assertThat(f.app.sql("SELECT gate_authority_id FROM ops.ad_decision_policy_bundle WHERE id=:id").param("id",f.graph.id("bundle")).query(UUID.class).single()).isEqualTo(f.graph.id("gate"));
    }
    @ParameterizedTest @ValueSource(strings={"PLATFORM","STORE","OBJECT","DIRECTION","BASIS"})
    void unDemonstratedGateEScopeIsRejectedByTheMonotonicPublicationTrigger(String axis) throws Exception {
        var f=fixture();UUID next=UUID.randomUUID();
        String original=f.app.sql("SELECT to_jsonb(g)::text FROM ops.ad_gate_authority g WHERE id=:id").param("id",f.graph.id("gate")).query(String.class).single();
        UUID store=axis.equals("STORE")?adjacentStore(f):f.graph.id("store");UUID object=UUID.randomUUID();
        String patch=switch(axis) {
            case "PLATFORM" -> "{\"platform_code\":\"WILDBERRIES\"}";
            case "STORE" -> "{\"store_id\":\""+store+"\"}";
            case "DIRECTION" -> "{\"direction\":\"OPTIMIZATION_INCREASE\"}";
            case "BASIS" -> "{\"candidate_basis\":\"CAUSE_BOUND_PROTECTION_STEP\"}";
            case "OBJECT" -> "{\"native_object_ids\":[\""+object+"\"],\"demonstrated_object_ids\":[\""+object+"\"],\"exact_object_values\":{\""+object+"\":{\"currentBid\":30,\"targetBid\":20,\"currencyCode\":\"RUB\",\"bidUnitCode\":\"CURRENCY_MAJOR\"}}}";
            default -> throw new AssertionError(axis);
        };
        assertSqlState(()->insertGateE(f,next,patch),"MO092");
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_gate_authority WHERE id=:id").param("id",next).query(Integer.class).single()).isZero();
        assertThat(f.app.sql("SELECT to_jsonb(g)::text FROM ops.ad_gate_authority g WHERE id=:id")
                .param("id",f.graph.id("gate")).query(String.class).single()).isEqualTo(original);
        // The adjacent Store legitimately makes company-wide sales coverage
        // unknown. A rejected Gate publication must preserve the old exact Gate;
        // it cannot manufacture complete coverage for that newly observed Store.
    }
    @ParameterizedTest @ValueSource(strings={"MISSING_VALUE","INVALID_UNIT","CURRENCY_MISMATCH"})
    void anActiveGateCannotPublishIncompleteNativeValueAuthority(String axis) throws Exception {
        var f=fixture();UUID next=UUID.randomUUID();
        String value=switch(axis) {case "MISSING_VALUE" -> "{}";case "INVALID_UNIT" -> "{\"currentBid\":30,\"targetBid\":20,\"currencyCode\":\"RUB\",\"bidUnitCode\":\"UNKNOWN\"}";
            case "CURRENCY_MISMATCH" -> "{\"currentBid\":30,\"targetBid\":20,\"currencyCode\":\"USD\",\"bidUnitCode\":\"CURRENCY_MAJOR\"}";default -> throw new AssertionError(axis);};
        assertSqlState(()->insertGateE(f,next,"{\"exact_object_values\":{\""+f.graph.id("object")+"\":"+value+"}}"),"MO092");
        assertThat(f.reasons()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"CURRENT_VALUE","TARGET_VALUE","UNIT","MAX_CHANGE"})
    void anUntestedNativePairOrLargerChangeBoundCannotBorrowPredecessorEvidence(String axis) throws Exception {
        var f=fixture();UUID next=UUID.randomUUID();
        String patch=switch(axis) {
            case "CURRENT_VALUE" -> "{\"exact_object_values\":{\""+f.graph.id("object")+"\":{\"currentBid\":31,\"targetBid\":20,\"currencyCode\":\"RUB\",\"bidUnitCode\":\"CURRENCY_MAJOR\"}}}";
            case "TARGET_VALUE" -> "{\"exact_object_values\":{\""+f.graph.id("object")+"\":{\"currentBid\":30,\"targetBid\":19,\"currencyCode\":\"RUB\",\"bidUnitCode\":\"CURRENCY_MAJOR\"}}}";
            case "UNIT" -> "{\"exact_object_values\":{\""+f.graph.id("object")+"\":{\"currentBid\":30,\"targetBid\":20,\"currencyCode\":\"RUB\",\"bidUnitCode\":\"CURRENCY_MINOR\"}}}";
            case "MAX_CHANGE" -> "{\"max_bid_change_amount\":100000}";
            default -> throw new AssertionError(axis);
        };
        assertSqlState(()->insertGateE(f,next,patch),"MO092");
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_gate_authority WHERE id=:id").param("id",next).query(Integer.class).single()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"ACTIVE","SALES","SPEND","BID_CHANGE","UNRESOLVED","HEADROOM","MEASUREMENT_WINDOW","RETAINED_WINDOW","CUMULATIVE_WINDOW"})
    void aNewEnvelopeCannotBroadenAnyIndependentAxisOrSilentlyChangeItsMeasurementBasis(String axis) throws Exception {
        var f=fixture();UUID next=UUID.randomUUID();
        // A synthetic published predecessor with a non-extreme valid share permits
        // the widening negative without violating the underlying 0..1 schema first.
        f.seed.sql("UPDATE core.ad_exposure_envelope SET max_affected_retained_sales_share=0.8 WHERE id=:id")
                .param("id",f.graph.id("exposure")).update();
        String patch=switch(axis) {
            case "ACTIVE" -> "{\"max_active_interventions\":11}";
            case "SALES" -> "{\"max_affected_retained_sales_share\":0.9}";
            case "SPEND" -> "{\"max_associated_spend_amount\":100001}";
            case "BID_CHANGE" -> "{\"max_cumulative_bid_change_amount\":501}";
            case "UNRESOLVED" -> "{\"max_unresolved_transmitted_writes\":3}";
            case "HEADROOM" -> "{\"reserved_recovery_headroom_count\":1}";
            case "MEASUREMENT_WINDOW" -> "{\"measurement_window_hours\":719}";
            case "RETAINED_WINDOW" -> "{\"retained_window_days\":14}";
            case "CUMULATIVE_WINDOW" -> "{\"cumulative_window_hours\":23}";
            default -> throw new AssertionError(axis);
        };
        UUID bundle=publishedEnvelopeBundle(f,patch);
        assertSqlState(()->insertGateE(f,next,"{\"bundle_id\":\""+bundle+"\"}"),"MO092");
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_gate_authority WHERE id=:id").param("id",next).query(Integer.class).single()).isZero();
    }
    @Test void aTighterSixAxisEnvelopeCanBePublishedWithoutChangingTheOldBundleOrEnablingProduction() throws Exception {
        var f=fixture();UUID next=UUID.randomUUID();
        UUID bundle=publishedEnvelopeBundle(f,"{\"max_active_interventions\":9,\"max_affected_retained_sales_share\":0.9,\"max_associated_spend_amount\":99999,\"max_cumulative_bid_change_amount\":499,\"max_unresolved_transmitted_writes\":1,\"reserved_recovery_headroom_count\":3}");
        insertGateE(f,next,"{\"bundle_id\":\""+bundle+"\"}");
        assertThat(f.app.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id").param("id",next).query(Boolean.class).single()).isFalse();
        assertThat(f.app.sql("SELECT status FROM ops.ad_decision_policy_bundle WHERE id=:id").param("id",bundle).query(String.class).single()).isEqualTo("DRAFT");
        assertThat(f.app.sql("SELECT exposure_envelope_id FROM ops.ad_decision_policy_bundle WHERE id=:id").param("id",f.graph.id("bundle")).query(UUID.class).single()).isEqualTo(f.graph.id("exposure"));
    }
    @Test void historicalGateEvEvidenceDoesNotImpersonateTheNewOwnersPilotWindow() throws Exception {
        var f=fixture();UUID next=UUID.randomUUID();
        f.seed.sql("UPDATE ops.ad_gate_authority SET status='EXPIRED',valid_from=clock_timestamp()-interval '2 days',valid_until=clock_timestamp()-interval '1 day' WHERE id=:id")
                .param("id",f.graph.id("gate")).update();
        String window=f.seed.sql("SELECT jsonb_build_object('status','ACTIVE','valid_from',clock_timestamp(),'valid_until',clock_timestamp()+interval '1 hour')::text").query(String.class).single();
        insertGateE(f,next,window);
        assertThat(f.app.sql("SELECT g.valid_until>p.valid_until AND NOT g.production_write_enabled FROM ops.ad_gate_authority g JOIN ops.ad_gate_authority p ON p.id=g.predecessor_gate_ev_id WHERE g.id=:id")
                .param("id",next).query(Boolean.class).single()).isTrue();
        // This verifies only publication of a separately bounded synthetic Owner
        // window. Final seal/attempt still enforce current intersected authority.
    }
    private static UUID publishedEnvelopeBundle(AdvertisingControlProofFixture f,String patch) {
        UUID envelope=UUID.randomUUID(),bundle=UUID.randomUUID();
        // The predecessor remains historical evidence. Current versions of the
        // same scope may not overlap, so replace it before publishing the next one.
        f.seed.sql("UPDATE core.ad_exposure_envelope SET status='RETIRED' WHERE id=:id")
                .param("id",f.graph.id("exposure")).update();
        f.seed.sql("""
            INSERT INTO core.ad_exposure_envelope SELECT (jsonb_populate_record(NULL::core.ad_exposure_envelope,
              to_jsonb(prior)||jsonb_build_object('id',:id::text,'policy_version',2,'status','ACTIVE','effective_from',clock_timestamp())||cast(:patch AS jsonb))).*
            FROM core.ad_exposure_envelope prior WHERE id=:prior
            """).param("id",envelope).param("patch",patch).param("prior",f.graph.id("exposure")).update();
        f.seed.sql("""
            INSERT INTO ops.ad_decision_policy_bundle SELECT (jsonb_populate_record(NULL::ops.ad_decision_policy_bundle,
              to_jsonb(prior)||jsonb_build_object('id',:id::text,'bundle_version',2,'status','DRAFT','gate_authority_id',NULL,
                'exposure_envelope_id',:envelope::text))).* FROM ops.ad_decision_policy_bundle prior WHERE id=:prior
            """).param("id",bundle).param("envelope",envelope).param("prior",f.graph.id("bundle")).update();
        return bundle;
    }
    private static void insertGateE(AdvertisingControlProofFixture f,UUID id,String patch) {
        f.seed.sql("""
            INSERT INTO ops.ad_gate_authority SELECT (jsonb_populate_record(NULL::ops.ad_gate_authority,
              to_jsonb(prior)||jsonb_build_object('id',:id::text,'gate_kind','GATE_E','predecessor_gate_ev_id',prior.id::text)
                ||cast(:patch AS jsonb))).* FROM ops.ad_gate_authority prior WHERE prior.id=:prior
            """).param("id",id).param("patch",patch).param("prior",f.graph.id("gate")).update();
    }
    private static UUID adjacentStore(AdvertisingControlProofFixture f) {
        UUID id=UUID.randomUUID();f.seed.sql("""
            INSERT INTO core.store SELECT (jsonb_populate_record(NULL::core.store,to_jsonb(store)||
              jsonb_build_object('id',:id::text,'code',:code,'display_name','Adjacent synthetic Store'))).*
            FROM core.store store WHERE id=:prior
            """).param("id",id).param("code","adjacent-"+id).param("prior",f.graph.id("store")).update();return id;
    }
}
