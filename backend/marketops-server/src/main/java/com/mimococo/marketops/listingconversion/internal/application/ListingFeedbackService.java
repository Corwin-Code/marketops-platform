package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFeedbackRepository;
import com.mimococo.marketops.marketplaceintegration.IngestionJobDirectory;
import com.mimococo.marketops.marketplaceintegration.RawEvidenceQuery;
import com.mimococo.marketops.shared.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Human-linked source text and auxiliary labels; no product, return or causal fact is created. */
@Service
public class ListingFeedbackService {
    private final ListingScopeAuthorization scopes;
    private final ListingFeedbackRepository feedback;
    private final RawEvidenceQuery raw;
    private final IngestionJobDirectory jobs;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final Clock clock;
    private final com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder audit;
    private final TransactionTemplate transactions;

    ListingFeedbackService(ListingScopeAuthorization scopes,ListingFeedbackRepository feedback,RawEvidenceQuery raw,
                           IngestionJobDirectory jobs,ObjectMapper json,IdGenerator ids,Clock clock,
                           com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder audit,
                           PlatformTransactionManager transactionManager) {
        this.scopes=scopes;this.feedback=feedback;this.raw=raw;this.jobs=jobs;this.json=json;this.ids=ids;this.clock=clock;
        this.audit=audit;
        this.transactions=new TransactionTemplate(transactionManager);
    }
    public record Source(UUID rawObservationId,String itemPointer,String identityPointer,String listingPointer,String textPointer) { }
    public record Classification(String themeCode,String qualificationState,String classifierVersion,String reason) { }
    public record Detail(ListingFeedbackRepository.Item original,List<ListingFeedbackRepository.Label> classifications) { }
    public record Overview(Instant asOf,Instant periodStart,Instant periodEnd,int itemLimit,
                           List<ListingFeedbackRepository.Theme> themes,List<ListingFeedbackRepository.Item> items) { }

