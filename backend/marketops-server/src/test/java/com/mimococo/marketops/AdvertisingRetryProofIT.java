package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual frozen-shape classification, custody hashes, readback, retry predicate and attempt-opening boundary. */
class AdvertisingRetryProofIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration,application,admin;
    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    private AdvertisingControlProofFixture fixture(boolean nativeKey) throws Exception {
        return new AdvertisingControlProofFixture(migration,application,admin,nativeKey);
    }
    @Test void explicitFrozenNotAppliedAndCurrentPriorAllowOnlySameCommandAndPayloadRetry() throws Exception {
        var f=fixture(false);var identity=f.app.sql("SELECT id,idempotency_key,target_bid_amount,prior_bid_amount,store_id,platform_code,ad_native_object_id,approval_decision_id,bundle_id,affected_set_digest FROM ops.ad_bid_command WHERE id=:id")
                .param("id",f.command).query().singleRow();
        UUID first=f.apply("{\"notApplied\":true}");
        assertThat(f.app.sql("SELECT error_code FROM ops.ad_bid_command_attempt WHERE id=:id").param("id",first).query(String.class).single()).isEqualTo("provider_explicit_not_applied");
        assertThat(f.retryProven()).isFalse();
        assertThat(f.readback(f.prior())).isEqualTo("MATCHES_PRIOR");
        assertThat(f.retryProven()).isTrue();
        f.state("EXECUTING");UUID retry=f.open("APPLY");
        assertThat(retry).isNotEqualTo(first);
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_bid_command_attempt WHERE command_id=:id AND purpose='APPLY'").param("id",f.command).query(Integer.class).single()).isEqualTo(2);
        assertThat(f.app.sql("SELECT id,idempotency_key,target_bid_amount,prior_bid_amount,store_id,platform_code,ad_native_object_id,approval_decision_id,bundle_id,affected_set_digest FROM ops.ad_bid_command WHERE id=:id")
                .param("id",f.command).query().singleRow()).isEqualTo(identity);
        assertThat(f.retryProven()).as("new in-flight APPLY consumes the observation opportunity").isFalse();
    }
    @Test void verifiedNativeKeyRequiresResolvedStatusThenCurrentReadbackBeforeRetry() throws Exception {
        var f=fixture(true);f.apply(null);
        assertThat(f.readback(f.prior())).isEqualTo("MATCHES_PRIOR");assertThat(f.retryProven()).isFalse();
        f.status("{\"state\":\"PENDING\"}");f.readback(f.prior());assertThat(f.retryProven()).isFalse();
        f.status("{\"state\":\"FAILED\"}");assertThat(f.retryProven()).isFalse();
        f.readback(f.prior());assertThat(f.retryProven()).isTrue();
        f.state("EXECUTING");assertThat(f.open("APPLY")).isNotNull();
    }
    @Test void noNativeKeyTimeoutAndRepeatedPriorDoNotProveNonApplication() throws Exception {
        var f=fixture(false);f.apply(null);f.readback(f.prior());f.readback(f.prior());
        assertThat(f.retryProven()).isFalse();assertSecondApplyRefused(f);
    }
    @Test void resolvedStatusWithoutVerifiedNativeKeyStillCannotAuthorizeRetry() throws Exception {
        var f=fixture(false);f.apply(null);f.status("{\"state\":\"FAILED\"}");f.readback(f.prior());
        assertThat(f.retryProven()).isFalse();assertSecondApplyRefused(f);
    }
    @ParameterizedTest @CsvSource({"20,RUB,CURRENCY_MAJOR,MATCHES_TARGET","25,RUB,CURRENCY_MAJOR,DIFFERENT","30,USD,CURRENCY_MAJOR,DIFFERENT","30,RUB,CURRENCY_MINOR,DIFFERENT"})
    void exactReadbackValueCurrencyAndNativeUnitEachRemainNecessary(int bid,String currency,String unit,String expected) throws Exception {
        var f=fixture(false);f.apply("{\"notApplied\":true}");
        assertThat(f.readback("{\"bid\":"+bid+",\"currency\":\""+currency+"\",\"unit\":\""+unit+"\"}")).isEqualTo(expected);
        assertThat(f.retryProven()).isFalse();assertSecondApplyRefused(f);
    }
    @Test void newerThirdValueSupersedesAnEarlierQualifyingPriorObservation() throws Exception {
        var f=fixture(false);f.apply("{\"notApplied\":true}");f.readback(f.prior());assertThat(f.retryProven()).isTrue();
        f.readback("{\"bid\":25,\"currency\":\"RUB\",\"unit\":\"CURRENCY_MAJOR\"}");
        assertThat(f.retryProven()).isFalse();assertSecondApplyRefused(f);
    }
    @ParameterizedTest @CsvSource({"200,false","503,true"})
    void IncompleteOrServerErrorBytesCannotTurnAnExplicitLookingFlagIntoProof(int httpStatus,boolean complete) throws Exception {
        var f=fixture(false);f.state("EXECUTING");UUID first=f.open("APPLY");
        f.complete(first,"{\"notApplied\":true}",httpStatus,complete);f.readback(f.prior());
        assertThat(f.app.sql("SELECT outcome_class FROM ops.ad_bid_command_attempt WHERE id=:id").param("id",first).query(String.class).single()).isEqualTo("UNKNOWN_STATE");
        assertThat(f.retryProven()).isFalse();assertSecondApplyRefused(f);
    }
    @ParameterizedTest @ValueSource(strings={"BUDGET","FENCE","PROFILE","CREDENTIAL","GATE_WINDOW","ACTOR"})
    void liveRetryBudgetFenceProfileCredentialGateAndActorAuthorityAreRechecked(String fault) throws Exception {
        var f=fixture(false);f.apply("{\"notApplied\":true}");f.readback(f.prior());assertThat(f.retryProven()).isTrue();
        switch(fault) {
            case "BUDGET" -> f.seed.sql("UPDATE ops.ad_bid_command SET retry_budget_remaining=0 WHERE id=:id").param("id",f.command).update();
            case "FENCE" -> f.seed.sql("UPDATE ops.ad_bid_command SET fence_token=2 WHERE id=:id").param("id",f.command).update();
            case "PROFILE" -> f.seed.sql("UPDATE platform.ad_semantic_profile SET status='RETIRED' WHERE id=:id").param("id",f.graph.id("profile")).update();
            case "CREDENTIAL" -> f.seed.sql("UPDATE platform.credential_metadata SET status='REVOKED' WHERE id=:id").param("id",f.graph.id("credential")).update();
            case "GATE_WINDOW" -> f.seed.sql("UPDATE ops.ad_gate_authority SET valid_until=clock_timestamp()-interval '1 second' WHERE id=:id").param("id",f.graph.id("gate")).update();
            case "ACTOR" -> f.seed.sql("UPDATE iam.user_account SET status='DISABLED',disabled_reason='fictional authority revocation' WHERE id=:id").param("id",f.graph.id("ownerUser")).update();
            default -> throw new AssertionError(fault);
        }
        assertThat(f.retryProven()).isFalse();
        if(!fault.equals("FENCE")) assertSecondApplyRefused(f);
    }
    @Test void applicationCannotInventTheReadbackClassificationOrRewriteCompletedProof() throws Exception {
        var f=fixture(false);UUID first=f.apply("{\"notApplied\":true}");f.readback(f.prior());
        assertSqlState(()->f.app.sql("UPDATE ops.ad_bid_command_readback SET match_state='MATCHES_PRIOR' WHERE command_id=:id").param("id",f.command).update(),"42501");
        assertSqlState(()->f.app.sql("UPDATE ops.ad_bid_command_attempt SET error_code='provider_explicit_not_applied' WHERE id=:id").param("id",first).update(),"42501");
    }
    private static void assertSecondApplyRefused(AdvertisingControlProofFixture f) {
        f.state("EXECUTING");assertSqlState(()->f.open("APPLY"),"MO090");
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_bid_command_attempt WHERE command_id=:id AND purpose='APPLY'").param("id",f.command).query(Integer.class).single()).isEqualTo(1);
    }
    interface Checked {void run() throws Exception;}
    static void assertSqlState(Checked action,String state) {
        Throwable error=catchThrowable(action::run);assertThat(error).isNotNull();
        while(error!=null&&!(error instanceof SQLException)) error=error.getCause();
        assertThat(error).isInstanceOf(SQLException.class);assertThat(((SQLException)error).getSQLState()).isEqualTo(state);
    }
}
