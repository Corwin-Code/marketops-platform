package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.aicopilot.AiCopilot;
import com.mimococo.marketops.aicopilot.AiDiagnosis;
import com.mimococo.marketops.aicopilot.ListingAssistancePurpose;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.identityaccess.*;
import com.mimococo.marketops.listingconversion.internal.domain.AffectedSetResolution;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Scope and disclosure remain deterministic before dispatch and before returning model material. */
@Service
public class ListingAssistanceService {
    private final ListingScopeAuthorization scopes;
    private final ListingFactRepository facts;
    private final BusinessAuthorization authorization;
    private final AiCopilot copilot;

    ListingAssistanceService(ListingScopeAuthorization scopes,ListingFactRepository facts,
                             BusinessAuthorization authorization,AiCopilot copilot) {
        this.scopes=scopes;this.facts=facts;this.authorization=authorization;this.copilot=copilot;
    }

    @Transactional(propagation=Propagation.NEVER)
    public AiDiagnosis assist(AuthenticatedActor actor,UUID listingId,MetricWindow window,ListingAssistancePurpose purpose) {
        var set=requireDisclosure(actor,listingId);
        if (window==null || purpose==null) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        var answer=copilot.assistListing(actor.userId(),actor.organizationId(),listingId,set.storeId(),
                set.members().listingVariantIds(),set.members().productVariantIds(),window,purpose);
        var current=requireDisclosure(actor,listingId);
        if (!current.equals(set)) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        return answer;
    }

    public AiDiagnosis read(AuthenticatedActor actor,UUID listingId,UUID invocationId) {
        requireDisclosure(actor,listingId);
        scopes.require(actor,listingId,ActionScopeCode.EVIDENCE_VIEW);
        var originalScope=copilot.listingInvocationScope(invocationId,actor.organizationId(),listingId)
                .orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED));
        authorization.require(actor,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,ResourceScope.store(originalScope.storeId()));
        authorization.require(actor,ActionScopeCode.EVIDENCE_VIEW,ResourceScope.store(originalScope.storeId()));
        for (UUID product:originalScope.productVariantIds()) authorization.require(actor,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScope.productVariant(product));
        return copilot.listingInvocation(invocationId,actor.organizationId(),listingId)
                .orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * Earlier assistance requests of one listing, newest first, without their content. Guarded like the
     * first steps of {@link #read}; opening one record still checks its original scope there.
     */
    @Transactional(readOnly=true)
    public java.util.List<AiCopilot.ListingInvocationRecord> history(AuthenticatedActor actor,UUID listingId,int limit) {
        requireDisclosure(actor,listingId);
        scopes.require(actor,listingId,ActionScopeCode.EVIDENCE_VIEW);
        return copilot.listingInvocations(actor.organizationId(),listingId,limit);
    }

    private record Disclosure(UUID storeId,AffectedSetResolution.Resolution members) { }
    private Disclosure requireDisclosure(AuthenticatedActor actor,UUID listingId) {
        var listing=scopes.require(actor,listingId,ActionScopeCode.LISTING_CONVERSION_VIEW);
        authorization.require(actor,ActionScopeCode.DIAGNOSTIC_VIEW,ResourceScope.store(listing.storeId()));
        authorization.require(actor,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,ResourceScope.store(listing.storeId()));
        var snapshot=facts.identitySnapshot(listingId,facts.databaseNow());
        var nativeScope=snapshot.identityLineage().path("nativeScope");
        var reasons=new ArrayList<String>();
        nativeScope.path("reasonCodes").forEach(reason->reasons.add(reason.asText()));
        var set=AffectedSetResolution.resolve(ListingFactRepository.snapshotMembers(snapshot.identityLineage()),
                nativeScope.path("state").asText(),reasons);
        if (!"COMPLETE".equals(set.state())) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        for (UUID member:set.productVariantIds()) authorization.require(actor,ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScope.productVariant(member));
        return new Disclosure(listing.storeId(),set);
    }
}
