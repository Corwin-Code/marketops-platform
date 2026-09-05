package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.internal.config.AdBidWriteProperties;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.AdBidCommandRepository;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWritePort;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteResult;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.Money;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advances advertising bid commands, one committed step at a time.
 *
 * <p>{@code Propagation.NEVER} is load-bearing. Every database call here is its
 * own committed statement, so the attempt row that says a call was started
 * exists before the call is made and survives whatever happens next. A worker
 * that held a transaction across the network would, on a crash, roll back the
 * only record that a bid change may have left the building.
 *
 * <p>An unclassified APPLY or its timeout enters {@code UNKNOWN_REQUIRES_READBACK};
 * it cannot authorize another submission. A status-query timeout for an already
 * identified native task keeps only its {@code PLATFORM_PENDING} observation path.
 * A later retry still requires the independent current Readback, verified
 * idempotency proof and live authority checked by the repository.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
class AdBidCommandWorker {

    private static final Logger log = LoggerFactory.getLogger(AdBidCommandWorker.class);

    private final AdBidCommandRepository commands;
    private final AdBidWritePort writePort;
    private final CredentialDirectory credentials;
    private final ListingIdentityDirectory listings;
    private final RawCustodyService custody;
    private final AdBidWriteProperties properties;
    private final IdGenerator ids;

    AdBidCommandWorker(
            AdBidCommandRepository commands,
            AdBidWritePort writePort,
            CredentialDirectory credentials,
            ListingIdentityDirectory listings,
            RawCustodyService custody,
            AdBidWriteProperties properties,
            IdGenerator ids) {
        this.commands = commands;
        this.writePort = writePort;
        this.credentials = credentials;
        this.listings = listings;
        this.custody = custody;
        this.properties = properties;
        this.ids = ids;
    }

    /** Recover abandoned leases, then advance whatever is claimable. */
    int runOnce(java.time.Instant now, int batchSize) {
        commands.recoverExpiredLeases();
        int advanced = 0;
        for (UUID commandId : commands.claimable(now, batchSize)) {
            if (advance(commandId)) {
                advanced++;
            }
        }
        return advanced;
    }

