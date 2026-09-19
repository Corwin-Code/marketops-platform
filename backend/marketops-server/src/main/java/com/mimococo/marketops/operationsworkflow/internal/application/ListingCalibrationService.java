package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.*;
import com.mimococo.marketops.identityaccess.*;
import com.mimococo.marketops.shared.*;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exact calibration lifecycle inside the sole Policy boundary; no direct table-writing privilege. */
@Service
public class ListingCalibrationService {
    private final JdbcClient jdbc;
    private final BusinessAuthorization authorization;
    private final AuthenticatedInvocationIssuer issuer;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final Clock clock;
    private final MetadataAuditRecorder audit;

    ListingCalibrationService(JdbcClient jdbc, BusinessAuthorization authorization, AuthenticatedInvocationIssuer issuer,
                              ObjectMapper json, IdGenerator ids, Clock clock, MetadataAuditRecorder audit) {
        this.jdbc=jdbc; this.authorization=authorization; this.issuer=issuer; this.json=json;
        this.ids=ids; this.clock=clock; this.audit=audit;
    }

    @Transactional
    public JsonNode prepare(AuthenticatedActor actor,JsonNode draft) {
        requireDraft(draft);
        UUID store=draft.path("storeId").isTextual() ? UUID.fromString(draft.path("storeId").asText()):null;
        ResourceScope scope="STORE".equals(draft.path("scopeKind").asText())
                ? ResourceScope.store(store):ResourceScope.organization(actor.organizationId());
        authorization.require(actor,ActionScopeCode.LISTING_CALIBRATION_PREPARE,scope);
        requireStepUp(actor);
        UUID id=ids.newId();
        String proof=proof("LISTING_CALIBRATION_PREPARE",id);
        jdbc.sql("SELECT ops.prepare_lc_calibration(:id,:proof,CAST(:body AS jsonb))")
                .param("id",id).param("proof",proof).param("body",json.writeValueAsString(draft)).query(UUID.class).single();
        record(actor,id,"DRAFTED");
        return view(actor,id);
    }

    @Transactional
    public JsonNode validate(AuthenticatedActor actor,UUID id,String digest,String reference) {
        return advance(actor,id,digest,reference,"validate_lc_calibration","LISTING_CALIBRATION_VALIDATE",ActionScopeCode.LISTING_CALIBRATION_VALIDATE);
    }

    @Transactional
    public JsonNode accept(AuthenticatedActor actor,UUID id,String digest,String reference) {
        return advance(actor,id,digest,reference,"accept_lc_calibration","LISTING_CALIBRATION_ACCEPT",ActionScopeCode.LISTING_CALIBRATION_ACCEPT);
    }

    @Transactional
    public JsonNode activate(AuthenticatedActor actor,UUID id,String digest,String reference) {
        return advance(actor,id,digest,reference,"activate_lc_calibration","LISTING_CALIBRATION_ACTIVATE",ActionScopeCode.LISTING_CALIBRATION_ACCEPT);
    }

    private JsonNode advance(AuthenticatedActor actor,UUID id,String digest,String reference,
                             String function,String purpose,ActionScopeCode action) {
        authorization.require(actor,action,scope(actor,id));
        requireStepUp(actor);
        if (digest==null || !digest.matches("[0-9a-f]{64}")) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        reference=MetadataFieldPolicy.requireText("evidenceReference",reference);
        String proof=proof(purpose,id);
        // Function identifiers are private constants, never request parameters.
        jdbc.sql("SELECT ops."+function+"(:id,:proof,:digest,:reference)")
                .param("id",id).param("proof",proof).param("digest",digest).param("reference",reference)
                .query(Object.class).optional();
        record(actor,id,purpose);
        return view(actor,id);
    }

