package com.mimococo.marketops.listingconversion.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.listingconversion.CandidateKind;
import com.mimococo.marketops.listingconversion.internal.application.ListingExperienceService;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/listing/experience")
class ListingExperienceController {
    private final ListingExperienceService experience;
    private final MetadataAuditRecorder audit;
    ListingExperienceController(ListingExperienceService experience,MetadataAuditRecorder audit) {
        this.experience=experience; this.audit=audit;
    }
    record Request(@NotNull UUID sourceActionId,@NotNull UUID sourceResultId,@NotNull UUID targetListingId,
                   @NotNull CandidateKind candidateKind,@NotBlank String applicabilityEvidenceReference) { }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    ListingExperienceService.View record(AuthenticatedActor actor,@Valid @RequestBody Request request) {
        return experience.record(actor,request.sourceActionId(),request.sourceResultId(),request.targetListingId(),
                request.candidateKind(),request.applicabilityEvidenceReference());
    }
    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    List<ListingExperienceService.View> target(AuthenticatedActor actor,@RequestParam UUID targetListingId) {
        var result=experience.forTarget(actor,targetListingId);
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION,actor.userId().toString(),
                AuditAction.READ,"lc-experience-application",targetListingId,null,Map.of(),
                "bounded candidate-preparation experience and current applicability",null));
        return result;
    }
}
