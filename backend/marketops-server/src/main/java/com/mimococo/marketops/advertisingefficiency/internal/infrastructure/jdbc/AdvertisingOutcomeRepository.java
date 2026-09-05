package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The frozen plan, the windows it fixes, and what was written about them.
 *
 * <p>Everything read here is read as of the command rather than as of now. The
 * plan comes from the bundle the command was created under, the window is
 * measured from the moment the write was proven to have landed, and the guard
 * is asked of the database rather than computed here — because the database is
 * what will refuse a settled claim that outruns it.
 */
@Repository
public class AdvertisingOutcomeRepository {

    private final JdbcClient jdbc;

    AdvertisingOutcomeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Commands whose next outcome stage is due.
     *
     * <p>Three disjoint reasons a command appears: its operational window has
     * closed and nothing operational has been written; its settlement window has
     * closed and nothing settled has been written; or a settled view exists and
     * the facts underneath it have been restated since. The third is the late
     * adjustment, and it is why this is a query rather than a queue.
     */
    public List<DueRow> due(Instant now, int limit) { return due(null,null,now,limit); }
    public List<DueRow> due(UUID organization,UUID object,Instant now,int limit) {
        return jdbc.sql("""
                WITH landed AS (SELECT command_id,min(observed_at) landed_at FROM ops.ad_bid_command_readback
                    WHERE match_state='MATCHES_TARGET' GROUP BY command_id)
                SELECT c.id command_id,NULL::uuid manual_packet_id,c.organization_id,c.store_id,c.platform_code,c.ad_native_object_id,
                    c.affected_set_digest,c.direction,l.landed_at,b.id baseline_id,b.outcome_policy_id policy_id,
                    b.outcome_policy_version policy_version,'DUAL_AXIS' primary_metric_code,'FROZEN_PRE_ACTION' comparison_basis,
                    (b.plan_snapshot->>'observationStartsMinutes')::integer observation_starts_minutes,
                    (b.plan_snapshot->>'operationalHours')::integer operational_window_hours,
                    (b.plan_snapshot->>'settledHours')::integer settlement_window_hours,
                    NULL::numeric improvement_threshold_ratio,NULL::numeric regression_threshold_ratio,
                    (b.plan_snapshot->>'minimumTraffic')::bigint minimum_traffic_count,
                    (b.plan_snapshot->>'minimumCoverage')::numeric minimum_settled_coverage_ratio,
                    candidate.cause_code,
                    stage.stage || CASE WHEN latest.id IS NULL THEN '' ELSE '_REVISED' END next_stage,
                    latest.id latest_settled_id,latest.revision_no latest_settled_revision
                FROM ops.ad_bid_command c JOIN landed l ON l.command_id=c.id
                JOIN ops.ad_bid_candidate candidate ON candidate.id=c.candidate_id
                JOIN ops.ad_outcome_baseline b ON b.id=c.outcome_baseline_id
                JOIN ops.ad_outcome_stage_baseline stage ON stage.outcome_baseline_id=b.id
                LEFT JOIN LATERAL (SELECT o.id,o.revision_no,o.evaluated_at FROM ops.ad_outcome_observation o
                    WHERE o.command_id=c.id AND o.outcome_stage IN(stage.stage,stage.stage||'_REVISED')
                    ORDER BY o.revision_no DESC LIMIT 1) latest ON true
                WHERE c.direction<>'EXACT_PRIOR_BID_COMPENSATION'
                    AND (CAST(:organization AS uuid) IS NULL OR c.organization_id=:organization)
                    AND (CAST(:object AS uuid) IS NULL OR c.ad_native_object_id=:object)
                    AND :now>=l.landed_at+make_interval(mins=>(b.plan_snapshot->>'observationStartsMinutes')::integer)
                        +make_interval(hours=>stage.window_hours)
                    AND (latest.id IS NULL OR EXISTS (SELECT 1 FROM ledger.ad_object_fact f
                            WHERE f.organization_id=c.organization_id AND f.ad_native_object_id=c.ad_native_object_id
                              AND f.recorded_at>latest.evaluated_at)
                        OR EXISTS(SELECT 1 FROM ledger.ad_linked_sale_event e WHERE e.organization_id=c.organization_id
                            AND e.ad_native_object_id=c.ad_native_object_id AND e.recorded_at>latest.evaluated_at)
                        OR EXISTS(SELECT 1 FROM ledger.sales_fact f JOIN core.fact_provenance p ON p.id=f.provenance_id
                            WHERE f.organization_id=c.organization_id AND f.platform_listing_variant_id=ANY(b.listing_variant_ids)
                              AND p.ingestion_time>latest.evaluated_at)
                        OR EXISTS(SELECT 1 FROM ledger.ad_settlement_attribution a JOIN ledger.ad_linked_sale_event e ON e.id=a.ad_linked_sale_event_id
                            WHERE a.organization_id=c.organization_id AND e.ad_native_object_id=c.ad_native_object_id
                              AND a.accepted_at>latest.evaluated_at))
                ORDER BY l.landed_at, CASE stage.stage WHEN 'OPERATIONAL' THEN 1 WHEN 'RETAINED' THEN 2 ELSE 3 END
                LIMIT :limit
                """).param("organization",organization).param("object",object).param("now",ts(now)).param("limit",limit).query(AdvertisingOutcomeRepository::mapDue).list();
    }