    @Transactional
    public JsonNode view(AuthenticatedActor actor,UUID id) {
        ResourceScope scope=scope(actor,id);
        boolean visible=List.of(ActionScopeCode.LISTING_CALIBRATION_PREPARE,ActionScopeCode.LISTING_CALIBRATION_VALIDATE,
                ActionScopeCode.LISTING_CALIBRATION_ACCEPT).stream().anyMatch(a -> authorization.evaluate(actor,a,scope).permitted());
        if (!visible) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        String body=jdbc.sql("""
                SELECT jsonb_build_object('package',to_jsonb(p),'governance',to_jsonb(g),
                 'requiredCategories',to_jsonb(core.lc_calibration_required_categories(p.purpose_code)),
                 'combinationFailures',to_jsonb(ops.lc_calibration_combination_failures(p.id)),
                 'values',(SELECT jsonb_agg(to_jsonb(v) ORDER BY v.category_code) FROM core.lc_calibration_value v WHERE v.package_id=p.id),
                 'events',(SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY e.occurred_at,e.id),'[]'::jsonb)
                   FROM ops.lc_calibration_event e WHERE e.package_id=p.id))::text
                FROM core.lc_calibration_package p JOIN ops.lc_calibration_governance g ON g.package_id=p.id WHERE p.id=:id
                """).param("id",id).query(String.class).single();
        record(actor,id,"READ");
        return json.readTree(body);
    }

    private ResourceScope scope(AuthenticatedActor actor,UUID id) {
        return jdbc.sql("SELECT organization_id,scope_kind,store_ref_id FROM core.lc_calibration_package WHERE id=:id")
                .param("id",id).query((rs,n) -> {
                    UUID org=rs.getObject("organization_id",UUID.class);
                    if (!actor.organizationId().equals(org)) throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
                    return "STORE".equals(rs.getString("scope_kind")) ? ResourceScope.store(rs.getObject("store_ref_id",UUID.class))
                            :ResourceScope.organization(org);
                }).optional().orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireStepUp(AuthenticatedActor actor) {
        if (!actor.stepUpSatisfiedAt(clock.instant())) throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
    }

    private String proof(String purpose,UUID id) {
        long[] context=jdbc.sql("SELECT pg_backend_pid(),txid_current()")
                .query((rs,n) -> new long[]{rs.getInt(1),rs.getLong(2)}).single();
        return issuer.issueControl(purpose,id,id,Math.toIntExact(context[0]),context[1]);
    }

    private void record(AuthenticatedActor actor,UUID id,String event) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,actor.userId().toString(),
                "READ".equals(event) ? AuditAction.READ:AuditAction.POLICY_CHANGE,"lc-calibration",id,null,
                Map.of("event",new FieldChange(null,event)),null,null));
    }

    private void requireDraft(JsonNode draft) {
        if (draft==null || !draft.isObject()) throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        SecretMaterialGuard.requireNonSecret("calibration",json.writeValueAsString(draft));
        MetadataFieldPolicy.requireRegistryCode(draft.path("code").asText());
        for (String field:List.of("purposeCode","scopeKind","evidenceReference","rationale","impact","differences","effectiveFrom")) {
            MetadataFieldPolicy.requireText(field,draft.path(field).asText());
        }
        if (!List.of("LISTING_CONVERSION","DESCRIPTION_CORRECTION","BOUNDED_EXPLORATION","PROMOTION")
                    .contains(draft.path("purposeCode").asText())
                || !List.of("ORGANIZATION","PLATFORM","STORE").contains(draft.path("scopeKind").asText())
                || !draft.path("version").isIntegralNumber() || !draft.path("version").canConvertToInt() || draft.path("version").asInt()<1
                || !draft.path("values").isArray() || draft.path("values").isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if ("STORE".equals(draft.path("scopeKind").asText()) && !draft.path("storeId").isTextual()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        for (JsonNode value:draft.path("values")) {
            for (String field:List.of("categoryCode","unitCode","scopeNote","evidenceReference")) {
                MetadataFieldPolicy.requireText(field,value.path(field).asText());
            }
        }
    }
}
