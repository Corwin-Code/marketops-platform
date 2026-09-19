package com.mimococo.marketops.listingconversion.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.listingconversion.internal.application.ListingOperationsReviewService;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only current, daily and weekly views from one canonical snapshot. */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/listing/operations-review")
class ListingOperationsReviewController {
    private final ListingOperationsReviewService reviews;
    private final MetadataAuditRecorder audit;

    ListingOperationsReviewController(ListingOperationsReviewService reviews,MetadataAuditRecorder audit) {
        this.reviews=reviews; this.audit=audit;
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    ListingOperationsReviewService.Bundle read(AuthenticatedActor actor,@RequestParam UUID storeId,
                                                @RequestParam(defaultValue="100") @Min(1) @Max(200) int limit) {
        var result=reviews.read(actor,storeId,limit);
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION,actor.userId().toString(),
                AuditAction.READ,"lc-operations-review",storeId,null,Map.of(),
                "current, daily and weekly canonical listing snapshot",null));
        return result;
    }
}