    @Transactional(propagation=Propagation.NEVER)
    public UUID capture(AuthenticatedActor actor,UUID listingId,Source source) {
        var listing=scopes.require(actor,listingId,ActionScopeCode.INTERNAL_FACT_INTAKE);
        scopes.require(actor,listingId,ActionScopeCode.EVIDENCE_VIEW);
        if (source==null || source.rawObservationId()==null) reject();
        String root="".equals(source.itemPointer())?"":pointer(source.itemPointer()), identityPath=pointer(source.identityPointer()),
                listingPath=pointer(source.listingPointer()),textPath=pointer(source.textPointer());
        var observation=raw.observation(source.rawObservationId()).orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        var job=jobs.job(observation.jobId()).orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!listing.organizationId().equals(job.organizationId()) || !listing.storeId().equals(job.storeId())
                || !listing.platformCode().equals(job.platformCode()))
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        Instant now=clock.instant();
        if (!observation.carriesPayload() || observation.sourceTime()==null || observation.ingestionTime().isAfter(now)
                || observation.sourceTime().isAfter(observation.ingestionTime())) reject();
        var bytes=raw.verifiedBody(observation.observationId())
                .orElseThrow(()->OperationRejectedException.of(ErrorCode.VALIDATION_FAILED));
        JsonNode item;
        try { item=json.readTree(bytes).at(root); }
        catch (tools.jackson.core.JacksonException invalid) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        var sourceIdentity=item.at(identityPath);
        var sourceListing=item.at(listingPath);
        var original=item.at(textPath);
        if (!item.isObject() || (!sourceIdentity.isString() && !sourceIdentity.isIntegralNumber())
                || (!sourceListing.isString() && !sourceListing.isIntegralNumber()) || !original.isString()
                || !listing.nativeListingKey().equals(sourceListing.asText())) reject();
        MetadataFieldPolicy.requireText("feedbackIdentity",sourceIdentity.asText());
        String identity=Digest.ofComponents(List.of(job.datasetKind(),sourceIdentity.asText()));
        String digest=Digest.ofText(original.asText());
        return transactions.execute(status -> {
            var current=scopes.require(actor,listingId,ActionScopeCode.INTERNAL_FACT_INTAKE);
            scopes.require(actor,listingId,ActionScopeCode.EVIDENCE_VIEW);
            if (!current.organizationId().equals(job.organizationId()) || !current.storeId().equals(job.storeId())
                    || !current.platformCode().equals(job.platformCode())
                    || !current.nativeListingKey().equals(sourceListing.asText()))
                throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
            feedback.lockListing(listingId);
            var previous=feedback.itemForSource(listingId,identity);
            if (previous.isPresent()) {
                if (!previous.get().originalDigest().equals(digest))
                    throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
                return previous.get().id();
            }
            var captured=new ListingFeedbackRepository.Item(ids.newId(),listingId,identity,observation.observationId(),
                    pointer(root+textPath),digest,observation.sourceTime(),observation.ingestionTime());
            feedback.insertItem(captured,current.organizationId(),actor.userId(),now);
            audit.recordChange(new com.mimococo.marketops.adminobservability.audit.MetadataAuditChange(
                    com.mimococo.marketops.adminobservability.audit.AuditSourceDomain.LISTING_CONVERSION,actor.userId().toString(),
                    com.mimococo.marketops.adminobservability.audit.AuditAction.CREATE,"lc-feedback-item",captured.id(),null,
                    java.util.Map.of(),"human-linked Raw feedback reference",null));
            return captured.id();
        });
    }

    @Transactional
    public UUID classify(AuthenticatedActor actor,UUID listingId,UUID itemId,Classification proposed) {
        var listing=scopes.require(actor,listingId,ActionScopeCode.INTERNAL_FACT_INTAKE);
        requireItem(listingId,itemId);
        if (proposed==null || proposed.themeCode()==null || !proposed.themeCode().matches("^[A-Z][A-Z0-9_]{1,62}$")
                || proposed.qualificationState()==null || !List.of("CONFIRMED","UNCERTAIN","CONFLICTED").contains(proposed.qualificationState())) reject();
        String version=MetadataFieldPolicy.requireText("classifierVersion",proposed.classifierVersion());
        String reason=MetadataFieldPolicy.requireText("classificationReason",proposed.reason());
        feedback.lockListing(listingId);
        var history=feedback.labels(itemId);
        if (!history.isEmpty()) {
            var previous=history.getLast();
            if (previous.themeCode().equals(proposed.themeCode()) && previous.qualificationState().equals(proposed.qualificationState())
                    && previous.classifierVersion().equals(version) && previous.reason().equals(reason)) return previous.id();
        }
        var label=new ListingFeedbackRepository.Label(ids.newId(),itemId,history.size(),proposed.themeCode(),
                proposed.qualificationState(),version,reason,actor.userId(),clock.instant());
        feedback.insertLabel(label,listing.organizationId());
        audit.recordChange(new com.mimococo.marketops.adminobservability.audit.MetadataAuditChange(
                com.mimococo.marketops.adminobservability.audit.AuditSourceDomain.LISTING_CONVERSION,actor.userId().toString(),
                com.mimococo.marketops.adminobservability.audit.AuditAction.CREATE,"lc-feedback-classification",label.id(),null,
                java.util.Map.of(),"append human feedback classification",null));
        return label.id();
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Overview overview(AuthenticatedActor actor,UUID listingId,Instant from,Instant to,int limit) {
        scopes.require(actor,listingId,ActionScopeCode.LISTING_CONVERSION_VIEW);
        scopes.require(actor,listingId,ActionScopeCode.EVIDENCE_VIEW);
        Instant at=clock.instant();
        if (from==null || to==null || !from.isBefore(to) || to.isAfter(at) || limit<1 || limit>200) reject();
        return new Overview(at,from,to,limit,feedback.themes(listingId,from,to,at),feedback.items(listingId,from,to,at,limit));
    }
    @Transactional(readOnly=true)
    public Detail detail(AuthenticatedActor actor,UUID listingId,UUID itemId) {
        scopes.require(actor,listingId,ActionScopeCode.LISTING_CONVERSION_VIEW);
        scopes.require(actor,listingId,ActionScopeCode.EVIDENCE_VIEW);
        return new Detail(requireItem(listingId,itemId),feedback.labels(itemId));
    }
    @Transactional(readOnly=true)
    public List<ListingFeedbackRepository.Theme> themes(AuthenticatedActor actor,UUID listingId,Instant from,Instant to) {
        scopes.require(actor,listingId,ActionScopeCode.LISTING_CONVERSION_VIEW);
        Instant at=clock.instant();
        if (from==null || to==null || !from.isBefore(to) || to.isAfter(at)) reject();
        return feedback.themes(listingId,from,to,at);
    }
    private ListingFeedbackRepository.Item requireItem(UUID listingId,UUID id) {
        var item=feedback.item(id).orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!item.listingId().equals(listingId)) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        return item;
    }
    private static String pointer(String value) {
        MetadataFieldPolicy.requireText("feedbackPointer",value);
        String valid=value;
        if (!valid.startsWith("/")) reject();
        try { tools.jackson.core.JsonPointer.compile(valid); }
        catch (IllegalArgumentException invalid) { reject(); }
        return valid;
    }
    private static void reject() { throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED); }
}
