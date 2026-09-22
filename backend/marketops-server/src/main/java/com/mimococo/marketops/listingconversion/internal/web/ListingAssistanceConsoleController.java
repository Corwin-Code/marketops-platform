package com.mimococo.marketops.listingconversion.internal.web;

import com.mimococo.marketops.aicopilot.AiDiagnosis;
import com.mimococo.marketops.aicopilot.ListingAssistancePurpose;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.listingconversion.internal.application.ListingAssistanceService;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/listing/health/listings/{listingId}/assistance")
class ListingAssistanceConsoleController {
    private final ListingAssistanceService assistance;
    private final com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder audit;
    ListingAssistanceConsoleController(ListingAssistanceService assistance,
            com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder audit) {
        this.assistance=assistance;this.audit=audit;
    }
    record Request(MetricWindow window,ListingAssistancePurpose purpose) { }
    @PostMapping
    AiDiagnosis assist(AuthenticatedActor actor,@PathVariable UUID listingId,@RequestBody Request request) {
        return assistance.assist(actor,listingId,request.window(),request.purpose()).consoleView();
    }
    /** Earlier requests for this listing, newest first, without content; never calls a model. */
    @GetMapping
    @Transactional
    java.util.List<com.mimococo.marketops.aicopilot.AiCopilot.ListingInvocationRecord> history(
            AuthenticatedActor actor,@PathVariable UUID listingId,
            @RequestParam(defaultValue="10") @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(50) int limit) {
        var result=assistance.history(actor,listingId,limit);
        audit.recordChange(new com.mimococo.marketops.adminobservability.audit.MetadataAuditChange(
                com.mimococo.marketops.adminobservability.audit.AuditSourceDomain.LISTING_CONVERSION,actor.userId().toString(),
                com.mimococo.marketops.adminobservability.audit.AuditAction.READ,"lc-ai-assistance",listingId,null,
                java.util.Map.of(),"history",null));
        return result;
    }

    @GetMapping("/{invocationId}")
    @Transactional
    AiDiagnosis read(AuthenticatedActor actor,@PathVariable UUID listingId,@PathVariable UUID invocationId) {
        var result=assistance.read(actor,listingId,invocationId);
        audit.recordChange(new com.mimococo.marketops.adminobservability.audit.MetadataAuditChange(
                com.mimococo.marketops.adminobservability.audit.AuditSourceDomain.LISTING_CONVERSION,actor.userId().toString(),
                com.mimococo.marketops.adminobservability.audit.AuditAction.READ,"lc-ai-assistance",invocationId,null,
                java.util.Map.of(),"read bounded listing assistance under current original-scope grants",null));
        return result.consoleView();
    }
}
