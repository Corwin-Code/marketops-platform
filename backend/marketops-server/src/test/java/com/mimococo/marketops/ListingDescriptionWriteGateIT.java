package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The description write gate, asserted from the outside.
 *
 * <p>With the capability verified and available, both switches on, the listing
 * allowlisted, the approval standing, the action launched and its allowance
 * occupied, the one thing that still closes the gate is the Owner-published
 * gate authority with production writes disabled. Nothing in this product can
 * open it; the database refuses the lease while it is closed.
 */
class ListingDescriptionWriteGateIT {

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

    private static ListingConversionFixture launched() throws Exception {
        var f = new ListingConversionFixture(migration, application, admin);
        assertThat(f.launch(UUID.randomUUID(), "actionOne", f.id("ownerUser")).path("launched").asBoolean()).isTrue();
        return f;
    }

    @Test
    @DisplayName("TC-LC-GATE-001 a launched API action becomes exactly one command, and the gate names only the authority")
    void gateIsClosedOnlyByTheOwnerAuthority() throws Exception {
        var f = launched();

        UUID command = f.createCommand(f.id("actionOne"), f.id("ownerUser"));

        assertThat(f.createCommand(f.id("actionOne"), f.id("ownerUser"))).isEqualTo(command);
        assertThat(f.app.sql("SELECT state, prior_text, idempotency_key FROM ops.lc_description_command WHERE id = :id")
                .param("id", command).query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3)).single())
                .isEqualTo("PENDING|" + ListingConversionFixture.PRIOR_TEXT_ONE + "|lcd-"
                        + f.id("actionOne").toString().replace("-", ""));
        assertThat(f.gateReasons(command)).containsExactly("PRODUCTION_WRITE_DISABLED");
        assertThatThrownBy(() -> f.app.sql("SELECT ops.lease_lc_description_command(:id, 'fixture-worker', 60)")
                .param("id", command).query(Long.class).single())
                .hasMessageContaining("PRODUCTION_WRITE_DISABLED");
        assertThat(f.app.sql("SELECT state FROM ops.lc_description_command WHERE id = :id").param("id", command)
                .query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("TC-LC-GATE-002 the application role cannot write a command, an attempt or a readback itself")
    void applicationRoleCannotWriteTheOutbox() throws Exception {
        var f = launched();
        for (String table : java.util.List.of("ops.lc_description_command", "ops.lc_description_command_attempt",
                "ops.lc_description_command_readback", "ops.lc_description_command_transition",
                "raw.lc_description_response_observation", "ops.lc_gate_authority")) {
            for (String privilege : java.util.List.of("INSERT", "UPDATE", "DELETE")) {
                assertThat(f.app.sql("SELECT has_table_privilege('marketops_app', :table, :privilege)")
                        .param("table", table).param("privilege", privilege).query(Boolean.class).single())
                        .describedAs("%s %s", table, privilege).isFalse();
            }
        }
    }

    @Test
    @DisplayName("TC-LC-GATE-003 a scoped kill switch, an elapsed approval and a containment each close the gate by name")
    void eachClosureIsNamed() throws Exception {
        var f = launched();
        UUID command = f.createCommand(f.id("actionOne"), f.id("ownerUser"));

        f.seed.sql("""
                INSERT INTO platform.feature_flag(id,flag_code,flag_kind,scope_kind,store_id,state,status,reason,created_at,updated_at)
                VALUES (gen_random_uuid(),'listing-description-write','WRITE_CAPABILITY','STORE',:store,'DISABLED','ACTIVE','fixture stop',now(),now())
                """).param("store", f.id("store")).update();
        assertThat(f.gateReasons(command)).contains("SCOPED_SWITCH_DISABLED", "PRODUCTION_WRITE_DISABLED");

        f.seed.sql("UPDATE ops.approval_decision SET decided_at=clock_timestamp()-interval '2 minutes', scope_expires_at = clock_timestamp() - interval '1 second' WHERE id = :id")
                .param("id", f.id("approvalOne")).update();
        f.seed.sql("UPDATE ops.lc_action_binding SET bound_at=clock_timestamp()-interval '2 minutes', expires_at = clock_timestamp() - interval '1 second' WHERE id = :id")
                .param("id", f.id("bindingOne")).update();
        assertThat(f.gateReasons(command)).contains("AUTHORIZATION_INVALID_OR_EXPIRED", "BINDING_EXPIRED");

        f.contain(UUID.randomUUID(), f.id("ownerUser"), f.id("listing"));
        assertThat(f.gateReasons(command)).contains("SCOPE_CONTAINED", "ACTION_NOT_LAUNCHED");
    }

    @Test
    @DisplayName("TC-LC-GATE-004 only a launched description change on the API path becomes a command")
    void manualOrUnlaunchedActionsNeverBecomeCommands() throws Exception {
        var f = new ListingConversionFixture(migration, application, admin);

        assertThatThrownBy(() -> f.createCommand(f.id("actionOne"), f.id("ownerUser")))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        f.launch(UUID.randomUUID(), "actionTwo", f.id("ownerUser"));
        assertThatThrownBy(() -> f.createCommand(f.id("actionTwo"), f.id("ownerUser")))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_description_command WHERE organization_id=:org")
                .param("org",f.id("organization")).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("TC-LC-GATE-005 a moved current text blocks the already queued command before any attempt")
    void movedTextRefusesExecution() throws Exception {
        var f = launched();
        UUID command=f.createCommand(f.id("actionOne"),f.id("ownerUser"));
        movedText(f);
        assertThat(f.createCommand(f.id("actionOne"),f.id("ownerUser"))).isEqualTo(command);
        assertThat(f.gateReasons(command)).contains("CURRENT_TEXT_MOVED");
        assertThatThrownBy(()->f.app.sql("SELECT ops.lease_lc_description_command(:id,'moved-text-fixture',60)")
                .param("id",command).query(Long.class).single()).hasMessageContaining("CURRENT_TEXT_MOVED");
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_description_command_attempt WHERE command_id=:id")
                .param("id",command).query(Integer.class).single()).isZero();
        assertThat(f.app.sql("SELECT state FROM ops.lc_description_command WHERE id=:id")
                .param("id",command).query(String.class).single()).isEqualTo("PENDING");
        assertThatThrownBy(() -> f.app.sql("SELECT ops.create_lc_description_command(:action, :actor, 999, 'listing-fixture')")
                .param("action", f.id("actionOne")).param("actor", f.id("ownerUser")).query(UUID.class).single())
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO090"));
    }

    @Test
    void movedTextBeforeLaunchCreatesNeitherLaunchNorCommand() throws Exception {
        var f=new ListingConversionFixture(migration,application,admin);
        movedText(f);
        assertThatThrownBy(()->f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser"))).hasMessageContaining("CURRENT_TEXT_MOVED");
        assertThat(f.actionState(f.id("actionOne"))).isEqualTo("APPROVED");
        for (String table:List.of("lc_launch","lc_exposure_occupation","lc_description_command"))
            assertThat(f.app.sql("SELECT count(*) FROM ops."+table+" WHERE action_id=:id")
                    .param("id",f.id("actionOne")).query(Integer.class).single()).isZero();
    }

    private static void movedText(ListingConversionFixture f) {
        f.seed.sql("""
                INSERT INTO core.lc_description_observation(id,organization_id,provenance_id,platform_listing_id,source_fact_key,
                    observed_at,acquired_at,description_text,text_digest,language_code,kiz_marked_declared)
                VALUES (gen_random_uuid(),:org,:provenance,:listing,'fictional-description-moved',now(),now(),
                    'Текст, изменённый кем-то ещё',encode(sha256(convert_to('Текст, изменённый кем-то ещё','UTF8')),'hex'),'ru',false)
                """).param("org", f.id("organization")).param("provenance", f.id("provenance"))
                .param("listing", f.id("listing")).update();

    }

    @Test
    void anotherScopeCannotLendEnablementOrImposeAStoreStop() throws Exception {
        var f=launched();
        var other=launched();
        UUID command=f.createCommand(f.id("actionOne"),f.id("ownerUser"));
        other.seed.sql("""
                INSERT INTO platform.feature_flag(id,flag_code,flag_kind,scope_kind,store_id,state,status,reason,created_at,updated_at)
                VALUES(gen_random_uuid(),'listing-description-write','WRITE_CAPABILITY','STORE',:store,'DISABLED','ACTIVE',
                    'synthetic unrelated stop',now(),now())
                """).param("store",other.id("store")).update();
        assertThat(f.gateReasons(command)).containsExactly("PRODUCTION_WRITE_DISABLED");
        f.seed.sql("UPDATE platform.feature_flag SET state='DISABLED' WHERE flag_code='listing-description-write' AND capability_id=:cap")
                .param("cap",f.id("capability")).update();
        assertThat(f.gateReasons(command)).contains("CAPABILITY_SWITCH_DISABLED");
        f.seed.sql("UPDATE platform.feature_flag SET state='ENABLED' WHERE flag_code='listing-description-write' AND capability_id=:cap")
                .param("cap",f.id("capability")).update();
        assertThat(f.gateReasons(command)).containsExactly("PRODUCTION_WRITE_DISABLED");
    }

    @Test
    @DisplayName("TC-LC-GATE-006 with every other condition met, the Owner authority alone decides")
    void theOwnerAuthorityAloneDecides() throws Exception {
        var f = launched();
        UUID command = f.createCommand(f.id("actionOne"), f.id("ownerUser"));
        assertThat(f.gateReasons(command)).containsExactly("PRODUCTION_WRITE_DISABLED");

        // A test-only isolated server: the Owner's row is flipped here to prove
        // the chain behind it is complete, and nowhere else.
        f.seed.sql("UPDATE ops.lc_gate_authority SET production_write_enabled = true WHERE id = :id")
                .param("id", f.id("gateAuthority")).update();
        assertThat(f.gateReasons(command)).isEmpty();

        f.seed.sql("UPDATE ops.lc_gate_authority SET production_write_enabled = false WHERE id = :id")
                .param("id", f.id("gateAuthority")).update();
        assertThat(f.gateReasons(command)).containsExactly("PRODUCTION_WRITE_DISABLED");
        assertThatThrownBy(() -> f.seed.sql(
                "UPDATE ops.lc_gate_authority SET production_write_enabled = true, status = 'SUSPENDED' WHERE id = :id")
                .param("id", f.id("gateAuthority")).update())
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("23514"));
    }
}
