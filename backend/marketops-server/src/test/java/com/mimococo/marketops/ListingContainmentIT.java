package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Stopping is fast and needs one person; resuming is slow and needs two.
 *
 * <p>A containment stops every live action inside its scope in the same
 * statement that records it. Nothing resumes by itself: re-enabling needs a
 * repair attestation and a business consent from two different people, and
 * the containment itself never revives a stopped action.
 */
class ListingContainmentIT {

    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE = TestDatabase.isolatedContainer();
    private static DataSource migration;
    private static DataSource application;
    private static DataSource admin;

    @BeforeAll
    static void database() {
        migration = new DriverManagerDataSource(DATABASE.getJdbcUrl(), TestDatabase.migrationRole(),
                TestDatabase.migrationPassword());
        application = new DriverManagerDataSource(DATABASE.getJdbcUrl(), TestDatabase.applicationRole(),
                TestDatabase.applicationPassword());
        admin = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }

    private static ListingConversionFixture ready() throws Exception {
        return new ListingConversionFixture(migration, application, admin);
    }

    @Test
    @DisplayName("TC-LC-CONTAIN-001 a listing stop contains the live action in that scope and no other")
    void stopContainsTheScope() throws Exception {
        var f = ready();
        f.launch(UUID.randomUUID(), "actionOne", f.id("ownerUser"));
        UUID containment = UUID.randomUUID();

        assertThat(f.contain(containment, f.id("ownerUser"), f.id("listing"))).isEqualTo(containment);

        assertThat(f.actionState(f.id("actionOne"))).isEqualTo("CONTAINED");
        assertThat(f.actionState(f.id("actionTwo"))).isEqualTo("APPROVED");
        assertThat(f.app.sql("SELECT ops.lc_scope_contained(:org, :listing)").param("org", f.id("organization"))
                .param("listing", f.id("listing")).query(Boolean.class).single()).isTrue();
        assertThat(f.app.sql("SELECT ops.lc_scope_contained(:org, :listing)").param("org", f.id("organization"))
                .param("listing", f.id("listingTwo")).query(Boolean.class).single()).isFalse();
        assertThat(f.app.sql("SELECT state FROM ops.lc_containment WHERE id = :id").param("id", containment)
                .query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("TC-LC-CONTAIN-002 the application role writes no containment or attestation row directly")
    void containmentIsWrittenOnlyByTheFunction() throws Exception {
        var f = ready();
        for (String table : java.util.List.of("ops.lc_containment", "ops.lc_containment_attestation")) {
            for (String privilege : java.util.List.of("INSERT", "UPDATE", "DELETE")) {
                assertThat(f.app.sql("SELECT has_table_privilege('marketops_app', :table, :privilege)")
                        .param("table", table).param("privilege", privilege).query(Boolean.class).single())
                        .describedAs("%s %s", table, privilege).isFalse();
            }
        }
        assertThatThrownBy(() -> f.contain(UUID.randomUUID(), f.id("executorUser"), f.id("listing")))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
    }

    @Test
    @DisplayName("TC-LC-CONTAIN-003 re-enabling needs a repair attestation from the cause owner role and a consent by another person")
    void reenableNeedsTwoAttestationsByTwoPeople() throws Exception {
        var f = ready();
        f.launch(UUID.randomUUID(), "actionOne", f.id("ownerUser"));
        UUID containment = UUID.randomUUID();
        f.contain(containment, f.id("ownerUser"), f.id("listing"));

        assertThatThrownBy(() -> reenable(f, containment, f.id("ownerUser")))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        // The operations lead holds no attestation authority and is not the cause owner role.
        assertThatThrownBy(() -> f.attest(UUID.randomUUID(), containment, f.id("verifierUser"), "REPAIR_ATTESTATION"))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        f.attest(UUID.randomUUID(), containment, f.id("ownerUser"), "REPAIR_ATTESTATION");
        assertThatThrownBy(() -> reenable(f, containment, f.id("ownerUser")))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));

        f.attest(UUID.randomUUID(), containment, f.id("verifierUser"), "BUSINESS_CONSENT");
        reenable(f, containment, f.id("ownerUser"));

        assertThat(f.app.sql("SELECT state FROM ops.lc_containment WHERE id = :id").param("id", containment)
                .query(String.class).single()).isEqualTo("REENABLED");
        assertThat(f.app.sql("SELECT ops.lc_scope_contained(:org, :listing)").param("org", f.id("organization"))
                .param("listing", f.id("listing")).query(Boolean.class).single()).isFalse();
        // Re-enabling the scope revives nothing: the stopped action stays stopped.
        assertThat(f.actionState(f.id("actionOne"))).isEqualTo("CONTAINED");
    }

