package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.listingconversion.MaterialityRoute;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.ListingActionDecisionAuthority;
import com.mimococo.marketops.operationsworkflow.ListingActionIntake;
import com.mimococo.marketops.operationsworkflow.ListingDecisionScope;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
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
import tools.jackson.databind.ObjectMapper;

/**
 * What the listing module answers when the workflow decides a listing action.
 *
 * <p>The decision scope is read from the action and its review; the deterministic
 * refusals are the module's own vocabulary; the binding is frozen here in the
 * same transaction as the approval and names exactly what the approval was given
 * against.
 */
@Service
class ListingActionDecisionService implements ListingActionDecisionAuthority {

    private final ListingActionRepository actions;
    private final ListingFactRepository facts;
    private final CalibrationService calibration;
    private final ListingExposureService exposureService;
    private final ListingActionIntake intake;
    private final IdGenerator ids;
    private final Clock clock;
    private final ObjectMapper json;

    ListingActionDecisionService(ListingActionRepository actions, ListingFactRepository facts, CalibrationService calibration,
                                 ListingActionIntake intake, IdGenerator ids, Clock clock, ObjectMapper json,ListingExposureService exposureService) {
        this.actions = actions;
        this.facts = facts;
        this.calibration = calibration;
        this.exposureService = exposureService;
        this.intake = intake;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ListingDecisionScope> decisionScope(UUID recommendationId) {
        return buildScope(recommendationId,false);
    }

    @Override
    @Transactional(readOnly=true)
    public Optional<ListingDecisionScope> recheckedDecisionScope(UUID recommendationId) {
        return buildScope(recommendationId,true);
    }

    private Optional<ListingDecisionScope> buildScope(UUID recommendationId,boolean recheckCurrentExposure) {
        return actions.actionForRecommendation(recommendationId).map(action -> {
            Optional<UUID> reviewer = actions.attestingReviewer(action.id());
            Instant now=actions.databaseNow();
            var recheck=calibration.recheckAction(action.id(),now);
            CalibrationService.Outcome resolved=recheck.outcome();
            Duration validity=resolved.ok()?CalibrationService.approvalValidity(resolved.resolved()).orElse(null):null;
            boolean meaningQualified=actions.hasQualifiedMeaningReview(action.id(),now);
            String document=actions.authoritySnapshot(recommendationId).orElse("{}");
            String route=action.materialityRoute();
            Map<String,String> materiality=recheckCurrentExposure?currentMateriality(action,resolved,meaningQualified,now):Map.of();
            if (recheckCurrentExposure && !"CURRENT".equals(materiality.get("state")))
                route=MaterialityRoute.MATERIALITY_UNRESOLVED.name();
            return new ListingDecisionScope(recommendationId, action.organizationId(), action.storeId(), action.listingId(),
                    action.id(), action.version(), ActionKind.valueOf(action.actionKind()), action.executionPath(),
                    action.state(), route, Boolean.TRUE.equals(action.contentAxisMaterial()),
                    Boolean.TRUE.equals(action.exposureAxisMaterial()), action.calibrationPackageId(),
                    action.calibrationVersion(), validity, action.affectedSetDigest(), action.targetTextDigest(),
                    action.currentTextDigest(), action.targetText() == null ? 0 : action.targetText().length(),
                    action.kizMarkedDeclared(), action.authorUserId(), reviewer.orElse(null), meaningQualified,
                    document,recheck.evidence(),materiality);
        });
    }

    private Map<String,String> currentMateriality(ListingActionRepository.ActionRow action,CalibrationService.Outcome resolved,
                                                 boolean meaningQualified,Instant at) {
        Map<String,String> evidence=new java.util.LinkedHashMap<>();
        evidence.put("model","LC_CURRENT_MATERIALITY_1");
        evidence.put("assessedAt",at.toString());
        if (!meaningQualified) { evidence.put("state","MEANING_REVIEW_UNQUALIFIED");return evidence; }
        var snapshot=facts.identitySnapshot(action.listingId(),at);
        var nativeScope=snapshot.identityLineage().path("nativeScope");
        var nativeReasons=new ArrayList<String>();
        nativeScope.path("reasonCodes").forEach(reason->nativeReasons.add(reason.asText()));
        var set=com.mimococo.marketops.listingconversion.internal.domain.AffectedSetResolution.resolve(
                ListingFactRepository.snapshotMembers(snapshot.identityLineage()),nativeScope.path("state").asText(),nativeReasons);
        if (!"COMPLETE".equals(set.state()) || !snapshot.digest().equals(action.affectedSetDigest())) {
            evidence.put("state","AFFECTED_SET_CHANGED_OR_UNQUALIFIED");return evidence;
        }
        var listing=facts.listing(action.listingId()).orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        var exposure=exposureService.assess(listing,snapshot.digest(),set.listingVariantIds(),resolved,at);
        evidence.put("projectionDigest",Digest.ofText(json.writeValueAsString(exposure.evidence())));
        evidence.put("exposureState",String.valueOf(exposure.evidence().get("state")));
        if (exposure.evidence().get("projection") instanceof com.mimococo.marketops.analyticsdecision.CanonicalScopeMetricQuery.Exposure projection) {
            var values=new ArrayList<com.mimococo.marketops.analyticsdecision.MetricValueView>(projection.memberValues());
            if (projection.storeValue()!=null) values.add(projection.storeValue());
            evidence.put("metricValueIds",json.writeValueAsString(values.stream().map(com.mimococo.marketops.analyticsdecision.MetricValueView::metricValueId).toList()));
            evidence.put("verificationRunIds",json.writeValueAsString(values.stream().map(com.mimococo.marketops.analyticsdecision.MetricValueView::verificationRunId)
                    .filter(java.util.Objects::nonNull).distinct().toList()));
        }
        evidence.put("state",exposure.material()==null?"CURRENT_EXPOSURE_UNRESOLVED"
                :exposure.material().equals(action.exposureAxisMaterial())?"CURRENT":"EXPOSURE_CLASSIFICATION_CHANGED");
        return evidence;
    }

    @Override
    @Transactional(readOnly = true)
    public String authorityDocument(UUID recommendationId) {
        return actions.authoritySnapshot(recommendationId).orElse("{}");
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> unresolvedReasons(UUID recommendationId) {
        Optional<ListingActionRepository.ActionRow> found = actions.actionForRecommendation(recommendationId);
        if (found.isEmpty()) {
            return List.of("ACTION_NOT_FOUND");
        }
        ListingActionRepository.ActionRow action = found.get();
        List<String> reasons = new ArrayList<>();
        if (MaterialityRoute.MATERIALITY_UNRESOLVED.name().equals(action.materialityRoute())) {
            reasons.add("MATERIALITY_UNRESOLVED");
        }
        CalibrationService.Outcome resolved = resolvedFor(action);
        if (!resolved.ok()) {
            reasons.add(resolved.state());
        }
        Optional<ListingFactRepository.AffectedSetRow> set = facts.affectedSetById(action.affectedSetId());
        if (set.isEmpty() || !"COMPLETE".equals(set.get().resolutionState())) {
            reasons.add("AFFECTED_SET_INCOMPLETE");
        }
        if (!facts.currentAffectedSetDigest(action.listingId()).equals(action.affectedSetDigest())) {
            reasons.add("AFFECTED_SET_DIGEST_CHANGED");
        }
        if (ActionKind.LISTING_DESCRIPTION_CHANGE.name().equals(action.actionKind())) {
            String latest = facts.latestDescription(action.listingId()).map(ListingFactRepository.DescriptionRow::textDigest)
                    .orElse(null);
            if (latest == null || !latest.equals(action.currentTextDigest())) {
                reasons.add("CURRENT_TEXT_MOVED");
            }
            if (action.kizMarkedDeclared() == null) {
                reasons.add("KIZ_MARKED_UNDECLARED");
            }
            if (resolved.ok()) {
                CalibrationService.lengthBounds(resolved.resolved()).ifPresent(bounds -> {
                    int length = action.targetText() == null ? 0 : action.targetText().codePointCount(0, action.targetText().length());
                    if (length < bounds[0] || length > bounds[1]) {
                        reasons.add("TEXT_LENGTH_OUT_OF_BOUNDS");
                    }
                });
            }
        }
        String healthState = actions.latestHealthNecessaryState(action.listingId()).orElse(null);
        if (healthState == null || "UNKNOWN".equals(healthState)) {
            reasons.add("LISTING_HEALTH_UNKNOWN");
        } else if ("FAIL".equals(healthState)) {
            reasons.add("LISTING_HEALTH_NECESSARY_FAILED");
        }
        if (actions.scopeContained(action.organizationId(), action.listingId())) {
            reasons.add("SCOPE_CONTAINED");
        }
        if (actions.attestingReviewer(action.id()).isEmpty()) {
            reasons.add("REVIEW_MISSING");
        }
        return List.copyOf(reasons);
    }

    @Override
    @Transactional
    public UUID bindApproval(UUID recommendationId, UUID approvalDecisionId, UUID guardrailEvaluationId,
                             Instant approvalScopeExpiresAt) {
        ListingActionRepository.ActionRow action = actions.actionForRecommendation(recommendationId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"REVIEWED".equals(action.state()) || action.calibrationPackageId() == null) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        Instant now = actions.databaseNow();
        Map<String, String> evidenceVersions = Map.of(
                "currentDescriptionObservationId", String.valueOf(action.currentObservationId()),
                "affectedSetId", action.affectedSetId().toString(),
                "healthNecessaryState", actions.latestHealthNecessaryState(action.listingId()).orElse("UNKNOWN"));
        Map<String, String> ruleVersions = Map.of(
                "calibrationPackageId", action.calibrationPackageId().toString(),
                "calibrationVersion", String.valueOf(action.calibrationVersion()),
                "guardrailEvaluationId", guardrailEvaluationId.toString());
        String bindingDigest = Digest.ofComponents(List.of(action.affectedSetDigest(),
                String.valueOf(action.currentTextDigest()), String.valueOf(action.targetTextDigest()), action.executionPath(),
                approvalDecisionId.toString(), guardrailEvaluationId.toString(),
                action.calibrationPackageId() + ":" + action.calibrationVersion(), approvalScopeExpiresAt.toString()));
        UUID bindingId = ids.newId();
        actions.insertBinding(bindingId, action.organizationId(), action.id(), approvalDecisionId, guardrailEvaluationId,
                action.targetTextDigest(), action.currentTextDigest(), action.affectedSetDigest(), action.executionPath(),
                evidenceVersions, ruleVersions, action.calibrationPackageId(), action.calibrationVersion(), bindingDigest,
                now, approvalScopeExpiresAt);
        if (!actions.moveAction(action.id(), "APPROVED", action.version(), now)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        return bindingId;
    }

    @Override
    @Transactional
    public void recordRejection(UUID recommendationId, UUID approvalDecisionId) {
        actions.actionForRecommendation(recommendationId).ifPresent(action -> {
            if (List.of("DRAFT", "REVIEWED").contains(action.state())) {
                actions.moveAction(action.id(), "CANCELLED", action.version(), clock.instant());
            }
        });
    }

    @Override
    @Transactional
    public void recordCommandCreated(UUID recommendationId, UUID commandId) {
        intake.taskForRecommendation(recommendationId).ifPresent(task ->
                intake.recordTaskOutcome(recommendationId, "OPERATIONAL", "lc-description-command:" + commandId,
                        "the description command was created from the launched action"));
    }

    private CalibrationService.Outcome resolvedFor(ListingActionRepository.ActionRow action) {
        return calibration.recheckAction(action.id(),clock.instant()).outcome();
    }
}
