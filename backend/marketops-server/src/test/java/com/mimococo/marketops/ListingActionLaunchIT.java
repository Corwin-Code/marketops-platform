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
    void listingExecutionEvidenceIsStampedByItsActualTransaction() throws Exception {
        var f=ready();
        UUID evaluation=UUID.randomUUID();
        long transaction;
        try (Connection connection=f.transaction()) {
            try (var insert=connection.prepareStatement("""
                    INSERT INTO ops.guardrail_evaluation(id,organization_id,recommendation_id,
                        lc_calibration_package_id,lc_calibration_version,purpose,outcome,reason_codes,
                        detail,input_digest,evaluated_at,correlation_id,authority_snapshot,listing_transaction_id)
                    SELECT ?,e.organization_id,e.recommendation_id,e.lc_calibration_package_id,e.lc_calibration_version,
                        'EXECUTION',e.outcome,e.reason_codes,e.detail,e.input_digest,clock_timestamp(),
                        'synthetic-transaction-stamp',ops.lc_authority_snapshot(e.recommendation_id),-1
                    FROM ops.lc_action_binding b JOIN ops.guardrail_evaluation e ON e.id=b.guardrail_evaluation_id
                    WHERE b.action_id=?
                    RETURNING listing_transaction_id,txid_current()
                    """)) {
                insert.setObject(1,evaluation);insert.setObject(2,f.id("actionOne"));
                try (var result=insert.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    transaction=result.getLong(1);
                    assertThat(transaction).isEqualTo(result.getLong(2)).isNotEqualTo(-1);
                }
            }
            connection.commit();
        }
        assertThat(f.app.sql("SELECT has_function_privilege(current_user,'ops.acquire_lc_launch_allowance(uuid,uuid,uuid,text,jsonb)','EXECUTE')")
                .query(Boolean.class).single()).isFalse();
        assertThat(f.app.sql("SELECT listing_transaction_id=:prior AND listing_transaction_id<>txid_current() FROM ops.guardrail_evaluation WHERE id=:id")
                .param("prior",transaction).param("id",evaluation).query(Boolean.class).single()).isTrue();
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_launch WHERE action_id=:id")
                .param("id",f.id("actionOne")).query(Long.class).single()).isZero();
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
        UUID command=UUID.fromString(answer.path("commandId").asText());
        assertThat(f.app.sql("""
                SELECT c.action_id=:action AND c.launch_id=:launch AND c.state='PENDING'
                    AND c.attempt_no=0 AND l.created_transaction_id IS NOT NULL
                    AND EXISTS(SELECT 1 FROM ops.guardrail_evaluation e WHERE e.id=l.execution_guardrail_id
                        AND e.listing_transaction_id=l.created_transaction_id AND e.purpose='EXECUTION'
                        AND e.detail->>'actionId'=l.action_id::text)
                FROM ops.lc_description_command c JOIN ops.lc_launch l ON l.id=c.launch_id WHERE c.id=:command
                """).param("action",f.id("actionOne")).param("launch",launch).param("command",command)
                .query(Boolean.class).single()).isTrue();
        assertThat(f.createCommand(f.id("actionOne"),f.id("ownerUser"))).isEqualTo(command);
        assertThat(f.app.sql("""
                SELECT n.nspname||'.'||p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE p.prosrc ~* 'insert[[:space:]]+into[[:space:]]+ops[.]lc_description_command[[:space:]]*[(]'
                """).query(String.class).list()).containsExactly("ops.create_lc_description_command");
    }

    @Test
    void aNecessaryOutcomeFailureBlocksOnlyItsListingAndAHealthyRevisionDoesNotReleaseIt() throws Exception {
        var f=ready();
        UUID failed=UUID.randomUUID();
        String insert="""
                INSERT INTO ops.lc_node_result(id,organization_id,plan_id,node_code,stage,revision_no,
                    calculation_run_id,accepted_threshold,verdict,protection_vector,protection_verdict,
                    stop_triggered,maturity_reached,evaluated_at,evaluation_evidence)
                SELECT :id,p.organization_id,p.id,p.formal_nodes->0->>'nodeCode','OPERATIONAL',:revision,
                    h.calculation_run_id,0.01,'UNDETERMINED',CAST(:vector AS jsonb),:verdict,false,false,clock_timestamp(),
                    jsonb_build_object('planDigest',p.plan_digest,'nodeCode',p.formal_nodes->0->>'nodeCode',
                        'requestedStage','OPERATIONAL','qualificationGaps','[]'::jsonb)
                FROM ops.lc_evaluation_plan p JOIN ops.lc_action a ON a.id=p.action_id
                JOIN LATERAL (SELECT calculation_run_id FROM mart.lc_listing_health
                    WHERE platform_listing_id=a.platform_listing_id ORDER BY health_version DESC LIMIT 1) h ON true
                WHERE a.id=:action
                """;
        String failedVector="{\"DIRECT_CONTRIBUTION_PROFIT\":\"FAIL\",\"LINKED_SCOPE_PROFIT\":\"UNDETERMINED\",\"OVERALL_RETURN_RATE\":\"UNDETERMINED\",\"CRITICAL_VARIANT_RETURN\":\"UNDETERMINED\",\"SUPPLY_COVERAGE\":\"UNDETERMINED\"}";
        assertThat(f.app.sql(insert).param("id",failed).param("revision",0).param("vector",failedVector)
                .param("verdict","FAIL").param("action",f.id("actionOne")).update()).isEqualTo(1);
        assertThat(f.app.sql("SELECT ops.lc_scope_contained(:org,:listing)").param("org",f.id("organization"))
                .param("listing",f.id("listing")).query(Boolean.class).single()).isTrue();
        assertThat(f.app.sql("SELECT ops.lc_scope_contained(:org,:listing)").param("org",f.id("organization"))
                .param("listing",f.id("listingTwo")).query(Boolean.class).single()).isFalse();
        String healthy=failedVector.replace("FAIL","PASS").replace("UNDETERMINED","PASS");
        assertThat(f.app.sql(insert).param("id",UUID.randomUUID()).param("revision",1).param("vector",healthy)
                .param("verdict","PASS").param("action",f.id("actionOne")).update()).isEqualTo(1);
        assertThat(f.app.sql("SELECT unnest(ops.lc_unreleased_outcome_failures(:org,:listing))")
                .param("org",f.id("organization")).param("listing",f.id("listing")).query(UUID.class).list())
                .containsExactly(failed);
        assertThat(f.app.sql("SELECT has_function_privilege(current_user,'ops.reenable_lc_containment_v0077(uuid,uuid)','EXECUTE')")
                .query(Boolean.class).single()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void authenticatedLaunchCannotBorrowAnAbsentOrPreviouslyCommittedExecutionEvaluation(boolean historical) throws Exception {
        var f=ready();
        UUID priorEvaluation;
        if (historical) try (Connection connection=f.transaction()) {
            priorEvaluation=f.syntheticExecutionEvidence(connection,f.id("actionOne"));
            connection.commit();
        } else priorEvaluation=null;
        try (Connection connection=f.transaction()) {
            String proof=f.proof(connection,f.id("ownerUser"),"LISTING_ACTION_LAUNCH",
                    f.id("recommendationOne"),f.id("approvalOne"));
            assertThatThrownBy(()->{
                try (var query=connection.prepareStatement("SELECT ops.acquire_lc_launch_allowance(?,?,?,?, '{}'::jsonb, ?)")) {
                    query.setObject(1,UUID.randomUUID());query.setObject(2,f.id("actionOne"));
                    query.setObject(3,f.id("ownerUser"));query.setString(4,proof);query.setObject(5,priorEvaluation);query.executeQuery();
                }
            }).isInstanceOfSatisfying(java.sql.SQLException.class,failure->{
                assertThat(failure.getSQLState()).isEqualTo("MO092");
                assertThat(failure.getMessage()).contains("exact current protected execution evaluation");
            });
            connection.rollback();
        }
        for (String table:List.of("lc_launch","lc_exposure_occupation","lc_description_command"))
            assertThat(f.app.sql("SELECT count(*) FROM ops."+table+" WHERE action_id=:id")
                    .param("id",f.id("actionOne")).query(Long.class).single()).as(table).isZero();
    }

    @Test
    void manualLaunchCreatesNoApiCommand() throws Exception {
        var f=ready();
        var result=f.launch(UUID.randomUUID(),"actionTwo",f.id("ownerUser"));
        assertThat(result.path("launched").asBoolean()).isTrue();
        assertThat(result.path("commandId").isNull()).isTrue();
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:action")
                .param("action",f.id("actionTwo")).query(Integer.class).single()).isZero();
    }

    @Test
    void commandCreationFailureRollsBackLaunchAndEveryAcquiredAxis() throws Exception {
        var f=ready();
        // Remove the command's described capability from this synthetic
        // platform without changing any launch/approval or allowance control.
        f.seed.sql("UPDATE platform.platform_capability SET capability_code='fixture-unavailable' WHERE id=:id")
                .param("id",f.id("capability")).update();
        assertThatThrownBy(()->f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser")))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThat(f.actionState(f.id("actionOne"))).isEqualTo("APPROVED");
        for (String table:List.of("lc_launch","lc_exposure_occupation","lc_description_command"))
            assertThat(f.app.sql("SELECT count(*) FROM ops."+table+" WHERE action_id=:action")
                    .param("action",f.id("actionOne")).query(Integer.class).single()).as(table).isZero();
    }

    @Test
    void idempotentCommandLookupStillRequiresTheCurrentActorsScope() throws Exception {
        var f=ready();
        f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser"));
        var foreign=ready();
        assertThatThrownBy(()->f.createCommand(f.id("actionOne"),foreign.id("ownerUser")))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
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
        var f = new ListingConversionFixture(migration,application,admin,false,70);
        f.seed.sql("UPDATE ops.lc_exposure_allowance SET limit_value=100 WHERE organization_id=:org")
                .param("org",f.id("organization")).update();
        assertThat(f.app.sql("SELECT cardinality(platform_listing_variant_ids) FROM core.lc_affected_set WHERE organization_id=:org")
                .param("org",f.id("organization")).query(Integer.class).list()).containsExactlyInAnyOrder(70,70);
        CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
        CountDownLatch secondMayFinish = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> first = pool.submit(() -> {
                try (Connection connection = f.transaction()) {
                    UUID executionEvaluation=f.syntheticExecutionEvidence(connection,f.id("actionOne"));
                    String proof = f.proof(connection, f.id("ownerUser"), "LISTING_ACTION_LAUNCH",
                            f.id("recommendationOne"), f.id("approvalOne"));
                    try (var query = connection.prepareStatement(
                            "SELECT ops.acquire_lc_launch_allowance(?, ?, ?, ?, '{}'::jsonb, ?)::text")) {
                        query.setObject(1, UUID.randomUUID());
                        query.setObject(2, f.id("actionOne"));
                        query.setObject(3, f.id("ownerUser"));
                        query.setString(4, proof);
                        query.setObject(5,executionEvaluation);
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
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_exposure_occupation WHERE organization_id=:org AND axis_code = 'CONCURRENT_LISTINGS' AND state <> 'RELEASED'")
                .param("org",f.id("organization")).query(Integer.class).single()).isEqualTo(1);
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
                        "SELECT ops.acquire_lc_launch_allowance(?, ?, ?, ?, '{}'::jsonb, NULL::uuid)")) {
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
    @DisplayName("TC-LC-LAUNCH-006 ordinary target observation cannot release active exposure; proven no-submit can")
    void releaseRequiresExactPurposeQualifiedEvidence() throws Exception {
        var f = ready();
        f.launch(UUID.randomUUID(), "actionOne", f.id("ownerUser"));
        assertThat(f.launch(UUID.randomUUID(), "actionTwo", f.id("ownerUser")).path("launched").asBoolean()).isFalse();
        UUID occupation = f.app.sql("SELECT id FROM ops.lc_exposure_occupation WHERE action_id = :id AND axis_code = 'CONCURRENT_LISTINGS'")
                .param("id", f.id("actionOne")).query(UUID.class).single();
        UUID evidence = f.displayObservation("listing", ListingConversionFixture.TARGET_TEXT_ONE, f.id("verifierUser"));

        assertThatThrownBy(()->f.release(occupation,f.id("ownerUser"),evidence))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThatThrownBy(()->f.app.sql("SELECT ops.observe_lc_occupation(:id,'UNKNOWN',0)")
                .param("id",occupation).query(Object.class).optional())
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("42501"));
        UUID command=f.createCommand(f.id("actionOne"),f.id("ownerUser"));
        assertThatThrownBy(()->f.release(occupation,f.id("ownerUser"),command,"NOT_APPLIED_PROVEN"))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThat(f.app.sql("SELECT ops.transition_lc_description_command(:id,0,NULL,'TERMINATED_WITHOUT_PROVIDER_CALL','SCOPE_STOPPED',NULL,NULL)")
                .param("id",command).query(String.class).single()).isEqualTo("TERMINATED_WITHOUT_PROVIDER_CALL");
        f.release(occupation,f.id("ownerUser"),command,"NOT_APPLIED_PROVEN");
        assertThat(f.app.sql("SELECT release_evidence->>'purpose' FROM ops.lc_exposure_occupation WHERE id=:id")
                .param("id",occupation).query(String.class).single()).isEqualTo("NOT_APPLIED");

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
    private static JsonNode projection(ListingConversionFixture f,String action) {
        return new tools.jackson.databind.ObjectMapper().readTree(f.app.sql(
                "SELECT ops.lc_allowance_projection(:action,clock_timestamp())::text")
                .param("action",f.id(action)).query(String.class).single());
    }

    @Test
    void configurationReplacementRetainsOldUnknownOccupationAndPreviewMatchesLaunch() throws Exception {
        var f=ready();
        f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser"));
        // Historical unsafe zeroes cannot erase the outstanding canonical obligation.
        f.seed.sql("UPDATE ops.lc_exposure_occupation SET state='UNKNOWN',occupied_value=0 WHERE action_id=:action")
                .param("action",f.id("actionOne")).update();
        f.seed.sql("UPDATE ops.lc_exposure_allowance SET status='RETIRED' WHERE id=:old")
                .param("old",f.id("allowanceConcurrent")).update();
        UUID replacement=UUID.randomUUID();
        f.seed.sql("""
                INSERT INTO ops.lc_exposure_allowance(id,organization_id,allowance_version,scope_kind,axis_code,
                  limit_value,reserve_value,unit_code,published_by_user_id,published_at,evidence_reference,effective_from,status)
                SELECT :id,organization_id,2,scope_kind,axis_code,limit_value,reserve_value,unit_code,
                  published_by_user_id,clock_timestamp(),'fixture://replacement',clock_timestamp(),'ACTIVE'
                FROM ops.lc_exposure_allowance WHERE id=:old
                """).param("id",replacement).param("old",f.id("allowanceConcurrent")).update();
        JsonNode before=projection(f,"actionTwo");
        assertThat(before.path("resolved").asBoolean()).isTrue();
        JsonNode axis=java.util.stream.StreamSupport.stream(before.path("axes").spliterator(),false)
                .filter(x->x.path("axisCode").asText().equals("CONCURRENT_LISTINGS")).findFirst().orElseThrow();
        assertThat(axis.path("allowanceId").asText()).isEqualTo(replacement.toString());
        assertThat(axis.path("occupiedValue").asInt()).isEqualTo(1);
        assertThat(axis.path("sufficient").asBoolean()).isFalse();
        assertThat(f.launch(UUID.randomUUID(),"actionTwo",f.id("ownerUser")).path("insufficientAxes"))
                .extracting(JsonNode::asText).containsExactly("CONCURRENT_LISTINGS");
    }

    @Test
    void missingRequiredAxisDoesNotDisappearFromLaunchRequirements() throws Exception {
        var f=ready();
        f.seed.sql("UPDATE ops.lc_exposure_allowance SET status='RETIRED' WHERE id=:id")
                .param("id",f.id("allowanceVariants")).update();
        assertThat(projection(f,"actionOne").path("gaps")).extracting(JsonNode::asText)
                .containsExactly("AFFECTED_VARIANTS:ALLOWANCE_MISSING");
        JsonNode result=f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser"));
        assertThat(result.path("launched").asBoolean()).isFalse();
        assertThat(f.actionState(f.id("actionOne"))).isEqualTo("APPROVED_NOT_LAUNCHABLE");
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_exposure_occupation WHERE organization_id=:org")
                .param("org",f.id("organization")).query(Integer.class).single()).isZero();
    }

    private static void addStoreBudget(ListingConversionFixture f) {
        f.seed.sql("""
                INSERT INTO ops.lc_exposure_allowance(id,organization_id,allowance_version,scope_kind,store_ref_id,axis_code,
                  limit_value,reserve_value,unit_code,published_by_user_id,published_at,evidence_reference,effective_from,status)
                SELECT gen_random_uuid(),organization_id,1,'STORE',:store,axis_code,10,0,unit_code,
                  published_by_user_id,clock_timestamp(),'fixture://store-budget',clock_timestamp(),'ACTIVE'
                FROM ops.lc_exposure_allowance WHERE id=:id
                """).param("store",f.id("store")).param("id",f.id("allowanceConcurrent")).update();
    }

    @Test
    void hierarchyRequiresAnAcceptedCompositionAndCannotBypassTheOrganizationBalance() throws Exception {
        var f=ready();
        addStoreBudget(f);
        assertThat(projection(f,"actionOne").path("gaps")).extracting(JsonNode::asText)
                .containsExactly("CONCURRENT_LISTINGS:SCOPE_COMPOSITION_UNRESOLVED");
        // A separate fixture accepts composition BEFORE activation; never mutate an accepted package.
        f=new ListingConversionFixture(migration,application,admin,false,1,
                "{\"axes\":[\"CONCURRENT_LISTINGS\",\"AFFECTED_VARIANTS\"],\"scopeComposition\":\"ALL_APPLICABLE\"}");
        addStoreBudget(f);
        assertThat(projection(f,"actionOne").path("axes")).hasSize(3);
        assertThat(f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser")).path("launched").asBoolean()).isTrue();
        assertThat(f.launch(UUID.randomUUID(),"actionTwo",f.id("ownerUser")).path("launched").asBoolean()).isFalse();
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_exposure_occupation WHERE action_id=:action")
                .param("action",f.id("actionOne")).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void callerAmountsCannotSetOrLowerCanonicalOccupation() throws Exception {
        var f=ready();
        JsonNode result=f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser"),
                "{\"CONCURRENT_LISTINGS\":\"0\",\"AFFECTED_VARIANTS\":\"0\",\"REVENUE_EXPOSURE\":\"999999\"}");
        assertThat(result.path("launched").asBoolean()).isTrue();
        assertThat(f.app.sql("SELECT requested_value=1 AND occupied_value=1 AND demand_evidence->>'actionId'=:actionText FROM ops.lc_exposure_occupation WHERE action_id=:action")
                .param("action",f.id("actionOne")).param("actionText",f.id("actionOne").toString())
                .query(Boolean.class).list()).containsExactly(true,true);
        assertThat(projection(f,"actionOne").path("axes")).allSatisfy(axis->
                assertThat(axis.path("requestedValue").asInt()).isZero());
    }

    @Test
    void disposalReserveCannotBeConsumedOrOffsetByAnotherAxis() throws Exception {
        var f=new ListingConversionFixture(migration,application,admin,false,70);
        f.seed.sql("UPDATE ops.lc_exposure_allowance SET limit_value=70,reserve_value=1 WHERE id=:id")
                .param("id",f.id("allowanceVariants")).update();
        var result=f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser"));
        assertThat(result.path("launched").asBoolean()).isFalse();
        assertThat(result.path("insufficientAxes")).extracting(JsonNode::asText).containsExactly("AFFECTED_VARIANTS");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"null","[]","42","[\"CONCURRENT_LISTINGS\",\"CONCURRENT_LISTINGS\"]","{\"axes\":[\"CONCURRENT_LISTINGS\"],\"scopeComposition\":\"UNACCEPTED_PRIORITY\"}"})
    void malformedAxisPolicyCannotBeTreatedAsUnlimited(String policy) throws Exception {
        var f=new ListingConversionFixture(migration,application,admin,false,1,policy);
        assertThat(projection(f,"actionOne").path("resolved").asBoolean()).isFalse();
        assertThat(f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser")).path("launched").asBoolean()).isFalse();
        assertThat(f.app.sql("SELECT count(*) FROM ops.lc_exposure_occupation WHERE organization_id=:org")
                .param("org",f.id("organization")).query(Integer.class).single()).isZero();
    }

    @Test
    void releaseCannotBorrowAnotherActorsProofOrAnotherActionsEvidence() throws Exception {
        var f=ready();
        var launched=f.launch(UUID.randomUUID(),"actionOne",f.id("ownerUser"));
        UUID command=UUID.fromString(launched.path("commandId").asText());
        f.app.sql("SELECT ops.transition_lc_description_command(:id,0,NULL,'TERMINATED_WITHOUT_PROVIDER_CALL','SCOPE_STOPPED',NULL,NULL)")
                .param("id",command).query(String.class).single();
        UUID occupation=UUID.fromString(launched.path("occupationIds").get(0).asText());
        try (Connection connection=f.transaction()) {
            String proof=f.proof(connection,f.id("verifierUser"),"LISTING_OCCUPATION_RELEASE",occupation,occupation);
            assertThatThrownBy(()->{
                try (var q=connection.prepareStatement("SELECT ops.release_lc_occupation(?,?,?,'NOT_APPLIED_PROVEN',?,'fixture://no-submit')")) {
                    q.setObject(1,occupation);q.setObject(2,f.id("ownerUser"));q.setString(3,proof);q.setObject(4,command);q.execute();
                }
            }).satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
            connection.rollback();
        }
        assertThatThrownBy(()->f.release(occupation,f.id("ownerUser"),UUID.randomUUID(),"NOT_APPLIED_PROVEN"))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThat(f.app.sql("SELECT state FROM ops.lc_exposure_occupation WHERE id=:id")
                .param("id",occupation).query(String.class).single()).isEqualTo("ACQUIRED");
    }

}