    public Optional<DueRow> manualEarly(UUID organization,UUID packet,Instant now) {
        return manualDue(organization,packet,now,1,false).stream().findFirst();
    }
    public List<DueRow> manualDue(UUID organization,UUID packet,Instant now,int limit,boolean dueOnly) {
        return manualDue(organization,packet,null,now,limit,dueOnly);
    }
    public List<DueRow> manualDue(UUID organization,UUID packet,UUID object,Instant now,int limit,boolean dueOnly) {
        return jdbc.sql("""
                SELECT NULL::uuid command_id,p.id manual_packet_id,p.organization_id,p.store_id,p.platform_code,p.ad_native_object_id,
                    p.affected_set_digest,coalesce(p.intended_state->>'direction','PROTECTION_DECREASE') direction,
                    proof.observed_at landed_at,b.outcome_policy_id policy_id,b.outcome_policy_version policy_version,
                    'DUAL_AXIS' primary_metric_code,'FROZEN_PRE_ACTION' comparison_basis,
                    (b.plan_snapshot->>'observationStartsMinutes')::integer observation_starts_minutes,
                    (b.plan_snapshot->>'operationalHours')::integer operational_window_hours,
                    (b.plan_snapshot->>'settledHours')::integer settlement_window_hours,
                    NULL::numeric improvement_threshold_ratio,NULL::numeric regression_threshold_ratio,
                    (b.plan_snapshot->>'minimumTraffic')::bigint minimum_traffic_count,
                    (b.plan_snapshot->>'minimumCoverage')::numeric minimum_settled_coverage_ratio,k.cause_code,
                    stage.stage||CASE WHEN latest.id IS NULL THEN '' ELSE '_REVISED' END next_stage,
                    latest.id latest_settled_id,latest.revision_no latest_settled_revision
                FROM ops.ad_manual_execution_packet p JOIN mart.ad_case k ON k.id=p.case_id
                JOIN ops.ad_manual_configuration_verification proof ON proof.id=p.current_proof_id AND proof.proves_configuration
                JOIN ops.ad_outcome_baseline b ON b.id=p.outcome_baseline_id
                JOIN ops.ad_outcome_stage_baseline stage ON stage.outcome_baseline_id=b.id
                LEFT JOIN LATERAL(SELECT o.id,o.revision_no,o.evaluated_at FROM ops.ad_outcome_observation o
                    WHERE o.manual_packet_id=p.id AND o.outcome_stage IN(stage.stage,stage.stage||'_REVISED')
                    ORDER BY o.revision_no DESC LIMIT 1) latest ON true
                WHERE p.state='MANUAL_CONFIGURATION_VERIFIED' AND (CAST(:organization AS uuid) IS NULL OR p.organization_id=:organization)
                    AND (CAST(:packet AS uuid) IS NULL OR p.id=:packet)
                    AND (CAST(:object AS uuid) IS NULL OR p.ad_native_object_id=:object) AND (:dueOnly OR stage.stage='OPERATIONAL')
                    AND (NOT :dueOnly OR :now>=proof.observed_at+make_interval(mins=>(b.plan_snapshot->>'observationStartsMinutes')::integer,hours=>stage.window_hours))
                    AND (NOT :dueOnly OR latest.id IS NULL OR EXISTS(SELECT 1 FROM ledger.ad_object_fact f
                        WHERE f.organization_id=p.organization_id AND f.ad_native_object_id=p.ad_native_object_id AND f.recorded_at>latest.evaluated_at)
                      OR EXISTS(SELECT 1 FROM ledger.ad_linked_sale_event e WHERE e.organization_id=p.organization_id
                        AND e.ad_native_object_id=p.ad_native_object_id AND e.recorded_at>latest.evaluated_at)
                      OR EXISTS(SELECT 1 FROM ledger.sales_fact f JOIN core.fact_provenance provenance ON provenance.id=f.provenance_id
                        WHERE f.organization_id=p.organization_id AND f.platform_listing_variant_id=ANY(b.listing_variant_ids)
                          AND provenance.ingestion_time>latest.evaluated_at)
                      OR EXISTS(SELECT 1 FROM ledger.ad_settlement_attribution attribution JOIN ledger.ad_linked_sale_event event
                        ON event.id=attribution.ad_linked_sale_event_id WHERE attribution.organization_id=p.organization_id
                          AND event.ad_native_object_id=p.ad_native_object_id AND attribution.accepted_at>latest.evaluated_at))
                ORDER BY proof.observed_at,CASE stage.stage WHEN 'OPERATIONAL' THEN 1 WHEN 'RETAINED' THEN 2 ELSE 3 END
                LIMIT :limit
                """).param("organization",organization).param("packet",packet).param("object",object).param("now",ts(now)).param("dueOnly",dueOnly)
                .param("limit",limit).query(AdvertisingOutcomeRepository::mapDue).list();
    }

