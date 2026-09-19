package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exact manual evidence bindings against isolated PostgreSQL as application role. */
class ListingManualVerificationIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration,application,admin;
    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    record Case(ListingConversionFixture fixture,UUID packet,String target) { }
    Case ready() throws Exception {
        var f=new ListingConversionFixture(migration,application,admin);
        UUID launch=UUID.randomUUID(),packet=UUID.randomUUID();
        assertThat(f.launch(launch,"actionTwo",f.id("ownerUser")).path("launched").asBoolean()).isTrue();
        f.app.sql("""
                INSERT INTO ops.lc_manual_packet(id,organization_id,action_id,launch_id,executor_user_id,issued_by_user_id,
                    issued_at,expires_at,native_listing_key,affected_set_digest,target_text,execution_path,state,updated_at,version)
                SELECT :packet,a.organization_id,a.id,:launch,:executor,:issuer,clock_timestamp(),b.expires_at,
                    l.native_listing_key,a.affected_set_digest,a.target_text,'MANUAL','ISSUED',clock_timestamp(),1
                FROM ops.lc_action a JOIN core.platform_listing l ON l.id=a.platform_listing_id
                JOIN ops.lc_action_binding b ON b.action_id=a.id WHERE a.id=:action
                """).param("packet",packet).param("launch",launch).param("executor",f.id("executorUser"))
                .param("issuer",f.id("ownerUser")).param("action",f.id("actionTwo")).update();
        f.app.sql("""
                INSERT INTO ops.lc_manual_report(id,organization_id,packet_id,reporter_user_id,
                    operation_time,reported_at,report_state,note)
                SELECT gen_random_uuid(),organization_id,id,executor_user_id,issued_at,clock_timestamp(),
                    'APPLIED','fixture operation report' FROM ops.lc_manual_packet WHERE id=:packet
                """).param("packet",packet).update();
        return new Case(f,packet,f.app.sql("SELECT target_text FROM ops.lc_action WHERE id=:id")
                .param("id",f.id("actionTwo")).query(String.class).single());
    }
    UUID provenance(Case c,UUID observer) {
        UUID id=UUID.randomUUID();
        c.fixture.seed.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id)
                VALUES(:id,:org,'MANUAL_ENTRY',clock_timestamp(),clock_timestamp(),:observer)
                """).param("id",id).param("org",c.fixture.id("organization")).param("observer",observer).update();
        return id;
    }
    UUID display(Case c,String listing,String text,String state,String observer,int timeShiftSeconds) {
        UUID id=UUID.randomUUID(),actor=c.fixture.id(observer),source=provenance(c,actor);
        c.fixture.seed.sql("""
                INSERT INTO core.lc_display_observation(id,organization_id,provenance_id,platform_listing_id,source_fact_key,
                    observed_at,acquired_at,evidence_grade,observer_user_id,display_state,displayed_text,displayed_text_digest,evidence_reference)
                VALUES(:id,:org,:source,:listing,:key,clock_timestamp()+make_interval(secs=>:shift),
                    clock_timestamp()+make_interval(secs=>greatest(:shift,0)),'INDEPENDENT_HUMAN',:actor,:state,:text,
                    encode(sha256(convert_to(:text,'UTF8')),'hex'),'fixture://customer-display')
                """).param("id",id).param("org",c.fixture.id("organization")).param("source",source)
                .param("listing",c.fixture.id(listing)).param("key",id.toString()).param("shift",timeShiftSeconds)
                .param("actor",actor).param("state",state).param("text",text).update();
        return id;
    }
    UUID management(Case c) {
        UUID id=UUID.randomUUID(),source=provenance(c,c.fixture.id("verifierUser"));
        c.fixture.seed.sql("""
                INSERT INTO core.lc_description_observation(id,organization_id,provenance_id,platform_listing_id,source_fact_key,
                    observed_at,acquired_at,description_text,text_digest,language_code,kiz_marked_declared)
                VALUES(:id,:org,:source,:listing,:key,clock_timestamp(),clock_timestamp(),:text,
                    encode(sha256(convert_to(:text,'UTF8')),'hex'),'ru',false)
                """).param("id",id).param("org",c.fixture.id("organization")).param("source",source)
                .param("listing",c.fixture.id("listingTwo")).param("key",id.toString()).param("text",c.target).update();
        return id;
    }
    UUID verify(Case c,UUID management,UUID display,String state,String basis) {
        UUID id=UUID.randomUUID();
        c.fixture.app.sql("""
                INSERT INTO ops.lc_manual_verification(id,organization_id,packet_id,verifier_user_id,verification_basis,
                    management_match,management_observation_id,display_observation_id,display_state,verified_at,note)
                VALUES(:id,:org,:packet,CAST(:verifier AS uuid),:basis,:match,:management,:display,:state,clock_timestamp(),'fixture verification')
                """).param("id",id).param("org",c.fixture.id("organization")).param("packet",c.packet)
                .param("verifier","INDEPENDENT_HUMAN".equals(basis)?c.fixture.id("verifierUser"):null).param("basis",basis)
                .param("match",management==null?"UNKNOWN":"MATCHED_TARGET").param("management",management)
                .param("display",display).param("state",state).update();
        return id;
    }
    @Test void independentExactManagementAndDisplayBindBeforeActionVerification() throws Exception {
        var c=ready();
        UUID id=verify(c,management(c),display(c,"listingTwo",c.target,"DISPLAYED","verifierUser",0),"DISPLAYED","INDEPENDENT_HUMAN");
        assertThat(c.fixture.app.sql("SELECT observation_binding->>'displayClaimExtent' FROM ops.lc_manual_verification WHERE id=:id")
                .param("id",id).query(String.class).single()).isEqualTo("OBSERVED_INSTANT_ONLY");
        c.fixture.app.sql("UPDATE ops.lc_action SET state='VERIFIED',version=version+1 WHERE id=:id")
                .param("id",c.fixture.id("actionTwo")).update();
        assertThat(c.fixture.actionState(c.fixture.id("actionTwo"))).isEqualTo("VERIFIED");
    }
    @Test void anotherListingCannotProveDisplayEvenWithTheSameTargetText() throws Exception {
        var c=ready();
        refused(c,display(c,"listing",c.target,"DISPLAYED","verifierUser",0),"DISPLAYED","MO092");
    }
    @Test void staleAndFutureObservationsCannotProveThisOperation() throws Exception {
        var c=ready();
        refused(c,display(c,"listingTwo",c.target,"DISPLAYED","verifierUser",-3600),"DISPLAYED","MO092");
        refused(c,display(c,"listingTwo",c.target,"DISPLAYED","verifierUser",3600),"DISPLAYED","MO092");
    }
    @Test void wrongTextAndUnknownDisplayCannotBecomeTargetDisplay() throws Exception {
        var c=ready();
        refused(c,display(c,"listingTwo","Other body","DISPLAYED","verifierUser",0),"DISPLAYED","MO093");
        refused(c,display(c,"listingTwo",null,"UNKNOWN","verifierUser",0),"DISPLAYED","MO093");
    }
    @Test void independentVerifierCannotLaunderAnExecutorsDisplayReport() throws Exception {
        var c=ready();
        refused(c,display(c,"listingTwo",c.target,"DISPLAYED","executorUser",0),"DISPLAYED","MO092");
    }
    @Test void managementMatchDoesNotRequireAnInventedCustomerDisplayClaim() throws Exception {
        var c=ready();
        UUID id=verify(c,management(c),null,"UNKNOWN","INDEPENDENT_HUMAN");
        assertThat(c.fixture.app.sql("SELECT display_state FROM ops.lc_manual_verification WHERE id=:id")
                .param("id",id).query(String.class).single()).isEqualTo("UNKNOWN");
    }
    @Test void anObservationBeforeTheReportedOperationCannotVerifyThatOperation() throws Exception {
        var c=ready();
        UUID observation=display(c,"listingTwo",c.target,"DISPLAYED","verifierUser",0);
        c.fixture.app.sql("""
                INSERT INTO ops.lc_manual_report(id,organization_id,packet_id,reporter_user_id,operation_time,reported_at,report_state,note)
                VALUES(gen_random_uuid(),:org,:packet,:executor,clock_timestamp(),clock_timestamp(),'APPLIED','fixture operation report')
                """).param("org",c.fixture.id("organization")).param("packet",c.packet)
                .param("executor",c.fixture.id("executorUser")).update();
        refused(c,observation,"DISPLAYED","MO092");
    }
    @Test void aHumanObservationCannotBeRelabelledAsOfficialEvidence() throws Exception {
        var c=ready();
        UUID observation=display(c,"listingTwo",c.target,"DISPLAYED","verifierUser",0);
        assertThatThrownBy(()->verify(c,null,observation,"DISPLAYED","OFFICIAL_EVIDENCE"))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
    }
    void refused(Case c,UUID observation,String claim,String sqlState) {
        assertThatThrownBy(()->verify(c,null,observation,claim,"INDEPENDENT_HUMAN"))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo(sqlState));
        assertThat(c.fixture.app.sql("SELECT count(*) FROM ops.lc_manual_verification WHERE packet_id=:packet")
                .param("packet",c.packet).query(Integer.class).single()).isZero();
    }
}
