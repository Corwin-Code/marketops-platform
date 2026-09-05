package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.marketplaceintegration.PriceCommandState;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import com.mimococo.marketops.marketplaceintegration.internal.application.PriceCommandService;
import com.mimococo.marketops.marketplaceintegration.internal.application.PriceCommandWorker;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PriceCommandRepository;
import com.mimococo.marketops.marketplaceintegration.port.PriceWritePort;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The worker that drives a price command, with the marketplace replaced by a
 * script of answers.
 *
 * <p>What is under test is this product's own behaviour when a platform answers
 * in each of the ways a platform can: it accepts, it refuses, it rate-limits,
 * it times out, and it answers a readback with something other than what was
 * asked for. Every one of those is a decision the worker has to make correctly,
 * and none of them can be exercised by contacting a real marketplace under this
 * authorization.
 *
 * <p><strong>None of this is evidence about Ozon, Wildberries or any other
 * external system, and it is not offered as such.</strong> The recorded answers
 * are a script written here; what they prove is that the worker responds to
 * each shape of answer the way the state machine says it must.
 */
@SpringBootTest
@ActiveProfiles("ci")
@Import(PriceCommandWorkerIT.ScriptedPlatform.class)
class PriceCommandWorkerIT {

    private static final String WORKER_NAMESPACE = "price-command-worker-it";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PriceCommandWorker worker;

    @Autowired
    private PriceCommandService commands;

    @Autowired
    private PriceCommandRepository commandRepository;

    @Autowired
    private ScriptedPlatform platform;

    @Autowired private com.mimococo.marketops.marketplaceintegration.RawCustody custody;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private com.mimococo.marketops.adminobservability.internal.infrastructure.jdbc.OperationalTelemetryRepository telemetry;

