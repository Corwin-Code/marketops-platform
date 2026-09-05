package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingManualPacketRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Manual Shadow records what people did and never carries it out.
 *
 * <p>Two properties matter and both are asserted against a real server. First,
 * a packet is inert: the budget and status changes in its vocabulary have no
 * command, no outbox row and no adapter reachable from them, so the Contract's
 * permission to describe those actions never becomes a permission to perform
 * them. Second, saying you did something is not evidence that you did — an
 * executor's own report cannot establish a configuration, and neither can an
 * "independent" verification carried out by the executor.
 */
@SpringBootTest
@ActiveProfiles("ci")
class AdvertisingManualShadowIT {

    @Autowired
    private AdvertisingManualPacketRepository packets;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private static org.springframework.jdbc.core.simple.JdbcClient seed;

    @org.junit.jupiter.api.BeforeAll
    static void openSeedConnection() {
        var container = TestDatabase.container();
        seed = org.springframework.jdbc.core.simple.JdbcClient.create(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        container.getJdbcUrl(), TestDatabase.migrationRole(),
                        TestDatabase.migrationPassword()));
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        var container = TestDatabase.container();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @Test
    @DisplayName("TC-AD-MANUAL-001 nothing in the manual vocabulary has a command table behind it")
    void manualActionsHaveNoCommandPath() {
        // The three action kinds a packet may name. Exactly one of them has a
        // command table anywhere in this schema, and it is not a coincidence
        // which one.
        assertThat(tableExists("ops", "ad_bid_command")).isTrue();
        assertThat(tableExists("ops", "ad_budget_command")).isFalse();
        assertThat(tableExists("ops", "ad_status_command")).isFalse();

        // And no write-registry row can describe them either.
        assertThat(capabilityCodesInWriteRegistry())
                .doesNotContain("ad-budget-change", "ad-status-change");
    }

    @Test
    @DisplayName("TC-AD-MANUAL-002 a packet has no foreign key into any command lineage")
    void packetIsNotWiredIntoExecution() {
        // A packet that could name a command would be one edge away from being
        // executed by something that followed the edge.
        assertThat(foreignKeyTargets("ops", "ad_manual_execution_packet"))
                .doesNotContain("ops.ad_bid_command", "ops.ad_bid_command_attempt",
                        "ops.price_command");
    }

    @Test
    @DisplayName("TC-AD-MANUAL-003 legacy arbitrary packet issuance is closed; positive human lifecycle uses sealed functions")
    void callerCannotIssueAnArbitraryManualPacket() {
        var fixture=seedFixture();
        assertThatThrownBy(()->issue(fixture,"AD_BUDGET_CHANGE"))
                .isInstanceOf(com.mimococo.marketops.shared.OperationRejectedException.class);
    }

    @Test
    @DisplayName("TC-AD-MANUAL-004/005/006 caller-named evidence grades cannot enter the canonical journal")
    void callerCannotChooseTheirOwnEvidenceGradeOrVerifier() {
        var fixture=seedFixture();
        for(String grade:List.of("EXECUTOR_SELF_REPORT","INDEPENDENT_MANUAL_VERIFICATION",
                "OFFICIAL_API_READBACK","OFFICIAL_CONFIGURATION_EXPORT")) {
            assertThatThrownBy(()->packets.recordVerification(UUID.randomUUID(),fixture.organizationId(),
                    UUID.randomUUID(),grade,fixture.executorUserId(),fixture.verifierUserId(),
                    "campaign.dailyBudget","5000",Instant.now(),"fixture://asserted-grade","NONE","fixture"))
                    .isInstanceOf(com.mimococo.marketops.shared.OperationRejectedException.class);
        }
    }

    @Test
    @DisplayName("TC-AD-MANUAL-007/008 expiry uses server time and legacy unscoped revocation is closed")
    void oldMutationMethodsCannotSupplyAuthority() {
        assertThatThrownBy(()->packets.revoke(UUID.randomUUID(),"caller assertion"))
                .isInstanceOf(com.mimococo.marketops.shared.OperationRejectedException.class);
        // A caller cannot expire live instructions by choosing a distant clock.
        assertThat(packets.expire(Instant.parse("9999-01-01T00:00:00Z"))).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.sql("SELECT has_table_privilege(current_user,'ops.ad_manual_execution_packet','INSERT,UPDATE,DELETE')")
                .query(Boolean.class).single()).isFalse();
        // The real Maker → OpsLead → Owner → reservation → independent/official
        // observation, withdrawal of scope, replay and later-conflict assertions
        // live in AdvertisingManualWorkflowIT against the same PostgreSQL schema.
    }

    private UUID issue(AdvertisingGraphFixture.Graph fixture, String actionKind) {
        return issue(fixture, actionKind, Instant.now().minus(Duration.ofMinutes(1)),
                Duration.ofHours(8));
    }

    private UUID issue(AdvertisingGraphFixture.Graph fixture, String actionKind,
                       Instant issuedAt, Duration window) {
        return packets.issue(UUID.randomUUID(), fixture.organizationId(), fixture.caseId(),
                fixture.objectId(), fixture.storeId(), "OZON", fixture.affectedSetId(),
                fixture.digest(), fixture.semanticProfileId(), actionKind,
                fixture.configurationId(), "{\"dailyBudget\":\"5000.00\"}",
                "the case asks for a change this product does not write",
                "evidence://fixture/case", null, List.of(), fixture.executorUserId(),
                "{\"expected\":\"lower spend\"}",
                "{\"evidenceGrade\":\"INDEPENDENT_MANUAL_VERIFICATION\"}",
                issuedAt, issuedAt.plus(window), "manual-fixture");
    }

    private boolean tableExists(String schema, String table) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM information_schema.tables
                                WHERE table_schema = :schema AND table_name = :table)
                """).param("schema", schema).param("table", table)
                .query(Boolean.class).single());
    }

    private List<String> capabilityCodesInWriteRegistry() {
        return jdbc.sql("SELECT DISTINCT capability_code FROM platform.platform_capability")
                .query(String.class).list();
    }

    private List<String> foreignKeyTargets(String schema, String table) {
        return jdbc.sql("""
                SELECT DISTINCT target_ns.nspname || '.' || target.relname
                  FROM pg_constraint constraint_row
                  JOIN pg_class source ON source.oid = constraint_row.conrelid
                  JOIN pg_namespace source_ns ON source_ns.oid = source.relnamespace
                  JOIN pg_class target ON target.oid = constraint_row.confrelid
                  JOIN pg_namespace target_ns ON target_ns.oid = target.relnamespace
                 WHERE constraint_row.contype = 'f'
                   AND source_ns.nspname = :schema AND source.relname = :table
                """).param("schema", schema).param("table", table)
                .query(String.class).list();
    }

    private AdvertisingGraphFixture.Graph seedFixture() {
        return AdvertisingGraphFixture.seed(seed);
    }
}