    public boolean tryReleaseReservation(UUID observation) {
        return jdbc.sql("SELECT ops.try_release_ad_reservation_after_outcome(:observation)")
                .param("observation",observation).query(Boolean.class).single();
    }

    public record FrozenBaseline(UUID id, UUID affectedSetId, String policyJson, String snapshotJson) { }
    public Optional<FrozenBaseline> frozenBaseline(UUID commandId,String stage) { return frozenBaseline(commandId,null,stage); }
    public Optional<FrozenBaseline> frozenBaseline(UUID commandId,UUID manualPacket,String stage) {
        return jdbc.sql("""
                SELECT b.id,b.affected_set_id,b.plan_snapshot::text,stage.snapshot::text
                FROM ops.ad_outcome_baseline b
                JOIN ops.ad_outcome_stage_baseline stage ON stage.outcome_baseline_id=b.id
                WHERE stage.stage=:stage AND (EXISTS(SELECT 1 FROM ops.ad_bid_command c WHERE c.id=:command AND c.outcome_baseline_id=b.id)
                    OR EXISTS(SELECT 1 FROM ops.ad_manual_execution_packet p WHERE p.id=:manual AND p.outcome_baseline_id=b.id))
                """).param("command",commandId).param("manual",manualPacket).param("stage",stage)
                .query((rs,index)->new FrozenBaseline(rs.getObject("id",UUID.class),rs.getObject("affected_set_id",UUID.class),
                        rs.getString("plan_snapshot"),rs.getString("snapshot"))).optional();
    }

    public void recordAxes(UUID observation,UUID baseline,String dual,String sales,BigDecimal beforeProfit,BigDecimal afterProfit,
            BigDecimal beforePerRub,BigDecimal afterPerRub,BigDecimal beforeSales,BigDecimal afterSales,String currency,String snapshot,String businessOutcome) {
        jdbc.sql("""
                INSERT INTO ops.ad_outcome_axes(observation_id,outcome_baseline_id,dual_axis_verdict,sales_preservation_verdict,
                    baseline_absolute_profit,observed_absolute_profit,baseline_profit_per_rub,observed_profit_per_rub,
                    company_baseline_sales,company_observed_sales,currency_code,input_snapshot,business_outcome)
                VALUES(:observation,:baseline,:dual,:sales,:beforeProfit,:afterProfit,:beforePerRub,:afterPerRub,:beforeSales,:afterSales,:currency,CAST(:snapshot AS jsonb),:business)
                """).param("observation",observation).param("baseline",baseline).param("dual",dual).param("sales",sales)
                .param("beforeProfit",beforeProfit).param("afterProfit",afterProfit).param("beforePerRub",beforePerRub).param("afterPerRub",afterPerRub)
                .param("beforeSales",beforeSales).param("afterSales",afterSales).param("currency",currency).param("snapshot",snapshot).param("business",businessOutcome).update();
    }