    @Test
    @DisplayName("TC-LC-CONTAIN-005 one person attesting both the repair and the consent is not two people")
    void onePersonIsNotTwo() throws Exception {
        var f = ready();
        UUID containment = UUID.randomUUID();
        f.contain(containment, f.id("ownerUser"), f.id("listingTwo"));
        f.attest(UUID.randomUUID(), containment, f.id("ownerUser"), "REPAIR_ATTESTATION");

        // The second half from the same person is refused at the attestation itself.
        assertThatThrownBy(() -> f.attest(UUID.randomUUID(), containment, f.id("ownerUser"), "BUSINESS_CONSENT"))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThatThrownBy(() -> reenable(f, containment, f.id("ownerUser")))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThat(f.app.sql("SELECT state FROM ops.lc_containment WHERE id = :id").param("id", containment)
                .query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    void reenableRejectsMissingProofAnotherActorAndAnAttestationProof() throws Exception {
        var f=ready();UUID containment=UUID.randomUUID();
        f.contain(containment,f.id("ownerUser"),f.id("listing"));
        f.attest(UUID.randomUUID(),containment,f.id("ownerUser"),"REPAIR_ATTESTATION");
        f.attest(UUID.randomUUID(),containment,f.id("verifierUser"),"BUSINESS_CONSENT");
        assertThatThrownBy(()->f.app.sql("SELECT ops.reenable_lc_containment(:id,:actor,'not-issued')")
                .param("id",containment).param("actor",f.id("ownerUser")).query(Object.class).optional())
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        for (boolean wrongActor:java.util.List.of(true,false)) {
            assertThatThrownBy(()->{
                try (var connection=f.transaction()) {
                    String proof=f.proof(connection,f.id("ownerUser"),wrongActor?"LISTING_CONTAINMENT_REENABLE":"LISTING_CONTAINMENT_CONSENT",
                            containment,containment);
                    try (var query=connection.prepareStatement("SELECT ops.reenable_lc_containment(?,?,?)")) {
                        query.setObject(1,containment);query.setObject(2,f.id(wrongActor?"verifierUser":"ownerUser"));
                        query.setString(3,proof);query.execute();
                    }
                }
            }).satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        }
        assertThat(f.app.sql("SELECT state FROM ops.lc_containment WHERE id=:id").param("id",containment).query(String.class).single())
                .isEqualTo("ACTIVE");
        f.reenable(containment,f.id("ownerUser"));
        assertThat(f.app.sql("SELECT state FROM ops.lc_containment WHERE id=:id").param("id",containment).query(String.class).single())
                .isEqualTo("REENABLED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"REPAIR_ROLE_REVOKED","CONSENT_SCOPE_EXPIRED"})
    void reenableRejectsAttestationsWhoseCurrentAuthorityNoLongerApplies(String expiredAuthority) throws Exception {
        var f=ready();UUID containment=UUID.randomUUID();
        f.contain(containment,f.id("ownerUser"),f.id("listing"));
        f.attest(UUID.randomUUID(),containment,f.id("ownerUser"),"REPAIR_ATTESTATION");
        f.attest(UUID.randomUUID(),containment,f.id("verifierUser"),"BUSINESS_CONSENT");

        UUID reenableActor;
        if(expiredAuthority.equals("REPAIR_ROLE_REVOKED")) {
            assertThat(f.seed.sql("""
                    UPDATE iam.user_role_assignment
                       SET status='REVOKED',effective_to=clock_timestamp()-interval '1 second',
                           reason='Synthetic current repair authority withdrawal',updated_at=clock_timestamp(),version=version+1
                     WHERE user_id=:actor AND role_code='OWNER' AND status='ACTIVE'
                    """).param("actor",f.id("ownerUser")).update()).isEqualTo(1);
            reenableActor=f.id("verifierUser");
        } else {
            assertThat(f.seed.sql("""
                    UPDATE iam.user_scope_grant
                       SET effective_to=clock_timestamp()-interval '1 second',updated_at=clock_timestamp(),version=version+1
                     WHERE user_id=:actor AND action_code='LISTING_CONTAINMENT_CONSENT' AND status='ACTIVE'
                    """).param("actor",f.id("verifierUser")).update()).isEqualTo(1);
            reenableActor=f.id("ownerUser");
        }

        assertThatThrownBy(()->f.reenable(containment,reenableActor))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThat(f.app.sql("SELECT state FROM ops.lc_containment WHERE id=:id")
                .param("id",containment).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(f.app.sql("SELECT ops.lc_scope_contained(:org,:listing)")
                .param("org",f.id("organization")).param("listing",f.id("listing"))
                .query(Boolean.class).single()).isTrue();
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_containment_attestation WHERE containment_id=:id")
                .param("id",containment).query(Long.class).single()).isEqualTo(2);
    }

    private static void reenable(ListingConversionFixture f, UUID containment, UUID actor) throws Exception {
        f.reenable(containment,actor);
    }

    @Test
    @DisplayName("TC-LC-CONTAIN-004 a stopped scope refuses a new launch and closes the description gate")
    void containedScopeRefusesWork() throws Exception {
        var f = ready();
        f.launch(UUID.randomUUID(), "actionOne", f.id("ownerUser"));
        UUID command = f.createCommand(f.id("actionOne"), f.id("ownerUser"));
        f.contain(UUID.randomUUID(), f.id("verifierUser"), f.id("listing"));

        assertThat(f.gateReasons(command)).contains("SCOPE_CONTAINED", "ACTION_NOT_LAUNCHED");
        assertThatThrownBy(() -> f.launch(UUID.randomUUID(), "actionOne", f.id("ownerUser")))
                .satisfies(failure -> assertThat(java.util.List.of("MO091", "MO092"))
                        .contains(ListingConversionFixture.sqlState(failure)));
    }
}