    private UUID commandId;
    private JdbcClient arranger;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        var container = TestDatabase.container();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @BeforeEach
    void seedOneExecutableCommand() {
        arranger = JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                TestDatabase.container().getJdbcUrl(), TestDatabase.migrationRole(), TestDatabase.migrationPassword()));
        platform.reset();
        PriceCommandFixture.resetSharedState(arranger);
        commandId = PriceCommandFixture.seed(arranger, WORKER_NAMESPACE + "-" + UUID.randomUUID());
        platform.beforeCall = request -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            // DriverManagerDataSource opens an independent connection, so an
            // uncommitted prepare would be invisible here.
            assertThat(arranger.sql("SELECT count(*) FROM ops.price_command_attempt"
                    + " WHERE command_id=:id AND outcome_class='IN_FLIGHT' AND request_digest=:digest")
                    .param("id", commandId).param("digest", request.digest())
                    .query(Integer.class).single()).isEqualTo(1);
        };
    }

    @org.junit.jupiter.api.AfterEach
    void retireOnlyThisFixtureAuthority() {
        arranger.sql("UPDATE ops.pilot_allowlist_entry SET status='REVOKED', revoked_reason='fixture finished'"
                + " WHERE store_id=(SELECT store_id FROM ops.price_command WHERE id=:id)")
                .param("id", commandId).update();
    }

    @Test
    @DisplayName("TC-WORKER-001 an accepted write is not a success until it is read back")
    void acceptedThenReadBack() {
        platform.answer(accepted());
        platform.answer(observed("105.0000"));

        assertThat(worker.advance(commandId)).isTrue();

        PriceCommandView command = commands.find(commandId).orElseThrow();
        assertThat(command.state()).isEqualTo(PriceCommandState.SUCCEEDED);
        assertThat(command.readbacks()).hasSize(1);
        assertThat(command.readbacks().getFirst().matchState()).isEqualTo("MATCHES_TARGET");
        assertThat(platform.purposes()).containsExactly("APPLY", "READBACK");
    }

    @Test
    @DisplayName("TC-WORKER-002 a readback observing something else is a mismatch, not a failure")
    void readbackObservedSomethingElse() {
        var before = telemetry.snapshot();
        platform.answer(accepted());
        platform.answer(observed("140.0000"));

        worker.advance(commandId);

        PriceCommandView command = commands.find(commandId).orElseThrow();
        assertThat(command.state()).isEqualTo(PriceCommandState.READBACK_MISMATCH);
        assertThat(command.readbacks().getFirst().matchState()).isEqualTo("DIFFERENT");
        var after = telemetry.snapshot();
        assertThat(after.get("price_command_awaiting_operator")).isEqualTo(before.get("price_command_awaiting_operator") + 1);
        assertThat(after.get("price_command_readback_mismatch")).isEqualTo(before.get("price_command_readback_mismatch") + 1);
    }

    @Test
    @DisplayName("TC-WORKER-003 a platform refusal ends the command and makes no readback")
    void platformRefused() {
        platform.answer(new PriceWriteResult(PriceWriteResult.Outcome.REJECTED, "HTTP 400",
                null, null, null, new byte[0], Instant.now(), "platform_rejected"));

        worker.advance(commandId);

        PriceCommandView command = commands.find(commandId).orElseThrow();
        assertThat(command.state()).isEqualTo(PriceCommandState.FAILED_FINAL);
        assertThat(command.failureCode()).isEqualTo("platform_rejected");
        assertThat(command.readbacks()).isEmpty();
        assertThat(platform.purposes()).containsExactly("APPLY");
    }

    @Test
    @DisplayName("TC-WORKER-004 an unclassifiable write is never repeated")
    void unknownWriteOutcome() {
        long before = telemetry.snapshot().get("price_command_awaiting_operator");
        platform.answer(new PriceWriteResult(PriceWriteResult.Outcome.UNKNOWN_STATE, null,
                null, null, null, new byte[0], Instant.now(),
                "platform_did_not_answer_a_write"));

        worker.advance(commandId);

        PriceCommandView command = commands.find(commandId).orElseThrow();
        assertThat(command.state()).isEqualTo(PriceCommandState.UNKNOWN_REQUIRES_READBACK);

        // A second pass does not pick it up: there is no path from unknown back
        // to executing, and the claim query does not offer it.
        int worked = worker.runOnce(10);
        assertThat(commands.find(commandId).orElseThrow().state())
                .isEqualTo(PriceCommandState.UNKNOWN_REQUIRES_READBACK);
        assertThat(worked).isZero();
        assertThat(telemetry.snapshot().get("price_command_awaiting_operator")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("TC-WORKER-005 a rate-limited write remains unknown and is not repeated")
    void rateLimited() {
        platform.answer(new PriceWriteResult(PriceWriteResult.Outcome.RETRIABLE_ERROR,
                "HTTP 429", null, null, null, new byte[0], Instant.now(),
                "platform_rate_limited"));

        worker.advance(commandId);

        PriceCommandView command = commands.find(commandId).orElseThrow();
        assertThat(command.state()).isEqualTo(PriceCommandState.UNKNOWN_REQUIRES_READBACK);
        assertThat(command.retryBudgetRemaining()).isEqualTo(3);
        assertThat(platform.purposes()).containsExactly("APPLY");
    }

    @Test
    @DisplayName("TC-WORKER-006 an unreadable readback leaves the outcome unknown")
    void readbackUnreadable() {
        platform.answer(accepted());
        platform.answer(new PriceWriteResult(PriceWriteResult.Outcome.UNKNOWN_STATE, "HTTP 200",
                null, null, null, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Instant.now(),
                "observed_price_not_at_recorded_pointer"));

        worker.advance(commandId);

        PriceCommandView command = commands.find(commandId).orElseThrow();
        assertThat(command.state()).isEqualTo(PriceCommandState.UNKNOWN_REQUIRES_READBACK);
        assertThat(command.readbacks()).hasSize(1);
        assertThat(command.readbacks().getFirst().matchState()).isEqualTo("UNREADABLE");
        assertThat(command.readbacks().getFirst().observedPrice()).isNull();
    }

    @Test
    @DisplayName("TC-WORKER-007 an asynchronous platform is enquired about before readback")
    void asynchronousWrite() {
        PriceCommandFixture.makeCapabilityAsynchronous(arranger);
        platform.answer(new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, "HTTP 202",
                "TASK-1", null, null, "{\"accepted\":true,\"task\":\"TASK-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Instant.now(), null));
        platform.answer(accepted());
        platform.answer(observed("105.0000"));

        worker.advance(commandId);

        PriceCommandView command = commands.find(commandId).orElseThrow();
        assertThat(command.state()).isEqualTo(PriceCommandState.SUCCEEDED);
        assertThat(platform.purposes())
                .containsExactly("APPLY", "STATUS_ENQUIRY", "READBACK");
    }

    @Test
    @DisplayName("TC-WORKER-008 every call is recorded before it is made")
    void attemptsAreRecorded() {
        platform.answer(accepted());
        platform.answer(observed("105.0000"));

        worker.advance(commandId);

        PriceCommandView command = commands.find(commandId).orElseThrow();
        assertThat(command.attempts()).hasSize(2);
        command.attempts().forEach(attempt -> {
            assertThat(attempt.startedAt()).isNotNull();
            assertThat(attempt.completedAt()).isNotNull();
            assertThat(attempt.outcomeClass()).isNotBlank();
        });
    }

    @Test
    @DisplayName("TC-WORKER-009 a pass with nothing ready does nothing")
    void quietPass() {
        PriceCommandFixture.closeGlobalSwitch(arranger);
        assertThat(telemetry.snapshot().get("price_command_gate_closed")).isEqualTo(1);

        assertThat(worker.runOnce(10)).isZero();
        assertThat(commands.find(commandId).orElseThrow().state())
                .isEqualTo(PriceCommandState.PENDING);
        assertThat(platform.purposes()).isEmpty();
    }

    @Test
    @DisplayName("TC-WORKER-010 an interrupted dispatch remains durable and recovers without reapplying")
    void interruptedDispatchIsNeverReapplied() {
        platform.failure = new IllegalStateException("synthetic interrupted dispatch");
        assertThatThrownBy(() -> worker.advance(commandId)).isInstanceOf(IllegalStateException.class);
        assertThat(commands.find(commandId).orElseThrow().attempts().getFirst().outcomeClass())
                .isEqualTo("IN_FLIGHT");
        arranger.sql("UPDATE ops.price_command SET lease_expires_at=now()-interval '1 second' WHERE id=:id")
                .param("id", commandId).update();
        platform.failure = null;
        worker.runOnce(10);
        assertThat(commands.find(commandId).orElseThrow().state())
                .isEqualTo(PriceCommandState.UNKNOWN_REQUIRES_READBACK);
        assertThat(platform.purposes()).containsExactly("APPLY");
    }

    @Test
    @DisplayName("TC-WORKER-011 external I/O refuses an inherited transaction")
    void inheritedTransactionsAreRefusedBeforeIo() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.execute(status -> worker.advance(commandId)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transaction.execute(status -> custody.store("tx-refusal", new byte[]{1})))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(platform.purposes()).isEmpty();
        assertThat(commands.find(commandId).orElseThrow().attempts()).isEmpty();
    }

    @Test
    @DisplayName("TC-WORKER-012 compensation uses a distinct stable write identity and proves the restored value")
    void compensationHasItsOwnWriteIdentity() {
        prepareCompensation();
        var command = commandRepository.row(commandId).orElseThrow();
        platform.answer(observed("105.0000"));
        platform.answer(accepted());
        platform.answer(observed(command.priorPrice().toPlainString()));

        assertThat(worker.compensate(commandId)).isTrue();

        assertThat(commands.find(commandId).orElseThrow().state()).isEqualTo(PriceCommandState.COMPENSATED);
        assertThat(platform.purposes()).containsExactly("READBACK", "RESTORE", "READBACK");
        var restore = platform.requests.get(1);
        assertThat(restore.idempotencyKey()).isNotEqualTo(command.idempotencyKey())
                .isEqualTo(Digest.ofComponents(List.of(command.idempotencyKey(), "RESTORE")));
        assertThat(restore.expectedVersionToken()).isEqualTo("fixture-current-version");
        assertThat(restore.targetPrice().amount()).isEqualByComparingTo(command.priorPrice());
        assertThat(worker.compensate(commandId)).isFalse();
        assertThat(platform.purposes()).containsExactly("READBACK", "RESTORE", "READBACK");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CHANGED", "NO_TOKEN", "REJECTED", "UNKNOWN", "UNREADABLE_AFTER", "CHANGED_AFTER"})
    @DisplayName("TC-WORKER-013 compensation refuses stale targets and cannot claim success without a final observation")
    void compensationDoesNotOverwriteOrGuess(String scenario) {
        prepareCompensation();
        if (scenario.equals("NO_TOKEN")) platform.etag = null;
        platform.answer(observed(scenario.equals("CHANGED") ? "140.0000" : "105.0000"));
        platform.answer(scenario.equals("REJECTED")
                ? new PriceWriteResult(PriceWriteResult.Outcome.REJECTED, "HTTP 412", null, null,
                    null, new byte[0], Instant.now(), "condition_changed")
                : scenario.equals("UNKNOWN")
                    ? new PriceWriteResult(PriceWriteResult.Outcome.UNKNOWN_STATE, null, null, null,
                        null, new byte[0], Instant.now(), "synthetic_timeout") : accepted());
        platform.answer(scenario.equals("UNREADABLE_AFTER")
                ? new PriceWriteResult(PriceWriteResult.Outcome.UNKNOWN_STATE, "HTTP 200", null, null,
                    null, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Instant.now(), null)
                : observed("140.0000"));

        assertThat(worker.compensate(commandId)).isTrue();

        assertThat(commands.find(commandId).orElseThrow().state()).isEqualTo(scenario.equals("REJECTED")
                ? PriceCommandState.COMPENSATION_FAILED : PriceCommandState.MANUAL_RESOLUTION);
        if (scenario.equals("CHANGED") || scenario.equals("NO_TOKEN")) {
            assertThat(platform.purposes()).containsExactly("READBACK");
        } else if (scenario.equals("REJECTED") || scenario.equals("UNKNOWN")) {
            assertThat(platform.purposes()).containsExactly("READBACK", "RESTORE");
        } else {
            assertThat(platform.purposes()).containsExactly("READBACK", "RESTORE", "READBACK");
        }
    }

    /** A crash after recording a target readback, before declaring success, followed by manual takeover. */
    private void prepareCompensation() {
        String owner = "compensation-fixture";
        long fence = commandRepository.lease(commandId, owner, 600);
        commandRepository.transition(commandId, fence, owner, "EXECUTING", null, null, null);
        recordFixtureResponse(PriceWriteRequest.Operation.APPLY, fence, owner, accepted());
        commandRepository.transition(commandId, fence, owner, "READBACK_PENDING", null, null, null);
        recordFixtureResponse(PriceWriteRequest.Operation.READBACK, fence, owner, observed("105.0000"));
        commandRepository.transition(commandId, fence, owner, "UNKNOWN_REQUIRES_READBACK", null, null, null);
        commandRepository.transition(commandId, fence, owner, "MANUAL_RESOLUTION", null, null, null);
        commandRepository.transition(commandId, fence, owner, "COMPENSATION_PENDING", null, null, null);
    }

    private void recordFixtureResponse(PriceWriteRequest.Operation operation, long fence, String owner, PriceWriteResult result) {
        var command = commandRepository.row(commandId).orElseThrow();
        var identity = jdbc.sql("""
                SELECT listing.native_listing_key,variant.native_variant_key
                FROM core.platform_listing_variant variant JOIN core.platform_listing listing ON listing.id=variant.platform_listing_id
                WHERE variant.id=:id
                """).param("id",command.platformListingVariantId()).query().singleRow();
        UUID attempt = UUID.randomUUID();
        var request = new PriceWriteRequest(operation,command.capabilityId(),null,
                (String)identity.get("native_listing_key"),(String)identity.get("native_variant_key"),
                Money.of(command.targetPrice(),command.currencyCode()),command.idempotencyKey(),null,null,attempt);
        commandRepository.openAttempt(attempt,commandId,operation.name(),fence,owner,request.digest(),owner);
        var response = result.withResponse(result.body(),new PriceWriteResult.Response(200,
                java.util.Map.of("etag","fixture-old-version"),request.digest(),"PROTOCOL_FIXTURE"));
        assertThat(commandRepository.completeAttempt(attempt,fence,owner,response,
                custody.store("compensation-fixture",result.body()).contentId(),request.digest()).outcome())
                .isEqualTo(PriceWriteResult.Outcome.ACCEPTED);
        if (operation == PriceWriteRequest.Operation.READBACK) {
            assertThat(commandRepository.insertReadback(UUID.randomUUID(),commandId,attempt,fence,owner,owner))
                    .isEqualTo("MATCHES_TARGET");
        }
    }

    private static PriceWriteResult accepted() {
        return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, "HTTP 200", null, null,
                null, "{\"accepted\":true,\"status\":\"done\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Instant.now(), null);
    }

    private static PriceWriteResult observed(String price) {
        return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, "HTTP 200", null,
                new BigDecimal(price), "RUB", ("{\"price\":\"" + price + "\",\"currency\":\"RUB\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), Instant.now(), null);
    }

    /**
     * A marketplace replaced by a script.
     *
     * <p>It records what it was asked to do and answers with whatever the test
     * queued. It contacts nothing, and it is not evidence about anything
     * outside this repository.
     */
    @TestConfiguration
    static class ScriptedPlatform implements PriceWritePort {

        private final Deque<PriceWriteResult> answers = new ArrayDeque<>();
        private final List<String> purposes = new ArrayList<>();
        private final List<PriceWriteRequest> requests = new ArrayList<>();
        String etag = "fixture-current-version";
        java.util.function.Consumer<PriceWriteRequest> beforeCall = request -> { };
        RuntimeException failure;

        @Bean
        @Primary
        PriceWritePort scriptedPriceWritePort() {
            return this;
        }

        void reset() {
            answers.clear();
            purposes.clear();
            requests.clear();
            etag = "fixture-current-version";
            failure = null;
        }

        void answer(PriceWriteResult result) {
            answers.addLast(result);
        }

        List<String> purposes() {
            return List.copyOf(purposes);
        }

        @Override
        public PriceWriteResult perform(PriceWriteRequest request) {
            purposes.add(request.operation().name());
            requests.add(request);
            beforeCall.accept(request);
            if (failure != null) throw failure;
            PriceWriteResult next = answers.pollFirst();
            return next == null
                    ? new PriceWriteResult(PriceWriteResult.Outcome.UNKNOWN_STATE, null, null,
                            null, null, new byte[0], Instant.now(), "no_answer_was_scripted")
                    : next.nativeStatus() != null && next.nativeStatus().startsWith("HTTP ")
                        ? next.withResponse(next.body(), new PriceWriteResult.Response(
                            Integer.parseInt(next.nativeStatus().substring(5)),
                            etag == null ? java.util.Map.of() : java.util.Map.of("etag",etag),
                            request.digest(), "PROTOCOL_FIXTURE")) : next;
        }
    }
}
