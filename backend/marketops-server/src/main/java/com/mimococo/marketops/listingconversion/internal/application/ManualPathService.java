package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.AuthenticatedInvocationIssuer;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.listingconversion.ManualPacketView;
import com.mimococo.marketops.listingconversion.PromotionEngagementView;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ManualPathRepository;
import com.mimococo.marketops.operationsworkflow.ListingActionIntake;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The governed manual path: packets, the executor's report, independent
 * verification, and the simple promotion engagements that run only here.
 *
 * <p>A packet is issued only from a launched action on the MANUAL path and
 * creates no command; the database refuses it otherwise. A report verifies
 * nothing; verification is by a different person or official evidence and
 * records management match and display separately.
 */
@Service
public class ManualPathService {

    private final ManualPathRepository manual;
    private final ListingActionRepository actions;
    private final ListingFactRepository facts;
    private final ListingActionIntake intake;
    private final BusinessAuthorization authorization;
    private final AuthenticatedInvocationIssuer issuer;
    private final JdbcClient jdbc;
    private final MetadataAuditRecorder audit;
    private final IdGenerator ids;
    private final Clock clock;

    ManualPathService(ManualPathRepository manual, ListingActionRepository actions, ListingFactRepository facts,
                      ListingActionIntake intake, BusinessAuthorization authorization, AuthenticatedInvocationIssuer issuer,
                      JdbcClient jdbc, MetadataAuditRecorder audit, IdGenerator ids, Clock clock) {
        this.manual = manual;
        this.actions = actions;
        this.facts = facts;
        this.intake = intake;
        this.authorization = authorization;
        this.issuer = issuer;
        this.jdbc = jdbc;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public ManualPacketView issuePacket(AuthenticatedActor actor, UUID actionId, UUID executorUserId) {
        ListingActionRepository.ActionRow action = requireAction(actor, actionId, ActionScopeCode.LISTING_ACTION_LAUNCH);
        if (!"LAUNCHED".equals(action.state())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!"MANUAL".equals(action.executionPath())) {
            throw OperationRejectedException.of(ErrorCode.EXECUTION_PATH_MISMATCH);
        }
        var launch = actions.launch(actionId).orElseThrow(() -> OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION));
        var binding = actions.binding(actionId).orElseThrow(() -> OperationRejectedException.of(ErrorCode.BINDING_INAPPLICABLE));
        ListingFactRepository.ListingContext listing = facts.listing(action.listingId()).orElseThrow();
        Instant now = clock.instant();
        UUID id = ids.newId();
        manual.insertPacket(id, action.organizationId(), actionId, launch.id(), executorUserId, actor.userId(), now,
                binding.expiresAt(), listing.nativeListingKey(), action.affectedSetDigest(), action.targetText());
        intake.recordTaskAction(actor, action.recommendationId(), "MANUAL_PACKET_ISSUED", "lc-manual-packet:" + id,
                "governed manual packet issued from the launch");
        recordAudit(actor, "lc-manual-packet", id, AuditAction.CREATE,
                Map.of("actionId", new FieldChange(null, actionId.toString()), "executor", new FieldChange(null, executorUserId.toString())), null);
        return manual.packet(id).orElseThrow();
    }

