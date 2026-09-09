package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;

/**
 * Launch is one function, one proof and every allowance axis, asserted against
 * a real server as the application role.
 *
 * <p>The properties here are the ones that make a second concurrent listing
 * unreachable rather than merely discouraged: the allowance is serialised in
 * the database, the state moves only through the function, and a person's
 * proof is good for exactly one launch of exactly one action.
 */
class ListingActionLaunchIT {

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
    @DisplayName("TC-LC-LAUNCH-001 a launch acquires every published axis and moves the action through the function")
    void launchAcquiresEveryAxis() throws Exception {
        var f = ready();
        UUID launch = UUID.randomUUID();

        JsonNode answer = f.launch(launch, "actionOne", f.id("ownerUser"));

        assertThat(answer.path("launched").asBoolean()).isTrue();
        assertThat(answer.path("occupationIds")).hasSize(2);
        assertThat(f.actionState(f.id("actionOne"))).isEqualTo("LAUNCHED");
        assertThat(f.app.sql("SELECT axis_code FROM ops.lc_exposure_occupation WHERE action_id = :id ORDER BY axis_code")
                .param("id", f.id("actionOne")).query(String.class).list())
                .containsExactly("AFFECTED_VARIANTS", "CONCURRENT_LISTINGS");
        assertThat(f.app.sql("SELECT proof_hash FROM ops.lc_launch WHERE id = :id").param("id", launch)
                .query(String.class).single()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("TC-LC-LAUNCH-002 the second listing is refused on the concurrent-listing axis and stays approved")
    void secondListingIsShortOnTheConcurrentAxis() throws Exception {
        var f = ready();
        f.launch(UUID.randomUUID(), "actionOne", f.id("ownerUser"));

        JsonNode answer = f.launch(UUID.randomUUID(), "actionTwo", f.id("ownerUser"));

        assertThat(answer.path("launched").asBoolean()).isFalse();
        assertThat(answer.path("insufficientAxes")).extracting(JsonNode::asText).containsExactly("CONCURRENT_LISTINGS");
        assertThat(f.actionState(f.id("actionTwo"))).isEqualTo("APPROVED_NOT_LAUNCHABLE");
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_launch WHERE action_id = :id").param("id", f.id("actionTwo"))
                .query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("TC-LC-LAUNCH-003 two launches racing for one allowance are serialised; exactly one wins")
    void racingLaunchesAreSerialised() throws Exception {
        var f = ready();
        CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
        CountDownLatch secondMayFinish = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> first = pool.submit(() -> {
                try (Connection connection = f.transaction()) {
                    String proof = f.proof(connection, f.id("ownerUser"), "LISTING_ACTION_LAUNCH",
                            f.id("recommendationOne"), f.id("approvalOne"));
                    try (var query = connection.prepareStatement(
                            "SELECT ops.acquire_lc_launch_allowance(?, ?, ?, ?, '{}'::jsonb)::text")) {
                        query.setObject(1, UUID.randomUUID());
                        query.setObject(2, f.id("actionOne"));
                        query.setObject(3, f.id("ownerUser"));
                        query.setString(4, proof);
                        JsonNode answer;
                        try (var rows = query.executeQuery()) {
                            rows.next();
                            answer = new tools.jackson.databind.ObjectMapper().readTree(rows.getString(1));
                        }
                        firstHoldsTheLock.countDown();
                        // Hold the transaction, and the advisory lock with it, until the
                        // second launch is known to be waiting behind it.
                        secondMayFinish.await(30, TimeUnit.SECONDS);
                        connection.commit();
                        return answer;
                    }
                }
            });
            firstHoldsTheLock.await(30, TimeUnit.SECONDS);
            Future<JsonNode> second = pool.submit(() -> f.launch(UUID.randomUUID(), "actionTwo", f.id("ownerUser")));
            Thread.sleep(500);
            assertThat(second.isDone()).describedAs("the second launch waits for the first").isFalse();
            secondMayFinish.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS).path("launched").asBoolean()).isTrue();
            assertThat(second.get(30, TimeUnit.SECONDS).path("launched").asBoolean()).isFalse();
        } finally {
            pool.shutdownNow();
        }
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_exposure_occupation WHERE axis_code = 'CONCURRENT_LISTINGS' AND state <> 'RELEASED'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-LC-LAUNCH-004 a proof issued to one person launches nothing for another")
    void proofBelongsToOnePerson() throws Exception {
        var f = ready();
        try (Connection connection = f.transaction()) {
            String proof = f.proof(connection, f.id("ownerUser"), "LISTING_ACTION_LAUNCH",
                    f.id("recommendationOne"), f.id("approvalOne"));
            assertThatThrownBy(() -> {
                try (var query = connection.prepareStatement(
                        "SELECT ops.acquire_lc_launch_allowance(?, ?, ?, ?, '{}'::jsonb)")) {
                    query.setObject(1, UUID.randomUUID());
                    query.setObject(2, f.id("actionOne"));
                    query.setObject(3, f.id("verifierUser"));
                    query.setString(4, proof);
                    query.executeQuery();
                }
            }).satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
            connection.rollback();
        }
        assertThat(f.actionState(f.id("actionOne"))).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("TC-LC-LAUNCH-005 the state cannot be moved by hand, reviewed by its author or packeted before launch")
    void stateMovesOnlyThroughTheFunctions() throws Exception {
        var f = ready();

        assertThatThrownBy(() -> f.app.sql("UPDATE ops.lc_action SET state = 'LAUNCHED' WHERE id = :id")
                .param("id", f.id("actionTwo")).update())
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThatThrownBy(() -> f.app.sql("""
                INSERT INTO ops.lc_action_review(id,organization_id,action_id,reviewer_user_id,attested_target_text_digest,
                    attested_current_text_digest,attested_affected_set_digest,facts_digest,verdict,reason,reviewed_at)
                SELECT gen_random_uuid(),organization_id,id,author_user_id,target_text_digest,current_text_digest,
                    affected_set_digest,repeat('f',64),'ATTESTED','self review',now()
                  FROM ops.lc_action WHERE id = :id
                """).param("id", f.id("actionTwo")).update())
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThatThrownBy(() -> f.app.sql("""
                INSERT INTO ops.lc_manual_packet(id,organization_id,action_id,launch_id,executor_user_id,issued_by_user_id,
                    issued_at,expires_at,native_listing_key,affected_set_digest,target_text,execution_path,state,updated_at,version)
                SELECT gen_random_uuid(),organization_id,id,:launch,:executor,:issuer,now(),now()+interval '30 minutes',
                    'fictional-listing-two',affected_set_digest,target_text,'MANUAL','ISSUED',now(),1
                  FROM ops.lc_action WHERE id = :id
                """).param("id", f.id("actionTwo")).param("launch", UUID.randomUUID())
                .param("executor", f.id("executorUser")).param("issuer", f.id("verifierUser")).update())
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO090"));
        assertThat(f.actionState(f.id("actionTwo"))).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("TC-LC-LAUNCH-006 a release with stop evidence frees the axis and the waiting action launches")
    void releaseFreesTheAxis() throws Exception {
        var f = ready();
        f.launch(UUID.randomUUID(), "actionOne", f.id("ownerUser"));
        assertThat(f.launch(UUID.randomUUID(), "actionTwo", f.id("ownerUser")).path("launched").asBoolean()).isFalse();
        UUID occupation = f.app.sql("SELECT id FROM ops.lc_exposure_occupation WHERE action_id = :id AND axis_code = 'CONCURRENT_LISTINGS'")
                .param("id", f.id("actionOne")).query(UUID.class).single();
        UUID evidence = f.displayObservation("listing", ListingConversionFixture.TARGET_TEXT_ONE, f.id("verifierUser"));

        f.release(occupation, f.id("ownerUser"), evidence);

        assertThat(f.app.sql("SELECT state FROM ops.lc_exposure_occupation WHERE id = :id").param("id", occupation)
                .query(String.class).single()).isEqualTo("RELEASED");
        assertThat(f.launch(UUID.randomUUID(), "actionTwo", f.id("ownerUser")).path("launched").asBoolean()).isTrue();
        assertThat(f.actionState(f.id("actionTwo"))).isEqualTo("LAUNCHED");
    }

    @Test
    @DisplayName("TC-LC-LAUNCH-007 a failed necessary condition or a contained scope refuses the launch outright")
    void healthAndContainmentRefuseLaunch() throws Exception {
        var f = ready();
        f.seed.sql("""
                INSERT INTO mart.lc_listing_health(id,organization_id,store_id,platform_listing_id,calculation_run_id,affected_set_id,
                    health_version,necessary_conditions,necessary_state,eligibility,opportunities,definition_digest,computed_at)
                SELECT gen_random_uuid(),organization_id,store_id,platform_listing_id,calculation_run_id,affected_set_id,2,
                    '[{"code":"NOT_CONTAINED","state":"FAIL","evidenceReference":"ops.lc_containment"}]','FAIL',
                    '{"MEASUREMENT":"UNKNOWN","PROTECTION":"INELIGIBLE","EVALUATION":"INELIGIBLE"}','[]',repeat('c',64),now()
                  FROM mart.lc_listing_health WHERE id = :id
                """).param("id", f.id("healthTwo")).update();

        assertThatThrownBy(() -> f.launch(UUID.randomUUID(), "actionTwo", f.id("ownerUser")))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThat(f.actionState(f.id("actionTwo"))).isEqualTo("APPROVED");

        f.contain(UUID.randomUUID(), f.id("ownerUser"), f.id("listing"));
        assertThat(f.actionState(f.id("actionOne"))).isEqualTo("CONTAINED");
        assertThatThrownBy(() -> f.launch(UUID.randomUUID(), "actionOne", f.id("ownerUser")))
                .satisfies(failure -> assertThat(List.of("MO091", "MO092"))
                        .contains(ListingConversionFixture.sqlState(failure)));
    }
}
