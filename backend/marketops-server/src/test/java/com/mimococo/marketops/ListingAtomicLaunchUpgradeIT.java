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
        String before=legacy.app.sql("SELECT to_jsonb(l)::text FROM ops.lc_launch l WHERE id=:id")
                .param("id",launch).query(String.class).single();
        assertThat(legacy.app.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:action")
                .param("action",legacy.id("actionOne")).query(Integer.class).single()).isZero();

        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();

        assertThat(legacy.app.sql("SELECT (to_jsonb(l)-'created_transaction_id')::text FROM ops.lc_launch l WHERE id=:id")
                .param("id",launch).query(String.class).single()).isEqualTo(before);
        assertThat(legacy.app.sql("SELECT created_transaction_id IS NULL FROM ops.lc_launch WHERE id=:id")
                .param("id",launch).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(()->legacy.createCommand(legacy.id("actionOne"),legacy.id("ownerUser")))
                .satisfies(failure->assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThat(legacy.actionState(legacy.id("actionOne"))).isEqualTo("LAUNCHED");
        assertThat(legacy.app.sql("SELECT count(*) FROM ops.lc_description_command WHERE action_id=:action")
                .param("action",legacy.id("actionOne")).query(Integer.class).single()).isZero();
    }
}
