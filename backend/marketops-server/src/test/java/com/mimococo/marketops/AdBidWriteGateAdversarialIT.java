package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The write gate, attacked one fact at a time.
 *
 * <p>A gate that refuses everything is as useless as one that refuses nothing,
 * and both look identical from a test that only ever asks a broken fixture what
 * it thinks. So every case below does the same thing: change exactly one fact
 * in the database, ask the gate again, and require that exactly the expected
 * reason appears or disappears. A reason that never moves is a reason nobody is
 * actually computing.
 *
 * <p>Nothing here makes the gate satisfiable, and nothing here is meant to. An
 * advertising capability cannot be verified anywhere, so the fixture's baseline
 * is a long list of refusals; what these cases prove is that each entry on it is
 * earned separately rather than emitted together.
 *
 * <p>The gate also has to <em>refuse</em> rather than raise. Every call below
 * goes through {@code ops.evaluate_ad_bid_write_gate} and expects an array back;
 * a function that threw would fail these cases as errors rather than as
 * assertions, which is the distinction V0053 was written to restore.
 */
@SpringBootTest
@ActiveProfiles("ci")
class AdBidWriteGateAdversarialIT {

    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE =
            TestDatabase.isolatedContainer();

    private static JdbcClient seed;
    private static AdvertisingGraphFixture.Graph graph;
    private static AdvertisingGraphFixture.Command command;

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
        seed = JdbcClient.create(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword()));
    }

    @BeforeEach
    void seedTheGraphOnce() {
        if (graph != null) {
            return;
        }
        graph = AdvertisingGraphFixture.seed(seed);
        AdvertisingGraphFixture.Decision decision = AdvertisingGraphFixture.seedDecision(
                seed, graph, "PROTECTION_DECREASE", "MAX_CPC_BOUNDED");
        command = AdvertisingGraphFixture.seedCommand(seed, graph, decision, "PENDING");
    }

    @Test
    @DisplayName("TC-AD-GATE-ADV-001 the gate answers with an array rather than raising")
    void theGateRefusesRatherThanRaises() {
        // The whole point of V0053. An ambiguous array append made this function
        // raise 'malformed array literal' instead of returning its reasons, and
        // a gate that throws is a gate whose refusals nobody can read.
        Set<String> reasons = reasons();

        assertThat(reasons).isNotEmpty();
        assertThat(reasons).contains("CAPABILITY_NOT_VERIFIED", "BUNDLE_UNRESOLVED");
    }

    @Test
    @DisplayName("TC-AD-GATE-ADV-002 a command that does not exist is refused by name")
    void anAbsentCommandIsRefusedByName() {
        // Never an empty array. An empty refusal list is how this function says
        // "permitted", so a command it cannot find has to answer with a reason
        // rather than with nothing.
        List<String> unknown = seed.sql(
                "SELECT unnest(ops.evaluate_ad_bid_write_gate(:id)) AS reason")
                .param("id", UUID.randomUUID()).query(String.class).list();

        assertThat(unknown).containsExactly("COMMAND_NOT_FOUND");
    }

    @Test
    @DisplayName("TC-AD-GATE-ADV-003 a kill switch adds exactly its own reason")
    void aKillSwitchAddsExactlyItsOwnReason() {
        Set<String> before = reasons();
        assertThat(before).doesNotContain("KILL_SWITCH_ACTIVE");
        UUID containment = activateContainment("KILL_SWITCH_ACTIVE");
        try {
            Set<String> withKill = reasons();

            assertThat(withKill).contains("KILL_SWITCH_ACTIVE");
            // Exactly one reason more. A kill switch that also flipped an
            // unrelated axis would make the two impossible to tell apart in an
            // incident, which is when somebody most needs to know which fired.
            assertThat(difference(withKill, before)).containsExactly("KILL_SWITCH_ACTIVE");
        } finally {
            reenable(containment);
        }
        assertThat(reasons()).isEqualTo(before);
    }

    @Test
    @DisplayName("TC-AD-GATE-ADV-004 a quarantine and a kill switch are separate reasons")
    void aQuarantineIsNotAKillSwitch() {
        Set<String> before = reasons();
        UUID containment = activateContainment("EMERGENCY_ENTITY_HOLD");
        try {
            Set<String> quarantined = reasons();

            // Five kinds of stop, and they are not degrees of one thing. An
            // operator reading a single severity would learn nothing about what
            // to fix.
            assertThat(difference(quarantined, before)).containsExactly("QUARANTINE_ACTIVE");
            assertThat(quarantined).doesNotContain("KILL_SWITCH_ACTIVE");
        } finally {
            reenable(containment);
        }
        assertThat(reasons()).isEqualTo(before);
    }

    @Test
    @DisplayName("TC-AD-GATE-ADV-005 the reservation cannot be released out from under a live command")
    void aLiveReservationCannotBeReleasedEarly() {
        Set<String> before = reasons();
        // The attack this case was written to try is refused one level lower
        // than the gate. A reservation may not move to RELEASED while its
        // configuration is unresolved, an unknown stands against it, its early
        // observation has not closed or a regression is open — so there is no
        // state in which a command names a released reservation and the gate has
        // to notice.
        assertThatThrownBy(() -> seed.sql("""
                UPDATE ops.ad_action_reservation
                   SET state = 'RELEASED', released_at = now(), release_reason = 'adversarial'
                 WHERE id = :id
                """).param("id", command.reservationId()).update())
                .hasMessageContaining("ad_action_reservation_release_conditions_ck");

        // And with every condition met it releases, at which point the command
        // that named it is refused rather than permitted.
        seed.sql("""
                UPDATE ops.ad_action_reservation
                   SET configuration_resolved = true, unknown_or_mismatch_open = false,
                       early_observation_complete = true, regression_open = false,
                       state = 'RELEASED', released_at = now(), release_reason = 'adversarial'
                 WHERE id = :id
                """).param("id", command.reservationId()).update();
        try {
            assertThat(difference(reasons(), before)).contains("RESERVATION_CONFLICT");
        } finally {
            seed.sql("""
                    UPDATE ops.ad_action_reservation
                       SET state = 'ACTIVE', released_at = NULL, release_reason = NULL,
                           configuration_resolved = false, early_observation_complete = false
                     WHERE id = :id
                    """).param("id", command.reservationId()).update();
        }
        assertThat(reasons()).isEqualTo(before);
    }

    @Test
    @DisplayName("TC-AD-GATE-ADV-006 an envelope that resolves removes exactly that reason and no other")
    void aResolvedEnvelopeRemovesOnlyItsOwnReason() {
        // The fixture already writes one, so this case works the other way: it
        // retires the envelope and requires the unresolved reason to appear
        // alone. An envelope axis that leaked into another reason would show up
        // here as a second difference.
        Set<String> before = reasons();
        assertThat(before).doesNotContain("AGGREGATE_ENVELOPE_UNRESOLVED");
        seed.sql("""
                UPDATE core.ad_exposure_envelope SET status = 'CANCELLED'
                 WHERE organization_id = :organizationId
                """).param("organizationId", graph.organizationId()).update();
        try {
            Set<String> withoutEnvelope = reasons();

            assertThat(difference(withoutEnvelope, before))
                    .containsExactly("AGGREGATE_ENVELOPE_UNRESOLVED");
        } finally {
            seed.sql("""
                    UPDATE core.ad_exposure_envelope SET status = 'ACTIVE'
                     WHERE organization_id = :organizationId
                    """).param("organizationId", graph.organizationId()).update();
        }
        assertThat(reasons()).isEqualTo(before);
    }

    @Test
    @DisplayName("TC-AD-GATE-ADV-007 every baseline reason names a fact somebody could establish")
    void everyBaselineReasonIsInTheDeclaredVocabulary() {
        // A reason the vocabulary does not carry is a reason no runbook can
        // explain and no console can present. The set is closed on purpose.
        assertThat(reasons()).isSubsetOf(List.of(
                "AFFECTED_SET_DIGEST_CHANGED", "AGGREGATE_ENVELOPE_BLOCKED",
                "AGGREGATE_ENVELOPE_UNRESOLVED", "APPROVAL_LEASE_EXPIRED",
                "AUTHORIZATION_INVALID_OR_EXPIRED", "BUNDLE_SCOPE_EXCEEDED", "BUNDLE_UNRESOLVED",
                "CANDIDATE_BASIS_NOT_ENABLED", "CAPABILITY_NOT_AVAILABLE_FOR_STORE",
                "CAPABILITY_NOT_VERIFIED", "CAPABILITY_SWITCH_DISABLED",
                "COMMAND_AUTHORITY_MISMATCH", "DIRECTION_NOT_ENABLED", "ENTITY_NOT_ALLOWLISTED",
                "GLOBAL_SWITCH_DISABLED", "GUARDRAIL_NOT_PASSED", "KILL_SWITCH_ACTIVE",
                "MAPPING_CONFLICT_OPEN", "MAPPING_UNRESOLVED", "MATERIALITY_UNRESOLVED",
                "ORDINARY_ROUTE_NOT_PROMOTED", "QUARANTINE_ACTIVE", "RECOMMENDATION_STALE",
                "RESERVATION_CONFLICT", "SCOPED_SWITCH_DISABLED", "COMMAND_NOT_FOUND"));
    }

    @Test
    @DisplayName("TC-AD-GATE-ADV-008 the gate is never satisfiable in this environment")
    void theGateCannotBeSatisfiedHere() {
        // The property every other case rests on. No advertising capability is
        // verified anywhere, no policy bundle is active, and neither can be made
        // so from here — which is why the fixture seeds a command directly
        // rather than asking the product to create one.
        assertThat(reasons()).isNotEmpty();
        assertThat(seed.sql("""
                SELECT count(*) FROM platform.platform_capability
                 WHERE capability_code = 'ad-bid-change' AND verification_state = 'VERIFIED'
                """).query(Long.class).single()).isZero();
        assertThat(seed.sql("""
                SELECT count(*) FROM ops.ad_decision_policy_bundle WHERE status = 'ACTIVE'
                """).query(Long.class).single()).isZero();
    }

    /** The gate's answer for the fixture command, as a set. */
    private static Set<String> reasons() {
        return Set.copyOf(seed.sql("""
                SELECT unnest(ops.evaluate_ad_bid_write_gate(:id)) AS reason
                """).param("id", command.commandId()).query(String.class).list());
    }

    private static Set<String> difference(Set<String> larger, Set<String> smaller) {
        return larger.stream().filter(reason -> !smaller.contains(reason))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** One containment over the fixture object, of the kind asked for. */
    private UUID activateContainment(String kind) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO ops.ad_containment (id, organization_id, containment_kind,
                        scope_kind, ad_native_object_id, cause_class, reason,
                        evidence_reference, activated_by_trigger, activated_at, state,
                        correlation_id, created_at, updated_at)
                VALUES (:id, :organizationId, :kind, 'ENTITY', :objectId,
                        'EXECUTION_INTEGRITY', 'adversarial gate probe',
                        'evidence://fixture/adversarial', 'ADVERSARIAL_GATE_PROBE', now(),
                        'ACTIVE', :correlationId, now(), now())
                """)
                .param("id", id)
                .param("organizationId", graph.organizationId())
                .param("kind", kind)
                .param("objectId", graph.objectId())
                .param("correlationId", "adversarial-" + id)
                .update();
        return id;
    }

    private void reenable(UUID containmentId) {
        seed.sql("""
                UPDATE ops.ad_containment
                   SET state = 'REENABLED', reenabled_at = now(),
                       endorsed_by_user_id = :endorser, approved_by_user_id = :approver,
                       root_cause_classified = true, unknowns_resolved = true,
                       authorities_replaced = true, results_reconciled = true,
                       capability_evidence_current = true,
                       -- A technical or security cause needs an attestation
                       -- before anything restarts, and the schema says so.
                       security_attestation_present = true,
                       -- Reenablement names the scope it restores, so nobody can
                       -- lift a hold and leave what it covered undefined.
                       reenabled_scope = '{"scope": "adversarial-probe"}'::jsonb
                 WHERE id = :id
                """)
                .param("id", containmentId)
                .param("endorser", graph.executorUserId())
                .param("approver", graph.verifierUserId())
                .update();
    }
}
