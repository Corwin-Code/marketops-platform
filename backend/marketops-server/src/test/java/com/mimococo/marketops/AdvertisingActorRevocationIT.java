package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual human authority revocation and restoration cannot resurrect sealed write or retry assets. */
class AdvertisingActorRevocationIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration,application,admin;
    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    @ParameterizedTest @CsvSource({
        "executorUser,MARKETPLACE_OPERATOR,ADVERTISING_TASK_ACT,ACCOUNT",
        "executorUser,MARKETPLACE_OPERATOR,ADVERTISING_TASK_ACT,ROLE",
        "executorUser,MARKETPLACE_OPERATOR,ADVERTISING_TASK_ACT,SCOPE",
        "executorUser,MARKETPLACE_OPERATOR,ADVERTISING_TASK_ACT,SESSION",
        "verifierUser,OPS_LEAD,AD_BID_CHANGE_ENDORSE,ACCOUNT",
        "verifierUser,OPS_LEAD,AD_BID_CHANGE_ENDORSE,ROLE",
        "verifierUser,OPS_LEAD,AD_BID_CHANGE_ENDORSE,SCOPE",
        "verifierUser,OPS_LEAD,AD_BID_CHANGE_ENDORSE,SESSION",
        "ownerUser,OWNER,AD_BID_CHANGE_APPROVE,ACCOUNT",
        "ownerUser,OWNER,AD_BID_CHANGE_APPROVE,ROLE",
        "ownerUser,OWNER,AD_BID_CHANGE_APPROVE,SCOPE",
        "ownerUser,OWNER,AD_BID_CHANGE_APPROVE,SESSION"
    })
    void eachHumanRevocationPermanentlyInvalidatesOldApprovalEvenAfterRestoration(String person,String role,String action,String fault) throws Exception {
        var f=ready();UUID actor=f.graph.id(person);
        Timestamp prior=f.seed.sql("SELECT credentials_valid_from FROM iam.user_account WHERE id=:id").param("id",actor).query(Timestamp.class).single();
        String revoke=switch(fault) {
            case "ACCOUNT" -> "UPDATE iam.user_account SET status='DISABLED',disabled_reason='fictional revocation' WHERE id=:id";
            case "ROLE" -> "UPDATE iam.user_role_assignment SET status='REVOKED' WHERE user_id=:id AND role_code=:role";
            case "SCOPE" -> "UPDATE iam.user_scope_grant SET status='REVOKED' WHERE user_id=:id AND action_code=:action";
            case "SESSION" -> "UPDATE iam.user_account SET credentials_valid_from=clock_timestamp() WHERE id=:id";
            default -> throw new AssertionError(fault);
        };
        assertThat(f.seed.sql(revoke).param("id",actor).param("role",role).param("action",action).update()).isPositive();
        assertThat(f.retryProven()).isFalse();assertThat(f.reasons()).contains("AUTHORITY_PERMANENTLY_INVALIDATED");
        String restore=switch(fault) {
            case "ACCOUNT" -> "UPDATE iam.user_account SET status='ACTIVE',disabled_reason=NULL WHERE id=:id";
            case "ROLE" -> "UPDATE iam.user_role_assignment SET status='ACTIVE' WHERE user_id=:id AND role_code=:role";
            case "SCOPE" -> "UPDATE iam.user_scope_grant SET status='ACTIVE' WHERE user_id=:id AND action_code=:action";
            case "SESSION" -> "UPDATE iam.user_account SET credentials_valid_from=:prior WHERE id=:id";
            default -> throw new AssertionError(fault);
        };
        f.seed.sql(restore).param("id",actor).param("role",role).param("action",action).param("prior",prior).update();
        assertThat(f.reasons()).contains("AUTHORITY_PERMANENTLY_INVALIDATED").doesNotContain("CURRENT_HUMAN_AUTHORITY_REVOKED");
        assertThat(f.retryProven()).isFalse();f.state("EXECUTING");
        AdvertisingRetryProofIT.assertSqlState(()->f.open("APPLY"),"MO090");
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_bid_command_attempt WHERE command_id=:id AND purpose='APPLY'")
                .param("id",f.command).query(Integer.class).single()).isEqualTo(1);
    }
    @Test void retiringAndRestoringTheIdentityProviderCannotReviveItsPriorApprovalAssets() throws Exception {
        var f=ready();
        f.seed.sql("UPDATE iam.identity_provider SET status='RETIRED' WHERE id=:id").param("id",f.graph.id("provider")).update();
        assertThat(f.retryProven()).isFalse();assertThat(f.reasons()).contains("CURRENT_HUMAN_AUTHORITY_REVOKED");
        f.seed.sql("UPDATE iam.identity_provider SET status='ACTIVE' WHERE id=:id").param("id",f.graph.id("provider")).update();
        assertThat(f.reasons()).contains("AUTHORITY_PERMANENTLY_INVALIDATED").doesNotContain("CURRENT_HUMAN_AUTHORITY_REVOKED");
        assertThat(f.retryProven()).isFalse();
    }
    @Test void displayNameChangesDoNotInventAnAuthorityRevocation() throws Exception {
        var f=ready();
        f.seed.sql("UPDATE iam.user_account SET display_name='Renamed fictional reviewer' WHERE id=:id")
                .param("id",f.graph.id("ownerUser")).update();
        assertThat(f.reasons()).isEmpty();assertThat(f.retryProven()).isTrue();
    }
    private AdvertisingControlProofFixture ready() throws Exception {
        var f=new AdvertisingControlProofFixture(migration,application,admin,false);
        f.apply("{\"notApplied\":true}");f.readback(f.prior());assertThat(f.retryProven()).isTrue();return f;
    }
}
