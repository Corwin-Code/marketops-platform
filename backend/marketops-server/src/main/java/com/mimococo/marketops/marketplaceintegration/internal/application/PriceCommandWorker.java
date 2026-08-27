package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.PriceCommandState;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PriceCommandRepository;
import com.mimococo.marketops.marketplaceintegration.port.PriceWritePort;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ListingVariantContext;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Drives one price command from claimed to finished, one platform call at a
 * time.
 *
 * <p>Nothing here is transactional. Each state change is its own committed
 * statement through the database's own function, and the platform call happens
 * between them, holding no lock and no open transaction. A worker that dies
 * mid-call therefore leaves a committed attempt record and an expiring lease,
 * which recovery turns into a command an operator can see rather than a row
 * nobody can move.
 *
 * <p>The worker draws no conclusions the database would not accept. It cannot
 * mark a command succeeded without a readback that observed the intended value,
 * cannot retry a write whose outcome is unknown, and cannot restore a price the
 * platform no longer holds — those rules live in the transition function, and
 * this class is written to work with them rather than around them.
 *
 * <p>An attempt row is written before every call and completed after it. That
 * ordering is the point: evidence that a call was started must survive the
 * process that started it.
 */
@Service
public class PriceCommandWorker {

    private static final Logger log = LoggerFactory.getLogger(PriceCommandWorker.class);

    /** How long a worker's claim on a command lasts. */
    private static final int LEASE_SECONDS = 120;

    /** How long to wait before a retriable condition is tried again. */
    private static final int RETRY_DELAY_SECONDS = 60;

    /** Refusal raised by the database when the write gate is closed. */
    private static final String GATE_CLOSED = "MO032";

    /** Refusal raised when a command is not in a state that allows the move. */
    private static final String TRANSITION_REFUSED = "MO031";

    /** Refusal raised when this worker no longer holds the command. */
    private static final String AUTHORITY_LOST = "MO030";

    private final PriceCommandRepository commands;
    private final PriceWritePort writePort;
    private final ListingIdentityDirectory listings;
    private final CredentialDirectory credentials;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final String workerName;

    PriceCommandWorker(PriceCommandRepository commands,
                       PriceWritePort writePort,
                       ListingIdentityDirectory listings,
                       CredentialDirectory credentials,
                       IdGenerator idGenerator,
                       Clock clock) {
        this.commands = commands;
        this.writePort = writePort;
        this.listings = listings;
        this.credentials = credentials;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.workerName = WorkerIdentity.current();
    }

    /**
     * Work through whatever is ready, and hand back whatever was abandoned.
     *
     * @return how many commands were acted on
     */
    public int runOnce(int batchSize) {
        int recovered = commands.recoverExpiredLeases();
        if (recovered > 0) {
            log.atWarn()
                    .addKeyValue("event", "price_command_leases_recovered")
                    .addKeyValue("recoveredCount", recovered)
                    .log("Commands whose worker stopped holding them were handed back");
        }
        int exhausted = commands.failExhaustedRetries();
        if (exhausted > 0) {
            log.atWarn()
                    .addKeyValue("event", "price_command_retry_budget_exhausted")
                    .addKeyValue("closedCount", exhausted)
                    .log("Commands with no retry budget left were closed as failed");
        }
        int worked = 0;
        for (UUID commandId : commands.claimable(clock.instant(), batchSize)) {
            if (advance(commandId)) {
                worked++;
            }
        }
        return worked;
    }

    /**
     * Take one command as far as one lease allows.
     *
     * @return whether the command was claimed and acted on
     */
    public boolean advance(UUID commandId) {
        long fence;
        try {
            fence = commands.lease(commandId, workerName, LEASE_SECONDS);
        } catch (DataAccessException refused) {
            reportRefusal(commandId, refused);
            return false;
        }

        try {
            commands.transition(commandId, fence, workerName,
                    PriceCommandState.EXECUTING.name(), null, null, null);
            PriceCommandRepository.CommandRow command = commands.row(commandId).orElseThrow();
            switch (resumePurpose(commandId)) {
                case APPLY -> apply(command, fence);
                case STATUS_ENQUIRY -> enquire(command, fence);
                case READBACK -> readbackFrom(command, fence,
                        PriceCommandState.EXECUTING);
                case RESTORE -> apply(command, fence);
            }
            return true;
        } catch (DataAccessException refused) {
            reportRefusal(commandId, refused);
            return false;
        }
    }