    public void recordCriticalGuard(UUID baseline,UUID product,UUID listing,String state,Instant at,UUID observation,BigDecimal before,BigDecimal after) {
        jdbc.sql("""
                INSERT INTO ops.ad_outcome_critical_guard(outcome_baseline_id,product_variant_id,listing_variant_id,guard_state,
                    observed_at,observation_id,baseline_sales,observed_sales)
                VALUES(:baseline,:product,:listing,:state,:at,:observation,:before,:after)
                """).param("baseline",baseline).param("product",product).param("listing",listing).param("state",state)
                .param("at",ts(at)).param("observation",observation).param("before",before).param("after",after).update();
    }

    /**
     * Whether a settled claim may be made for this command right now.
     *
     * <p>Asked of the database on purpose. The same predicate is a check
     * constraint on the observation row, so a service that computed a friendlier
     * answer here would simply be refused when it tried to write it.
     */
    public OutcomeEvaluation.GuardState guardState(UUID commandId, BigDecimal coverage) {
        String state = jdbc.sql("SELECT ops.ad_completed_sales_guard_state(:commandId, :coverage)")
                .param("commandId", commandId)
                .param("coverage", coverage)
                .query(String.class)
                .single();
        return OutcomeEvaluation.GuardState.valueOf(state);
    }

    /** Record one observation. Never an update: a later view is a later revision. */
    public UUID record(UUID id, DueRow due, String stage, int revisionNo,
                       UUID supersedesObservationId, String adjustmentReason,
                       Instant windowStartsAt, Instant windowEndsAt,
                       com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure
                               baseline,
                       com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure
                               observed,
                       Long observedTraffic, BigDecimal settledCoverage,
                       OutcomeEvaluation evaluation, Instant evaluatedAt, String inputDigest,
                       String correlationId) {
        jdbc.sql("""
                INSERT INTO ops.ad_outcome_observation (
                    id, organization_id, command_id, manual_packet_id, ad_native_object_id, affected_set_digest,
                    outcome_policy_id, outcome_policy_version, outcome_stage, revision_no,
                    supersedes_observation_id, adjustment_reason, window_starts_at,
                    window_ends_at, baseline_metric_state, baseline_metric_value,
                    observed_metric_state, observed_metric_value, observed_traffic_count,
                    settled_coverage_ratio, verdict, guard_state, unresolved_reason_codes,
                    evaluated_at, input_digest, correlation_id)
                VALUES (:id, :organizationId, :commandId, :manualPacket, :objectId, :digest, :policyId,
                    :policyVersion, :stage, :revisionNo, :supersedes, :adjustmentReason,
                    :windowStartsAt, :windowEndsAt, :baselineState, :baselineValue,
                    :observedState, :observedValue, :traffic, :coverage, :verdict, :guardState,
                    CAST(:reasons AS text[]), :evaluatedAt, :inputDigest, :correlationId)
                """)
                .param("id", id)
                .param("organizationId", due.organizationId())
                .param("commandId", due.commandId()).param("manualPacket",due.manualPacketId())
                .param("objectId", due.adNativeObjectId())
                .param("digest", due.affectedSetDigest())
                .param("policyId", due.policyId())
                .param("policyVersion", due.policyVersion())
                .param("stage", stage)
                .param("revisionNo", revisionNo)
                .param("supersedes", supersedesObservationId)
                .param("adjustmentReason", adjustmentReason)
                .param("windowStartsAt", ts(windowStartsAt))
                .param("windowEndsAt", ts(windowEndsAt))
                .param("baselineState", baseline.valueState().name())
                .param("baselineValue", baseline.orElse(null))
                .param("observedState", observed.valueState().name())
                .param("observedValue", observed.orElse(null))
                .param("traffic", observedTraffic)
                .param("coverage", settledCoverage)
                .param("verdict", evaluation.verdict().name())
                .param("guardState", evaluation.guardState().name())
                .param("reasons", textArrayLiteral(evaluation.unresolvedReasons()))
                .param("evaluatedAt", ts(evaluatedAt))
                .param("inputDigest", inputDigest)
                .param("correlationId", correlationId)
                .update();
        return id;
    }

