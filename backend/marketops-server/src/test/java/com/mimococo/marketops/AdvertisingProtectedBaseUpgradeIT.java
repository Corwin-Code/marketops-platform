package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exact protected-main V0001–V0035 bytes upgrade on a private PostgreSQL server. */
class AdvertisingProtectedBaseUpgradeIT {
    private static final Map<String,String> PROTECTED_BASE_SHA256=Map.ofEntries(
            Map.entry("V0001__create_foundation_schemas.sql", "7c7f34ba3a3746883e236a5a4e6eb0efc87e58e3b33f895d7f1d71c369d0eb0d"),
            Map.entry("V0002__enable_btree_gist_extension.sql", "438f67ccf3c2f640a1e7a4e325e24fb60d1eb4f363ab545e1e69babba202db16"),
            Map.entry("V0003__create_metadata_audit_event.sql", "efbe9ff5bb32fb96011f9d5b80426fb8d4134a2bd2314bb6294d8eb60be8135c"),
            Map.entry("V0004__create_core_organization_metadata.sql", "39bff4689febfe3effa1e73953589976fa030c035412263e365f0d1a7be65d2c"),
            Map.entry("V0005__create_iam_access_metadata.sql", "51a47d8cf735ff6378e4d0a3d201725b255e8f835e662a7d68b34d6dd51950f1"),
            Map.entry("V0006__create_platform_registry_metadata.sql", "7225402b7a77cc04933e5d030772f9f6e2e98f4e552ac577e1254f998d73e902"),
            Map.entry("V0007__create_ingestion_control_plane_authority.sql", "83ccccf382389ec62a401393cd85508183240eeb3d94215549a101f6ab5e8781"),
            Map.entry("V0008__attach_control_epoch_triggers.sql", "8f148e213c8cd2f50c57c0d585708d59677d72663e4d45a6c62000dc663d5fea"),
            Map.entry("V0009__create_control_boundary_kinds_and_decision_evidence.sql", "9b906c8e732aec72b7f069c5374cb0dc9894071e18e091281b9c28785336885d"),
            Map.entry("V0010__create_ingestion_run_checkpoint_and_raw_evidence.sql", "a3b8ca08b796c1d211f17a042a8ec546cd0009d4457e2d68dcd930dfc36a13d9"),
            Map.entry("V0011__create_human_identity_and_business_authorization.sql", "d5312e5c2348e8abe0fb445652567ad9a0e9ad1b7ce6eeb3a5202bb58af94555"),
            Map.entry("V0012__create_product_listing_identity_and_mapping.sql", "e6ea3caa30f69e9f533a69cb1387e25396c15f3f0fce1f0bcdced00c30522bb7"),
            Map.entry("V0013__create_cross_domain_operating_facts.sql", "db63fd8a4e50ceb39c78b58856a44ff4b4eed8e758a3975bc96fe9c439aad552"),
            Map.entry("V0014__create_internal_fact_intake_and_file_import.sql", "f0649d939caab975ff796b2e257ea1e642f9e7bf851bd7616171ac82e0a0b42a"),
            Map.entry("V0015__create_canonical_metric_definitions_and_values.sql", "cc695d22a1249b3e3520e359e75cdbfe7a90fb7519f0cc6e0b74369bdbdbed9b"),
            Map.entry("V0016__create_deterministic_diagnosis_rules_and_findings.sql", "9a7b8efa5d1948d3d5ca8522b6d5dd011eb4be2e16f53145c7c7364ae831056c"),
            Map.entry("V0017__create_ai_projection_invocation_and_output.sql", "b20b7138b2e177b87ec1a579cbdcdd13c60b4ed045b6721463c5496f974e74ff"),
            Map.entry("V0018__create_recommendation_task_and_approval_workflow.sql", "1df2c7fd599354dce06cd8f3a4d6363a88c65fa2c02446cbe569d56b333422d1"),
            Map.entry("V0019__create_commercial_policy_and_guardrails.sql", "ba6916d059cb5586bfe784e261a0243832acf97b097c112072899b5d01526e58"),
            Map.entry("V0020__create_price_command_outbox_readback_and_write_gate.sql", "8437316450f3bae2e5ef532d360121ad825d25cac2cd67cb840cc31683d6721d"),
            Map.entry("V0021__create_platform_api_profile_and_request_shape.sql", "b67375962b84a3f37becd968af7f2aaf97df7b75760ce69748a1f4895a9fc97a"),
            Map.entry("V0022__create_ingestion_run_lifecycle_and_replay_guard.sql", "9a93dcaaafca30b9f738129974babe46509784c232854337ea023200d7c33f6e"),
            Map.entry("V0023__create_declared_normalization_and_drift_observation.sql", "6fdc3d7bef0b4f0e395a73bac1a7553b134668dd8a684224a74aeaa04f0df0fb"),
            Map.entry("V0024__create_capability_write_operation_shape.sql", "55db8c34247172c1746439bb41a9cd376b4ad70e5e256ef0fbf0c5ee2ff80e2b"),
            Map.entry("V0025__create_price_command_attempt_completion_and_lease_recovery.sql", "5039cdbf120e4db29d642d8d22dc3f952119921f04f3568eb367f15b9f8fdfa7"),
            Map.entry("V0026__rename_operational_capability_column_to_action_kind.sql", "82f42eea7990dc6502447d4832a08ca107830495f8f9d249e9d4b09c088b4df2"),
            Map.entry("V0027__create_account_bound_registry_verification.sql", "fbdd185d03324d145793ec7844ebc17aa02b844f8aaffe89579b5df1f6479ba1"),
            Map.entry("V0028__create_bounded_diagnostic_export.sql", "43170cf370079706ed8b39792815a5ec2bb447fae2d876afcd830cf7e30a1acf"),
            Map.entry("V0029__version_profit_economics_and_commercial_inputs.sql", "5ca31479f5230abb30c1607ac25f2c819145eebab0bcfed7c27750cc90968d03"),
            Map.entry("V0030__create_availability_risk_policy_inbound_and_case.sql", "6ad81c234c20134c5444f8b2a7606977fed5661987cd0354ab9ccfd2dda18e3d"),
            Map.entry("V0031__track_sustained_availability_lane.sql", "4322ccbbf2ae5e32724c29e4160bdcbacec798932fe6b8fd7be258861435042b"),
            Map.entry("V0032__create_availability_fact_feed_cursor.sql", "72fef0835de8d294ae92351758ba05931a9eafc7b38c4cd996720a743d4eec0e"),
            Map.entry("V0033__track_case_improvement_observation.sql", "767b3e79a6f17780940a18d323371757e04a82479f7d9882f6d140387619e7f2"),
            Map.entry("V0034__close_availability_deep_review_findings.sql", "c7be2f0b6ae9444287875bdfccf4f9bc997e3080ff8689c77e6b6d80a33551b1"),
            Map.entry("V0035__close_availability_targeted_findings.sql", "e3ce956b28fa60a82aa4f6bce5f760bd803fd9222e5eed04777b6a03753532a7"));