    /**
     * Restore the previous price, under its own lease and its own gate.
     *
     * <p>Separate from {@link #advance} because a restore is authorised by a
     * person for a command that has already stopped moving on its own. It is
     * still fenced and still gated: every reason a write may not happen applies
     * to putting a price back.
     */
    public boolean compensate(UUID commandId) {
        long fence;
        try {
            fence = commands.leaseCompensation(commandId, workerName, LEASE_SECONDS);
        } catch (DataAccessException refused) {
            reportRefusal(commandId, refused);
            return false;
        }

        PriceCommandRepository.CommandRow command = commands.row(commandId).orElseThrow();
        UUID attemptId = idGenerator.newId();
        PriceWriteResult result = call(command, attemptId, fence,
                PriceWriteRequest.Operation.RESTORE,
                Money.of(command.priorPrice(), command.currencyCode()), null);

        if (result.outcome() != PriceWriteResult.Outcome.ACCEPTED) {
            commands.transition(commandId, fence, workerName,
                    PriceCommandState.COMPENSATION_FAILED.name(),
                    result.errorCode() == null ? "restore_not_accepted" : result.errorCode(),
                    null, null);
            return true;
        }

        // The database refuses to call this compensated until a readback has
        // observed the prior value, so the observation is made here rather than
        // trusting the platform's acknowledgement.
        Optional<UUID> readback = observe(command, fence, "MATCHES_PRIOR");
        if (readback.isEmpty()) {
            commands.transition(commandId, fence, workerName,
                    PriceCommandState.COMPENSATION_FAILED.name(),
                    "restore_not_observed", null, null);
            return true;
        }
        commands.transition(commandId, fence, workerName,
                PriceCommandState.COMPENSATED.name(), null, null, readback.get());
        return true;
    }

    /** Make the write, and record what the platform said about it. */
    private void apply(PriceCommandRepository.CommandRow command, long fence) {
        UUID attemptId = idGenerator.newId();
        PriceWriteResult result = call(command, attemptId, fence,
                PriceWriteRequest.Operation.APPLY,
                Money.of(command.targetPrice(), command.currencyCode()), null);

        switch (result.outcome()) {
            case ACCEPTED -> {
                if (result.nativeTaskKey() != null) {
                    commands.transition(command.id(), fence, workerName,
                            PriceCommandState.PLATFORM_PENDING.name(), null, null, null);
                    enquire(command, fence);
                } else {
                    readbackFrom(command, fence, PriceCommandState.EXECUTING);
                }
            }
            case REJECTED -> commands.transition(command.id(), fence, workerName,
                    PriceCommandState.FAILED_FINAL.name(),
                    result.errorCode() == null ? "platform_rejected" : result.errorCode(),
                    null, null);
            case RETRIABLE_ERROR -> retriable(command, fence, result.errorCode());
            case TIMEOUT, UNKNOWN_STATE -> commands.transition(command.id(), fence, workerName,
                    PriceCommandState.UNKNOWN_REQUIRES_READBACK.name(), null, null, null);
        }
    }

    /**
     * Ask what became of asynchronous work.
     *
     * <p>Called from {@code PLATFORM_PENDING} after an apply, or from
     * {@code EXECUTING} when a retry resumed an enquiry. Both states have the
     * edges this needs, so the caller's state decides where the answer lands.
     */
    private void enquire(PriceCommandRepository.CommandRow command, long fence) {
        Optional<String> taskKey = commands.latestTaskKey(command.id());
        if (taskKey.isEmpty()) {
            commands.transition(command.id(), fence, workerName,
                    PriceCommandState.UNKNOWN_REQUIRES_READBACK.name(), null, null, null);
            return;
        }
        UUID attemptId = idGenerator.newId();
        PriceWriteResult result = call(command, attemptId, fence,
                PriceWriteRequest.Operation.STATUS_ENQUIRY,
                Money.of(command.targetPrice(), command.currencyCode()), taskKey.get());

        switch (result.outcome()) {
            case ACCEPTED -> readbackFrom(command, fence, currentState(command.id()));
            case REJECTED -> commands.transition(command.id(), fence, workerName,
                    PriceCommandState.FAILED_FINAL.name(),
                    result.errorCode() == null ? "platform_task_rejected" : result.errorCode(),
                    null, null);
            case RETRIABLE_ERROR -> retriable(command, fence, result.errorCode());
            case TIMEOUT, UNKNOWN_STATE -> commands.transition(command.id(), fence, workerName,
                    PriceCommandState.UNKNOWN_REQUIRES_READBACK.name(), null, null, null);
        }
    }

