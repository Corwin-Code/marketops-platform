package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.internal.config.ListingDescriptionWriteProperties;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ListingDescriptionCommandRepository;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWritePort;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteResult;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advances description commands, one committed step at a time.
 *
 * <p>{@code Propagation.NEVER} is load-bearing, exactly as for the advertising
 * worker: the attempt row that says a call was started exists before the call
 * is made and survives whatever happens next.
 *
 * <p>An unclassified APPLY or its timeout enters {@code UNKNOWN_REQUIRES_READBACK}
 * and cannot authorize another submission. A retry-after value is converted per
 * platform unit before it becomes a delay, and a delay never extends approval.
 */
@Service
@Transactional(propagation = Propagation.NEVER)
class ListingDescriptionCommandWorker {

    private static final Logger log = LoggerFactory.getLogger(ListingDescriptionCommandWorker.class);

    private final ListingDescriptionCommandRepository commands;
    private final DescriptionWritePort writePort;
    private final CredentialDirectory credentials;
    private final RawCustodyService custody;
    private final ListingDescriptionWriteProperties properties;
    private final IdGenerator ids;

    ListingDescriptionCommandWorker(ListingDescriptionCommandRepository commands,
                                    DescriptionWritePort writePort,
                                    CredentialDirectory credentials,
                                    RawCustodyService custody,
                                    ListingDescriptionWriteProperties properties,
                                    IdGenerator ids) {
        this.commands = commands;
        this.writePort = writePort;
        this.credentials = credentials;
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
        Optional<ListingDescriptionCommandRepository.CommandRow> found = commands.row(commandId);
        if (found.isEmpty()) {
            return false;
        }
        ListingDescriptionCommandRepository.CommandRow command = found.get();
        String owner = WorkerIdentity.current();
        try {
            return switch (command.state()) {
                case "PLATFORM_PENDING" -> pollStatus(commandId, owner);
                case "COMPENSATION_PENDING" -> compensate(commandId, owner);
                case "UNKNOWN_REQUIRES_READBACK" -> observeAfterUnknown(commandId, owner);
                default -> apply(commandId, owner, "RETRY_WAIT".equals(command.state()));
            };
        } catch (RuntimeException refused) {
            log.warn("event=lc_description_command_not_advanced commandId={} state={} failureType={}",
                    commandId, command.state(), refused.getClass().getSimpleName());
            return false;
        }
    }

    private boolean apply(UUID commandId, String owner, boolean retry) {
        long fence = commands.lease(commandId, owner, properties.getLeaseSeconds());
        ListingDescriptionCommandRepository.CommandRow command = commands.row(commandId).orElseThrow();
        boolean restoration = commands.isExactRestoration(commandId);
        if (retry || restoration) {
            commands.transition(commandId, fence, owner, "READBACK_PENDING", null, null, null);
            if (!observe(command, fence, owner, true, restoration && !retry)) {
                return true;
            }
        } else {
            commands.transition(commandId, fence, owner, "EXECUTING", null, null, null);
        }
        DescriptionWriteResult result = call(command, fence, owner,
                restoration ? DescriptionWriteRequest.Operation.RESTORE : DescriptionWriteRequest.Operation.APPLY,
                command.targetText(), restoration ? commands.restoreVersionToken(commandId).orElseThrow() : null);
        return switch (result.outcome()) {
            case ACCEPTED -> {
                if (result.nativeTaskKey() != null) {
                    commands.transition(commandId, fence, owner, "PLATFORM_PENDING", null, null, null);
                    commands.deferObservation(commandId, fence, owner, delayFor(result));
                } else {
                    commands.transition(commandId, fence, owner, "READBACK_PENDING", null, null, null);
                    observe(command, fence, owner);
                }
                yield true;
            }
            case REJECTED -> {
                commands.transition(commandId, fence, owner, "FAILED_FINAL",
                        result.errorCode() == null ? "platform_rejected" : result.errorCode(), null, null);
                yield true;
            }
            case RETRIABLE_ERROR -> {
                commands.transition(commandId, fence, owner, "READBACK_PENDING", null, null, null);
                observe(command, fence, owner);
                yield true;
            }
            case TIMEOUT, UNKNOWN_STATE -> {
                commands.transition(commandId, fence, owner, "UNKNOWN_REQUIRES_READBACK", null, null, null);
                yield true;
            }
        };
    }