    private boolean advance(UUID commandId) {
        Optional<AdBidCommandRepository.CommandRow> found = commands.row(commandId);
        if (found.isEmpty()) {
            return false;
        }
        AdBidCommandRepository.CommandRow command = found.get();
        String owner = WorkerIdentity.current();
        try {
            return switch (command.state()) {
                case "PLATFORM_PENDING" -> pollStatus(commandId, owner);
                case "COMPENSATION_PENDING" -> compensate(commandId, owner);
                case "UNKNOWN_REQUIRES_READBACK" -> observeAfterUnknown(commandId, owner);
                default -> apply(commandId, owner, "RETRY_WAIT".equals(command.state()));
            };
        } catch (RuntimeException refused) {
            // A failed completion may follow dispatch. Preserve the durable
            // attempt for fenced recovery; exception text may contain transport data.
            log.warn("event=ad_bid_command_not_advanced commandId={} state={} failureType={}",
                    commandId, command.state(), refused.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Lease, transition to executing, and make the one apply this command gets.
     *
     * <p>The gate runs inside the lease and again inside the attempt open, so
     * two independent refusals stand between a claimable command and a socket.
     */
    private boolean apply(UUID commandId, String owner, boolean retry) {
        long fence = commands.lease(commandId, owner, properties.getLeaseSeconds());
        AdBidCommandRepository.CommandRow command = commands.row(commandId).orElseThrow();
        if (retry) {
            // The retry lease advances the fence. Re-observe at that fence;
            // neither a previous lease's proof nor a pending retry is a send.
            commands.transition(commandId, fence, owner, "READBACK_PENDING", null, null, null);
            if (!observe(command, fence, owner, true)) {
                return true;
            }
        } else {
            commands.transition(commandId, fence, owner, "EXECUTING", null, null, null);
        }
        AdBidWriteResult result = call(command, fence, owner,
                AdBidWriteRequest.Operation.APPLY,
                Money.of(command.targetBidAmount(), command.currencyCode()), null);

        return switch (result.outcome()) {
            case ACCEPTED -> {
                if (result.nativeTaskKey() != null) {
                    commands.transition(commandId, fence, owner, "PLATFORM_PENDING",
                            null, null, null);
                    commands.deferObservation(commandId, fence, owner, properties.getRetryDelaySeconds());
                } else {
                    commands.transition(commandId, fence, owner, "READBACK_PENDING",
                            null, null, null);
                    observe(command, fence, owner);
                }
                yield true;
            }
            case REJECTED -> {
                commands.transition(commandId, fence, owner, "FAILED_FINAL",
                        result.errorCode() == null ? "platform_rejected" : result.errorCode(),
                        null, null);
                yield true;
            }
            case RETRIABLE_ERROR -> {
                // Explicit NOT_APPLIED still needs a current prior-value readback.
                commands.transition(commandId, fence, owner, "READBACK_PENDING", null, null, null);
                observe(command, fence, owner);
                yield true;
            }
            // A timeout and an unclassifiable answer are the same thing: we do
            // not know. The write is never repeated from here.
            case TIMEOUT, UNKNOWN_STATE -> {
                commands.transition(commandId, fence, owner, "UNKNOWN_REQUIRES_READBACK",
                        null, null, null);
                yield true;
            }
        };
    }

    private boolean pollStatus(UUID commandId, String owner) {
        long fence = commands.leaseStatus(commandId, owner, properties.getLeaseSeconds());
        AdBidCommandRepository.CommandRow command = commands.row(commandId).orElseThrow();
        AdBidWriteResult result = call(command, fence, owner,
                AdBidWriteRequest.Operation.STATUS_ENQUIRY,
                Money.of(command.targetBidAmount(), command.currencyCode()), null);
        switch (result.outcome()) {
            case ACCEPTED -> {
                commands.transition(commandId, fence, owner, "READBACK_PENDING", null, null, null);
                observe(command, fence, owner);
            }
            case RETRIABLE_ERROR -> {
                if ("provider_explicit_not_applied".equals(result.errorCode())) {
                    commands.transition(commandId, fence, owner, "READBACK_PENDING", null, null, null);
                    observe(command, fence, owner);
                } else {
                    commands.deferObservation(commandId, fence, owner, properties.getRetryDelaySeconds());
                }
            }
            case REJECTED -> commands.transition(commandId, fence, owner, "FAILED_FINAL",
                    "native_task_rejected", null, null);
            case TIMEOUT, UNKNOWN_STATE -> {
                // The accepted APPLY already supplied a frozen native task identity.
                // An inconclusive status read cannot erase that addressable work or
                // authorize another APPLY. Keep polling this same task, read-only.
                commands.deferObservation(commandId, fence, owner, properties.getRetryDelaySeconds());
            }
        }
        return true;
    }

    /** The only route out of an unknown result: look, do not act. */
    private boolean observeAfterUnknown(UUID commandId, String owner) {
        long fence = commands.leaseReadback(commandId, owner, properties.getLeaseSeconds());
        AdBidCommandRepository.CommandRow command = commands.row(commandId).orElseThrow();
        observe(command, fence, owner);
        return true;
    }

    /**
     * Observe what the platform holds and let the database decide what it means.
     *
     * <p>The match state is derived by {@code record_ad_bid_command_readback},
     * never proposed here. A third value routes to investigation because
     * something outside this lineage owns that bid now.
     */
    private void observe(AdBidCommandRepository.CommandRow command, long fence, String owner) {
        observe(command, fence, owner, false);
    }

    /** True only when a fresh retry-lease readback authorizes the next APPLY. */
    private boolean observe(AdBidCommandRepository.CommandRow command, long fence, String owner,
            boolean retryPreflight) {
        AdBidWriteResult result = call(command, fence, owner,
                AdBidWriteRequest.Operation.READBACK,
                Money.of(command.targetBidAmount(), command.currencyCode()), null);
        if (result.response() == null) {
            commands.transition(command.id(), fence, owner, "UNKNOWN_REQUIRES_READBACK",
                    null, null, null);
            return false;
        }
        UUID readbackId = ids.newId();
        String match = commands.transitionReadback(readbackId, command.id(), fence, owner);
        switch (match) {
            case "MATCHES_TARGET" -> commands.transition(command.id(), fence, owner,
                    "READBACK_MATCHED", null, null, readbackId);
            case "MATCHES_PRIOR" -> {
                if (commands.retryIsProven(command.id())) {
                    if (retryPreflight) {
                        commands.transition(command.id(), fence, owner, "EXECUTING", null, null, null);
                        return true;
                    }
                    commands.transition(command.id(), fence, owner, "RETRY_WAIT", null,
                            properties.getRetryDelaySeconds(), null);
                } else {
                    commands.transition(command.id(), fence, owner,
                            "READBACK_MISMATCH", null, null, null);
                }
            }
            case "DIFFERENT" -> commands.transition(command.id(), fence, owner,
                    "LATER_CHANGE_OR_MISMATCH_INVESTIGATION", null, null, null);
            default -> commands.transition(command.id(), fence, owner,
                    "UNKNOWN_REQUIRES_READBACK", null, null, null);
        }
        return false;
    }

    /** Compensation observations never use original-action success transitions. */
    private boolean compensate(UUID commandId, String owner) {
        long fence = commands.leaseCompensation(commandId, owner, properties.getLeaseSeconds());
        AdBidCommandRepository.CommandRow command = commands.row(commandId).orElseThrow();
        if (!commands.restoreAlreadyAttempted(commandId)) {
            if (!"MATCHES_TARGET".equals(observeCompensation(command, fence, owner))) {
                commands.transition(commandId, fence, owner, "MANUAL_RESOLUTION",
                        "compensation_current_owner_not_proven", null, null);
                return true;
            }
            AdBidWriteResult restore = call(command, fence, owner,
                    AdBidWriteRequest.Operation.RESTORE,
                    Money.of(command.priorBidAmount(), command.currencyCode()),
                    commands.restoreVersionToken(commandId).orElse(null));
            if (restore.outcome() != AdBidWriteResult.Outcome.ACCEPTED) {
                commands.transition(commandId, fence, owner,
                        restore.outcome() == AdBidWriteResult.Outcome.REJECTED
                                ? "COMPENSATION_FAILED" : "MANUAL_RESOLUTION",
                        "restore_result_requires_resolution", null, null);
                return true;
            }
            if (restore.nativeTaskKey() != null) {
                commands.deferObservation(commandId, fence, owner, properties.getRetryDelaySeconds());
                return true;
            }
        } else if (commands.nativeTaskKey(commandId).isPresent()) {
            AdBidWriteResult status = call(command, fence, owner,
                    AdBidWriteRequest.Operation.STATUS_ENQUIRY,
                    Money.of(command.priorBidAmount(), command.currencyCode()), null);
            if (status.outcome() == AdBidWriteResult.Outcome.RETRIABLE_ERROR) {
                commands.deferObservation(commandId, fence, owner, properties.getRetryDelaySeconds());
                return true;
            }
            if (status.outcome() != AdBidWriteResult.Outcome.ACCEPTED) {
                commands.transition(commandId, fence, owner, "MANUAL_RESOLUTION",
                        "restore_native_state_unresolved", null, null);
                return true;
            }
        }
        String match = observeCompensation(command, fence, owner);
        commands.transition(commandId, fence, owner,
                "MATCHES_PRIOR".equals(match) ? "COMPENSATED" : "MANUAL_RESOLUTION",
                "MATCHES_PRIOR".equals(match) ? null : "restore_readback_not_exact", null, null);
        return true;
    }

    private String observeCompensation(AdBidCommandRepository.CommandRow command,
            long fence, String owner) {
        AdBidWriteResult result = call(command, fence, owner, AdBidWriteRequest.Operation.READBACK,
                Money.of(command.targetBidAmount(), command.currencyCode()), null);
        if (result.response() == null) {
            return "UNREADABLE";
        }
        return commands.transitionReadback(ids.newId(), command.id(), fence, owner);
    }

    /**
     * One call: record the attempt, make it, record what came back.
     *
     * <p>The attempt row is written before the call and completed after it, both
     * as their own committed statements, so an interrupted worker leaves a record
     * that a call was started rather than no record at all.
     */
    private AdBidWriteResult call(
            AdBidCommandRepository.CommandRow command, long fence, String owner,
            AdBidWriteRequest.Operation operation, Money amount, String versionToken) {
        UUID attemptId = ids.newId();
        UUID credentialId = credentials.writeCredential(command.storeId(), command.capabilityId())
                .orElse(null);
        if (credentialId == null) {
            // No credential reference means no call and no attempt row: nothing
            // happened, and the command waits for a person.
            return AdBidWriteResult.refusedBeforeDispatch(
                    "credential_reference_absent", java.time.Instant.EPOCH);
        }
        AdBidWriteRequest request = new AdBidWriteRequest(
                operation, command.capabilityId(), credentialId,
                command.nativeCampaignKey(), command.nativeObjectKey(),
                amount, command.bidUnitCode(),
                AdBidWriteRequest.operationIdempotencyKey(operation, command.idempotencyKey()),
                operation == AdBidWriteRequest.Operation.STATUS_ENQUIRY
                        ? commands.nativeTaskKey(command.id()).orElse(null) : null,
                versionToken, attemptId);

        commands.openAttempt(attemptId, command.id(), operation.name(), fence, owner,
                request.digest(), CorrelationId.current());

        AdBidWriteResult result = writePort.perform(request);

        // Evidence binding. An acceptance with no response, or a response that
        // names a different request, is not evidence about this call.
        if ((result.response() == null
                && result.outcome() == AdBidWriteResult.Outcome.ACCEPTED)
                || (result.response() != null
                    && !request.digest().equals(result.response().requestDigest()))) {
            result = new AdBidWriteResult(AdBidWriteResult.Outcome.UNKNOWN_STATE,
                    null, null, null, null, null, null, result.completedAt(),
                    "provider_evidence_missing_or_unbound", null);
        }

        UUID contentId = result.response() == null
                ? null : custody.store("ad-bid-response", result.body()).contentId();
        return commands.completeAttempt(attemptId, fence, owner, result, contentId,
                request.digest());
    }
}
