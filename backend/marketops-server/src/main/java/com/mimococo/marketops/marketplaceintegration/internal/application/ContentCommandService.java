package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.marketplaceintegration.ContentChangeSubmission;
import com.mimococo.marketops.marketplaceintegration.ContentCommandGateway;
import com.mimococo.marketops.marketplaceintegration.ContentCommandView;
import com.mimococo.marketops.marketplaceintegration.ContentWriteStatus;
import com.mimococo.marketops.marketplaceintegration.internal.config.ContentWriteProperties;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ContentCommandRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ContentCommandRepository.CommandRow;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ContentCommandRepository.Move;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ContentCommandRepository.NewEvent;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.KillSwitchRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import com.mimococo.marketops.shared.ProductionWritePolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approved content changes and their commands (W2): recording a change, reading what became
 * of it, and the two decisions a person may take about a command the worker could not finish —
 * read the card again, or close the command.
 */
@Service
public class ContentCommandService implements ContentCommandGateway {

    /** The flag every content write is gated on (V0035). */
    static final String CONTENT_WRITE_FLAG = "listing-content-write";

    /** How soon before the evidence lapses the status starts saying so. */
    private static final Duration EVIDENCE_WARNING = Duration.ofDays(7);

    private final ContentCommandRepository commands;
    private final KillSwitchRepository switches;
    private final ProductionWritePolicy productionWrites;
    private final ContentWriteProperties properties;
    private final MetadataAuditRecorder audit;
    private final IdGenerator ids;
    private final Clock clock;