    /**
     * Move to readback and observe what the platform now holds.
     *
     * <p>This is the only path to success. Platform acceptance says the request
     * was taken; only this says the marketplace holds the intended value.
     */
    private void readbackFrom(PriceCommandRepository.CommandRow command, long fence,
                              PriceCommandState from) {
        if (from != PriceCommandState.READBACK_PENDING) {
            commands.transition(command.id(), fence, workerName,
                    PriceCommandState.READBACK_PENDING.name(), null, null, null);
        }
        Optional<UUID> readback = observe(command, fence, "MATCHES_TARGET");
        if (readback.isPresent()) {
            commands.transition(command.id(), fence, workerName,
                    PriceCommandState.SUCCEEDED.name(), null, null, readback.get());
            return;
        }
        // observe recorded what it saw; the state it leaves the command in
        // depends on whether the platform answered at all.
        PriceCommandState now = currentState(command.id());
        if (now == PriceCommandState.READBACK_PENDING) {
            commands.transition(command.id(), fence, workerName,
                    PriceCommandState.READBACK_MISMATCH.name(), null, null, null);
        }
    }

    /**
     * Read the platform and record the comparison.
     *
     * @param expectedMatch the match state that means this observation confirms
     *                      what the caller was hoping for
     * @return the readback identifier when it matched, otherwise empty
     */
    private Optional<UUID> observe(PriceCommandRepository.CommandRow command, long fence,
                                   String expectedMatch) {
        UUID attemptId = idGenerator.newId();
        PriceWriteResult result = call(command, attemptId, fence,
                PriceWriteRequest.Operation.READBACK,
                Money.of(command.targetPrice(), command.currencyCode()), null);

        Instant observedAt = clock.instant();
        UUID readbackId = idGenerator.newId();
        if (result.outcome() == PriceWriteResult.Outcome.RETRIABLE_ERROR) {
            retriable(command, fence, result.errorCode());
            return Optional.empty();
        }
        if (result.outcome() != PriceWriteResult.Outcome.ACCEPTED
                || result.observedPrice() == null) {
            commands.insertReadback(readbackId, command.id(), attemptId, observedAt, null, null,
                    "UNREADABLE", null, CorrelationId.current());
            commands.transition(command.id(), fence, workerName,
                    PriceCommandState.UNKNOWN_REQUIRES_READBACK.name(), null, null, null);
            return Optional.empty();
        }

        String matchState = classify(command, result);
        commands.insertReadback(readbackId, command.id(), attemptId, observedAt,
                result.observedPrice(),
                result.observedCurrency() == null
                        ? command.currencyCode() : result.observedCurrency(),
                matchState, null, CorrelationId.current());
        return matchState.equals(expectedMatch) ? Optional.of(readbackId) : Optional.empty();
    }

    /**
     * Compare what the platform holds against what this command intended.
     *
     * <p>A value in another currency is not the intended value even when the
     * number matches, so the currency is part of the comparison rather than an
     * afterthought.
     */
    private static String classify(PriceCommandRepository.CommandRow command,
                                   PriceWriteResult result) {
        String observedCurrency = result.observedCurrency() == null
                ? command.currencyCode() : result.observedCurrency();
        if (!observedCurrency.equals(command.currencyCode())) {
            return "DIFFERENT";
        }
        BigDecimal observed = result.observedPrice();
        if (observed.compareTo(command.targetPrice()) == 0) {
            return "MATCHES_TARGET";
        }
        return observed.compareTo(command.priorPrice()) == 0 ? "MATCHES_PRIOR" : "DIFFERENT";
    }