    /**
     * Reopen the lineage a settled regression came from.
     *
     * <p>Idempotent, and refused by the database for anything that is not a
     * guarded settled regression. The containment it writes is what the lane
     * resolver reads on the next calculation, so the case that appears is
     * produced by the same authority every other case is.
     */
    public UUID reopenAfterRegression(UUID containmentId,UUID observationId,String accountableRoleCode,String correlationId) {
        return jdbc.sql("SELECT ops.activate_ad_regression_containment(:observation)")
                .param("observation",observationId).query(UUID.class).single();
    }

    /** Every observation about one command, oldest first. */
    public List<ObservationRow> forCommand(UUID commandId) {
        return jdbc.sql("""
                SELECT id, command_id, outcome_stage, revision_no, supersedes_observation_id,
                       adjustment_reason, window_starts_at, window_ends_at,
                       baseline_metric_state, baseline_metric_value, observed_metric_state,
                       observed_metric_value, observed_traffic_count, settled_coverage_ratio,
                       verdict, guard_state, unresolved_reason_codes, evaluated_at
                  FROM ops.ad_outcome_observation
                 WHERE command_id = :commandId
                 ORDER BY evaluated_at, revision_no
                """)
                .param("commandId", commandId)
                .query(AdvertisingOutcomeRepository::mapObservation)
                .list();
    }

    /**
     * Every observation about one command, narrowed to the caller's stores.
     *
     * <p>The command carries the store, so the narrowing happens in SQL as well
     * as in the caller. Both stages and every restatement come back in the order
     * they were taken: collapsing them here would hide that an answer changed.
     */
    public List<com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView> forCommand(
            UUID organizationId, UUID commandId, List<UUID> permittedStoreIds) {
        return jdbc.sql("""
                SELECT o.id, o.command_id, o.manual_packet_id, o.outcome_stage, o.revision_no,
                       o.supersedes_observation_id, o.adjustment_reason, o.window_starts_at,
                       o.window_ends_at, o.baseline_metric_state, o.baseline_metric_value,
                       o.observed_metric_state, o.observed_metric_value,
                       o.observed_traffic_count, o.settled_coverage_ratio, o.verdict,
                       o.guard_state, o.unresolved_reason_codes, o.evaluated_at
                  FROM ops.ad_outcome_observation o
                  JOIN ops.ad_bid_command c
                    ON c.id = o.command_id AND c.organization_id = o.organization_id
                 WHERE o.organization_id = :organizationId
                   AND o.command_id = :commandId
                   AND c.store_id = ANY (CAST(:permittedStoreIds AS uuid[]))
                 ORDER BY o.evaluated_at, o.revision_no
                """)
                .param("organizationId", organizationId)
                .param("commandId", commandId)
                .param("permittedStoreIds", uuidArrayLiteral(permittedStoreIds))
                .query(AdvertisingOutcomeRepository::mapOutcomeView)
                .list().stream().map(this::withOutcomeAxes).toList();
    }

    public List<com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView> forManualPacket(
            UUID organizationId,UUID packetId,List<UUID> permittedStoreIds) {
        return jdbc.sql("""
                SELECT o.* FROM ops.ad_outcome_observation o JOIN ops.ad_manual_execution_packet p ON p.id=o.manual_packet_id
                WHERE o.organization_id=:org AND p.organization_id=:org AND p.id=:packet
                AND p.store_id=ANY(CAST(:stores AS uuid[])) ORDER BY o.evaluated_at,o.revision_no,o.id
                """).param("org",organizationId).param("packet",packetId).param("stores",uuidArrayLiteral(permittedStoreIds))
                .query(AdvertisingOutcomeRepository::mapOutcomeView).list().stream().map(this::withOutcomeAxes).toList();
    }