    ContentCommandService(ContentCommandRepository commands, KillSwitchRepository switches,
                          ProductionWritePolicy productionWrites, ContentWriteProperties properties,
                          MetadataAuditRecorder audit, IdGenerator ids, Clock clock) {
        this.commands = commands;
        this.switches = switches;
        this.productionWrites = productionWrites;
        this.properties = properties;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ContentCommandView submit(ContentChangeSubmission submission) {
        ContentCommandRepository.ListingKeys keys = commands.listingKeys(submission.organizationId(),
                        submission.platformListingVariantId())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (keys.offerKey() == null || keys.offerKey().isBlank()) {
            // The marketplace addresses a content write by the seller article; without it there is
            // no listing to write to.
            throw OperationRejectedException.of(ErrorCode.CAPABILITY_NOT_USABLE);
        }
        if (commands.liveCommandFor(submission.platformListingVariantId()).isPresent()) {
            throw OperationRejectedException.of(ErrorCode.CONCURRENT_INTERVENTION);
        }
        UUID changeId = ids.newId();
        UUID commandId = ids.newId();
        Instant now = clock.instant();
        ContentCommandRepository.NewChange change = new ContentCommandRepository.NewChange(changeId,
                submission.organizationId(), keys.storeId(), submission.platformListingVariantId(),
                keys.nativeListingKey(), keys.offerKey(), submission.priorTitle(), submission.priorDescription(),
                submission.priorObservedAt(), submission.targetTitle(), submission.targetDescription(),
                submission.titleChanged(), submission.descriptionChanged(), submission.sourceInvocationId(),
                submission.reason(), submission.approvedByUserId(), submission.approvedAt(),
                submission.approvalExpiresAt());
        commands.insertChange(change);
        commands.insertCommand(commandId, change, now);
        commands.appendEvent(new NewEvent(ids.newId(), commandId, "CREATED", null, "PENDING", null, null, null,
                null, null, null, null, null, null, null, submission.approvedByUserId(), now));
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.MARKETPLACE_INTEGRATION,
                submission.approvedByUserId().toString(), AuditAction.APPROVAL_DECISION, "content-change",
                changeId, keys.offerKey(),
                Map.of("titleChanged", new FieldChange(null, Boolean.toString(submission.titleChanged())),
                        "descriptionChanged", new FieldChange(null,
                                Boolean.toString(submission.descriptionChanged())),
                        "commandId", new FieldChange(null, commandId.toString())),
                submission.reason(), null));
        return view(commands.row(commandId).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContentCommandView> find(UUID organizationId, UUID commandId) {
        return commands.row(organizationId, commandId).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContentCommandView> latestForListing(UUID organizationId, UUID platformListingVariantId) {
        return commands.latestForListing(organizationId, platformListingVariantId).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContentCommandView> forStore(UUID organizationId, UUID storeId, int limit) {
        return commands.forStore(organizationId, storeId, limit).stream().map(this::view).toList();
    }

    @Override
    @Transactional
    public ContentCommandView requestReadback(UUID organizationId, UUID commandId, UUID actorUserId) {
        CommandRow command = require(organizationId, commandId);
        Instant now = clock.instant();
        String next = switch (command.state()) {
            case "UNKNOWN_REQUIRES_READBACK" -> "UNKNOWN_REQUIRES_READBACK";
            case "READBACK_MISMATCH" -> "AWAITING_READBACK";
            default -> throw OperationRejectedException.of(ErrorCode.COMMAND_STATE_INVALID);
        };
        if (!commands.resolve(commandId, command.state(), Move.to(next, now).freshReadbacks(), now)) {
            throw OperationRejectedException.of(ErrorCode.CONCURRENT_INTERVENTION);
        }
        resolution(command, actorUserId, next, "readback_requested", null, now);
        return view(commands.row(commandId).orElseThrow());
    }

    @Override
    @Transactional
    public ContentCommandView close(UUID organizationId, UUID commandId, UUID actorUserId, String reason) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        CommandRow command = require(organizationId, commandId);
        Instant now = clock.instant();
        String next = switch (command.state()) {
            // Nothing was sent yet: the change is withdrawn.
            case "PENDING" -> "CANCELLED";
            // Something may have been sent: a person takes over and says so.
            case "UNKNOWN_REQUIRES_READBACK", "READBACK_MISMATCH" -> "CLOSED";
            default -> throw OperationRejectedException.of(ErrorCode.COMMAND_STATE_INVALID);
        };
        if (!commands.resolve(commandId, command.state(),
                Move.to(next, now).outcome("closed_by_person", validReason), now)) {
            throw OperationRejectedException.of(ErrorCode.CONCURRENT_INTERVENTION);
        }
        resolution(command, actorUserId, next, "closed_by_person", validReason, now);
        return view(commands.row(commandId).orElseThrow());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> gate(UUID organizationId, UUID commandId) {
        require(organizationId, commandId);
        List<String> reasons = new ArrayList<>(commands.gateReasons(commandId));
        if (!productionWrites.productionWritesEnabled()) {
            reasons.add("PRODUCTION_WRITES_DISABLED");
        }
        return List.copyOf(reasons);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContentCommandView> succeeded(UUID storeId, int limit) {
        return commands.succeeded(storeId, limit).stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ContentWriteStatus writeStatus(UUID storeId) {
        Optional<ContentCommandRepository.StoreCapability> capability = commands.storeCapability(storeId);
        List<KillSwitchRepository.FlagRow> flags = switches.flags(CONTENT_WRITE_FLAG);
        boolean capabilitySwitch = capability.isPresent() && flags.stream().anyMatch(flag ->
                "CAPABILITY".equals(flag.scopeKind()) && capability.get().capabilityId().equals(flag.capabilityId())
                        && "ACTIVE".equals(flag.status()) && "ENABLED".equals(flag.state()));
        boolean globalSwitch = flags.stream().anyMatch(flag -> "GLOBAL".equals(flag.scopeKind())
                && "ACTIVE".equals(flag.status()) && "ENABLED".equals(flag.state()));
        Instant now = clock.instant();
        List<String> reasons = new ArrayList<>();
        if (!productionWrites.productionWritesEnabled()) {
            reasons.add("PRODUCTION_WRITES_DISABLED");
        }
        if (!Boolean.TRUE.equals(properties.getWorkerEnabled())) {
            reasons.add("WORKER_DISABLED");
        }
        if (capability.isEmpty()) {
            reasons.add("CAPABILITY_NOT_REGISTERED");
        } else {
            ContentCommandRepository.StoreCapability found = capability.get();
            if (!"VERIFIED".equals(found.verificationState()) || !"ACTIVE".equals(found.status())) {
                reasons.add("CAPABILITY_NOT_VERIFIED");
            } else if (found.evidenceValidUntil() == null || !found.evidenceValidUntil().isAfter(now)) {
                reasons.add("CAPABILITY_EVIDENCE_NOT_CURRENT");
            } else if (found.evidenceValidUntil().isBefore(now.plus(EVIDENCE_WARNING))) {
                reasons.add("CAPABILITY_EVIDENCE_EXPIRING");
            }
            if (!"AVAILABLE".equals(found.storeAvailability())) {
                reasons.add("CAPABILITY_NOT_AVAILABLE_FOR_STORE");
            }
            if (!capabilitySwitch) {
                reasons.add("CAPABILITY_SWITCH_DISABLED");
            }
        }
        if (!globalSwitch) {
            reasons.add("GLOBAL_SWITCH_DISABLED");
        }
        return new ContentWriteStatus(storeId, commands.storePlatform(storeId).orElse(null),
                productionWrites.productionWritesEnabled(), Boolean.TRUE.equals(properties.getWorkerEnabled()),
                capability.map(ContentCommandRepository.StoreCapability::capabilityId).orElse(null),
                capability.map(ContentCommandRepository.StoreCapability::verificationState).orElse(null),
                capability.map(ContentCommandRepository.StoreCapability::storeAvailability).orElse(null),
                capability.map(ContentCommandRepository.StoreCapability::evidenceValidUntil).orElse(null),
                capabilitySwitch, globalSwitch, reasons);
    }

    private void resolution(CommandRow command, UUID actorUserId, String stateAfter, String outcome,
                            String detail, Instant at) {
        commands.appendEvent(new NewEvent(ids.newId(), command.id(), "RESOLUTION", null, stateAfter, null, outcome,
                null, null, null, null, null, null, detail, null, actorUserId, at));
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.MARKETPLACE_INTEGRATION,
                actorUserId.toString(), AuditAction.COMMAND_TRANSITION, "content-command", command.id(),
                command.offerKey(), Map.of("state", new FieldChange(command.state(), stateAfter)),
                detail == null ? outcome : detail, null));
    }

    private CommandRow require(UUID organizationId, UUID commandId) {
        return commands.row(organizationId, commandId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private ContentCommandView view(CommandRow row) {
        List<ContentCommandView.Event> events = commands.events(row.id()).stream()
                .map(event -> new ContentCommandView.Event(event.sequence(), event.kind(), event.stateAfter(),
                        event.httpStatus(), event.outcome(), event.nativeTaskKey(), event.taskStatus(),
                        event.observedTitle(), event.observedDescription(), event.titleMatch(),
                        event.descriptionMatch(), event.detail(), event.actorUserId(), event.recordedAt()))
                .toList();
        return new ContentCommandView(row.id(), row.changeId(), row.storeId(), row.platformListingVariantId(),
                row.nativeListingKey(), row.offerKey(), row.state(), row.gateReasons(), row.outcomeCode(),
                row.outcomeDetail(), row.nativeTaskKey(), row.nextActionAt(), row.createdAt(), row.updatedAt(),
                row.terminalAt(), row.priorTitle(), row.priorDescription(), row.priorObservedAt(),
                row.targetTitle(), row.targetDescription(), row.titleChanged(), row.descriptionChanged(),
                row.sourceInvocationId(), row.reason(), row.approvedByUserId(), row.approvedAt(),
                row.approvalExpiresAt(), events);
    }
}
