package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Nothing leaves after the authority that permitted it stops holding.
 *
 * <p>The gate is evaluated three times on the way to a marketplace: when a
 * command is created, when a worker takes the lease, and once more inside the
 * function that opens the attempt — after the destination has been built and
 * immediately before anything is sent. The last of those is the one that
 * matters here, because it is the only one that can catch a kill switch thrown
 * in the milliseconds while a request was being assembled.
 *
 * <p>Commands are created through the real sealed creator in an isolated
 * fictional protocol graph. Capability authority is then withdrawn before any
 * dispatch; privileged fixture changes put stale workers under pressure.
 */
@SpringBootTest
@ActiveProfiles("ci")
class AdvertisingTransmissionBoundaryIT {

    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE =
            TestDatabase.isolatedContainer();

    private static JdbcClient seed;
    private static DataSource migration,application,admin;

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @BeforeAll
    static void openSeedConnection() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        application=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed = JdbcClient.create(migration);
    }

    @Test
    @DisplayName("TC-AD-BOUNDARY-001 an unverified capability closes the gate on its own")
    void unverifiedCapabilityClosesTheGate() {
        var command = seedCommand("PENDING");

        List<String> reasons = gateReasons(command.commandId());

        // The structural refusal this whole Slice rests on. No advertising
        // capability is verified anywhere, so no command can be transmitted,
        // whatever else is true about it.
        assertThat(reasons).contains("CAPABILITY_NOT_VERIFIED");
    }

    @Test
    @DisplayName("TC-AD-BOUNDARY-002 leasing is refused before anything is prepared")
    void leasingIsRefusedWhileNothingIsVerified() {
        var command = seedCommand("PENDING");

        // ops.lease_ad_bid_command evaluates the gate before it hands a worker
        // anything, so the refusal happens before a destination is built, before
        // a credential is touched and before any socket exists. The message
        // names every reason rather than the first, so an operator sees the
        // whole distance to a usable configuration.
        assertThatThrownBy(() -> lease(command.commandId()))
                .hasMessageContaining("the advertising write gate is closed")
                .hasMessageContaining("CAPABILITY_NOT_VERIFIED");
    }

    @Test
    @DisplayName("TC-AD-BOUNDARY-003 a kill switch thrown mid-flight closes the gate")
    void aKillSwitchThrownMidFlightClosesTheGate() {
        var command = seedCommand("EXECUTING");

        // The lease is live and the fence is held: everything a worker needs to
        // send is in hand. This is the window the transmission-time evaluation
        // exists for, and the only one that can catch a switch thrown while a
        // request was being assembled.
        List<String> before = gateReasons(command.commandId());
        assertThat(before).doesNotContain("KILL_SWITCH_ACTIVE");

        activateKillSwitch(command.commandId());

        assertThat(gateReasons(command.commandId()))
                .contains("KILL_SWITCH_ACTIVE")
                .hasSizeGreaterThan(before.size());
        assertThatThrownBy(() -> openAttempt(command.commandId(), 1L, "APPLY"))
                .hasMessageContaining("closed at transmission");
    }

    @Test
    @DisplayName("TC-AD-BOUNDARY-004 an expired approval is named as its own refusal")
    void anExpiredApprovalIsNamedAsItsOwnRefusal() {
        var command = seedCommand("PENDING");
        assertThat(gateReasons(command.commandId())).doesNotContain("APPROVAL_LEASE_EXPIRED");

        // The approval that permitted this command runs out while it is still
        // sitting in the outbox. Nothing has been sent, and the gate now says so
        // in its own words rather than folding it into a generic refusal.
        seed.sql("""
                UPDATE ops.ad_bid_command SET approval_expires_at = now() - interval '1 minute'
                 WHERE id = :id
                """).param("id", command.commandId()).update();

        assertThat(gateReasons(command.commandId())).contains("APPROVAL_LEASE_EXPIRED");
    }

    @Test
    @DisplayName("TC-AD-BOUNDARY-005 a leased command cannot transmit once its reservation lapses")
    void leasedWorkCannotTransmitAfterTheReservationLapses() {
        var command = seedCommand("EXECUTING");

        // Model a stale worker after reservation retirement with a privileged
        // isolated fixture mutation. Canonical release itself is exercised by
        // FrozenOutcomeIT; the worker must validate its lease at transmission.
        seed.sql("""
                UPDATE ops.ad_action_reservation
                   SET configuration_resolved = true, early_observation_complete = true,
                       state='RELEASED',released_at=clock_timestamp(),release_reason='isolated stale-worker fixture'
                 WHERE id = :id
                """).param("id", command.reservationId()).update();

        assertThat(gateReasons(command.commandId())).contains("RESERVATION_CONFLICT");
        assertThatThrownBy(() -> openAttempt(command.commandId(), 1L, "APPLY"))
                .hasMessageContaining("closed at transmission");
    }

    @Test
    @DisplayName("TC-AD-BOUNDARY-006 an unknown result never becomes a second mutating call")
    void anUnknownResultIsNeverRepeated() {
        var command = seedCommand("EXECUTING");

        // A call went out and nothing came back. The attempt is recorded as
        // UNKNOWN_STATE, which is what the product says when it cannot tell
        // whether a real bid changed.
        seed.sql("""
                INSERT INTO ops.ad_bid_command_attempt (id, command_id, attempt_no, purpose,
                        fence_token, lease_owner, started_at, completed_at, outcome_class,
                        correlation_id, request_digest, operation_snapshot)
                VALUES (gen_random_uuid(), :id, 1, 'APPLY', 1, 'boundary-fixture', now(),
                        now(), 'UNKNOWN_STATE', 'boundary-fixture', :digest, '{}'::jsonb)
                """).param("id", command.commandId())
                .param("digest", com.mimococo.marketops.shared.Digest.ofText("first-apply"))
                .update();
        seed.sql("""
                UPDATE ops.ad_bid_command SET state = 'UNKNOWN_REQUIRES_READBACK'
                 WHERE id = :id
                """).param("id", command.commandId()).update();

        // Two layers refuse a second APPLY and they refuse for different
        // reasons, which is what makes this hold however the command got here.
        // From the unknown state, the purpose is simply not openable.
        assertThatThrownBy(() -> openAttemptIgnoringGate(command.commandId(), 1L, "APPLY"))
                .hasMessageContaining("a APPLY attempt cannot be opened from "
                        + "UNKNOWN_REQUIRES_READBACK");

        // And if something had put the command back into EXECUTING — which the
        // transition graph does not allow, asserted below — the mutating-once
        // rule refuses anyway, because the point is that nobody knows whether
        // the first call landed.
        seed.sql("UPDATE ops.ad_bid_command SET state = 'EXECUTING' WHERE id = :id")
                .param("id", command.commandId()).update();
        assertThatThrownBy(() -> openAttemptIgnoringGate(command.commandId(), 1L, "APPLY"))
                .hasMessageContaining("a mutating command operation cannot be dispatched twice");

        // And the graph itself offers no way back to EXECUTING, so no code path
        // can reach a retry by transitioning around the refusal.
        assertThat(seed.sql("""
                SELECT count(*) FROM ops.ad_bid_command_transition
                 WHERE from_state = 'UNKNOWN_REQUIRES_READBACK' AND to_state = 'EXECUTING'
                """).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("TC-AD-BOUNDARY-007 a stale fence cannot open an attempt at all")
    void aStaleFenceOpensNothing() {
        var command = seedCommand("EXECUTING");

        // A worker that lost its lease and did not notice. The fence it holds is
        // the one thing that tells it so.
        assertThatThrownBy(() -> openAttemptIgnoringGate(command.commandId(), 0L, "APPLY"))
                .hasMessageContaining("the lease that authorised this attempt is not current");
    }

    private record Command(UUID commandId,UUID reservationId) { }
    private Command seedCommand(String state) {
        try {
            var graph=AdvertisingR1Fixture.seed(migration);UUID command;
            try(var app=application.getConnection()) {
                app.setAutoCommit(false);
                String proof=AdvertisingR1Fixture.proof(admin,app,graph,graph.id("ownerUser"),null,graph.id("recommendation"),graph.id("approval"));
                AdvertisingR1Fixture.seal(app,graph,proof);command=AdvertisingR1Fixture.createCommand(app,graph);app.commit();
            }
            seed.sql("UPDATE platform.platform_capability SET verification_state='UNVERIFIED' WHERE platform_code=:platform")
                    .param("platform",graph.platform()).update();
            if("EXECUTING".equals(state)) seed.sql("""
                UPDATE ops.ad_bid_command SET state='EXECUTING',fence_token=1,lease_owner='boundary-fixture',
                  lease_expires_at=clock_timestamp()+interval '10 minutes' WHERE id=:id
                """).param("id",command).update();
            return new Command(command,graph.id("reservation"));
        } catch(Exception failure) { throw new AssertionError("fictional sealed command fixture failed",failure); }
    }

    private List<String> gateReasons(UUID commandId) {
        return jdbc.sql("SELECT unnest(ops.evaluate_ad_bid_write_gate(:id))")
                .param("id", commandId).query(String.class).list();
    }

    private void lease(UUID commandId) {
        seed.sql("SELECT ops.lease_ad_bid_command(:id, 'boundary-fixture', 600)")
                .param("id", commandId).query(Long.class).single();
    }

    private UUID openAttempt(UUID commandId, long fence, String purpose) {
        return openAttemptIgnoringGate(commandId, fence, purpose);
    }

    private UUID openAttemptIgnoringGate(UUID commandId, long fence, String purpose) {
        return seed.sql("""
                SELECT ops.open_ad_bid_command_attempt(gen_random_uuid(), :id, :purpose, :fence,
                        'boundary-fixture', :digest, 'boundary-fixture')
                """).param("id", commandId).param("purpose", purpose).param("fence", fence)
                .param("digest", com.mimococo.marketops.shared.Digest.ofText(
                        commandId + ":" + purpose))
                .query(UUID.class).single();
    }

    private void activateKillSwitch(UUID commandId) {
        seed.sql("""
                INSERT INTO ops.ad_containment (id, organization_id, containment_kind,
                        scope_kind, affected_set_digest, cause_class, reason,
                        evidence_reference, activated_by_trigger, activated_at, state,
                        correlation_id, created_at, updated_at)
                SELECT gen_random_uuid(), c.organization_id, 'KILL_SWITCH_ACTIVE',
                       'AFFECTED_SET', c.affected_set_digest, 'BUSINESS_HARM',
                       'synthetic incident thrown mid-flight',
                       'evidence://fixture/kill-switch', 'OPERATOR_DECISION', now(), 'ACTIVE',
                       'boundary-fixture', now(), now()
                  FROM ops.ad_bid_command c WHERE c.id = :id
                """).param("id", commandId).update();
    }
}