    private com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView withOutcomeAxes(
            com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView view) {
        var critical = jdbc.sql("""
                SELECT product_variant_id,listing_variant_id,guard_state,baseline_sales,observed_sales
                FROM ops.ad_outcome_critical_guard WHERE observation_id=:id ORDER BY product_variant_id,listing_variant_id
                """).param("id",view.id()).query((rs,n)->new com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView.CriticalGuard(
                        rs.getObject("product_variant_id",UUID.class),rs.getObject("listing_variant_id",UUID.class),
                        rs.getString("guard_state"),rs.getBigDecimal("baseline_sales"),rs.getBigDecimal("observed_sales"))).list();
        var axes = jdbc.sql("SELECT * FROM ops.ad_outcome_axes WHERE observation_id=:id").param("id",view.id())
                .query((rs,n)->new com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView.Axes(
                        rs.getString("dual_axis_verdict"),rs.getString("sales_preservation_verdict"),rs.getString("business_outcome"),
                        rs.getBigDecimal("baseline_absolute_profit"),rs.getBigDecimal("observed_absolute_profit"),
                        rs.getBigDecimal("baseline_profit_per_rub"),rs.getBigDecimal("observed_profit_per_rub"),
                        rs.getBigDecimal("company_baseline_sales"),rs.getBigDecimal("company_observed_sales"),
                        rs.getString("currency_code"),rs.getString("input_snapshot"),critical)).optional().orElse(null);
        return view.withAxes(axes);
    }

    private static com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView
            mapOutcomeView(ResultSet rs, int index) throws SQLException {
        java.sql.Array reasons = rs.getArray("unresolved_reason_codes");
        return new com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView(
                rs.getObject("id", UUID.class),
                rs.getObject("command_id", UUID.class),
                rs.getObject("manual_packet_id", UUID.class),
                rs.getString("outcome_stage"),
                rs.getInt("revision_no"),
                rs.getObject("supersedes_observation_id", UUID.class),
                rs.getString("adjustment_reason"),
                rs.getTimestamp("window_starts_at").toInstant(),
                rs.getTimestamp("window_ends_at").toInstant(),
                rs.getString("baseline_metric_state"),
                rs.getBigDecimal("baseline_metric_value"),
                rs.getString("observed_metric_state"),
                rs.getBigDecimal("observed_metric_value"),
                longOrNull(rs, "observed_traffic_count"),
                rs.getBigDecimal("settled_coverage_ratio"),
                rs.getString("verdict"),
                rs.getString("guard_state"),
                reasons == null ? List.of() : List.of((String[]) reasons.getArray()),
                rs.getTimestamp("evaluated_at").toInstant(),null);
    }