    private boolean pollStatus(UUID commandId, String owner) {
        long fence = commands.leaseStatus(commandId, owner, properties.getLeaseSeconds());
        ListingDescriptionCommandRepository.CommandRow command = commands.row(commandId).orElseThrow();
        DescriptionWriteResult result = call(command, fence, owner,
                DescriptionWriteRequest.Operation.STATUS_ENQUIRY, null, null);
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
                    commands.deferObservation(commandId, fence, owner, delayFor(result));
                }
            }
            case REJECTED -> {
                if (result.response() == null) {
                    // A local inability to enquire says nothing about the accepted native task.
                    commands.deferObservation(commandId, fence, owner, delayFor(result));
                } else {
                    commands.transition(commandId, fence, owner, "FAILED_FINAL",
                            "native_task_rejected", null, null);
                }
            }
            case TIMEOUT, UNKNOWN_STATE -> commands.deferObservation(commandId, fence, owner, delayFor(result));
        }
        return true;
    }

    /** The only route out of an unknown result: look, do not act. */
    private boolean observeAfterUnknown(UUID commandId, String owner) {
        long fence = commands.leaseReadback(commandId, owner, properties.getLeaseSeconds());
        ListingDescriptionCommandRepository.CommandRow command = commands.row(commandId).orElseThrow();
        observe(command, fence, owner);
        return true;
    }

    private void observe(ListingDescriptionCommandRepository.CommandRow command, long fence, String owner) {
        observe(command, fence, owner, false, false);
    }

    /** True only when a fresh retry-lease readback authorizes the next APPLY. */
    private boolean observe(ListingDescriptionCommandRepository.CommandRow command, long fence, String owner,
                            boolean retryPreflight, boolean initialRestoration) {
        if (commands.providerWaitActive(command.id())) {
            commands.deferObservation(command.id(), fence, owner, properties.getRetryDelaySeconds());
            return false;
        }
        DescriptionWriteResult result = call(command, fence, owner,
                DescriptionWriteRequest.Operation.READBACK, null, null);
        if (result.outcome() != DescriptionWriteResult.Outcome.ACCEPTED && commands.providerWaitActive(command.id())) {
            commands.deferObservation(command.id(), fence, owner, properties.getRetryDelaySeconds());
            return false;
        }
        if (result.response() == null) {
            commands.transition(command.id(), fence, owner, "UNKNOWN_REQUIRES_READBACK", null, null, null);
            return false;
        }
        UUID readbackId = ids.newId();
        String match = commands.transitionReadback(readbackId, command.id(), fence, owner);
        switch (match) {
            case "MATCHES_TARGET" -> commands.transition(command.id(), fence, owner,
                    "READBACK_MATCHED", null, null, readbackId);
            case "MATCHES_PRIOR" -> {
                if (initialRestoration && commands.restoreVersionToken(command.id()).isPresent()) {
                    commands.transition(command.id(), fence, owner, "EXECUTING", null, null, null);
                    return true;
                }
                if (commands.retryIsProven(command.id())) {
                    if (retryPreflight) {
                        if (commands.providerWaitActive(command.id())) {
                            commands.deferObservation(command.id(), fence, owner, properties.getRetryDelaySeconds());
                            return false;
                        }
                        commands.transition(command.id(), fence, owner, "EXECUTING", null, null, null);
                        return true;
                    }
                    commands.transition(command.id(), fence, owner, "RETRY_WAIT", null,
                            delayFor(result), null);
                } else {
                    commands.transition(command.id(), fence, owner, "READBACK_MISMATCH", null, null, null);
                }
            }
            case "DIFFERENT" -> commands.transition(command.id(), fence, owner,
                    "LATER_CHANGE_OR_MISMATCH_INVESTIGATION", null, null, null);
            default -> commands.transition(command.id(), fence, owner,
                    "UNKNOWN_REQUIRES_READBACK", null, null, null);
        }
        return false;
    }

    /** Historical compensation requests cannot reuse the opposite business approval. */
    private boolean compensate(UUID commandId, String owner) {
        long fence = commands.leaseCompensation(commandId, owner, properties.getLeaseSeconds());
        commands.transition(commandId, fence, owner, "MANUAL_RESOLUTION",
                "new_restoration_action_required", null, null);
        return true;
    }

    /** The platform's own wait, converted, or the configured default when it gave none. */
    private int delayFor(DescriptionWriteResult result) {
        return result.retryAfterSeconds() == null ? properties.getRetryDelaySeconds()
                : Math.max(1, result.retryAfterSeconds());
    }

    /** One call: record the attempt, make it, record what came back. */
    private DescriptionWriteResult call(ListingDescriptionCommandRepository.CommandRow command, long fence,
                                        String owner, DescriptionWriteRequest.Operation operation,
                                        String text, String versionToken) {
        if (command.nativeListingKey() == null || command.nativeListingKey().isBlank()) {
            return DescriptionWriteResult.refusedBeforeDispatch("command_native_identity_unbound",
                    java.time.Instant.EPOCH);
        }
        UUID attemptId = ids.newId();
        UUID credentialId = credentials.writeCredential(command.storeId(), command.capabilityId()).orElse(null);
        if (credentialId == null) {
            return DescriptionWriteResult.refusedBeforeDispatch("credential_reference_absent",
                    java.time.Instant.EPOCH);
        }
        String attributeKey = (operation == DescriptionWriteRequest.Operation.RESTORE
                ? credentials.restorationAttributeKey(command.capabilityId())
                : credentials.descriptionAttributeKey(command.capabilityId())).orElse(null);
        DescriptionWriteRequest request = new DescriptionWriteRequest(
                operation, command.capabilityId(), credentialId, command.nativeListingKey(), null,
                text, attributeKey, command.kizMarkedDeclared(),
                DescriptionWriteRequest.operationIdempotencyKey(operation, command.idempotencyKey()),
                operation == DescriptionWriteRequest.Operation.STATUS_ENQUIRY
                        ? commands.nativeTaskKey(command.id()).orElse(null) : null,
                versionToken, attemptId);
        commands.openAttempt(attemptId, command.id(), operation.name(), fence, owner,
                request.digest(), CorrelationId.current());
        DescriptionWriteResult result = writePort.perform(request);
        if ((result.response() == null && result.outcome() == DescriptionWriteResult.Outcome.ACCEPTED)
                || (result.response() != null && !request.digest().equals(result.response().requestDigest()))) {
            result = new DescriptionWriteResult(DescriptionWriteResult.Outcome.UNKNOWN_STATE,
                    null, null, null, result.completedAt(), "provider_evidence_missing_or_unbound",
                    result.retryAfterSeconds(), null);
        }
        UUID contentId = result.response() == null
                ? null : custody.store("lc-description-response", result.body()).contentId();
        return commands.completeAttempt(attemptId, fence, owner, result, contentId, request.digest());
    }
}
