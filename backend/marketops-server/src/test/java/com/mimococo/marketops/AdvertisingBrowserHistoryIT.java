package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/** Verify each synthetic browser oracle satisfies real schema and immutable command creation. */
class AdvertisingBrowserHistoryIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration;
    @BeforeAll static void database() throws Exception {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        com.mimococo.marketops.shared.internal.migration.ManagedMigrationRunner.migrate(migration);
    }
    @ParameterizedTest @ValueSource(strings={"HISTORY_UNKNOWN","HISTORY_MISMATCH","HISTORY_REGRESSION","HISTORY_EXPIRED"})
    void historyUsesRealSealedCreationAndDistinguishesEveryReadOracle(String scenario) throws Exception {
        try(var context=new GenericApplicationContext()) {
            context.setEnvironment(new MockEnvironment()
                    .withProperty("marketops.identity.invocation.jdbc-url",DATABASE.getJdbcUrl())
                    .withProperty("marketops.identity.invocation.username",DATABASE.getUsername())
                    .withProperty("marketops.identity.invocation.password",DATABASE.getPassword()));
            context.registerBean(DataSource.class,()->new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                    TestDatabase.applicationRole(),TestDatabase.applicationPassword()));
            context.refresh();
            var graph=AdvertisingBrowserHistorySeed.seed(context,migration,scenario);
            var jdbc=JdbcClient.create(migration);var command=graph.id("historyCommand");
            assertThat(command).isNotNull();
            assertThat(jdbc.sql("SELECT count(*) FROM ops.ad_action_authorization WHERE recommendation_id=:id")
                    .param("id",graph.id("recommendation")).query(Integer.class).single()).isOne();
            assertThat(jdbc.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org")
                    .param("org",graph.id("organization")).query(Integer.class).single()).isOne();
            String expected=switch(scenario) {
                case "HISTORY_UNKNOWN" -> "UNKNOWN_REQUIRES_READBACK";
                case "HISTORY_MISMATCH" -> "READBACK_MISMATCH";
                case "HISTORY_REGRESSION" -> "READBACK_MATCHED";
                default -> "PENDING";
            };
            assertThat(jdbc.sql("SELECT state FROM ops.ad_bid_command WHERE id=:id").param("id",command)
                    .query(String.class).single()).isEqualTo(expected);
            if(scenario.equals("HISTORY_REGRESSION")) {
                assertThat(jdbc.sql("SELECT outcome_stage FROM ops.ad_outcome_observation WHERE command_id=:id ORDER BY evaluated_at")
                        .param("id",command).query(String.class).list()).containsExactly("OPERATIONAL","RETAINED","SETTLED","SETTLED_REVISED");
                assertThat(jdbc.sql("SELECT count(*) FROM ops.ad_containment WHERE organization_id=:org AND state='ACTIVE'")
                        .param("org",graph.id("organization")).query(Integer.class).single()).isOne();
            }
            if(scenario.equals("HISTORY_EXPIRED")) {
                assertThat(jdbc.sql("""
                    SELECT approval_expires_at=(SELECT scope_expires_at FROM ops.approval_decision WHERE id=:approval)
                      AND approval_expires_at<=created_at+interval '10 seconds' FROM ops.ad_bid_command WHERE id=:id
                    """).param("approval",graph.id("approval")).param("id",command).query(Boolean.class).single()).isTrue();
            }
        }
    }
}
