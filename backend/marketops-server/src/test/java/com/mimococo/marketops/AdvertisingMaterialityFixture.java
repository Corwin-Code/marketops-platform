package com.mimococo.marketops;

import java.util.function.UnaryOperator;
import javax.sql.DataSource;

/** Fictional ordinary-policy preconditions. No human decision or baseline is seeded. */
final class AdvertisingMaterialityFixture {
    private AdvertisingMaterialityFixture() { }
    static AdvertisingR1Fixture.Graph seedUnapproved(DataSource migration) throws Exception {
        return AdvertisingR1Fixture.seedUnapproved(migration,ordinarySql());
    }
    static UnaryOperator<String> ordinarySql() {
        return original -> {
            var filtered=new StringBuilder();
            for(String statement:original.replaceAll("(?m)^\\s*--.*$", "").split(";")) {
                if(statement.isBlank()) continue;
                if(statement.contains("INSERT INTO ops.ad_outcome_baseline")
                        ||statement.contains("INSERT INTO ops.ad_outcome_stage_baseline")
                        ||statement.contains("INSERT INTO ops.ad_outcome_critical_unit")
                        ||statement.contains("INSERT INTO core.ad_outcome_critical_unit_rule")
                        ||statement.contains("INSERT INTO ops.ad_candidate_selection")
                        ||statement.contains("INSERT INTO ops.ad_candidate_endorsement")
                        ||statement.contains("INSERT INTO ops.guardrail_evaluation")
                        ||statement.contains("INSERT INTO ops.approval_decision")
                        ||statement.contains("UPDATE ops.recommendation SET state='APPROVED'")
                        ||statement.contains("SELECT ops.take_ad_action_reservation")) continue;
                if(statement.contains("INSERT INTO ops.ad_decision_policy_bundle")) {
                    // Freeze the promotion reference at Bundle creation. Its reciprocal
                    // reference is completed in this same fixture transaction.
                    statement=statement.replace("gate_scope_reference, validation_state",
                                    "ordinary_promotion_id, gate_scope_reference, validation_state")
                            .replace("'342cf264-3eb4-5105-b854-3e25ee3aa2ea', 'VALIDATED'",
                                    "'1590af1e-26b8-48a6-98d3-7de7620b2e2c', '342cf264-3eb4-5105-b854-3e25ee3aa2ea', 'VALIDATED'");
                }
                if(statement.contains("UPDATE ops.ad_decision_policy_bundle SET gate_authority_id=")) {
                    filtered.append("""
                        UPDATE core.ad_materiality_policy SET owner_user_id='9264ceb0-c29a-5837-9339-c84bfe73a444',ordinary_nonzero_envelope_amount=20,
                          ordinary_relative_envelope_ratio=0.4,material_absolute_change_amount=50,
                          material_relative_change_ratio=0.5,material_spend_exposure_amount=1000000,
                          material_affected_variant_count=100,material_critical_sales_amount=1000000,
                          material_cumulative_change_amount=1000,material_cumulative_window_hours=24
                        WHERE id='f5b0a314-35c2-501b-a542-7506f943a465';
                        INSERT INTO ops.commercial_policy(id,organization_id,policy_code,policy_version,scope_kind,
                          lifecycle_objective,currency_code,effective_from,status,published_by_user_id,reason,created_at,updated_at)
                        VALUES('52b86045-eabe-4408-b124-2cb207904301','8689c119-8fa0-50b7-8ba2-f9bf3039d336',
                          'fictional-ordinary-lifecycle',1,'ORGANIZATION','MATURE','RUB',now()-interval '1 day','ACTIVE',
                          '9264ceb0-c29a-5837-9339-c84bfe73a444','Fictional Owner lifecycle policy',now(),now());
                        INSERT INTO ops.ad_gate_authority SELECT (jsonb_populate_record(NULL::ops.ad_gate_authority,
                          to_jsonb(g)||jsonb_build_object('id','fdc22ec7-ff50-4300-bfa6-70ff170461c7','gate_kind','GATE_E',
                            'predecessor_gate_ev_id',g.id,'max_bid_change_amount',20))).*
                          FROM ops.ad_gate_authority g WHERE g.id='342cf264-3eb4-5105-b854-3e25ee3aa2ea';
                        INSERT INTO ops.ad_ordinary_promotion(id,gate_authority_id,bundle_id,material_envelope_amount,
                          shadow_evidence_reference,pilot_evidence_reference,matured_outcome_reference,rollback_evidence_reference,
                          owner_approval_reference,valid_from,valid_until,status)
                        VALUES('1590af1e-26b8-48a6-98d3-7de7620b2e2c','fdc22ec7-ff50-4300-bfa6-70ff170461c7',
                          'cacdad4e-1a61-5901-b7f9-68062f95d854',20,'fixture://ordinary/shadow','fixture://ordinary/all-material-pilot',
                          'fixture://ordinary/mature-operational-and-settled','fixture://ordinary/rollback',
                          'fixture://ordinary/owner-exact-scope',now()-interval '1 hour',now()+interval '1 day','ACTIVE');
                        """);
                    statement=statement.replace("gate_authority_id='342cf264-3eb4-5105-b854-3e25ee3aa2ea'",
                            "gate_authority_id='fdc22ec7-ff50-4300-bfa6-70ff170461c7'");
                }
                filtered.append(statement).append(';');
            }
            return filtered.toString();
        };
    }
}