    /**
     * Make one call, bracketed by the record that it happened.
     *
     * <p>The attempt is written before the call and completed after, so a
     * process that dies in between still leaves evidence that a marketplace was
     * contacted.
     */
    private PriceWriteResult call(PriceCommandRepository.CommandRow command, UUID attemptId,
                                  long fence, PriceWriteRequest.Operation operation,
                                  Money price, String taskKey) {
        Instant startedAt = clock.instant();
        commands.openAttempt(attemptId, command.id(), command.attemptNo(), operation.name(),
                fence, workerName, startedAt, CorrelationId.current());

        Optional<ListingVariantContext> context =
                listings.variantContext(command.platformListingVariantId(), startedAt);
        PriceWriteResult result = writePort.perform(new PriceWriteRequest(
                operation, command.capabilityId(),
                credentials.writeCredential(command.storeId(), command.capabilityId())
                        .orElse(null),
                context.map(ListingVariantContext::nativeListingKey).orElse(null),
                context.map(ListingVariantContext::nativeVariantKey).orElse(null),
                price, command.idempotencyKey(), taskKey));

        commands.completeAttempt(attemptId, outcomeClassOf(result), result.nativeStatus(),
                result.nativeTaskKey(), null, result.errorCode(), result.completedAt());
        return result;
    }

    private static String outcomeClassOf(PriceWriteResult result) {
        return switch (result.outcome()) {
            case ACCEPTED -> "ACCEPTED";
            case REJECTED -> "REJECTED";
            case RETRIABLE_ERROR -> "RETRIABLE_ERROR";
            case TIMEOUT -> "TIMEOUT";
            case UNKNOWN_STATE -> "UNKNOWN_STATE";
        };
    }

    /**
     * Wait and try again.
     *
     * <p>Every retriable condition lands in the same waiting state regardless of
     * how much budget is left, because the states a command can be in are the
     * ones the transition table declares and no state has an edge straight to a
     * terminal failure from every place a retry can happen. Exhaustion is
     * therefore handled where it belongs — by the sweep that closes a command
     * whose budget is spent — and one waiting command means one thing rather
     * than two.
     */
    private void retriable(PriceCommandRepository.CommandRow command, long fence,
                           String errorCode) {
        commands.transition(command.id(), fence, workerName,
                PriceCommandState.RETRY_WAIT.name(), null, RETRY_DELAY_SECONDS, null);
        log.atInfo()
                .addKeyValue("event", "price_command_retry_scheduled")
                .addKeyValue("commandId", command.id())
                .addKeyValue("errorCode", errorCode)
                .addKeyValue("retryBudgetRemaining", command.retryBudgetRemaining() - 1)
                .addKeyValue("correlationId", CorrelationId.current())
                .log("A price command will be tried again");
    }

    /** What the previous attempt was doing, so a retry resumes rather than repeats. */
    private PriceWriteRequest.Operation resumePurpose(UUID commandId) {
        return commands.latestAttemptPurpose(commandId)
                .map(PriceWriteRequest.Operation::valueOf)
                .orElse(PriceWriteRequest.Operation.APPLY);
    }

    private PriceCommandState currentState(UUID commandId) {
        return commands.row(commandId)
                .map(PriceCommandRepository.CommandRow::state)
                .orElse(PriceCommandState.MANUAL_RESOLUTION);
    }

    /**
     * Report a refusal without turning it into a failure.
     *
     * <p>A closed gate, a state that moved and a lost lease are all normal
     * outcomes of concurrent operation. Each is logged with its own code so an
     * operator can tell "the switch is off" from "another worker got there
     * first", and none of them changes the command.
     */
    private void reportRefusal(UUID commandId, DataAccessException refused) {
        String sqlState = sqlStateOf(refused);
        String event = switch (sqlState) {
            case GATE_CLOSED -> "price_command_gate_closed";
            case TRANSITION_REFUSED -> "price_command_transition_refused";
            case AUTHORITY_LOST -> "price_command_authority_lost";
            default -> "price_command_refused";
        };
        log.atInfo()
                .addKeyValue("event", event)
                .addKeyValue("commandId", commandId)
                .addKeyValue("sqlState", sqlState)
                .addKeyValue("correlationId", CorrelationId.current())
                .log("A price command was not advanced");
    }

    private static String sqlStateOf(DataAccessException refused) {
        Throwable cause = refused;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sqlFailure) {
                return sqlFailure.getSQLState() == null ? "unknown" : sqlFailure.getSQLState();
            }
            cause = cause.getCause();
        }
        return "unknown";
    }
}
