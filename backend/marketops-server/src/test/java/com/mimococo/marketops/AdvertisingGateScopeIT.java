package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Eight independent Gate/Bundle scope axes are frozen and rechecked by the actual APPLY admission. */
class AdvertisingGateScopeIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration,application,admin;
    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    @ParameterizedTest @EnumSource(AdvertisingGateScopeMutation.Axis.class)
    void anApprovedActionCannotBorrowChangedScopeEvenWhenTheNewLimitIsLooser(AdvertisingGateScopeMutation.Axis axis) throws Exception {
        var f=new AdvertisingControlProofFixture(migration,application,admin,false);
        assertThat(f.reasons()).isEmpty();
        AdvertisingGateScopeMutation.mutate(f.seed,f.graph,f.graph.id("gate"),axis);
        assertThat(f.reasons()).as("current %s cannot be substituted for approved scope",axis)
                .contains("COMPLETE_AUTHORITY_SNAPSHOT_CHANGED");
        f.state("EXECUTING");
        AdvertisingRetryProofIT.assertSqlState(()->f.open("APPLY"),"MO092");
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_bid_command_attempt WHERE command_id=:id")
                .param("id",f.command).query(Integer.class).single()).isZero();
        assertThat(f.app.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",f.graph.id("gate")).query(Boolean.class).single()).isFalse();
    }
}
