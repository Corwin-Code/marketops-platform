package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A real V0072 -> V0073 upgrade; the old independent row is input, not new proof. */
class AdvertisingManualObservationUpgradeIT {
    @Test void actualUpgradePreservesBareValueHistoryButItCannotBecomeQualifiedCurrentEvidence() throws Exception {
        try(var database=TestDatabase.isolatedContainer()) {
            var migration=new DriverManagerDataSource(database.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
            var original=Flyway.configure().dataSource(migration).locations("classpath:db/migration")
                    .target("0072").cleanDisabled(true).load();
            assertThat(original.migrate().migrationsExecuted).isEqualTo(72);
            JdbcClient seed=JdbcClient.create(migration);
            var graph=AdvertisingR1Fixture.seedManual(migration);
            UUID packet=UUID.randomUUID(),proof=UUID.randomUUID();
            // This is explicitly historical V72 input. No claim is made that this otherwise
            // incomplete old packet traversed a complete Planner/Owner/early-safety lifecycle.
            seed.sql("""
                    INSERT INTO ops.ad_manual_execution_packet(id,organization_id,case_id,ad_native_object_id,store_id,
                      platform_code,affected_set_id,affected_set_digest,semantic_profile_id,action_kind,observed_configuration_id,
                      intended_state,reason,evidence_reference,maker_user_id,endorser_user_id,approver_user_id,
                      expected_impact,verification_plan,state,issued_at,expires_at,correlation_id,created_at,updated_at,
                      executor_user_id,execution_started_at)
                    SELECT :packet,c.organization_id,c.id,c.ad_native_object_id,c.store_id,o.platform_code,c.affected_set_id,
                      a.affected_set_digest,c.semantic_profile_id,'AD_BID_CHANGE',:configuration,'{"targetBid":20}',
                      'Historical synthetic value-only record','fixture://v72-bare-value',:executor,:verifier,:owner,
                      '{"state":"OUTCOME_UNPROVEN"}','{"evidenceGrade":"INDEPENDENT_OR_OFFICIAL"}',
                      'MANUAL_CONFIGURATION_VERIFIED',clock_timestamp()-interval '2 hours',clock_timestamp()+interval '1 hour',
                      'fixture-v72-history',clock_timestamp()-interval '2 hours',clock_timestamp(),:executor,
                      clock_timestamp()-interval '90 minutes'
                    FROM mart.ad_case c JOIN core.ad_native_object o ON o.id=c.ad_native_object_id
                    JOIN core.ad_affected_set a ON a.id=c.affected_set_id WHERE c.id=:case
                    """).param("packet",packet).param("configuration",graph.id("configuration")).param("executor",graph.id("executorUser"))
                    .param("verifier",graph.id("verifierUser")).param("owner",graph.id("ownerUser")).param("case",graph.id("caseId")).update();
            seed.sql("""
                    INSERT INTO ops.ad_manual_configuration_verification(id,organization_id,packet_id,evidence_grade,
                      executor_user_id,verifier_user_id,observed_field_path,observed_value,observed_at,evidence_reference,
                      conflict_state,proves_configuration,recorded_at,correlation_id)
                    VALUES(:id,:org,:packet,'INDEPENDENT_MANUAL_VERIFICATION',:executor,:verifier,'targetBid','20',
                      clock_timestamp()-interval '1 hour','fixture://original-bare-value','NONE',true,
                      clock_timestamp()-interval '1 hour','fixture-v72-history')
                    """).param("id",proof).param("org",graph.id("organization")).param("packet",packet)
                    .param("executor",graph.id("executorUser")).param("verifier",graph.id("verifierUser")).update();
            seed.sql("UPDATE ops.ad_manual_execution_packet SET current_proof_id=:proof WHERE id=:packet")
                    .param("proof",proof).param("packet",packet).update();
            String before=seed.sql("SELECT to_jsonb(v)::text FROM ops.ad_manual_configuration_verification v WHERE id=:id")
                    .param("id",proof).query(String.class).single();
            var originalChecksums=seed.sql("SELECT installed_rank,version,script,checksum,installed_on,success FROM public.flyway_schema_history ORDER BY installed_rank")
                    .query().listOfRows();
            assertThat(Flyway.configure().dataSource(migration).locations("classpath:db/migration").cleanDisabled(true).load()
                    .migrate().migrationsExecuted).isEqualTo(1);
            var application=JdbcClient.create(new DriverManagerDataSource(database.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword()));
            assertThat(application.sql("SELECT ops.ad_manual_observation_is_qualified(:id)").param("id",proof).query(Boolean.class).single()).isFalse();
            assertThat(application.sql("SELECT (to_jsonb(v)-'independent_observation')::text FROM ops.ad_manual_configuration_verification v WHERE id=:id")
                    .param("id",proof).query(String.class).single()).isEqualTo(before);
            assertThat(application.sql("SELECT independent_observation IS NULL FROM ops.ad_manual_configuration_verification WHERE id=:id")
                    .param("id",proof).query(Boolean.class).single()).isTrue();
            assertThat(seed.sql("SELECT installed_rank,version,script,checksum,installed_on,success FROM public.flyway_schema_history WHERE installed_rank<=72 ORDER BY installed_rank")
                    .query().listOfRows()).isEqualTo(originalChecksums);
            UUID issued=UUID.randomUUID();
            seed.sql("""
                    INSERT INTO ops.ad_manual_execution_packet
                    SELECT (jsonb_populate_record(NULL::ops.ad_manual_execution_packet,to_jsonb(p)||
                      jsonb_build_object('id',:issued,'state','MANUAL_PACKET_ISSUED','execution_started_at',NULL,
                        'executor_user_id',NULL,'reservation_id',NULL,'current_proof_id',NULL))).*
                    FROM ops.ad_manual_execution_packet p WHERE id=:packet
                    """).param("issued",issued).param("packet",packet).update();
            assertThatThrownBy(()->seed.sql("""
                    INSERT INTO ops.ad_manual_execution_packet
                    SELECT (jsonb_populate_record(NULL::ops.ad_manual_execution_packet,to_jsonb(p)||
                      jsonb_build_object('id',gen_random_uuid()))).*
                    FROM ops.ad_manual_execution_packet p WHERE id=:issued
                    """).param("issued",issued).update()).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            // Historical uncertainty may coexist, but must still refuse another issued instruction.
            seed.sql("UPDATE ops.ad_manual_execution_packet SET state='MANUAL_EXECUTION_UNCERTAIN' WHERE id=:issued")
                    .param("issued",issued).update();
            assertThatThrownBy(()->seed.sql("""
                    INSERT INTO ops.ad_manual_execution_packet
                    SELECT (jsonb_populate_record(NULL::ops.ad_manual_execution_packet,to_jsonb(p)||
                      jsonb_build_object('id',gen_random_uuid(),'state','MANUAL_PACKET_ISSUED'))).*
                    FROM ops.ad_manual_execution_packet p WHERE id=:issued
                    """).param("issued",issued).update()).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            // New rows cannot exploit the NOT VALID grandfathering of already existing history.
            assertThatThrownBy(()->seed.sql("INSERT INTO ops.ad_manual_configuration_verification SELECT (jsonb_populate_record(NULL::ops.ad_manual_configuration_verification,to_jsonb(v)||jsonb_build_object('id',gen_random_uuid()))).* FROM ops.ad_manual_configuration_verification v WHERE id=:id")
                    .param("id",proof).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasMessageContaining("ad_manual_independent_envelope_required_ck");
            assertThat(application.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org").param("org",graph.id("organization")).query(Integer.class).single()).isZero();
            assertThat(application.sql("SELECT count(*) FROM ops.ad_outcome_observation WHERE organization_id=:org").param("org",graph.id("organization")).query(Integer.class).single()).isZero();
        }
    }
}
