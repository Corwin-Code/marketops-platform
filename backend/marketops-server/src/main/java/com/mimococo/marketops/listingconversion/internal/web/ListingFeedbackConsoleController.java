package com.mimococo.marketops.listingconversion.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.listingconversion.internal.application.ListingFeedbackService;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFeedbackRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/listing/health/listings/{listingId}/feedback")
class ListingFeedbackConsoleController {
    private final ListingFeedbackService feedback;
    private final com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder audit;
    ListingFeedbackConsoleController(ListingFeedbackService feedback,com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder audit) {
        this.feedback=feedback;this.audit=audit;
    }

    @PostMapping("/sources")
    Map<String,UUID> capture(AuthenticatedActor actor,@PathVariable UUID listingId,
                            @RequestBody ListingFeedbackService.Source source) {
        return Map.of("itemId",feedback.capture(actor,listingId,source));
    }
    @PostMapping("/{itemId}/classifications")
    Map<String,UUID> classify(AuthenticatedActor actor,@PathVariable UUID listingId,@PathVariable UUID itemId,
                             @RequestBody ListingFeedbackService.Classification classification) {
        return Map.of("classificationId",feedback.classify(actor,listingId,itemId,classification));
    }
    @GetMapping("/{itemId}")
    @Transactional
    ListingFeedbackService.Detail detail(AuthenticatedActor actor,@PathVariable UUID listingId,@PathVariable UUID itemId) {
        var result=feedback.detail(actor,listingId,itemId);
        auditRead(actor,itemId,"feedback original reference and classification history");
        return result;
    }
    @GetMapping("/themes")
    @Transactional
    List<ListingFeedbackRepository.Theme> themes(AuthenticatedActor actor,@PathVariable UUID listingId,
                                                @RequestParam Instant from,@RequestParam Instant to) {
        var result=feedback.themes(actor,listingId,from,to);
        auditRead(actor,listingId,"feedback period themes");
        return result;
    }
    @GetMapping
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    ListingFeedbackService.Overview overview(AuthenticatedActor actor,@PathVariable UUID listingId,
            @RequestParam Instant from,@RequestParam Instant to,@RequestParam(defaultValue="50") int limit) {
        var result=feedback.overview(actor,listingId,from,to,limit);
        auditRead(actor,listingId,"feedback common snapshot");
        return result;
    }
    private void auditRead(AuthenticatedActor actor,UUID id,String reason) {
        audit.recordChange(new com.mimococo.marketops.adminobservability.audit.MetadataAuditChange(
                com.mimococo.marketops.adminobservability.audit.AuditSourceDomain.LISTING_CONVERSION,actor.userId().toString(),
                com.mimococo.marketops.adminobservability.audit.AuditAction.READ,"lc-feedback",id,null,Map.of(),reason,null));
    }
}
