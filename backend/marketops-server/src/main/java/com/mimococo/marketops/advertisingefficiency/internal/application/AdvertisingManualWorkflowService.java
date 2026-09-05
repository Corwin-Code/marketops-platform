package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingManualPacketRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingManualWorkflowRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingManualWorkflowRepository.Scope;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.AuthenticatedInvocationIssuer;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import com.mimococo.marketops.operationsworkflow.AdvertisingManualTaskJournal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A separately governed human workflow. This service has no command, outbox or Provider port. */
@Service
@Transactional
public class AdvertisingManualWorkflowService {
    private final AdvertisingManualWorkflowRepository workflow;
    private final AdvertisingManualPacketRepository packets;
    private final BusinessAuthorization authorization;
    private final AdvertisingDisclosureService disclosure;
    private final AuthenticatedInvocationIssuer issuer;
    private final ObjectMapper mapper;
    private final AdvertisingManualTaskJournal journal;
    private final com.mimococo.marketops.operationsworkflow.AdvertisingOutcomePlanning outcomes;
    private final java.time.Clock clock;
    private final com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository observations;

    public AdvertisingManualWorkflowService(AdvertisingManualWorkflowRepository workflow,
            AdvertisingManualPacketRepository packets, BusinessAuthorization authorization,
            AdvertisingDisclosureService disclosure, AuthenticatedInvocationIssuer issuer, ObjectMapper mapper, AdvertisingManualTaskJournal journal,
            com.mimococo.marketops.operationsworkflow.AdvertisingOutcomePlanning outcomes, java.time.Clock clock,
            com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository observations) {
        this.workflow=workflow; this.packets=packets; this.authorization=authorization;
        this.disclosure=disclosure; this.issuer=issuer; this.mapper=mapper; this.journal=journal; this.outcomes=outcomes; this.clock=clock; this.observations=observations;
    }

    public ObjectNode options(AuthenticatedActor actor, UUID caseId) {
        Scope scope=caseScope(actor,caseId);
        ObjectNode result=mapper.createObjectNode();
        result.put("caseId",caseId.toString()); result.put("productionWriteEnabled",false);
        // Diagnostic Case visibility does not grant access to native action proposals.
        var visibleOptions=permitted(actor,scope,ActionScopeCode.ADVERTISING_VIEW)
                ? workflow.options(caseId) : List.<AdvertisingManualWorkflowRepository.Option>of();
        result.set("options",mapper.valueToTree(visibleOptions));
        result.set("allowedActions",mapper.valueToTree(!visibleOptions.isEmpty()
                && permitted(actor,scope,ActionScopeCode.ADVERTISING_TASK_ACT)
                ? List.of("SELECT_MANUAL_PROPOSAL") : List.of()));
        return result;
    }

    public ObjectNode select(AuthenticatedActor actor, UUID caseId, UUID policyId, UUID candidateId, String reason) {
        Scope scope=caseScope(actor,caseId); require(actor,scope,ActionScopeCode.ADVERTISING_TASK_ACT);
        UUID proposal=workflow.generate(UUID.randomUUID(),caseId,policyId,candidateId);
        UUID packet=UUID.randomUUID();
        UUID baseline=outcomes.prepareManual(actor.organizationId(),proposal,clock.instant());
        workflow.select(packet,proposal,baseline,MetadataFieldPolicy.requireText("reason",reason),
                proof("MANUAL_PACKET_SELECT",proposal,packet));
        return packet(actor,packet);
    }

    public ObjectNode decide(AuthenticatedActor actor, UUID packet, long version, boolean approve) {
        Scope scope=packetScope(actor,packet);
        require(actor,scope,approve?ActionScopeCode.ADVERTISING_MANUAL_APPROVE:ActionScopeCode.ADVERTISING_MANUAL_ENDORSE);
        disclosure.requireDecisionEvidence(actor,scope.objectId(),scope.digest());
        workflow.decide(packet,version,approve,proof(approve?"MANUAL_PACKET_APPROVE":"MANUAL_PACKET_ENDORSE",packet,packet));
        if(approve) journal.recordManualAction(actor,packet,packet,"MANUAL_PACKET_ISSUED","Owner approved the canonical manual packet");
        return packet(actor,packet);
    }

    public ObjectNode start(AuthenticatedActor actor, UUID packet, long version) {
        require(actor,packetScope(actor,packet),ActionScopeCode.ADVERTISING_MANUAL_EXECUTE);
        workflow.start(packet,version,proof("MANUAL_EXECUTION_START",packet,packet));
        return packet(actor,packet);
    }

    public ObjectNode report(AuthenticatedActor actor, UUID packet, long version) {
        return observe(actor,packet,version,"REPORT",null,null);
    }

    public ObjectNode independent(AuthenticatedActor actor, UUID packet, long version, String value) {
        return observe(actor,packet,version,"INDEPENDENT",MetadataFieldPolicy.requireText("observedValue",value),null);
    }

    public ObjectNode official(AuthenticatedActor actor, UUID packet, long version, UUID configuration) {
        if(configuration==null) throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        return observe(actor,packet,version,"OFFICIAL",null,configuration);
    }