    /**
     * A uuid array as PostgreSQL reads it.
     *
     * <p>Every element is already a {@link UUID}, so the literal cannot carry
     * anything a uuid array may not hold.
     */
    private static String uuidArrayLiteral(List<UUID> ids) {
        StringBuilder literal = new StringBuilder("{");
        for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(ids.get(index).toString());
        }
        return literal.append('}').toString();
    }

    /** One command's next due stage, and the frozen plan that judges it. */
    public record DueRow(
            UUID commandId, UUID organizationId, UUID storeId, String platformCode,
            UUID adNativeObjectId, String affectedSetDigest, String direction,
            Instant landedAt, UUID policyId, int policyVersion, String primaryMetricCode,
            String comparisonBasis, int observationStartsMinutes, int operationalWindowHours,
            int settlementWindowHours, BigDecimal improvementThresholdRatio,
            BigDecimal regressionThresholdRatio, long minimumTrafficCount,
            BigDecimal minimumSettledCoverageRatio, String causeCode, String nextStage,
            UUID latestSettledId, Integer latestSettledRevision, UUID manualPacketId) {
        public DueRow(UUID commandId, UUID organizationId, UUID storeId, String platformCode,
            UUID adNativeObjectId, String affectedSetDigest, String direction, Instant landedAt, UUID policyId, int policyVersion,
            String primaryMetricCode, String comparisonBasis, int observationStartsMinutes, int operationalWindowHours,
            int settlementWindowHours, BigDecimal improvementThresholdRatio, BigDecimal regressionThresholdRatio,
            long minimumTrafficCount, BigDecimal minimumSettledCoverageRatio, String causeCode, String nextStage,
            UUID latestSettledId, Integer latestSettledRevision) {
            this(commandId,organizationId,storeId,platformCode,adNativeObjectId,affectedSetDigest,direction,landedAt,policyId,policyVersion,
                primaryMetricCode,comparisonBasis,observationStartsMinutes,operationalWindowHours,settlementWindowHours,
                improvementThresholdRatio,regressionThresholdRatio,minimumTrafficCount,minimumSettledCoverageRatio,causeCode,
                nextStage,latestSettledId,latestSettledRevision,null);
        }

        /** When the observation window opens: after the change has had time to reach. */
        public Instant windowStartsAt() {
            return landedAt.plus(java.time.Duration.ofMinutes(observationStartsMinutes));
        }

        /** When the window for one stage closes. */
        public Instant windowEndsAt(String stage) {
            int hours = "OPERATIONAL".equals(stage) ? operationalWindowHours
                    : "RETAINED".equals(stage) ? 720 : Math.max(720,settlementWindowHours);
            return windowStartsAt().plus(java.time.Duration.ofHours(hours));
        }
    }

    /** One recorded observation. */
    public record ObservationRow(
            UUID id, UUID commandId, String outcomeStage, int revisionNo,
            UUID supersedesObservationId, String adjustmentReason, Instant windowStartsAt,
            Instant windowEndsAt, String baselineMetricState, BigDecimal baselineMetricValue,
            String observedMetricState, BigDecimal observedMetricValue,
            Long observedTrafficCount, BigDecimal settledCoverageRatio, String verdict,
            String guardState, List<String> unresolvedReasonCodes, Instant evaluatedAt) {
    }

    private static DueRow mapDue(ResultSet rs, int index) throws SQLException {
        return new DueRow(
                rs.getObject("command_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getString("platform_code"),
                rs.getObject("ad_native_object_id", UUID.class),
                rs.getString("affected_set_digest"),
                rs.getString("direction"),
                rs.getTimestamp("landed_at").toInstant(),
                rs.getObject("policy_id", UUID.class),
                rs.getInt("policy_version"),
                rs.getString("primary_metric_code"),
                rs.getString("comparison_basis"),
                rs.getInt("observation_starts_minutes"),
                rs.getInt("operational_window_hours"),
                rs.getInt("settlement_window_hours"),
                rs.getBigDecimal("improvement_threshold_ratio"),
                rs.getBigDecimal("regression_threshold_ratio"),
                rs.getLong("minimum_traffic_count"),
                rs.getBigDecimal("minimum_settled_coverage_ratio"),
                rs.getString("cause_code"),
                rs.getString("next_stage"),
                rs.getObject("latest_settled_id", UUID.class),
                integerOrNull(rs, "latest_settled_revision"),rs.getObject("manual_packet_id",UUID.class));
    }

    private static ObservationRow mapObservation(ResultSet rs, int index) throws SQLException {
        Array reasons = rs.getArray("unresolved_reason_codes");
        return new ObservationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("command_id", UUID.class),
                rs.getString("outcome_stage"),
                rs.getInt("revision_no"),
                rs.getObject("supersedes_observation_id", UUID.class),
                rs.getString("adjustment_reason"),
                rs.getTimestamp("window_starts_at").toInstant(),
                rs.getTimestamp("window_ends_at").toInstant(),
                rs.getString("baseline_metric_state"),
                rs.getBigDecimal("baseline_metric_value"),
                rs.getString("observed_metric_state"),
                rs.getBigDecimal("observed_metric_value"),
                longOrNull(rs, "observed_traffic_count"),
                rs.getBigDecimal("settled_coverage_ratio"),
                rs.getString("verdict"),
                rs.getString("guard_state"),
                reasons == null ? List.of() : List.of((String[]) reasons.getArray()),
                rs.getTimestamp("evaluated_at").toInstant());
    }

    private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static SqlParameterValue ts(Instant instant) {
        return new SqlParameterValue(Types.TIMESTAMP,
                instant == null ? null : Timestamp.from(instant));
    }

    private static String textArrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder literal = new StringBuilder("{");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append('"').append(values.get(index)
                    .replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return literal.append('}').toString();
    }

}
