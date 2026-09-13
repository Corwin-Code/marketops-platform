package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A real forward migration retains an old orphan launch without inventing current transport authority. */
class ListingAtomicLaunchUpgradeIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();

    @Test void oldLaunchCannotBorrowANewTransactionToCreateACommand() throws Exception {
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        var application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        var admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").target("90").load().migrate();
        var legacy=new ListingConversionFixture(migration,application,admin);
        UUID launch=UUID.randomUUID();
        assertThat(legacy.launch(launch,"actionOne",legacy.id("ownerUser")).path("launched").asBoolean()).isTrue();
        String before=legacy.app.sql("""
                SELECT jsonb_build_object('id',l.id,'organizationId',l.organization_id,'actionId',l.action_id,
                    'bindingId',l.binding_id,'planId',l.plan_id,'launchedByUserId',l.launched_by_user_id,
                    'launchedAt',l.launched_at,'proofHash',l.proof_hash)::text
                  FROM ops.lc_launch l WHERE id=:id
                """)
                .param("id",launch).query(String.class).single();
        assertThat(legacy.app.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:action")
                .param("action",legacy.id("actionOne")).query(Integer.class).single()).isZero();

        UUID simulation = UUID.randomUUID();
        legacy.seed.sql("""
                INSERT INTO ops.lc_simulation (id,organization_id,candidate_id,calculation_run_id,scenario_set,
                    inputs_digest,results,inverse_minimum_quantity,inverse_state,demand_gate_passed,computed_at)
                VALUES (:id,:org,:candidate,:run,'[]',repeat('b',64),'[]',8,'COMPUTED',true,now())
                """).param("id",simulation).param("org",legacy.id("organization"))
                .param("candidate",legacy.id("candidateOne")).param("run",legacy.id("calculationRun")).update();
        String simulationBefore = legacy.app.sql("SELECT to_jsonb(s)::text FROM ops.lc_simulation s WHERE id=:id")
                .param("id",simulation).query(String.class).single();

        UUID historicalQueue=UUID.randomUUID();
        legacy.app.sql("""
                INSERT INTO ops.lc_recalculation_queue(id,organization_id,platform_listing_id,trigger_class,target_minutes,
                    trigger_reference,accepted_at,started_at,finished_at,state)
                VALUES (:id,:org,:listing,'RISK',5,'synthetic historical unbound receipt',now(),now(),now(),'FINISHED')
                """).param("id",historicalQueue).param("org",legacy.id("organization"))
                .param("listing",legacy.id("listing")).update();
        String queueBefore=legacy.app.sql("""
                SELECT jsonb_build_object('id',q.id,'organizationId',q.organization_id,
                    'platformListingId',q.platform_listing_id,'triggerClass',q.trigger_class,
                    'targetMinutes',q.target_minutes,'triggerReference',q.trigger_reference,
                    'sourceTime',q.source_time,'acceptedAt',q.accepted_at,'startedAt',q.started_at,
                    'finishedAt',q.finished_at,'state',q.state,'calculationRunId',q.calculation_run_id,
                    'failureCode',q.failure_code)::text
                  FROM ops.lc_recalculation_queue q WHERE id=:id
                """)
                .param("id",historicalQueue).query(String.class).single();

        String meaningValuesBefore=legacy.app.sql("""
                SELECT jsonb_agg(to_jsonb(v) ORDER BY v.category_code)::text FROM core.lc_calibration_value v
                  WHERE package_id=:id AND category_code IN ('ORDINARY_TRIGGER_CONTENT','MATERIAL_TRIGGER_CONTENT')
                """).param("id",legacy.id("calibrationPackage")).query(String.class).single();
        UUID historicalTask=UUID.randomUUID();
        legacy.app.sql("""
                INSERT INTO ops.work_task(id,organization_id,recommendation_id,title,state,due_at,created_at,updated_at)
                VALUES (:task,:org,:recommendation,'Historical unbound Listing responsibility','OPEN',
                    now()+interval '2 days',now()-interval '1 day',now()-interval '1 day')
                """).param("task",historicalTask).param("org",legacy.id("organization"))
                .param("recommendation",legacy.id("recommendationOne")).update();
        String historicalTaskBefore=legacy.app.sql("SELECT to_jsonb(t)::text FROM ops.work_task t WHERE id=:id")
                .param("id",historicalTask).query(String.class).single();
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
        assertThat(legacy.app.sql("SELECT to_jsonb(t)::text FROM ops.work_task t WHERE id=:id")
                .param("id",historicalTask).query(String.class).single()).isEqualTo(historicalTaskBefore);
        assertThat(legacy.app.sql("SELECT count(*) FROM ops.lc_task_responsibility WHERE task_id=:id")
                .param("id",historicalTask).query(Integer.class).single()).isZero();
        assertThat(legacy.app.sql("""
                SELECT jsonb_agg(to_jsonb(v) ORDER BY v.category_code)::text FROM core.lc_calibration_value v
                  WHERE package_id=:id AND category_code IN ('ORDINARY_TRIGGER_CONTENT','MATERIAL_TRIGGER_CONTENT')
                """).param("id",legacy.id("calibrationPackage")).query(String.class).single()).isEqualTo(meaningValuesBefore);
        assertThat(legacy.app.sql("SELECT core.lc_meaning_catalog(:id,'LISTING_DESCRIPTION_CHANGE') IS NULL")
                .param("id",legacy.id("calibrationPackage")).query(Boolean.class).single()).isTrue();
        assertThat(legacy.app.sql("SELECT NOT ops.lc_action_has_meaning_review(:id,statement_timestamp())")
                .param("id",legacy.id("actionOne")).query(Boolean.class).single()).isTrue();

        assertThat(legacy.app.sql("""
                SELECT jsonb_build_object('id',q.id,'organizationId',q.organization_id,
                    'platformListingId',q.platform_listing_id,'triggerClass',q.trigger_class,
                    'targetMinutes',q.target_minutes,'triggerReference',q.trigger_reference,
                    'sourceTime',q.source_time,'acceptedAt',q.accepted_at,'startedAt',q.started_at,
                    'finishedAt',q.finished_at,'state',q.state,'calculationRunId',q.calculation_run_id,
                    'failureCode',q.failure_code)::text
                  FROM ops.lc_recalculation_queue q WHERE id=:id
                """).param("id",historicalQueue).query(String.class).single()).isEqualTo(queueBefore);
        assertThat(legacy.app.sql("""
                SELECT lease_generation IS NULL AND leased_until IS NULL AND health_result_id IS NULL
                  AND calculation_run_id IS NULL FROM ops.lc_recalculation_queue WHERE id=:id
                """).param("id",historicalQueue).query(Boolean.class).single()).isTrue();


        assertThat(legacy.app.sql("""
                SELECT (to_jsonb(s)-'model_version'-'input_snapshot'-'conditional_scenarios_passed')::text
                  FROM ops.lc_simulation s WHERE id=:id
                """).param("id",simulation).query(String.class).single()).isEqualTo(simulationBefore);
        assertThat(legacy.app.sql("""
                SELECT model_version='LEGACY_UNQUALIFIED' AND input_snapshot IS NULL
                  AND conditional_scenarios_passed IS NULL FROM ops.lc_simulation WHERE id=:id
                """).param("id",simulation).query(Boolean.class).single()).isTrue();

        assertThat(legacy.app.sql("""
                SELECT core.lc_listing_identity_snapshot(:listing,statement_timestamp())#>>'{nativeScope,state}'
                """).param("listing",legacy.id("listing")).query(String.class).single()).isEqualTo("INCOMPLETE");
        assertThat(legacy.app.sql("SELECT calibration_dependencies IS NULL AND materiality_evidence IS NULL FROM ops.lc_action WHERE id=:id")
                .param("id",legacy.id("actionOne")).query(Boolean.class).single()).isTrue();
        assertThat(legacy.app.sql("SELECT native_scope_observation_id IS NULL FROM core.lc_affected_set WHERE id=:id")
                .param("id",legacy.id("affectedSetOne")).query(Boolean.class).single()).isTrue();

        assertThat(legacy.app.sql("""
                SELECT jsonb_build_object('id',l.id,'organizationId',l.organization_id,'actionId',l.action_id,
                    'bindingId',l.binding_id,'planId',l.plan_id,'launchedByUserId',l.launched_by_user_id,
                    'launchedAt',l.launched_at,'proofHash',l.proof_hash)::text
                  FROM ops.lc_launch l WHERE id=:id
                """)
                .param("id",launch).query(String.class).single()).isEqualTo(before);
        assertThat(legacy.app.sql("SELECT created_transaction_id IS NULL AND execution_guardrail_id IS NULL FROM ops.lc_launch WHERE id=:id")
                .param("id",launch).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(()->legacy.createCommand(legacy.id("actionOne"),legacy.id("ownerUser")))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThat(legacy.actionState(legacy.id("actionOne"))).isEqualTo("LAUNCHED");
        assertThat(legacy.app.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:action")
                .param("action",legacy.id("actionOne")).query(Integer.class).single()).isZero();
    }
}
