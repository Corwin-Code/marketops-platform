package com.mimococo.marketops.advertisingefficiency.internal.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.mimococo.marketops.advertisingefficiency.AdvertisingBriefView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCaseView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingContainment;
import com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView;
import com.mimococo.marketops.advertisingefficiency.ManualExecutionPacketView;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingDisclosureRepository;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One fail-closed projector for every delivery channel. Raw domain records must
 * not be serialized directly by advertising console or delivery handlers.
 * Decisions still use the canonical authorities, never this projection.
 */
@Service
@Transactional(readOnly = true)
public class AdvertisingDisclosureService implements AdvertisingDisclosurePolicy {
    public enum Channel { API, EXPORT, ATTACHMENT, NOTIFICATION, AI_EXPLANATION }

    private final BusinessAuthorization authorization;
    private final AdvertisingDisclosureRepository scopes;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AdvertisingDisclosureService(BusinessAuthorization authorization,
            AdvertisingDisclosureRepository scopes, ObjectMapper mapper, Clock clock) {
        this.authorization = authorization;
        this.scopes = scopes;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public tools.jackson.databind.node.ArrayNode discloseTaskEvents(AuthenticatedActor actor, UUID objectId,
            String digest, List<com.mimococo.marketops.operationsworkflow.WorkTaskEventView> events) {
        boolean full = digest != null && mayReadDecisionEvidence(actor, objectId, digest);
        var result = mapper.createArrayNode();
        for (var event : events) {
            ObjectNode raw = mapper.valueToTree(event);
            if (full) result.add(raw);
            else {
                ObjectNode projected = allow(raw, "id", "taskId", "sequenceNo", "eventKind", "lineageKey",
                        "fromAssigneeUserId", "toAssigneeUserId", "actorUserId", "actorRoleCode", "occurredAt");
                projected.put("disclosureState", "MASKED");
                result.add(projected);
            }
        }
        return result;
    }

    @Override
    public ObjectNode discloseRecommendation(AuthenticatedActor actor, RecommendationView view) {
        ObjectNode raw = mapper.valueToTree(view);
        if (!"AD_NATIVE_OBJECT".equals(view.subjectKind().name())) return raw;
        if (!mayReadNativeRecommendation(actor, view.id()))
            throw OperationRejectedException.of(ErrorCode.APPROVAL_EVIDENCE_SCOPE_BLOCKED);
        boolean full=scopes.recommendationScope(actor.organizationId(),view.id())
                .map(reference->mayReadDecisionEvidence(actor,reference.objectId(),reference.affectedSetDigest())).orElse(false);
        if (full) return raw;
        ObjectNode result = allow(raw, "id", "organizationId", "storeId", "subjectKind", "subjectId",
                "actionKind", "origin", "window", "state", "entityVersionDigest", "validUntil", "createdAt", "version");
        ObjectNode parameters = mapper.valueToTree(view.proposedParameters());
        result.set("proposedParameters", allow(parameters, "currentBid", "currentBidAmount", "targetBid",
                "targetBidAmount", "direction", "candidateId", "adBidCandidateId", "currencyCode", "currency",
                "bidUnitCode", "affectedSetDigest", "adNativeObjectId"));
        result.putObject("expectedEffect");
        result.putArray("evidence");
        result.put("riskLabel", "MASKED");
        result.putNull("priorityScore");
        result.put("disclosureState", "MASKED");
        return result;
    }

    @Override
    public boolean mayReadDecisionEvidence(AuthenticatedActor actor, UUID objectId) {
        return scopes.objectScope(actor.organizationId(),objectId)
                .map(scope -> completeEvidenceScope(actor,scope)).orElse(false);
    }

    @Override
    public boolean mayReadDecisionEvidence(AuthenticatedActor actor, UUID objectId, String digest) {
        if(digest==null) return false;
        return scopes.objectScope(actor.organizationId(), objectId, digest)
                .map(scope -> completeEvidenceScope(actor, scope)).orElse(false);
    }

    @Override
    public void requireDecisionEvidence(AuthenticatedActor actor, UUID objectId) {
        if(!mayReadDecisionEvidence(actor,objectId)) {
            throw OperationRejectedException.of(ErrorCode.APPROVAL_EVIDENCE_SCOPE_BLOCKED);
        }
    }

    @Override
    public void requireDecisionEvidence(AuthenticatedActor actor, UUID objectId, String digest) {
        if (!mayReadDecisionEvidence(actor, objectId, digest)) {
            throw OperationRejectedException.of(ErrorCode.APPROVAL_EVIDENCE_SCOPE_BLOCKED);
        }
    }

    private boolean completeEvidenceScope(AuthenticatedActor actor,
            AdvertisingDisclosureRepository.ObjectScope scope) {
        return "COMPLETE".equals(scope.resolutionState()) && !scope.productVariantIds().isEmpty()
                && permitted(actor, ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW,
                        ResourceScope.store(scope.storeId()))
                && scope.productVariantIds().stream().allMatch(id -> permitted(actor,
                        ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW,
                        ResourceScope.productVariant(id)));
    }

    public boolean organizationView(AuthenticatedActor actor) {
        return permitted(actor, ActionScopeCode.ADVERTISING_VIEW,
                ResourceScope.organization(actor.organizationId()));
    }

    public boolean organizationEvidence(AuthenticatedActor actor) {
        return permitted(actor, ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW,
                ResourceScope.organization(actor.organizationId()));
    }

    private boolean permitted(AuthenticatedActor actor, ActionScopeCode action, ResourceScope scope) {
        return authorization.evaluate(actor, action, scope).permitted();
    }

    public ObjectNode caseView(AuthenticatedActor actor, AdvertisingCaseView view, Channel channel) {
        var scope = scopes.caseObjectScope(actor.organizationId(), view.adNativeObjectId(), view.affectedSetDigest())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        authorization.require(actor, ActionScopeCode.ADVERTISING_VIEW, ResourceScope.store(scope.storeId()));
        boolean full = view.affectedSetDigest()!=null && completeEvidenceScope(actor, scope)
                && view.affectedSetDigest().equals(scope.affectedSetDigest());
        ObjectNode raw = mapper.valueToTree(view);
        ObjectNode result = full ? raw : allow(raw, "id", "storeId", "platformCode", "adNativeObjectId",
                "nativeObjectKind", "nativeObjectKey", "nativeCampaignKey", "nativeObjectName", "biddingMode",
                "controlGranularityState", "lineageGeneration", "lane", "protectionTier",
                "accountableRoleCode", "currentBidState", "currentBidAmount", "asOf",
                "calculatedAt", "sustainedLane", "sustainedCycles", "sustainedSince", "affectedSetDigest",
                "affectedSetResolution", "affectedVariantCount");
        if (!full) {
            for (String field : List.of("contributionProfit", "profitPerAdRub", "officialSpend",
                    "eligibleTraffic", "adLinkedConversion", "maxCpc", "attributionGap")) {
                result.put(field + "State", "MASKED");
            }
            for (String field : List.of("contributionProfitAmount", "profitPerAdRubValue", "officialSpendAmount",
                    "eligibleTrafficCount", "adLinkedConversionValue", "maxCpcAmount", "attributionGapRatio",
                    "recoverableProfitAmount", "rankScore", "profitCurrencyCode")) {
                result.putNull(field);
            }
            result.putArray("rankFactors");
            result.putArray("evidence");
            result.putArray("blockerCodes");
            result.put("causeCode", "MASKED");
            result.put("evidenceState", "MASKED");
            result.put("confidenceState", "MASKED");
            var variants = result.putArray("variants");
            for (UUID id : scope.productVariantIds()) {
                variants.addObject().put("productVariantId", id.toString()).put("valueState", "MASKED");
            }
        }
        result.set("affectedProductVariantIds", mapper.valueToTree(scope.productVariantIds()));
        result.set("affectedListingVariantIds", mapper.valueToTree(scope.listingVariantIds()));
        result.put("storeTimezone", scope.storeTimezone());
        result.set("nativeRelationships", mapper.valueToTree(scopes.relationships(
                actor.organizationId(), view.adNativeObjectId(), scope.storeId())));
        ObjectNode profile = result.putObject("semanticProfile");
        profile.put("id", scope.semanticProfileId().toString());
        profile.put("version", scope.semanticProfileVersion());
        profile.put("verificationState", scope.verificationState());
        profile.put("sourceMaturity", scope.sourceMaturity());
        profile.put("controlLevel", scope.controlLevel());
        profile.put("bidUnitCode", scope.bidUnitCode());
        profile.put("biddingMode", scope.biddingMode());
        scopes.nativeRules(actor.organizationId(),view.adNativeObjectId()).ifPresent(value->profile.set("nativeRules",mapper.readTree(value)));
        result.put("disclosureState", full ? "FULL" : "MASKED");
        result.put("deliveryChannel", channel.name());
        var controlActions=result.putArray("allowedControlActions");
        if(actor.holds(BusinessRoleCode.MARKETPLACE_OPERATOR) && permitted(actor,ActionScopeCode.ADVERTISING_TASK_ACT,ResourceScope.store(scope.storeId()))) controlActions.add("EMERGENCY_ENTITY_HOLD");
        if(actor.holds(BusinessRoleCode.OPS_LEAD) && permitted(actor,ActionScopeCode.ADVERTISING_POLICY_MANAGE,ResourceScope.store(scope.storeId()))) controlActions.add("BUSINESS_STORE_STOP");
        if(actor.holds(BusinessRoleCode.TECH_DATA) && permitted(actor,ActionScopeCode.ADVERTISING_TECHNICAL_STOP,ResourceScope.store(scope.storeId()))) controlActions.add("TECHNICAL_STORE_STOP");
        result.put("productionWriteEnabled", false);
        return result;
    }

    @Override
    public boolean mayReadNativeRecommendation(AuthenticatedActor actor, UUID recommendationId) {
        return scopes.recommendationScope(actor.organizationId(), recommendationId)
                .map(reference -> actionScope(actor, reference, ActionScopeCode.ADVERTISING_VIEW)).orElse(false);
    }

    @Override
    public boolean mayReadNativeCommand(AuthenticatedActor actor,UUID commandId) {
        return scopes.commandScope(actor.organizationId(),commandId)
                .flatMap(reference->scopes.objectScope(actor.organizationId(),reference.objectId(),reference.affectedSetDigest()))
                .filter(scope->"COMPLETE".equals(scope.resolutionState()) && !scope.productVariantIds().isEmpty())
                .map(scope->permitted(actor,ActionScopeCode.ADVERTISING_VIEW,ResourceScope.store(scope.storeId()))
                        && scope.productVariantIds().stream().allMatch(product->permitted(actor,ActionScopeCode.ADVERTISING_VIEW,
                                ResourceScope.productVariant(product))))
                .orElse(false);
    }

    public void requireCommandRead(AuthenticatedActor actor,UUID commandId) {
        if(!mayReadNativeCommand(actor,commandId)) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
    }

    public boolean mayReadNativePacket(AuthenticatedActor actor,UUID packetId) {
        return scopes.packetScope(actor.organizationId(),packetId)
                .map(reference->actionScope(actor,reference,ActionScopeCode.ADVERTISING_VIEW)).orElse(false);
    }

    public void requirePacketRead(AuthenticatedActor actor,UUID packetId) {
        if(!mayReadNativePacket(actor,packetId)) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
    }

    public java.util.Optional<ObjectNode> reservation(AuthenticatedActor actor,
            com.mimococo.marketops.advertisingefficiency.AdvertisingReservationView view) {
        var reference=new AdvertisingDisclosureRepository.DecisionScope(view.adNativeObjectId(),view.affectedSetDigest());
        if(!actionScope(actor,reference,ActionScopeCode.ADVERTISING_VIEW)) return java.util.Optional.empty();
        ObjectNode result=mapper.valueToTree(view);
        boolean full=mayReadDecisionEvidence(actor,view.adNativeObjectId(),view.affectedSetDigest());
        if(!full) result.put("releaseReason","MASKED");
        result.put("disclosureState",full?"FULL":"MASKED");
        return java.util.Optional.of(result);
    }

    public ObjectNode command(AuthenticatedActor actor,com.mimococo.marketops.marketplaceintegration.AdBidCommandView view) {
        requireCommandRead(actor,view.id());
        ObjectNode raw=mapper.valueToTree(view);
        if(mayReadDecisionEvidence(actor,view.adNativeObjectId(),view.affectedSetDigest())) {
            raw.put("disclosureState","FULL"); return raw;
        }
        ObjectNode result=allow(raw,"id","recommendationId","storeId","adNativeObjectId","platformCode",
                "direction","state","currencyCode","bidUnitCode","priorBidAmount","targetBidAmount","affectedSetDigest",
                "attemptNo","retryBudgetRemaining","approvalExpiresAt","createdAt","updatedAt","terminalAt","readbacks");
        result.put("disclosureState","MASKED");
        var attempts=result.putArray("attempts");
        for(var attempt:view.attempts()) attempts.add(allow(mapper.valueToTree(attempt),"id","attemptNo","purpose","outcomeClass","startedAt","completedAt"));
        return result;
    }

    public ObjectNode manualPacket(AuthenticatedActor actor, ManualExecutionPacketView view) {
        requirePacketRead(actor,view.id());
        ObjectNode raw = mapper.valueToTree(view);
        boolean full = scopes.packetScope(actor.organizationId(), view.id()).map(scope ->
                mayReadDecisionEvidence(actor, scope.objectId(), scope.affectedSetDigest())).orElse(false);
        ObjectNode details = scopes.packetDetails(actor.organizationId(),view.id())
                .map(value -> (ObjectNode) mapper.readTree(value)).orElse(mapper.createObjectNode());
        if (full) {
            raw.set("packetDetails",details);
            raw.put("disclosureState","FULL");
            raw.set("allowedActions", mapper.valueToTree(manualActions(actor, view)));
            raw.put("configurationProven", view.configurationProven());
            return raw;
        }
        ObjectNode result = allow(raw, "id", "caseId", "adNativeObjectId", "actionKind", "blockerCodes",
                "state", "issuedAt", "expiresAt", "proposalId", "manualPolicyId", "executorUserId",
                "executionStartedAt", "reservationId", "currentProofId", "version");
        // The Maker needs the exact native action. Only explicitly named native
        // configuration fields cross this boundary; finance/impact JSON does not.
        result.set("packetDetails",allow(details,"organizationId","platformCode","storeId","marketplaceAccountId",
                "storeTimezone","affectedSetId","affectedSetDigest","affectedProductVariantIds","affectedListingVariantIds",
                "nativeObjectKind","nativeObjectKey","nativeCampaignKey","observedConfigurationId","observedConfiguration","verificationPlan"));
        result.put("intendedState", nativeIntendedState(view.intendedState()));
        result.put("reason", "MASKED");
        result.put("disclosureState", "MASKED");
        result.putArray("blockerCodes");
        result.set("allowedActions", mapper.valueToTree(manualActions(actor, view)));
        result.put("configurationProven", view.configurationProven());
        var verifications = result.putArray("verifications");
        for (var item : view.verifications()) {
            verifications.add(allow(mapper.valueToTree(item), "id", "evidenceGrade", "conflictState",
                    "provesConfiguration", "observedAt", "observedFieldPath", "observedValue"));
        }
        return result;
    }

    public List<String> manualActions(AuthenticatedActor actor, ManualExecutionPacketView view) {
        var reference = scopes.packetScope(actor.organizationId(), view.id()).orElse(null);
        if (reference == null || view.proposalId() == null) return List.of();
        if (List.of("MANUAL_PACKET_DRAFT","MANUAL_PACKET_ENDORSED","MANUAL_PACKET_ISSUED").contains(view.state())
                && (!view.expiresAt().isAfter(clock.instant())
                    || !scopes.manualAuthorityCurrent(actor.organizationId(),view.id()))) return List.of();
        List<String> actions = new java.util.ArrayList<>();
        boolean evidence = mayReadDecisionEvidence(actor, reference.objectId(), reference.affectedSetDigest());
        if ("MANUAL_PACKET_DRAFT".equals(view.state()) && evidence && !actor.userId().equals(view.makerUserId())
                && actionScope(actor, reference, ActionScopeCode.ADVERTISING_MANUAL_ENDORSE)) actions.add("ENDORSE");
        if ("MANUAL_PACKET_ENDORSED".equals(view.state()) && evidence && !actor.userId().equals(view.makerUserId())
                && !actor.userId().equals(view.endorserUserId())
                && actionScope(actor, reference, ActionScopeCode.ADVERTISING_MANUAL_APPROVE)) actions.add("APPROVE");
        if ("MANUAL_PACKET_ISSUED".equals(view.state())
                && actionScope(actor, reference, ActionScopeCode.ADVERTISING_MANUAL_EXECUTE)) actions.add("START");
        if ("MANUAL_EXECUTION_IN_PROGRESS".equals(view.state()) && actor.userId().equals(view.executorUserId())
                && actionScope(actor, reference, ActionScopeCode.ADVERTISING_MANUAL_EXECUTE)) actions.add("REPORT");
        if (List.of("MANUAL_EXECUTION_IN_PROGRESS", "ACTION_REPORTED_CONFIGURATION_UNVERIFIED",
                        "MANUAL_EXECUTION_UNCERTAIN", "MANUAL_CONFIGURATION_VERIFIED").contains(view.state())
                && !actor.userId().equals(view.executorUserId())
                && actionScope(actor, reference, ActionScopeCode.ADVERTISING_MANUAL_VERIFY)) {
            actions.add("INDEPENDENT_VERIFY"); actions.add("OFFICIAL_VERIFY");
            if(evidence && view.configurationProven()) actions.add("OBSERVE_EARLY_SAFETY");
        }
        return List.copyOf(actions);
    }

    private boolean actionScope(AuthenticatedActor actor, AdvertisingDisclosureRepository.DecisionScope reference,
            ActionScopeCode action) {
        return scopes.objectScope(actor.organizationId(), reference.objectId(), reference.affectedSetDigest())
                .filter(scope -> "COMPLETE".equals(scope.resolutionState()) && !scope.productVariantIds().isEmpty())
                .map(scope -> permitted(actor, action, ResourceScope.store(scope.storeId()))
                        && scope.productVariantIds().stream().allMatch(id -> permitted(actor, action,
                                ResourceScope.productVariant(id)))).orElse(false);
    }

    public ObjectNode outcome(AuthenticatedActor actor, AdvertisingOutcomeView view) {
        boolean full = (view.commandId() != null ? scopes.commandScope(actor.organizationId(), view.commandId())
                : scopes.packetScope(actor.organizationId(),view.manualPacketId()))
                .map(scope -> mayReadDecisionEvidence(actor, scope.objectId(), scope.affectedSetDigest())).orElse(false);
        ObjectNode raw = mapper.valueToTree(view);
        if (full) return raw;
        ObjectNode result = allow(raw, "id", "commandId", "manualPacketId", "outcomeStage", "revisionNo",
                "supersedesObservationId", "windowStartsAt", "windowEndsAt", "evaluatedAt");
        result.put("verdict", "MASKED");
        result.put("guardState", "MASKED");
        result.putArray("unresolvedReasonCodes");
        result.put("baselineMetricState", "MASKED");
        result.put("observedMetricState", "MASKED");
        result.put("disclosureState", "MASKED");
        return result;
    }

    public List<ObjectNode> containments(AuthenticatedActor actor, List<UUID> stores,
            List<AdvertisingContainment> views) {
        Set<UUID> visible = Set.copyOf(scopes.relevantContainmentIds(actor.organizationId(), stores));
        boolean full = organizationEvidence(actor);
        return views.stream().filter(view -> visible.contains(view.id())).map(view -> {
            ObjectNode raw = mapper.valueToTree(view);
            ObjectNode result = full ? raw : allow(raw, "id", "containmentKind", "scopeKind",
                    "activatedAt", "state", "outstandingConditions", "reenabledAt", "holding");
            if(!full) { result.put("causeClass","MASKED"); result.put("reason","MASKED"); result.put("disclosureState","MASKED"); }
            var actions=result.putArray("allowedActions");
            var attested=scopes.containmentAttestations(view.id());
            result.set("attestedConditions",mapper.valueToTree(attested));
            result.set("outstandingConditions",mapper.valueToTree(view.outstandingConditions().stream()
                    .filter(condition->!attested.contains(condition)).toList()));
            scopes.containmentStore(actor.organizationId(),view.id()).ifPresent(store->{
                if(!view.holding()) return;
                if(actor.holds(BusinessRoleCode.OPS_LEAD) && permitted(actor,ActionScopeCode.ADVERTISING_POLICY_MANAGE,ResourceScope.store(store))) {
                    for(String condition:List.of("ROOT_CAUSE_CLASSIFIED","UNKNOWNS_RESOLVED","AUTHORITIES_REPLACED","RESULTS_RECONCILED","CAPABILITY_EVIDENCE_CURRENT"))
                        if(!attested.contains(condition)) actions.add("ATTEST_"+condition);
                    if(!actor.userId().equals(view.activatedByUserId()) && !attested.contains("OPERATIONS_ENDORSEMENT")) actions.add("ATTEST_OPERATIONS_ENDORSEMENT");
                }
                if(actor.holds(BusinessRoleCode.TECH_DATA) && permitted(actor,ActionScopeCode.ADVERTISING_TECHNICAL_ATTEST,ResourceScope.store(store))
                        && !attested.contains("SECURITY_ATTESTATION_PRESENT")) actions.add("ATTEST_SECURITY_ATTESTATION_PRESENT");
                if(actor.holds(BusinessRoleCode.OWNER) && permitted(actor,ActionScopeCode.ADVERTISING_POLICY_MANAGE,ResourceScope.store(store))
                        && !actor.userId().equals(view.activatedByUserId()) && !actor.userId().equals(view.endorsedByUserId())
                        && attested.containsAll(List.of("ROOT_CAUSE_CLASSIFIED","UNKNOWNS_RESOLVED","AUTHORITIES_REPLACED","RESULTS_RECONCILED","CAPABILITY_EVIDENCE_CURRENT","OPERATIONS_ENDORSEMENT"))) actions.add("REENABLE");
            });
            return result;
        }).toList();
    }

    public ObjectNode brief(AuthenticatedActor actor, AdvertisingBriefView view) {
        boolean organization = organizationView(actor);
        boolean full = organization && organizationEvidence(actor);
        List<UUID> stores = authorization.permittedStoreIds(actor, ActionScopeCode.ADVERTISING_VIEW);
        Set<UUID> visible = Set.copyOf(scopes.visibleBriefReferences(
                actor.organizationId(), view.id(), stores, organization));
        ObjectNode raw = mapper.valueToTree(view);
        ObjectNode result = full ? raw : allow(raw, "id", "briefKind", "periodKey", "periodStartsAt",
                "periodEndsAt", "asOf", "cursorPositionAt", "revisionNo", "revisionKind",
                "supersedesPublicationId", "publishedAt");
        if (!full) {
            result.put("disclosureState", "MASKED");
            result.putArray("gapCodes");
            result.put("contentDigest", "MASKED");
        }
        var sections = result.putArray("sections");
        for (var section : view.sections()) {
            ObjectNode row = sections.addObject();
            row.put("sectionCode", section.sectionCode()).put("ordinal", section.ordinal());
            var items = row.putArray("items");
            for (var item : section.items()) {
                if (!visible.contains(item.referenceId())) continue;
                ObjectNode itemRaw = mapper.valueToTree(item);
                ObjectNode projected = full ? itemRaw : allow(itemRaw, "subjectKind", "referenceId",
                        "lane", "observedAt");
                if (!full) {
                    projected.put("causeCode", "MASKED");
                    projected.put("evidenceState", "MASKED");
                    projected.put("valueState", "MASKED");
                    projected.putArray("blockerCodes");
                    projected.put("disclosureState", "MASKED");
                }
                items.add(projected);
            }
            row.put("itemCount", items.size());
            row.put("coverageState", full ? section.coverageState() : "MASKED");
            row.set("blockerCodes", mapper.valueToTree(full ? section.blockerCodes()
                    : List.of()));
            if (full) row.put("summaryNote", section.summaryNote());
            row.put("complete", full && section.complete());
        }
        result.put("fullyCovered", full && view.fullyCovered());
        return result;
    }

    public ObjectNode maskedExposure() {
        ObjectNode result = mapper.createObjectNode();
        result.put("disclosureState", "MASKED");
        result.put("status", "MASKED");
        result.put("resolved", false);
        result.putArray("exhaustedAxes").add("DISCLOSURE_SCOPE_MASKED");
        return result;
    }

    public ObjectNode full(Object value) { return mapper.valueToTree(value); }

    private String nativeIntendedState(String value) {
        if (value == null) return "UNRESOLVED";
        try {
            JsonNode parsed = mapper.readTree(value);
            if (!(parsed instanceof ObjectNode object)) return "UNRESOLVED";
            return allow(object, "currentBid", "targetBid", "currentBudget", "targetBudget", "currentStatus", "targetStatus",
                    "bidAmount", "bid_amount", "targetBidAmount", "target_bid_amount",
                    "budgetAmount", "budget_amount", "status", "enabled", "currencyCode", "currency",
                    "bidUnitCode", "unitCode", "unit").toString();
        } catch (RuntimeException invalid) {
            return "UNRESOLVED";
        }
    }

    private ObjectNode allow(ObjectNode source, String... fields) {
        ObjectNode result = mapper.createObjectNode();
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null) result.set(field, value.deepCopy());
        }
        return result;
    }
}