    @Transactional
    public ManualPacketView report(AuthenticatedActor actor, UUID packetId, Instant operationTime, String reportState, String note) {
        ManualPacketView packet = requirePacket(actor, packetId, ActionScopeCode.LISTING_MANUAL_EXECUTE);
        Instant now = clock.instant();
        if (!actor.stepUpSatisfiedAt(now)) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        if (!actor.userId().equals(packet.executorUserId())) {
            throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        }
        if (operationTime == null || operationTime.isAfter(now)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        UUID id = ids.newId();
        manual.insertReport(id, actor.organizationId(), packetId, actor.userId(), operationTime, now,
                MetadataFieldPolicy.requireText("reportState", reportState), MetadataFieldPolicy.requireText("note", note));
        if ("ISSUED".equals(packet.state())) {
            manual.movePacket(packetId, "REPORTED", packet.version(), now);
        }
        var action = actions.action(packet.actionId()).orElseThrow();
        intake.recordTaskAction(actor, action.recommendationId(), "MANUAL_EXECUTION_REPORTED", "lc-manual-report:" + id,
                "executor reported " + reportState);
        recordAudit(actor, "lc-manual-packet", packetId, AuditAction.UPDATE, Map.of("report", new FieldChange(null, reportState)), note);
        return manual.packet(packetId).orElseThrow();
    }

    @Transactional
    public ManualPacketView verify(AuthenticatedActor actor, UUID packetId, String basis, String managementMatch,
                                   UUID managementObservationId, UUID displayObservationId, String displayState, String note, UUID promotionObservationId) {
        ManualPacketView packet = requirePacket(actor, packetId, ActionScopeCode.LISTING_MANUAL_VERIFY);
        Instant now = clock.instant();
        if (!actor.stepUpSatisfiedAt(now)) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        if (actor.userId().equals(packet.executorUserId())
                || packet.reports().stream().anyMatch(report -> report.reporterUserId().equals(actor.userId()))) {
            throw OperationRejectedException.of(ErrorCode.INDEPENDENCE_REQUIRED);
        }
        var verifiedAction=actions.action(packet.actionId()).orElseThrow();
        if("LISTING_PROMOTION_ACTION".equals(verifiedAction.actionKind())
                && !promotionFinancialAccess(actor,verifiedAction.id(),verifiedAction.storeId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        boolean human = "INDEPENDENT_HUMAN".equals(basis);
        UUID id = ids.newId();
        manual.insertVerification(id, actor.organizationId(), packetId, human ? actor.userId() : null,
                MetadataFieldPolicy.requireText("basis", basis), MetadataFieldPolicy.requireText("managementMatch", managementMatch),
                managementObservationId, displayObservationId, MetadataFieldPolicy.requireText("displayState", displayState),
                now, MetadataFieldPolicy.requireText("note", note),promotionObservationId);
        var action = actions.action(packet.actionId()).orElseThrow();
        if ("MATCHED_TARGET".equals(managementMatch)) {
            manual.movePacket(packetId, "VERIFIED", manual.packet(packetId).orElseThrow().version(), now);
            if ("LAUNCHED".equals(action.state())) {
                actions.moveAction(action.id(), "VERIFIED", action.version(), now);
            }
            intake.recordTaskAction(actor, action.recommendationId(), "MANUAL_EXECUTION_VERIFIED", "lc-manual-verification:" + id,
                    "management match verified; display " + displayState);
        } else {
            intake.recordTaskAction(actor, action.recommendationId(), "EVIDENCE_RETURNED", "lc-manual-verification:" + id,
                    "verification recorded " + managementMatch + "; display " + displayState);
        }
        recordAudit(actor, "lc-manual-packet", packetId, AuditAction.VERIFICATION_CHANGE,
                Map.of("managementMatch", new FieldChange(null, managementMatch), "displayState", new FieldChange(null, displayState)), note);
        return manual.packet(packetId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public ManualPacketView packet(AuthenticatedActor actor, UUID packetId) {
        return requirePacket(actor, packetId, ActionScopeCode.LISTING_CONVERSION_VIEW);
    }

    @Transactional(readOnly = true)
    public List<ManualPacketView> packetsForAction(AuthenticatedActor actor, UUID actionId) {
        requireAction(actor, actionId, ActionScopeCode.LISTING_CONVERSION_VIEW);
        return manual.packetsForAction(actionId);
    }

    @Transactional(readOnly = true)
    public List<ManualPacketView> myPackets(AuthenticatedActor actor, int limit) {
        var stores = authorization.permittedStoreIds(actor, ActionScopeCode.LISTING_CONVERSION_VIEW);
        return manual.packetsForExecutor(actor.organizationId(), actor.userId(), limit).stream()
                .filter(packet -> actions.action(packet.actionId())
                        .filter(action -> actor.organizationId().equals(action.organizationId())
                                && stores.contains(action.storeId())).isPresent())
                .toList();
    }

    // ------------------------------------------------------------------ engagements

    public record EngagementRequest(String engagementKind, String nativePromotionKey, Map<String, String> terms,
                                    boolean priceFreeze, boolean autoParticipation, String termsEvidenceReference,
                                    Map<String, String> obligations) {
    }

    @Transactional
    public PromotionEngagementView adopt(AuthenticatedActor actor, UUID listingId, EngagementRequest request) {
        ListingFactRepository.ListingContext listing = requireListing(actor, listingId, ActionScopeCode.LISTING_PROMOTION_MANAGE);
        UUID id = ids.newId();
        manual.insertEngagement(id, listing.organizationId(), listing.storeId(), listingId, null,
                MetadataFieldPolicy.requireText("engagementKind", request.engagementKind()), request.nativePromotionKey(),
                request.terms() == null ? Map.of() : request.terms(), request.priceFreeze(), request.autoParticipation(),
                MetadataFieldPolicy.requireText("termsEvidenceReference", request.termsEvidenceReference()), true,
                request.obligations() == null ? Map.of() : request.obligations(), clock.instant());
        recordAudit(actor, "lc-promotion-engagement", id, AuditAction.CREATE, Map.of("adopted", new FieldChange(null, "true")), null);
        return disclose(actor,manual.engagement(id).orElseThrow());
    }

    @Transactional
    public PromotionEngagementView enter(AuthenticatedActor actor, UUID actionId, EngagementRequest request) {
        ListingActionRepository.ActionRow action = requireAction(actor, actionId, ActionScopeCode.LISTING_PROMOTION_MANAGE);
        if (!"LAUNCHED".equals(action.state()) && !"VERIFIED".equals(action.state())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!"LISTING_PROMOTION_ACTION".equals(action.actionKind())) {
            throw OperationRejectedException.of(ErrorCode.EXECUTION_PATH_MISMATCH);
        }
        UUID id = ids.newId();
        manual.insertEngagement(id, action.organizationId(), action.storeId(), action.listingId(), actionId,
                MetadataFieldPolicy.requireText("engagementKind", request.engagementKind()), request.nativePromotionKey(),
                request.terms() == null ? Map.of() : request.terms(), request.priceFreeze(), request.autoParticipation(),
                exactTermsReference(request.termsEvidenceReference()), false,
                request.obligations() == null ? Map.of() : request.obligations(), clock.instant());
        recordAudit(actor, "lc-promotion-engagement", id, AuditAction.CREATE, Map.of("actionId", new FieldChange(null, actionId.toString())), null);
        return disclose(actor,manual.engagement(id).orElseThrow());
    }

    @Transactional
    public PromotionEngagementView authorizeExit(AuthenticatedActor actor, UUID engagementId, String reasonCode) {
        PromotionEngagementView engagement = requireEngagement(actor, engagementId, ActionScopeCode.LISTING_PROMOTION_MANAGE);
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        if (!List.of("MARGIN_BELOW_BOUND", "RETURN_RATE_ABOVE_BOUND", "SUPPLY_COVERAGE_LOST", "PLATFORM_TERMS_CHANGED",
                "OWNER_DECISION").contains(reasonCode)) {
            throw OperationRejectedException.of(ErrorCode.EXIT_REASON_NOT_APPROVED);
        }
        long[] context = jdbc.sql("SELECT pg_backend_pid(), txid_current()")
                .query((rs, row) -> new long[] {rs.getInt(1), rs.getLong(2)}).single();
        String proof = issuer.issueControl("LISTING_PROMOTION_EXIT", engagementId, engagementId, Math.toIntExact(context[0]),
                context[1]);
        manual.authorizeExit(engagementId, actor.userId(), proof, reasonCode);
        recordAudit(actor, "lc-promotion-engagement", engagementId, AuditAction.STATUS_CHANGE,
                Map.of("state", new FieldChange(engagement.state(), "EXITING"), "exitReason", new FieldChange(null, reasonCode)), null);
        return disclose(actor,manual.engagement(engagementId).orElseThrow());
    }

    /** The two separate releases: new transactions stopped, then obligations cleared. */
    @Transactional
    public PromotionEngagementView release(AuthenticatedActor actor, UUID engagementId, String releaseKind) {
        PromotionEngagementView engagement = requireEngagement(actor, engagementId, ActionScopeCode.LISTING_PROMOTION_MANAGE);
        Instant now = clock.instant();
        String to;
        if ("NEW_TRANSACTIONS_STOPPED".equals(releaseKind) && "EXITING".equals(engagement.state())) {
            to = "STOPPED";
        } else if ("OBLIGATIONS_CLEARED".equals(releaseKind) && "STOPPED".equals(engagement.state())) {
            to = "CLEARED";
        } else {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!manual.release(engagementId, to, now, engagement.version())) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        recordAudit(actor, "lc-promotion-engagement", engagementId, AuditAction.STATUS_CHANGE,
                Map.of("state", new FieldChange(engagement.state(), to)), null);
        return disclose(actor,manual.engagement(engagementId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<PromotionEngagementView> engagements(AuthenticatedActor actor, UUID listingId) {
        requireListing(actor, listingId, ActionScopeCode.LISTING_CONVERSION_VIEW);
        return manual.engagements(listingId).stream().map(engagement->disclose(actor,engagement)).toList();
    }

    @Transactional(readOnly = true)
    public PromotionEngagementView engagement(AuthenticatedActor actor,UUID id) {
        return disclose(actor,requireEngagement(actor,id,ActionScopeCode.LISTING_CONVERSION_VIEW));
    }

    private PromotionEngagementView disclose(AuthenticatedActor actor,PromotionEngagementView engagement) {
        boolean permitted;
        if(engagement.actionId()==null) {
            // Old adopted facts have no frozen product scope. Organization-wide financial
            // access covers that uncertainty; a store grant cannot stand in for missing lineage.
            permitted=authorization.evaluate(actor,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                    ResourceScope.organization(actor.organizationId())).permitted();
        } else {
            permitted=promotionFinancialAccess(actor,engagement.actionId(),engagement.storeId());
        }
        return engagement.withFinancialDisclosure(permitted);
    }

    private boolean promotionFinancialAccess(AuthenticatedActor actor,UUID actionId,UUID storeId) {
        var products=actions.promotionEvidenceProducts(actionId);
        if(authorization.evaluate(actor,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScope.organization(actor.organizationId())).permitted()) return true;
        return authorization.evaluate(actor,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScope.store(storeId)).permitted() && !products.isEmpty()
                && products.stream().allMatch(product->authorization.evaluate(actor,
                    ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,ResourceScope.productVariant(product)).permitted());
    }

    private static String exactTermsReference(String reference) {
        MetadataFieldPolicy.requireText("termsEvidenceReference", reference);
        return reference;
    }

    // ------------------------------------------------------------------ helpers

    private ManualPacketView requirePacket(AuthenticatedActor actor, UUID packetId, ActionScopeCode scope) {
        ManualPacketView packet = manual.packet(packetId).orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        requireAction(actor, packet.actionId(), scope);
        return packet;
    }

    private PromotionEngagementView requireEngagement(AuthenticatedActor actor, UUID id, ActionScopeCode scope) {
        PromotionEngagementView engagement = manual.engagement(id).orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        requireListing(actor, engagement.platformListingId(), scope);
        return engagement;
    }

    private ListingActionRepository.ActionRow requireAction(AuthenticatedActor actor, UUID actionId, ActionScopeCode scope) {
        ListingActionRepository.ActionRow action = actions.action(actionId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!actor.organizationId().equals(action.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        authorization.require(actor, scope, ResourceScope.store(action.storeId()));
        return action;
    }

    private ListingFactRepository.ListingContext requireListing(AuthenticatedActor actor, UUID listingId, ActionScopeCode scope) {
        ListingFactRepository.ListingContext listing = facts.listing(listingId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!actor.organizationId().equals(listing.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        authorization.require(actor, scope, ResourceScope.store(listing.storeId()));
        return listing;
    }

    private void recordAudit(AuthenticatedActor actor, String entityType, UUID entityId, AuditAction action,
                             Map<String, FieldChange> changes, String reason) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION, actor.userId().toString(), action,
                entityType, entityId, null, changes, reason, null));
    }
}
