package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.RawCustody;
import com.mimococo.marketops.marketplaceintegration.ContentText;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ContentCommandRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ContentCommandRepository.CommandRow;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ContentCommandRepository.Move;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ContentCommandRepository.NewEvent;
import com.mimococo.marketops.marketplaceintegration.port.ContentWritePort;
import com.mimococo.marketops.marketplaceintegration.port.ContentWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.ContentWriteResult;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.ProductionWritePolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carries one content command a step further on each pass (W2).
 *
 * <p>Nothing here is transactional and nothing waits inside a lease: each pass takes the command,
 * makes the calls its state needs, records each one, moves the command and lets go, scheduling
 * when it should next be looked at. A worker that dies mid-call leaves an expiring lease and, for
 * a write, an apply recorded as started without an answer, which recovery turns into a command
 * that is only ever read from then on.
 *
 * <p>The order of a write is fixed: the gate, a read of the card (it must still say exactly what
 * the Owner saw, both fields), the write, the platform task, then readbacks until the card shows
 * the new text or the schedule runs out. Only a readback that shows the new title and description
 * makes the change succeeded.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ContentCommandWorker {

    private static final Logger log = LoggerFactory.getLogger(ContentCommandWorker.class);

    /** How long one pass may hold a command. */
    private static final int LEASE_SECONDS = 120;

    /** How long a command waits before the gate is asked again. */
    private static final Duration GATE_RECHECK = Duration.ofSeconds(60);

    /** How long a refused or unreadable read waits before it is tried again. */
    private static final Duration READ_RETRY = Duration.ofSeconds(60);

    /** How many reads before the write may fail before the change is given up, nothing written. */
    private static final int PRE_READ_ATTEMPTS = 5;

    /** How many times a rate-limited write is tried again before the change is given up. */
    private static final int APPLY_RETRY_BUDGET = 3;

    /** Pauses between enquiries about the platform task: soon at first, then once a minute. */
    private static final int[] TASK_POLL_DELAYS_SECONDS = {10, 20, 30, 60};

    /** Enquiries about one task before the readbacks are left to decide. */
    private static final int TASK_POLL_LIMIT = 40;

    /**
     * Pauses between readbacks while the card does not show the new text yet. A marketplace may
     * show an accepted change only after it has processed or reviewed it, so the readbacks spread
     * over a few hours; after the last one the command waits for a person.
     */
    private static final int[] READBACK_DELAYS_SECONDS = {10, 30, 120, 600, 1800, 7200};

    private final ContentCommandRepository commands;
    private final ContentWritePort writePort;
    private final RawCustody custody;
    private final CredentialDirectory credentials;
    private final ProductionWritePolicy productionWrites;
    private final IdGenerator ids;
    private final Clock clock;
    private final String workerName;

    ContentCommandWorker(ContentCommandRepository commands, ContentWritePort writePort, RawCustody custody,
                         CredentialDirectory credentials, ProductionWritePolicy productionWrites,
                         IdGenerator ids, Clock clock) {
        this.commands = commands;
        this.writePort = writePort;
        this.custody = custody;
        this.credentials = credentials;
        this.productionWrites = productionWrites;
        this.ids = ids;
        this.clock = clock;
        this.workerName = WorkerIdentity.current();
    }

    /**
     * Hand back abandoned commands, then advance whatever is due.
     *
     * @return how many commands were acted on
     */
    public int runOnce(int batchSize) {
        int recovered = commands.recoverExpiredLeases(clock.instant());
        if (recovered > 0) {
            log.atWarn()
                    .addKeyValue("event", "content_command_leases_recovered")
                    .addKeyValue("recoveredCount", recovered)
                    .log("Content commands whose worker stopped holding them were handed back");
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
     * Take one command as far as its state allows on this pass.
     *
     * @return whether the command was taken
     */
    public boolean advance(UUID commandId) {
        Optional<Long> fence = commands.lease(commandId, workerName, clock.instant(), LEASE_SECONDS);
        if (fence.isEmpty()) {
            return false;
        }
        CommandRow command = commands.row(commandId).orElseThrow();
        try {
            switch (command.state()) {
                case "PENDING" -> pending(command, fence.get());
                case "AWAITING_TASK" -> enquire(command, fence.get());
                case "AWAITING_READBACK", "UNKNOWN_REQUIRES_READBACK" -> readback(command, fence.get());
                default -> commands.move(commandId, fence.get(), workerName,
                        Move.to(command.state(), clock.instant().plus(GATE_RECHECK)), clock.instant());
            }
        } catch (RuntimeException failure) {
            // The lease expires on its own; recovery decides what the command is from its events.
            log.atError()
                    .addKeyValue("event", "content_command_step_failed")
                    .addKeyValue("commandId", commandId)
                    .addKeyValue("state", command.state())
                    .addKeyValue("errorClass", failure.getClass().getSimpleName())
                    .addKeyValue("sqlState", sqlState(failure))
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("A content command step failed; its lease will expire");
        }
        return true;
    }

    /** Before the write: the gate, the credential, the read of the card, then the write itself. */
    private void pending(CommandRow command, long fence) {
        Instant now = clock.instant();
        // A write that already left under this command is never sent again, even when the row still
        // says PENDING: the move after the answer can be lost (a failed statement, a crash). Only an
        // answer that asked to retry later sends again.
        Optional<ContentCommandRepository.ApplyTrace> sent = commands.lastApply(command.id())
                .filter(trace -> !ContentWriteResult.Outcome.RETRIABLE_ERROR.name().equals(trace.outcome()));
        if (sent.isPresent()) {
            resume(command, fence, sent.get(), now);
            return;
        }
        List<String> reasons = new ArrayList<>(commands.gateReasons(command.id()));
        if (reasons.contains("APPROVAL_EXPIRED")) {
            finish(command, fence, "CANCELLED", "approval_expired", null);
            return;
        }
        if (!productionWrites.productionWritesEnabled()) {
            reasons.add("PRODUCTION_WRITES_DISABLED");
        }
        Optional<UUID> capabilityId = commands.storeCapability(command.storeId())
                .map(ContentCommandRepository.StoreCapability::capabilityId);
        Optional<UUID> credentialId = capabilityId.flatMap(id -> credentials.writeCredential(command.storeId(), id));
        if (reasons.isEmpty() && credentialId.isEmpty()) {
            reasons.add("WRITE_CREDENTIAL_UNRESOLVED");
        }
        if (!reasons.isEmpty()) {
            if (!reasons.equals(command.gateReasons())) {
                event(command, fence, "GATE_CLOSED", "PENDING", null, String.join(",", reasons));
            }
            commands.move(command.id(), fence, workerName,
                    Move.to("PENDING", now.plus(GATE_RECHECK)).gate(reasons), now);
            return;
        }
        UUID capability = capabilityId.get();
        UUID credential = credentialId.get();

        ContentWriteResult read = call(command, fence, "PRE_READ", request(command, capability, credential,
                ContentWriteRequest.Operation.READBACK));
        if (read.outcome() != ContentWriteResult.Outcome.OBSERVED) {
            if (!read.dispatched() && read.outcome() == ContentWriteResult.Outcome.REJECTED) {
                finish(command, fence, "FAILED_BEFORE_WRITE", read.errorCode(), read.detail());
            } else if (command.retryCount() + 1 >= PRE_READ_ATTEMPTS) {
                finish(command, fence, "FAILED_BEFORE_WRITE", "pre_read_failed",
                        read.errorCode() == null ? null : read.errorCode());
            } else {
                commands.move(command.id(), fence, workerName,
                        Move.to("PENDING", now.plus(READ_RETRY)).counted(1, 0, 0), now);
            }
            return;
        }
        ContentText.Match title = ContentText.compare(read.observedTitle(), command.targetTitle(), command.priorTitle());
        ContentText.Match description = ContentText.compare(read.observedDescription(), command.targetDescription(),
                command.priorDescription());
        if (title == ContentText.Match.MATCHES_TARGET && description == ContentText.Match.MATCHES_TARGET) {
            // The card already says what the change would write: nothing to send.
            finish(command, fence, "SUCCEEDED", "already_applied", null);
            return;
        }
        boolean titleAsSeen = ContentText.same(read.observedTitle(), command.priorTitle());
        boolean descriptionAsSeen = ContentText.same(read.observedDescription(), command.priorDescription());
        if (!titleAsSeen || !descriptionAsSeen) {
            // Someone changed the card since the Owner looked. Writing now would overwrite their
            // change, including in a field this change meant to keep.
            finish(command, fence, "FAILED_BEFORE_WRITE", "prior_text_moved",
                    (!titleAsSeen ? "title" : "") + (!titleAsSeen && !descriptionAsSeen ? "," : "")
                            + (!descriptionAsSeen ? "description" : ""));
            return;
        }

        // The gate once more, just before the write leaves: a switch turned off a moment ago wins.
        List<String> latest = commands.gateReasons(command.id());
        if (!latest.isEmpty() || !productionWrites.productionWritesEnabled()) {
            commands.move(command.id(), fence, workerName, Move.to("PENDING", now.plus(GATE_RECHECK))
                    .gate(latest.isEmpty() ? List.of("PRODUCTION_WRITES_DISABLED") : latest), now);
            return;
        }
        event(command, fence, "APPLY_STARTED", null, null, null);
        ContentWriteResult applied = call(command, fence, "APPLY", request(command, capability, credential,
                ContentWriteRequest.Operation.APPLY));
        Instant answered = clock.instant();
        switch (applied.outcome()) {
            case ACCEPTED -> commands.move(command.id(), fence, workerName,
                    Move.to("AWAITING_TASK", answered.plusSeconds(TASK_POLL_DELAYS_SECONDS[0]))
                            .taskKey(applied.nativeTaskKey()).resolved(capability, credential), answered);
            case REJECTED -> finish(command, fence, applied.dispatched() ? "FAILED" : "FAILED_BEFORE_WRITE",
                    applied.errorCode(), applied.detail());
            case RETRIABLE_ERROR -> {
                if (command.retryCount() + 1 > APPLY_RETRY_BUDGET) {
                    finish(command, fence, "FAILED_BEFORE_WRITE", applied.errorCode(), applied.detail());
                } else {
                    int wait = applied.retryAfterSeconds() == null ? 60 : applied.retryAfterSeconds();
                    commands.move(command.id(), fence, workerName, Move.to("PENDING", answered.plusSeconds(wait))
                            .counted(1, 0, 0).outcome(applied.errorCode(), applied.detail()), answered);
                }
            }
            default -> commands.move(command.id(), fence, workerName,
                    Move.to("UNKNOWN_REQUIRES_READBACK", answered.plusSeconds(READBACK_DELAYS_SECONDS[1]))
                            .resolved(capability, credential).freshReadbacks()
                            .outcome(applied.errorCode() == null ? "apply_outcome_unknown" : applied.errorCode(),
                                    applied.detail()), answered);
        }
    }

    /**
     * Carry on after a write whose state move was lost, from what its events recorded: a task to
     * follow when the platform named one, otherwise only readbacks.
     */
    private void resume(CommandRow command, long fence, ContentCommandRepository.ApplyTrace sent, Instant now) {
        if (ContentWriteResult.Outcome.REJECTED.name().equals(sent.outcome())) {
            finish(command, fence, "FAILED", "platform_rejected", null);
            return;
        }
        Move move = ContentWriteResult.Outcome.ACCEPTED.name().equals(sent.outcome()) && sent.nativeTaskKey() != null
                ? Move.to("AWAITING_TASK", now).taskKey(sent.nativeTaskKey())
                : Move.to("UNKNOWN_REQUIRES_READBACK", now).freshReadbacks()
                        .outcome("apply_answer_lost_with_worker", null);
        Optional<Resolved> resolved = resolve(command);
        if (resolved.isPresent()) {
            move = move.resolved(resolved.get().capability(), resolved.get().credential());
        }
        if (commands.move(command.id(), fence, workerName, move, now)) {
            event(command, fence, "STATE", move.state(), "resumed_after_apply", null);
        }
    }

    /** Ask what became of the platform task the write opened. */
    private void enquire(CommandRow command, long fence) {
        Instant now = clock.instant();
        Optional<Resolved> resolved = resolve(command);
        if (resolved.isEmpty()) {
            commands.move(command.id(), fence, workerName, Move.to(command.state(), now.plus(GATE_RECHECK)), now);
            return;
        }
        UUID capability = resolved.get().capability();
        UUID credential = resolved.get().credential();
        if (command.nativeTaskKey() == null) {
            commands.move(command.id(), fence, workerName,
                    Move.to("AWAITING_READBACK", now).freshReadbacks().resolved(capability, credential), now);
            return;
        }
        ContentWriteResult status = call(command, fence, "STATUS", request(command, capability, credential,
                ContentWriteRequest.Operation.STATUS_ENQUIRY));
        Instant answered = clock.instant();
        switch (status.outcome()) {
            case TASK_SUCCEEDED -> commands.move(command.id(), fence, workerName,
                    Move.to("AWAITING_READBACK", answered).counted(0, 1, 0).freshReadbacks()
                            .resolved(capability, credential), answered);
            case TASK_FAILED -> finish(command, fence, "FAILED", "platform_task_failed", status.detail());
            default -> {
                if (command.taskPolls() + 1 >= TASK_POLL_LIMIT) {
                    // The task never said; the card will.
                    commands.move(command.id(), fence, workerName, Move.to("AWAITING_READBACK", answered)
                            .counted(0, 1, 0).freshReadbacks().resolved(capability, credential)
                            .outcome("task_status_unresolved", null), answered);
                } else {
                    int delay = TASK_POLL_DELAYS_SECONDS[Math.min(command.taskPolls(),
                            TASK_POLL_DELAYS_SECONDS.length - 1)];
                    commands.move(command.id(), fence, workerName,
                            Move.to("AWAITING_TASK", answered.plusSeconds(delay)).counted(0, 1, 0)
                                    .resolved(capability, credential), answered);
                }
            }
        }
    }

    /** Read the card and compare both fields with the change. */
    private void readback(CommandRow command, long fence) {
        Instant now = clock.instant();
        Optional<Resolved> resolved = resolve(command);
        if (resolved.isEmpty()) {
            commands.move(command.id(), fence, workerName, Move.to(command.state(), now.plus(GATE_RECHECK)), now);
            return;
        }
        ContentWriteResult read = call(command, fence, "READBACK", request(command, resolved.get().capability(),
                resolved.get().credential(), ContentWriteRequest.Operation.READBACK));
        Instant answered = clock.instant();
        boolean matched = read.outcome() == ContentWriteResult.Outcome.OBSERVED
                && ContentText.compare(read.observedTitle(), command.targetTitle(), command.priorTitle())
                        == ContentText.Match.MATCHES_TARGET
                && ContentText.compare(read.observedDescription(), command.targetDescription(),
                        command.priorDescription()) == ContentText.Match.MATCHES_TARGET;
        if (matched) {
            finish(command, fence, "SUCCEEDED", "readback_matched", null);
            return;
        }
        int done = command.readbacks() + 1;
        if (done <= READBACK_DELAYS_SECONDS.length) {
            commands.move(command.id(), fence, workerName,
                    Move.to(command.state(), answered.plusSeconds(READBACK_DELAYS_SECONDS[done - 1]))
                            .counted(0, 0, 1).resolved(resolved.get().capability(), resolved.get().credential()),
                    answered);
            return;
        }
        String outcome;
        if (read.outcome() != ContentWriteResult.Outcome.OBSERVED) {
            outcome = "readback_unavailable";
        } else if (ContentText.compare(read.observedTitle(), command.targetTitle(), command.priorTitle())
                        == ContentText.Match.DIFFERENT
                || ContentText.compare(read.observedDescription(), command.targetDescription(),
                        command.priorDescription()) == ContentText.Match.DIFFERENT) {
            outcome = "readback_different";
        } else {
            outcome = "readback_still_prior";
        }
        commands.move(command.id(), fence, workerName, Move.to("READBACK_MISMATCH", answered.plus(Duration.ofDays(3650)))
                .counted(0, 0, 1).outcome(outcome, null), answered);
    }

    /** End the command with a stable code. */
    private void finish(CommandRow command, long fence, String state, String code, String detail) {
        Instant now = clock.instant();
        boolean moved = commands.move(command.id(), fence, workerName, Move.to(state, now).outcome(code, detail), now);
        if (moved) {
            event(command, fence, "STATE", state, code, detail);
            log.atInfo()
                    .addKeyValue("event", "content_command_finished")
                    .addKeyValue("commandId", command.id())
                    .addKeyValue("state", state)
                    .addKeyValue("outcomeCode", code)
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("A content command finished");
        }
    }

    /**
     * The capability and credential a command reads with: the ones it recorded, or resolved again
     * when a lost move left none on the row.
     */
    private Optional<Resolved> resolve(CommandRow command) {
        if (command.capabilityId() != null && command.credentialId() != null) {
            return Optional.of(new Resolved(command.capabilityId(), command.credentialId()));
        }
        return commands.storeCapability(command.storeId())
                .map(ContentCommandRepository.StoreCapability::capabilityId)
                .flatMap(capability -> credentials.writeCredential(command.storeId(), capability)
                        .map(credential -> new Resolved(capability, credential)));
    }

    /** The SQL state behind a failure, when a database error caused it. */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private record Resolved(UUID capability, UUID credential) {
    }

    private ContentWriteRequest request(CommandRow command, UUID capability, UUID credential,
                                        ContentWriteRequest.Operation operation) {
        return new ContentWriteRequest(operation, capability, credential, command.offerKey(),
                operation == ContentWriteRequest.Operation.APPLY ? command.targetTitle() : null,
                operation == ContentWriteRequest.Operation.APPLY ? command.targetDescription() : null,
                command.nativeTaskKey(), command.id());
    }

    /** Make one call and record it, the answer kept in custody when one arrived. */
    private ContentWriteResult call(CommandRow command, long fence, String kind, ContentWriteRequest request) {
        ContentWriteResult result = writePort.perform(request);
        UUID contentId = result.body().length == 0 ? null : custody.store("content-response", result.body()).contentId();
        String titleMatch = null;
        String descriptionMatch = null;
        if (result.outcome() == ContentWriteResult.Outcome.OBSERVED) {
            titleMatch = ContentText.compare(result.observedTitle(), command.targetTitle(), command.priorTitle()).name();
            descriptionMatch = ContentText.compare(result.observedDescription(), command.targetDescription(),
                    command.priorDescription()).name();
        }
        commands.appendEvent(new NewEvent(ids.newId(), command.id(), kind, fence, null, result.httpStatus(),
                result.outcome().name(), result.nativeTaskKey(), result.taskStatus(), result.observedTitle(),
                result.observedDescription(), titleMatch, descriptionMatch,
                result.errorCode() == null ? result.detail()
                        : result.errorCode() + (result.detail() == null ? "" : ": " + result.detail()),
                contentId, null, result.answeredAt()));
        return result;
    }

    private void event(CommandRow command, long fence, String kind, String stateAfter, String outcome, String detail) {
        commands.appendEvent(new NewEvent(ids.newId(), command.id(), kind, fence, stateAfter, null, outcome,
                null, null, null, null, null, null, detail, null, null, clock.instant()));
    }
}
