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
 * <p>The outcome routing has one asymmetry worth stating plainly. A retriable
 * condition goes to {@code RETRY_WAIT}; a timeout or an unclassifiable answer
 * goes to {@code UNKNOWN_REQUIRES_READBACK} and never comes back to
 * {@code EXECUTING}. The distinction is not about how likely the call was to
 * have landed. It is that only one of them is evidence.
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
                case "COMPENSATION_PENDING" -> compensate(commandId, owner);
                case "UNKNOWN_REQUIRES_READBACK" -> observeAfterUnknown(commandId, owner);
                default -> apply(commandId, owner);
            };
        } catch (RuntimeException refused) {
            // Every refusal here is the database declining, which means nothing
            // was sent. The code is logged and never stored: a stable failure
            // code belongs to the transition that records it.
            log.warn("event=ad_bid_command_not_advanced commandId={} state={} reason={}",
                    commandId, command.state(), refused.toString());
            return false;
        }
    }

    /**
     * Lease, transition to executing, and make the one apply this command gets.
     *
     * <p>The gate runs inside the lease and again inside the attempt open, so
     * two independent refusals stand between a claimable command and a socket.
     */
    private boolean apply(UUID commandId, String owner) {
        long fence = commands.lease(commandId, owner, properties.getLeaseSeconds());
        commands.transition(commandId, fence, owner, "EXECUTING", null, null, null);

        AdBidCommandRepository.CommandRow command = commands.row(commandId).orElseThrow();
        AdBidWriteResult result = call(command, fence, owner,
                AdBidWriteRequest.Operation.APPLY,
                Money.of(command.targetBidAmount(), command.currencyCode()), null);

        return switch (result.outcome()) {
            case ACCEPTED -> {
                if (result.nativeTaskKey() != null) {
                    commands.transition(commandId, fence, owner, "PLATFORM_PENDING",
                            null, null, null);
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
                commands.transition(commandId, fence, owner, "RETRY_WAIT", null,
                        properties.getRetryDelaySeconds(), null);
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
        AdBidWriteResult result = call(command, fence, owner,
                AdBidWriteRequest.Operation.READBACK,
                Money.of(command.targetBidAmount(), command.currencyCode()), null);
        if (result.response() == null) {
            commands.transition(command.id(), fence, owner, "UNKNOWN_REQUIRES_READBACK",
                    null, null, null);
            return;
        }
        UUID readbackId = ids.newId();
        String match = commands.transitionReadback(readbackId, command.id(), fence, owner);
        switch (match) {
            case "MATCHES_TARGET" -> commands.transition(command.id(), fence, owner,
                    "READBACK_MATCHED", null, null, readbackId);
            case "MATCHES_PRIOR" -> commands.transition(command.id(), fence, owner,
                    "READBACK_MISMATCH", null, null, null);
            case "DIFFERENT" -> commands.transition(command.id(), fence, owner,
                    "LATER_CHANGE_OR_MISMATCH_INVESTIGATION", null, null, null);
            default -> commands.transition(command.id(), fence, owner,
                    "UNKNOWN_REQUIRES_READBACK", null, null, null);
        }
    }

    /** Restore the captured prior bid, and prove afterwards that it landed. */
    private boolean compensate(UUID commandId, String owner) {
        long fence = commands.leaseCompensation(commandId, owner, properties.getLeaseSeconds());
        AdBidCommandRepository.CommandRow command = commands.row(commandId).orElseThrow();

        // A fresh observation first: a restore may only proceed while this
        // command still owns the value the platform holds.
        observe(command, fence, owner);

        AdBidWriteResult result = call(command, fence, owner,
                AdBidWriteRequest.Operation.RESTORE,
                Money.of(command.priorBidAmount(), command.currencyCode()),
                commands.restoreVersionToken(commandId).orElse(null));
        if (result.outcome() != AdBidWriteResult.Outcome.ACCEPTED) {
            commands.transition(commandId, fence, owner,
                    result.outcome() == AdBidWriteResult.Outcome.REJECTED
                            ? "COMPENSATION_FAILED" : "MANUAL_RESOLUTION",
                    "restore_not_accepted", null, null);
            return true;
        }
        observe(command, fence, owner);
        commands.transition(commandId, fence, owner, "COMPENSATED", null, null, null);
        return true;
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
            commands.transition(command.id(), fence, owner, "MANUAL_RESOLUTION",
                    "credential_reference_absent", null, null);
            return AdBidWriteResult.refusedBeforeDispatch(
                    "credential_reference_absent", java.time.Instant.EPOCH);
        }
        AdBidWriteRequest request = new AdBidWriteRequest(
                operation, command.capabilityId(), credentialId,
                command.nativeCampaignKey(), command.nativeObjectKey(),
                amount, command.bidUnitCode(),
                AdBidWriteRequest.operationIdempotencyKey(operation, command.idempotencyKey()),
                null, versionToken, attemptId);

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