    @Test void exactProtectedMainUpgradePreservesAppliedChecksumsAndExistingCanonicalData() throws Exception {
        for(var entry:PROTECTED_BASE_SHA256.entrySet()) {
            try(var resource=getClass().getClassLoader().getResourceAsStream("db/migration/"+entry.getKey())) {
                assertThat(resource).as(entry.getKey()).isNotNull();
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resource.readAllBytes())))
                        .as("Protected Base 08ad7da7d9e75b4ddd1c387a22ac0affba9e1430 migration %s",entry.getKey()).isEqualTo(entry.getValue());
            }
        }
        try(var database=TestDatabase.isolatedContainer()) {
            var source=new DriverManagerDataSource(database.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
            var jdbc=new JdbcTemplate(source);
            var original=Flyway.configure().dataSource(source).locations("classpath:db/migration").target("0035").cleanDisabled(true).load();
            assertThat(original.migrate().migrationsExecuted).isEqualTo(35);
            var before=jdbc.queryForList("SELECT installed_rank,version,script,checksum,installed_on,success FROM public.flyway_schema_history ORDER BY installed_rank");
            UUID organization=UUID.randomUUID();
            jdbc.update("INSERT INTO core.organization(id,code,display_name,status,created_at,updated_at) VALUES(?,?,'Protected Base synthetic data','ACTIVE',clock_timestamp(),clock_timestamp())",organization,"upgrade-"+organization);
            var after=com.mimococo.marketops.shared.internal.migration.ManagedMigrationRunner.migrate(source);
            assertThat(after.migrationsApplied()).isEqualTo(30);
            assertThat(after.schemaVersion()).isEqualTo("0065");
            assertThat(jdbc.queryForList("SELECT installed_rank,version,script,checksum,installed_on,success FROM public.flyway_schema_history WHERE installed_rank<=35 ORDER BY installed_rank")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT display_name FROM core.organization WHERE id=?",String.class,organization)).isEqualTo("Protected Base synthetic data");
            assertThat(com.mimococo.marketops.shared.internal.migration.ManagedMigrationRunner.migrate(source).migrationsApplied()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ops.ad_gate_authority WHERE production_write_enabled",Integer.class)).isZero();
        }
    }
}
