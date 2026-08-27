package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.marketplaceintegration.PriceCommandState;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import com.mimococo.marketops.marketplaceintegration.internal.application.PriceCommandService;
import com.mimococo.marketops.marketplaceintegration.internal.application.PriceCommandWorker;
import com.mimococo.marketops.marketplaceintegration.port.PriceWritePort;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult;
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
    private ScriptedPlatform platform;

    private UUID commandId;

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
        platform.reset();
        PriceCommandFixture.resetSharedState(jdbc);
        commandId = PriceCommandFixture.seed(jdbc, WORKER_NAMESPACE + "-" + UUID.randomUUID());
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
        platform.answer(accepted());
        platform.answer(observed("140.0000"));

        worker.advance(commandId);

        PriceCommandView command = commands.find(commandId).orElseThrow();
        assertThat(command.state()).isEqualTo(PriceCommandState.READBACK_MISMATCH);
        assertThat(command.readbacks().getFirst().matchState()).isEqualTo("DIFFERENT");
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
    }

    @Test
    @DisplayName("TC-WORKER-005 a rate-limited write waits rather than failing")
    void rateLimited() {
        platform.answer(new PriceWriteResult(PriceWriteResult.Outcome.RETRIABLE_ERROR,
                "HTTP 429", null, null, null, new byte[0], Instant.now(),
                "platform_rate_limited"));

        worker.advance(commandId);

        PriceCommandView command = commands.find(commandId).orElseThrow();
        assertThat(command.state()).isEqualTo(PriceCommandState.RETRY_WAIT);
        assertThat(command.retryBudgetRemaining()).isEqualTo(2);
        assertThat(command.nextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("TC-WORKER-006 an unreadable readback leaves the outcome unknown")
    void readbackUnreadable() {
        platform.answer(accepted());
        platform.answer(new PriceWriteResult(PriceWriteResult.Outcome.UNKNOWN_STATE, null,
                null, null, null, new byte[0], Instant.now(),
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
        PriceCommandFixture.makeCapabilityAsynchronous(jdbc);
        platform.answer(new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, "HTTP 202",
                "TASK-1", null, null, new byte[0], Instant.now(), null));
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
        PriceCommandFixture.closeGlobalSwitch(jdbc);

        assertThat(worker.runOnce(10)).isZero();
        assertThat(commands.find(commandId).orElseThrow().state())
                .isEqualTo(PriceCommandState.PENDING);
        assertThat(platform.purposes()).isEmpty();
    }

    private static PriceWriteResult accepted() {
        return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, "HTTP 200", null, null,
                null, new byte[0], Instant.now(), null);
    }

    private static PriceWriteResult observed(String price) {
        return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, "HTTP 200", null,
                new BigDecimal(price), "RUB", new byte[0], Instant.now(), null);
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

        @Bean
        @Primary
        PriceWritePort scriptedPriceWritePort() {
            return this;
        }

        void reset() {
            answers.clear();
            purposes.clear();
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
            PriceWriteResult next = answers.pollFirst();
            return next == null
                    ? new PriceWriteResult(PriceWriteResult.Outcome.UNKNOWN_STATE, null, null,
                            null, null, new byte[0], Instant.now(), "no_answer_was_scripted")
                    : next;
        }
    }
}
