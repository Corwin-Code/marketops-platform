package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Frozen synthetic pre-action input plus real mature/late Outcome evaluation; no current-policy swap. */
@SpringBootTest @ActiveProfiles("ci")
class AdvertisingFrozenOutcomePolicyIT {
    @Autowired ApplicationContext context;
    AdvertisingFrozenOutcomeIT f;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) { AdvertisingFrozenOutcomeIT.properties(p); }
    @BeforeEach void originalFrozenAction(TestInfo info) throws Exception {
        f=new AdvertisingFrozenOutcomeIT();context.getAutowireCapableBeanFactory().autowireBean(f);f.fixture(info);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void matureLateRecalculationKeepsTheFrozenThresholdDespiteNewFavorableOrConflictedPolicies(boolean conflicting) {
        var original=f.retainedGolden("79.5");
        assertThat(original.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.UNCHANGED);
        String originalObservation=observation(original.observationId()),frozen=frozenBytes();
        UUID newer=policy(2);if(conflicting)policy(3);
        assertMicroImprovementAndFavorableThresholdCounterfactual(original.observationId(),newer);
        String current=f.seed.sql("SELECT state FROM core.ad_outcome_policy_resolution(:org,:platform,:store,'PROTECTION_DECREASE','PROVEN_ADVERTISING_LOSS',:at)")
                .param("org",f.graph.id("organization")).param("platform",f.graph.platform()).param("store",f.graph.id("store"))
                .param("at",Timestamp.from(f.read)).query(String.class).single();
        assertThat(current).isEqualTo(conflicting?"OUTCOME_POLICY_CONFLICTED":"RESOLVED");
        if(!conflicting)assertThat(f.seed.sql("SELECT policy_id FROM core.ad_outcome_policy_resolution(:org,:platform,:store,'PROTECTION_DECREASE','PROVEN_ADVERTISING_LOSS',:at)")
                .param("org",f.graph.id("organization")).param("platform",f.graph.platform()).param("store",f.graph.id("store"))
                .param("at",Timestamp.from(f.read)).query(UUID.class).single()).isEqualTo(newer);
        // Actual new coverage/configuration/cost verification inputs retain the same
        // economics. Their evaluation must consume the approved threshold 10/0.1,
        // not the newer zero thresholds or the current selection conflict.
        f.read=f.read.plusSeconds(60);refreshCoverage();f.context();f.refreshCostProof();
        var revision=f.service.evaluate(f.dueStage("RETAINED_REVISED"),f.read).orElseThrow();
        assertThat(revision.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.UNCHANGED);
        assertThat(revision.revisionNo()).isEqualTo(2);
        assertMicroImprovementAndFavorableThresholdCounterfactual(revision.observationId(),newer);
        assertThat(revision.observationId()).isNotEqualTo(original.observationId());
        assertThat(observation(original.observationId())).isEqualTo(originalObservation);
        assertThat(frozenBytes()).isEqualTo(frozen);
        assertThat(f.seed.sql("SELECT outcome_policy_id FROM ops.ad_outcome_baseline WHERE id=:id")
                .param("id",f.graph.id("baseline")).query(UUID.class).single()).isEqualTo(f.graph.id("outcome"));
        assertThat(f.seed.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",f.graph.id("gate")).query(Boolean.class).single()).isFalse();
    }

    private void assertMicroImprovementAndFavorableThresholdCounterfactual(UUID observation,UUID newerPolicy) {
        record Axes(java.math.BigDecimal baselineAbsolute,java.math.BigDecimal observedAbsolute,
                java.math.BigDecimal baselinePerRub,java.math.BigDecimal observedPerRub) { }
        var axes=f.seed.sql("SELECT baseline_absolute_profit,observed_absolute_profit,baseline_profit_per_rub,observed_profit_per_rub FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",observation).query((rs,index)->new Axes(rs.getBigDecimal("baseline_absolute_profit"),
                        rs.getBigDecimal("observed_absolute_profit"),rs.getBigDecimal("baseline_profit_per_rub"),rs.getBigDecimal("observed_profit_per_rub"))).single();
        assertThat(axes.baselineAbsolute()).isEqualByComparingTo("100");assertThat(axes.baselinePerRub()).isEqualByComparingTo("1");
        assertThat(axes.observedAbsolute()).isEqualByComparingTo("105");assertThat(axes.observedPerRub()).isEqualByComparingTo("1.05");
        assertThat(f.seed.sql("SELECT (plan_snapshot->>'absoluteDelta')::numeric FROM ops.ad_outcome_baseline WHERE id=:id")
                .param("id",f.graph.id("baseline")).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("10");
        assertThat(f.seed.sql("SELECT (plan_snapshot->>'perRubDelta')::numeric FROM ops.ad_outcome_baseline WHERE id=:id")
                .param("id",f.graph.id("baseline")).query(java.math.BigDecimal.class).single()).isEqualByComparingTo("0.1");
        // Use the actual observed axes and the actual newer row's thresholds. This is
        // a numeric counterfactual, not permission to substitute that row for the plan.
        var counterfactual=f.seed.sql("SELECT * FROM core.ad_outcome_policy WHERE id=:id").param("id",newerPolicy)
                .query((rs,index)->com.mimococo.marketops.advertisingefficiency.internal.domain.DualAxisVerdict.evaluate(
                        AdvertisingFrozenOutcomeIT.amount(axes.baselineAbsolute().toPlainString()),
                        AdvertisingFrozenOutcomeIT.amount(axes.observedAbsolute().toPlainString()),
                        AdvertisingFrozenOutcomeIT.amount(axes.baselinePerRub().toPlainString()),
                        AdvertisingFrozenOutcomeIT.amount(axes.observedPerRub().toPlainString()),
                        rs.getBigDecimal("material_profit_delta"),rs.getBigDecimal("material_profit_per_rub_delta"),
                        rs.getBigDecimal("non_worsening_profit_band"),rs.getBigDecimal("non_worsening_per_rub_band"),
                        rs.getInt("comparison_scale"),rs.getString("comparison_rounding_mode"),
                        rs.getBoolean("material_boundary_inclusive"),true,true)).single();
        assertThat(counterfactual.outcome()).isEqualTo(com.mimococo.marketops.advertisingefficiency.internal.domain.DualAxisVerdict.Outcome.VERIFIED_EFFICIENCY_SUCCESS);
    }

    private void refreshCoverage() {
        // Same exact window, one append-only successor; preserve every prior byte.
        record Prior(UUID id,String snapshot) { }
        var prior=f.seed.sql("""
                SELECT r.id,to_jsonb(r)::text snapshot FROM ledger.return_quality_evidence_snapshot r
                WHERE r.organization_id=:org AND r.platform_listing_variant_id=:listing
                 AND r.report_window_start=:from AND r.report_window_end=:to
                 AND NOT EXISTS(SELECT 1 FROM ledger.return_quality_evidence_snapshot n WHERE n.supersedes_snapshot_id=r.id)
                """).param("org",f.graph.id("organization")).param("listing",f.graph.id("listingVariant"))
                .param("from",Timestamp.from(f.from)).param("to",Timestamp.from(f.to))
                .query((rs,index)->new Prior(rs.getObject("id",UUID.class),rs.getString("snapshot"))).single();
        assertThat(f.seed.sql("""
                INSERT INTO ledger.return_quality_evidence_snapshot
                SELECT (jsonb_populate_record(NULL::ledger.return_quality_evidence_snapshot,to_jsonb(r)||jsonb_build_object(
                  'id',gen_random_uuid(),'supersedes_snapshot_id',r.id,'accepted_at',CAST(:at AS timestamptz),
                  'completed_source_updated_at',CAST(:at AS timestamptz),'retained_source_updated_at',CAST(:at AS timestamptz),
                  'return_source_updated_at',CAST(:at AS timestamptz),'qc_source_updated_at',CAST(:at AS timestamptz)))).*
                FROM ledger.return_quality_evidence_snapshot r WHERE r.id=:prior
                 AND NOT EXISTS(SELECT 1 FROM ledger.return_quality_evidence_snapshot n WHERE n.supersedes_snapshot_id=r.id)
                """).param("at",Timestamp.from(f.read)).param("prior",prior.id()).update()).isEqualTo(1);
        assertThat(f.seed.sql("SELECT to_jsonb(r)::text FROM ledger.return_quality_evidence_snapshot r WHERE id=:id")
                .param("id",prior.id()).query(String.class).single()).isEqualTo(prior.snapshot());
        assertThat(f.seed.sql("SELECT count(*) FROM ledger.return_quality_evidence_snapshot WHERE supersedes_snapshot_id=:id AND accepted_at=:at")
                .param("id",prior.id()).param("at",Timestamp.from(f.read)).query(Integer.class).single()).isEqualTo(1);
    }
    private UUID policy(int version) {
        UUID id=UUID.randomUUID();
        f.seed.sql("""
                INSERT INTO core.ad_outcome_policy SELECT (jsonb_populate_record(NULL::core.ad_outcome_policy,to_jsonb(p)||jsonb_build_object(
                  'id',CAST(:id AS uuid),'policy_version',CAST(:version AS integer),'scope_kind','STORE',
                  'platform_code',CAST(:platform AS text),'store_ref_id',CAST(:store AS uuid),
                  'effective_from',CAST(:at AS timestamptz)-interval '1 second','effective_to',NULL,
                  'material_profit_delta',0,'material_profit_per_rub_delta',0,'material_boundary_inclusive',true,
                  'reason','Synthetic later favorable current policy; never substitutes an old frozen plan'))).*
                FROM core.ad_outcome_policy p WHERE id=:old
                """).param("id",id).param("version",version).param("platform",f.graph.platform()).param("store",f.graph.id("store"))
                .param("at",Timestamp.from(f.read)).param("old",f.graph.id("outcome")).update();return id;
    }
    private String observation(UUID id) { return f.seed.sql("SELECT to_jsonb(o)::text FROM ops.ad_outcome_observation o WHERE id=:id").param("id",id).query(String.class).single(); }
    private String frozenBytes() {
        return f.seed.sql("""
                SELECT jsonb_build_object('baseline',to_jsonb(b),
                  'stages',(SELECT jsonb_agg(to_jsonb(s) ORDER BY stage) FROM ops.ad_outcome_stage_baseline s WHERE s.outcome_baseline_id=b.id),
                  'attestation',(SELECT to_jsonb(a) FROM ops.ad_outcome_baseline_attestation a WHERE a.outcome_baseline_id=b.id))::text
                FROM ops.ad_outcome_baseline b WHERE b.id=:id
                """).param("id",f.graph.id("baseline")).query(String.class).single();
    }
}
