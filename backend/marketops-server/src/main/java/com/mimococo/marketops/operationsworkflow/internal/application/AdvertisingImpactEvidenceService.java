package com.mimococo.marketops.operationsworkflow.internal.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads published facts and the frozen outcome plan; it never estimates a second profit metric. */
@Service
public class AdvertisingImpactEvidenceService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    AdvertisingImpactEvidenceService(JdbcClient jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }

    public JsonNode capture(UUID recommendationId,Instant at,UUID bundleId) {
        return jdbc.sql("""
                SELECT jsonb_build_object('evidenceState','CANONICAL_SNAPSHOT','asOf',CAST(:at AS timestamptz),
                    'risk',ops.ad_exception_risk_snapshot(c.id),
                    'submittedConfiguration',jsonb_build_object('current',cd.current_bid_amount,
                        'target',cd.provider_normalized_amount,'currency',cd.currency_code,'unit',cd.bid_unit_code,
                        'providerStep',sp.bid_step,'providerPrecision',sp.bid_precision,
                        'providerMinimum',sp.bid_minimum,'providerMaximum',sp.bid_maximum,
                        'semanticProfileId',sp.id,'semanticProfileVersion',sp.profile_version,
                        'providerVerificationState',sp.verification_state,
                        'direction',cd.direction,'basis',cd.candidate_basis),
                    'economicMaxCpc',jsonb_build_object('state',c.max_cpc_state,'amount',c.max_cpc_amount,
                        'currency',c.profit_currency_code,'unit','CURRENCY_MAJOR'),
                    'submittedUnitMaxCpc',jsonb_build_object('state',c.max_cpc_state,'amount',cd.max_cpc_amount,
                        'currency',cd.currency_code,'unit',cd.bid_unit_code),
                    'expectedEffect',r.expected_effect,
                    'recoveryState',CASE WHEN cd.candidate_basis='CAUSE_BOUND_PROTECTION_STEP'
                        THEN 'EXPOSURE_LIMIT_ONLY_NOT_PROFITABILITY_OR_HEALTH'
                        WHEN cd.direction='PROTECTION_DECREASE' AND cd.provider_normalized_amount>cd.max_cpc_amount
                        THEN 'RECOVERY_IN_PROGRESS_NOT_HEALTHY' ELSE 'OUTCOME_VERIFICATION_REQUIRED' END,
                    'policyVersions',ops.ad_bundle_authority_snapshot(CAST(:bundle AS uuid)),
                    'materiality',ops.ad_materiality_assessment(CAST(:bundle AS uuid),cd.id),
                    'metricEvidence',coalesce((SELECT jsonb_agg(jsonb_build_object('evidenceId',e.id,
                        'role',e.evidence_role,'metric',to_jsonb(m)) ORDER BY e.id)
                        FROM mart.ad_case_evidence e JOIN mart.metric_value m ON m.id=e.metric_value_id
                        WHERE e.case_id=c.id AND e.calculation_id=c.calculation_id),'[]'::jsonb),
                    'qualificationPeriods',coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.period_start,q.period_end)
                        FROM mart.ad_qualification_period q WHERE q.ad_native_object_id=c.ad_native_object_id
                            AND q.qualification_policy_id=(SELECT qualification_policy_id FROM ops.ad_decision_policy_bundle WHERE id=CAST(:bundle AS uuid))
                            AND q.period_end<=:at),'[]'::jsonb),
                    'frozenOutcomePlan',CASE WHEN baseline.id IS NULL THEN jsonb_build_object('state','NOT_AVAILABLE')
                        ELSE jsonb_build_object('id',baseline.id,'state',baseline.state,'blockers',baseline.blocker_codes,
                            'validUntil',baseline.valid_until,'policyId',baseline.outcome_policy_id,
                            'policyVersion',baseline.outcome_policy_version,'plan',baseline.plan_snapshot,
                            'stages',(SELECT jsonb_agg(jsonb_build_object('stage',b.stage,'windowHours',b.window_hours,
                                'baseline',b.snapshot) ORDER BY b.stage) FROM ops.ad_outcome_stage_baseline b WHERE b.outcome_baseline_id=baseline.id),
                            'criticalSalesUnits',coalesce((SELECT jsonb_agg(jsonb_build_object('productVariantId',u.product_variant_id,
                                'listingVariantId',u.listing_variant_id,'ruleId',u.rule_id) ORDER BY u.product_variant_id,u.listing_variant_id)
                                FROM ops.ad_outcome_critical_unit u WHERE u.outcome_baseline_id=baseline.id),'[]'::jsonb)) END,
                    'aggregateExposure',jsonb_build_object('measurement',ops.ad_exposure_snapshot(c.organization_id,c.store_id,cd.direction),
                        'failingAxes',ops.ad_exposure_failures(c.organization_id,c.store_id,cd.direction),
                        'activeInterventions',(SELECT count(*) FROM ops.ad_action_reservation x WHERE x.organization_id=c.organization_id AND x.state='ACTIVE'),
                        'reservations',coalesce((SELECT jsonb_agg(jsonb_build_object('id',x.id,'storeId',x.store_id,
                            'objectId',x.ad_native_object_id,'affectedSetDigest',x.affected_set_digest,'kind',x.intervention_kind,
                            'direction',x.direction,'state',x.state,'unknownOrMismatchOpen',x.unknown_or_mismatch_open,
                            'configurationResolved',x.configuration_resolved,'earlyObservationComplete',x.early_observation_complete,
                            'regressionOpen',x.regression_open) ORDER BY x.id)
                            FROM ops.ad_action_reservation x WHERE x.organization_id=c.organization_id AND x.state='ACTIVE'),'[]'::jsonb)),
                    'alternatives',coalesce((SELECT jsonb_agg(jsonb_build_object('candidateId',other.id,
                        'basis',other.candidate_basis,'target',other.provider_normalized_amount,'unit',other.bid_unit_code,
                        'state',alternative.state,'selected',EXISTS(SELECT 1 FROM ops.ad_candidate_selection selection WHERE selection.recommendation_id=alternative.id),'blockerCodes',c.blocker_codes) ORDER BY other.ordinal,other.id) FROM ops.ad_bid_candidate other
                        JOIN ops.recommendation alternative ON alternative.proposed_parameters->>'candidateId'=other.id::text
                          AND alternative.action_kind='AD_BID_CHANGE'
                        WHERE other.case_id=c.id AND other.id<>cd.id),'[]'::jsonb),
                    'uncertainty',jsonb_build_object('evidenceState',c.evidence_state,'confidence',c.confidence_state,
                        'blockers',c.blocker_codes,'attributionGapState',c.attribution_gap_state,'attributionGap',c.attribution_gap_ratio))::text
                FROM ops.recommendation r JOIN ops.ad_bid_candidate cd ON cd.id=(r.proposed_parameters->>'candidateId')::uuid
                JOIN mart.ad_case c ON c.id=cd.case_id JOIN platform.ad_semantic_profile sp ON sp.id=cd.semantic_profile_id
                LEFT JOIN ops.ad_candidate_selection selected ON selected.recommendation_id=r.id
                LEFT JOIN LATERAL(SELECT b.* FROM ops.ad_outcome_baseline b WHERE b.candidate_id=cd.id
                    AND (selected.recommendation_id IS NULL OR b.id=selected.outcome_baseline_id)
                    AND b.case_calculation_id=c.calculation_id AND b.policy_version_digest=c.policy_version_digest
                    AND b.prepared_at<=:at AND b.valid_until>:at ORDER BY b.prepared_at DESC,b.id DESC LIMIT 1) baseline ON true
                WHERE r.id=:recommendation AND r.action_kind='AD_BID_CHANGE'
                """).param("recommendation",recommendationId).param("bundle",bundleId).param("at",Timestamp.from(at)).query(String.class)
                .optional().map(json::readTree).orElseGet(()->json.createObjectNode().put("evidenceState","NOT_AVAILABLE"));
    }

    public void record(UUID evaluationId,UUID recommendationId,JsonNode evidence,Instant at) {
        jdbc.sql("INSERT INTO ops.ad_impact_preview_evidence(evaluation_id,recommendation_id,evidence,recorded_at) VALUES(:evaluation,:recommendation,CAST(:evidence AS jsonb),:at)")
                .param("evaluation",evaluationId).param("recommendation",recommendationId)
                .param("evidence",json.writeValueAsString(evidence)).param("at",Timestamp.from(at)).update();
    }
}