    private ObjectNode observe(AuthenticatedActor actor, UUID packet, long version, String kind, String value, UUID configuration) {
        boolean report="REPORT".equals(kind);
        require(actor,packetScope(actor,packet),report?ActionScopeCode.ADVERTISING_MANUAL_EXECUTE:ActionScopeCode.ADVERTISING_MANUAL_VERIFY);
        UUID verification=workflow.observe(UUID.randomUUID(),packet,version,kind,value,configuration,
                proof(report?"MANUAL_EXECUTION_REPORT":"MANUAL_INDEPENDENT_VERIFY",packet,packet));
        if(!report && packets.packet(packet).map(view->view.configurationProven()).orElse(false))
            journal.recordManualAction(actor,packet,verification,"MANUAL_EXECUTION_VERIFIED","Current configuration verified against the exact manual packet");
        return packet(actor,packet);
    }

    public List<ObjectNode> outcomes(AuthenticatedActor actor, UUID packetId) {
        disclosure.requirePacketRead(actor,packetId);
        Scope scope=packetScope(actor,packetId);
        return observations.forManualPacket(actor.organizationId(),packetId,List.of(scope.storeId())).stream()
                .map(view->disclosure.outcome(actor,view)).toList();
    }

    public ObjectNode observeEarlySafety(AuthenticatedActor actor, UUID packet) {
        Scope scope=packetScope(actor,packet);
        require(actor,scope,ActionScopeCode.ADVERTISING_MANUAL_VERIFY);
        disclosure.requireDecisionEvidence(actor,scope.objectId(),scope.digest());
        UUID observation=outcomes.observeManual(actor.organizationId(),packet,clock.instant());
        ObjectNode response=packet(actor,packet); response.put("earlyObservationId",observation.toString()); return response;
    }

    public record Policy(UUID storeId, UUID semanticProfileId, int policyVersion, String causeCode,
            String actionKind, String candidateBasis, BigDecimal targetBudget, String targetStatus,
            String currencyCode, String verificationMode, int configurationMaxAgeSeconds,
            int packetLeaseSeconds, UUID outcomePolicyId, Instant effectiveFrom, Instant effectiveTo, String evidenceReference) { }

    public UUID publish(AuthenticatedActor actor, Policy request) {
        authorization.require(actor,ActionScopeCode.ADVERTISING_POLICY_MANAGE,ResourceScope.store(request.storeId()));
        UUID id=UUID.randomUUID(); ObjectNode content=mapper.createObjectNode();
        content.put("id",id.toString()); content.put("organization_id",actor.organizationId().toString());
        content.put("store_id",request.storeId().toString()); content.put("semantic_profile_id",request.semanticProfileId().toString());
        content.put("outcome_policy_id",request.outcomePolicyId().toString());
        content.put("policy_version",request.policyVersion()); content.put("cause_code",request.causeCode());
        content.put("action_kind",request.actionKind()); content.put("candidate_basis",request.candidateBasis());
        content.put("target_budget",request.targetBudget()); content.put("target_status",request.targetStatus());
        content.put("currency_code",request.currencyCode()); content.put("verification_mode",request.verificationMode());
        content.put("configuration_max_age_seconds",request.configurationMaxAgeSeconds());
        content.put("packet_lease_seconds",request.packetLeaseSeconds());
        content.put("effective_from",request.effectiveFrom().toString()); content.put("effective_to",request.effectiveTo().toString());
        content.put("evidence_reference",MetadataFieldPolicy.requireText("evidenceReference",request.evidenceReference()));
        return workflow.publish(content.toString(),proof("MANUAL_POLICY_PUBLISH",id,request.storeId()));
    }

    private ObjectNode packet(AuthenticatedActor actor, UUID id) {
        return disclosure.manualPacket(actor,packets.packet(id).orElseThrow(
                ()->OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED)));
    }
    private Scope caseScope(AuthenticatedActor actor, UUID id) {
        return readable(actor,workflow.caseScope(id).orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED)));
    }
    private Scope packetScope(AuthenticatedActor actor, UUID id) {
        return readable(actor,workflow.packetScope(id).orElseThrow(()->OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED)));
    }
    private Scope readable(AuthenticatedActor actor, Scope scope) {
        if(!actor.organizationId().equals(scope.organizationId())) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        authorization.require(actor,ActionScopeCode.ADVERTISING_VIEW,ResourceScope.store(scope.storeId())); return scope;
    }
    private void require(AuthenticatedActor actor,Scope scope,ActionScopeCode action) {
        if(scope.variants().isEmpty()) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        authorization.require(actor,action,ResourceScope.store(scope.storeId()));
        scope.variants().forEach(id->authorization.require(actor,action,ResourceScope.productVariant(id)));
    }
    private boolean permitted(AuthenticatedActor actor,Scope scope,ActionScopeCode action) {
        return !scope.variants().isEmpty() && authorization.evaluate(actor,action,ResourceScope.store(scope.storeId())).permitted()
            && scope.variants().stream().allMatch(id->authorization.evaluate(actor,action,ResourceScope.productVariant(id)).permitted());
    }
    private String proof(String purpose,UUID target,UUID version) {
        var transaction=workflow.transaction();
        return issuer.issueControl(purpose,target,version,transaction.backendPid(),transaction.transactionId());
    }
}
