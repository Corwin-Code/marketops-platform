package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Finite approval authority is checked against real PostgreSQL time. Historical synthetic input
 * places an unchanged, legal fifteen-minute lease near its end; no clock, gate or lease rule is
 * replaced. The shared fixture's synthetic approval/Planner oracle supplies inputs, while actual
 * issuer proofs, app-role sealing and command creation are the boundary under test.
 */
class AdvertisingMinimumExpiryIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static DataSource migration,application,admin;
    private static JdbcClient seed,appRead;
    private static final String TEMPLATE_RECOMMENDATION="60cb55d4-9471-5910-b63b-78afb479a8aa";
    private static final String TEMPLATE_OWNER="9264ceb0-c29a-5837-9339-c84bfe73a444";
    private static final String TEMPLATE_TARGET="355c4854-db15-5ecf-8e2e-358bc6629a6c";
    private static final String TEMPLATE_SEMANTIC="71491f3e-1853-5678-983a-10f023a23a10";
    private static final String TEMPLATE_FRESHNESS="09e18445-327a-5350-a892-df536fde9900";

    @BeforeAll static void database() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);appRead=JdbcClient.create(application);
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }
    enum Bound {
        LEASE, RECOMMENDATION, OWNER_SELECTED, POLICY, SEMANTIC_PROFILE, FRESHNESS_PROFILE,
        REQUIRED_EVIDENCE, BASELINE, CREDENTIAL_METADATA, CREDENTIAL_ATTESTATION, GATE,
        ACTOR_SCOPE_GRANT, ACTOR_ROLE, MAKER_SCOPE_GRANT, MAKER_ROLE, ENDORSER_SCOPE_GRANT, ENDORSER_ROLE
    }
    private Connection transaction() throws SQLException {
        var connection=application.getConnection();connection.setAutoCommit(false);return connection;
    }
    private Instant databaseNow() {
        return appRead.sql("SELECT clock_timestamp()").query((row,index)->row.getTimestamp(1).toInstant()).single();
    }
    private String proof(Connection app,AdvertisingR1Fixture.Graph graph) throws Exception {
        return AdvertisingR1Fixture.proof(admin,app,graph,graph.id("ownerUser"),null,
                graph.id("recommendation"),graph.id("approval"));
    }
    private UUID create(Connection app,AdvertisingR1Fixture.Graph graph) throws SQLException {
        try(var query=app.prepareStatement("SELECT ops.create_ad_bid_command(?,(SELECT version FROM ops.recommendation WHERE id=?),?,'minimum-expiry-fixture')")) {
            query.setObject(1,graph.id("recommendation"));query.setObject(2,graph.id("recommendation"));
            query.setObject(3,graph.id("reservation"));
            try(var row=query.executeQuery()) { row.next();return row.getObject(1,UUID.class); }
        }
    }
    private String[] gate(UUID command) {
        return appRead.sql("SELECT ops.evaluate_ad_bid_write_gate(:id)").param("id",command)
                .query((row,index)->(String[])row.getArray(1).getArray()).single();
    }
    private Instant authorizationExpiry(UUID authority) {
        return appRead.sql("SELECT expires_at FROM ops.ad_action_authorization WHERE id=:id").param("id",authority)
                .query((row,index)->row.getTimestamp(1).toInstant()).single();
    }
    private Instant commandExpiry(UUID command) {
        return appRead.sql("SELECT approval_expires_at FROM ops.ad_bid_command WHERE id=:id").param("id",command)
                .query((row,index)->row.getTimestamp(1).toInstant()).single();
    }

    @ParameterizedTest(name="unique earliest {0} stays frozen through waiting and expires")
    @EnumSource(Bound.class)
    void eachMinimumAuthorityBoundIsFrozenAtFinalApprovalAndCannotRenewWhileWaiting(Bound bound) throws Exception {
        // Fifteen seconds of harness allowance preserves the legal 900-second minimum lease.
        Instant historicalApproval=databaseNow().minusSeconds(885);
        Instant expected=historicalApproval.plusSeconds(900);
        var graph=AdvertisingR1Fixture.seedOutcome(migration,sql->historicalFixture(sql,bound,historicalApproval));
        Map<String,Instant> limits=actualLimits(graph);
        assertThat(limits.get(bound.name())).as("independently loaded %s input",bound).isEqualTo(expected);
        limits.forEach((name,limit)-> {
            if(limit!=null && !name.equals(bound.name())) assertThat(limit).as("%s must be the only earliest input; other=%s",bound,name).isAfter(expected);
        });
        UUID authority,command;
        try(var app=transaction()) {
            authority=AdvertisingR1Fixture.seal(app,graph,proof(app,graph));
            AdvertisingR1Fixture.reserve(app,graph);
            command=create(app,graph);app.commit();
        }
        assertThat(authorizationExpiry(authority)).as("%s minimum is frozen by actual seal",bound).isEqualTo(expected);
        assertThat(commandExpiry(command)).isEqualTo(expected);
        assertThat(gate(command)).doesNotContain("SEALED_AUTHORIZATION_MISSING_OR_EXPIRED");
        assertThat(appRead.sql("SELECT final_approved_at FROM ops.ad_action_authorization WHERE id=:id").param("id",authority)
                .query((row,index)->row.getTimestamp(1).toInstant()).single()).isEqualTo(historicalApproval);
        try(var app=transaction()) {
            assertThat(create(app,graph)).isEqualTo(command);app.commit();
        }
        assertThat(authorizationExpiry(authority)).isEqualTo(expected);
        assertThat(commandExpiry(command)).isEqualTo(expected);
        awaitDatabaseTime(expected);
        assertThat(gate(command)).contains("SEALED_AUTHORIZATION_MISSING_OR_EXPIRED");
        try(var app=transaction()) {
            assertThatThrownBy(()->create(app,graph)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("current immutable approval authority required");app.rollback();
        }
        assertThat(authorizationExpiry(authority)).isEqualTo(expected);
        assertThat(commandExpiry(command)).isEqualTo(expected);
        assertThat(appRead.sql("SELECT count(*) FROM ops.ad_bid_command WHERE recommendation_id=:id")
                .param("id",graph.id("recommendation")).query(Integer.class).single()).isEqualTo(1);
        assertThat(appRead.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",graph.id("gate")).query(Boolean.class).single()).isFalse();
    }
    private void awaitDatabaseTime(Instant deadline) throws InterruptedException {
        long boundedEnd=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(!databaseNow().isAfter(deadline) && System.nanoTime()<boundedEnd) Thread.sleep(100);
        assertThat(databaseNow()).as("actual database time must cross the frozen deadline").isAfter(deadline);
    }

    private static String historicalFixture(String sql,Bound bound,Instant approvedAt) {
        String early="now()+interval '15 minutes'";
        String beforeSelection="";
        switch(bound) {
            case LEASE -> sql=replaceExactlyOnce(sql,"3600, 1800,","900, 900,");
            case RECOMMENDATION -> sql=replaceExactlyOnce(sql,"now() + interval '3 days'",early);
            case OWNER_SELECTED -> sql=replaceExactlyOnce(sql,"now() + interval '2 hours'",early);
            case POLICY -> beforeSelection="UPDATE core.ad_bid_target_policy SET effective_to="+early+" WHERE id='"+TEMPLATE_TARGET+"';\n";
            case SEMANTIC_PROFILE -> beforeSelection="UPDATE platform.ad_semantic_profile SET effective_to="+early+" WHERE id='"+TEMPLATE_SEMANTIC+"';\n";
            case FRESHNESS_PROFILE -> beforeSelection="UPDATE core.ad_freshness_profile SET effective_to="+early+" WHERE id='"+TEMPLATE_FRESHNESS+"';\n";
            case REQUIRED_EVIDENCE -> beforeSelection="UPDATE mart.ad_case_purpose_evidence SET expires_at="+early+" WHERE freshness_profile_id='"+TEMPLATE_FRESHNESS+"';\n";
            case BASELINE -> sql=replaceExactlyOnce(sql,"now()+interval '18 minutes'",early);
            case CREDENTIAL_METADATA -> sql=replaceExactlyOnce(sql,"now()+interval '25 minutes'",early);
            case CREDENTIAL_ATTESTATION -> sql=replaceExactlyOnce(sql,"now()+interval '20 minutes'",early);
            case GATE -> sql=replaceExactlyOnce(sql,"now(),now()-interval '1 hour',now()+interval '1 hour',1,50", "now(),now()-interval '1 hour',"+early+",1,50");
            case ACTOR_SCOPE_GRANT -> beforeSelection="UPDATE iam.user_scope_grant SET effective_to="+early+" WHERE user_id='"+TEMPLATE_OWNER+"' AND action_code='AD_BID_CHANGE_APPROVE';\n";
            case ACTOR_ROLE -> beforeSelection="UPDATE iam.user_role_assignment SET effective_to="+early+" WHERE user_id='"+TEMPLATE_OWNER+"' AND role_code='OWNER';\n";
            case MAKER_SCOPE_GRANT -> beforeSelection="UPDATE iam.user_scope_grant SET effective_to="+early+" WHERE user_id='0998716b-6f78-56da-bbea-554b20cfd093' AND action_code='ADVERTISING_TASK_ACT';\n";
            case MAKER_ROLE -> beforeSelection="UPDATE iam.user_role_assignment SET effective_to="+early+" WHERE user_id='0998716b-6f78-56da-bbea-554b20cfd093' AND role_code='MARKETPLACE_OPERATOR';\n";
            case ENDORSER_SCOPE_GRANT -> beforeSelection="UPDATE iam.user_scope_grant SET effective_to="+early+" WHERE user_id='8ec704dd-3aa5-529c-93db-def4bbf39260' AND action_code='AD_BID_CHANGE_ENDORSE';\n";
            case ENDORSER_ROLE -> beforeSelection="UPDATE iam.user_role_assignment SET effective_to="+early+" WHERE user_id='8ec704dd-3aa5-529c-93db-def4bbf39260' AND role_code='OPS_LEAD';\n";
        }
        // These are original fixture input changes before any accepted human snapshot exists.
        // Recompute the fixture recommendation digest from those inputs before selection/endorsement.
        if(!beforeSelection.isEmpty()) {
            beforeSelection+="UPDATE ops.recommendation SET entity_version_digest=ops.ad_entity_version_digest(subject_id,(proposed_parameters->>'candidateId')::uuid) WHERE id='"+TEMPLATE_RECOMMENDATION+"';\n";
            sql=replaceExactlyOnce(sql,"INSERT INTO ops.ad_candidate_selection",beforeSelection+"INSERT INTO ops.ad_candidate_selection");
        }
        // Keep the current synthetic company coverage interval covering admission.
        // All approval-time facts and the authority clock remain historical.
        sql=replaceExactlyOnce(sql,"now()-interval '60 days',now()+interval '5 minutes'",
                "now()-interval '60 days',clock_timestamp()+interval '5 minutes'");
        return sql.replace("now()","TIMESTAMPTZ '"+approvedAt+"'");
    }
    private static String replaceExactlyOnce(String sql,String before,String after) {
        assertThat(sql.indexOf(before)).as("known fixture input %s",before).isGreaterThanOrEqualTo(0);
        assertThat(sql.lastIndexOf(before)).as("unambiguous fixture input %s",before).isEqualTo(sql.indexOf(before));
        return sql.replace(before,after);
    }
    /** Independent direct column reads, never the seal's own least(...) calculation. */
    private Map<String,Instant> actualLimits(AdvertisingR1Fixture.Graph graph) {
        Map<String,Instant> result=new LinkedHashMap<>();
        var rows=seed.sql("""
            SELECT 'LEASE' name,a.decided_at+make_interval(secs=>least(p.lease_seconds,p.material_lease_seconds)) deadline
              FROM ops.approval_decision a CROSS JOIN core.ad_approval_lease_policy p WHERE a.id=:approval AND p.id=:lease
            UNION ALL SELECT 'RECOMMENDATION',valid_until FROM ops.recommendation WHERE id=:recommendation
            UNION ALL SELECT 'OWNER_SELECTED',scope_expires_at FROM ops.approval_decision WHERE id=:approval
            UNION ALL SELECT 'POLICY',effective_to FROM core.ad_bid_target_policy WHERE id=:target
            UNION ALL SELECT 'SEMANTIC_PROFILE',effective_to FROM platform.ad_semantic_profile WHERE id=:semantic
            UNION ALL SELECT 'FRESHNESS_PROFILE',p.effective_to FROM core.ad_freshness_profile p JOIN mart.ad_case_purpose_evidence e ON e.freshness_profile_id=p.id
              WHERE e.case_id=:case AND e.evidence_kind='OFFICIAL_AD_SPEND' AND e.decision_purpose='PROTECTION_BID_WRITE'
            UNION ALL SELECT 'REQUIRED_EVIDENCE',min(expires_at) FROM mart.ad_case_purpose_evidence WHERE case_id=:case AND decision_purpose='PROTECTION_BID_WRITE'
            UNION ALL SELECT 'BASELINE',valid_until FROM ops.ad_outcome_baseline WHERE id=:baseline
            UNION ALL SELECT 'CREDENTIAL_METADATA',expires_at FROM platform.credential_metadata WHERE id=:credential
            UNION ALL SELECT 'CREDENTIAL_ATTESTATION',min(valid_until) FROM platform.ad_write_credential_attestation WHERE credential_id=:credential
            UNION ALL SELECT 'GATE',valid_until FROM ops.ad_gate_authority WHERE id=:gate
            UNION ALL SELECT 'ACTOR_SCOPE_GRANT',min(effective_to) FROM iam.user_scope_grant WHERE user_id=:owner AND action_code='AD_BID_CHANGE_APPROVE' AND status='ACTIVE'
            UNION ALL SELECT 'ACTOR_ROLE',min(effective_to) FROM iam.user_role_assignment WHERE user_id=:owner AND role_code='OWNER' AND status='ACTIVE'
            UNION ALL SELECT 'MAKER_SCOPE_GRANT',min(effective_to) FROM iam.user_scope_grant WHERE user_id=:maker AND action_code='ADVERTISING_TASK_ACT' AND status='ACTIVE'
            UNION ALL SELECT 'MAKER_ROLE',min(effective_to) FROM iam.user_role_assignment WHERE user_id=:maker AND role_code='MARKETPLACE_OPERATOR' AND status='ACTIVE'
            UNION ALL SELECT 'ENDORSER_SCOPE_GRANT',min(effective_to) FROM iam.user_scope_grant WHERE user_id=:endorser AND action_code='AD_BID_CHANGE_ENDORSE' AND status='ACTIVE'
            UNION ALL SELECT 'ENDORSER_ROLE',min(effective_to) FROM iam.user_role_assignment WHERE user_id=:endorser AND role_code='OPS_LEAD' AND status='ACTIVE'
            """).param("approval",graph.id("approval")).param("lease",graph.id("approvalLease"))
                .param("recommendation",graph.id("recommendation")).param("target",graph.id("targetPolicy"))
                .param("semantic",graph.id("profile")).param("case",graph.id("caseId"))
                .param("baseline",graph.id("baseline")).param("credential",graph.id("credential"))
                .param("gate",graph.id("gate")).param("owner",graph.id("ownerUser"))
                .param("maker",graph.id("executorUser")).param("endorser",graph.id("verifierUser"))
                .query((row,index)->new Limit(row.getString(1),row.getTimestamp(2))).list();
        for(Limit row:rows) result.put(row.name(),row.at()==null?null:row.at().toInstant());
        assertThat(result).hasSize(Bound.values().length);return result;
    }
    private record Limit(String name,Timestamp at) { }
}
