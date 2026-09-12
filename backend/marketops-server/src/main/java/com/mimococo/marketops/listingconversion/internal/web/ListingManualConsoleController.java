package com.mimococo.marketops.listingconversion.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.listingconversion.ManualPacketView;
import com.mimococo.marketops.listingconversion.PromotionEngagementView;
import com.mimococo.marketops.listingconversion.internal.application.ManualPathService;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The manual execution path and official promotion engagements.
 *
 * <p>A packet carries exactly the approved material to the person who will
 * apply it by hand; their report is theirs alone, and the verification that
 * closes the loop comes from someone else. Promotion participation is always
 * manual: the platform's own console applies it, this one records, exits and
 * releases it.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/listing/manual")
class ListingManualConsoleController {

    private final ManualPathService manual;
    private final MetadataAuditRecorder audit;

    ListingManualConsoleController(ManualPathService manual,
                                   MetadataAuditRecorder audit) {
        this.manual = manual;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ packets

    @Transactional
    @GetMapping(value = "/packets", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ManualPacketView> myPackets(AuthenticatedActor actor,
                                     @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        List<ManualPacketView> result = manual.myPackets(actor, limit);
        auditRead(actor, "lc-manual-packet", actor.userId(), "my-packets");
        return result;
    }

    @Transactional
    @GetMapping(value = "/packets/{packetId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ManualPacketView packet(AuthenticatedActor actor, @PathVariable UUID packetId) {
        ManualPacketView result = manual.packet(actor, packetId);
        auditRead(actor, "lc-manual-packet", packetId, "packet");
        return result;
    }

    @Transactional
    @GetMapping(value = "/actions/{actionId}/packets", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ManualPacketView> packetsForAction(AuthenticatedActor actor, @PathVariable UUID actionId) {
        List<ManualPacketView> result = manual.packetsForAction(actor, actionId);
        auditRead(actor, "lc-manual-packet", actionId, "packets-for-action");
        return result;
    }

    @PostMapping(value = "/actions/{actionId}/packets", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ManualPacketView issuePacket(AuthenticatedActor actor, @PathVariable UUID actionId,
                                 @Valid @RequestBody IssueRequest request) {
        return manual.issuePacket(actor, actionId, request.executorUserId());
    }

    @PostMapping(value = "/packets/{packetId}/report", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ManualPacketView report(AuthenticatedActor actor, @PathVariable UUID packetId,
                            @Valid @RequestBody ReportRequest request) {
        return manual.report(actor, packetId, request.operationTime(), request.reportState(), request.note());
    }

    @PostMapping(value = "/packets/{packetId}/verify", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ManualPacketView verify(AuthenticatedActor actor, @PathVariable UUID packetId,
                            @Valid @RequestBody VerifyRequest request) {
        return manual.verify(actor, packetId, request.basis(), request.managementMatch(),
                request.managementObservationId(), request.displayObservationId(), request.displayState(),
                request.note(),request.promotionObservationId());
    }

    // ------------------------------------------------------------------ engagements

    @Transactional
    @GetMapping(value = "/engagements", produces = MediaType.APPLICATION_JSON_VALUE)
    List<PromotionEngagementView> engagements(AuthenticatedActor actor, @RequestParam UUID listingId) {
        List<PromotionEngagementView> result = manual.engagements(actor, listingId);
        auditRead(actor, "lc-promotion-engagement", listingId, "engagements");
        return result;
    }

    @Transactional
    @GetMapping(value = "/engagements/{engagementId}", produces = MediaType.APPLICATION_JSON_VALUE)
    PromotionEngagementView engagement(AuthenticatedActor actor, @PathVariable UUID engagementId) {
        PromotionEngagementView result = manual.engagement(actor,engagementId);
        auditRead(actor, "lc-promotion-engagement", engagementId, "engagement");
        return result;
    }

    @PostMapping(value = "/listings/{listingId}/engagements", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    PromotionEngagementView adopt(AuthenticatedActor actor, @PathVariable UUID listingId,
                                  @Valid @RequestBody EngagementRequest request) {
        return manual.adopt(actor, listingId, request.toService());
    }

    @PostMapping(value = "/actions/{actionId}/engagements", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    PromotionEngagementView enter(AuthenticatedActor actor, @PathVariable UUID actionId,
                                  @Valid @RequestBody EngagementRequest request) {
        return manual.enter(actor, actionId, request.toService());
    }

    @PostMapping(value = "/engagements/{engagementId}/exit", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    PromotionEngagementView exit(AuthenticatedActor actor, @PathVariable UUID engagementId,
                                 @Valid @RequestBody ExitRequest request) {
        return manual.authorizeExit(actor, engagementId, request.reasonCode());
    }

    @PostMapping(value = "/engagements/{engagementId}/release", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    PromotionEngagementView release(AuthenticatedActor actor, @PathVariable UUID engagementId,
                                    @Valid @RequestBody ReleaseKindRequest request) {
        return manual.release(actor, engagementId, request.releaseKind());
    }

    private void auditRead(AuthenticatedActor actor, String entityType, UUID entityId, String reason) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION,
                actor.userId().toString(), AuditAction.READ, entityType, entityId, null, Map.of(), reason, null));
    }

    record IssueRequest(@NotNull UUID executorUserId) {
    }

    record ReportRequest(@NotNull Instant operationTime, @NotBlank String reportState, String note) {
    }

    record VerifyRequest(@NotBlank String basis, @NotBlank String managementMatch, UUID managementObservationId,
                         UUID displayObservationId, String displayState, String note, UUID promotionObservationId) {
    }

    record EngagementRequest(@NotBlank String engagementKind, @NotBlank String nativePromotionKey,
                             Map<String, String> terms, boolean priceFreeze, boolean autoParticipation,
                             @NotBlank String termsEvidenceReference, Map<String, String> obligations) {

        ManualPathService.EngagementRequest toService() {
            return new ManualPathService.EngagementRequest(engagementKind, nativePromotionKey,
                    terms == null ? Map.of() : terms, priceFreeze, autoParticipation, termsEvidenceReference,
                    obligations == null ? Map.of() : obligations);
        }
    }

    record ExitRequest(@NotBlank String reasonCode) {
    }

    record ReleaseKindRequest(@NotBlank String releaseKind) {
    }
}
