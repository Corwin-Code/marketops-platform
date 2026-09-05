package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.operatingfacts.FactWindow;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Actual Metric Engine and app-role writer against synthetic facts in disposable PostgreSQL. */
@SpringBootTest @ActiveProfiles("ci") @Import(AdvertisingVerticalPathIT.Runtime.class)
class AnalyticsMetricReevaluationIT {
    @Autowired ApplicationContext context;
    AdvertisingVerticalPathIT fixture;
    FactWindow cohort;
    MetricValueView original;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        AdvertisingVerticalPathIT.properties(properties);
    }

    @BeforeEach void canonicalFirstEvaluation() throws Exception {
        fixture=new AdvertisingVerticalPathIT();
        context.getAutowireCapableBeanFactory().autowireBean(fixture);
        fixture.topologyAndAuthorityOnly();
        fixture.acceptPreActionFacts(true);
        cohort=FactWindow.alignedEndingAt(fixture.start,MetricWindow.D30.length());
        original=costAt(fixture.start);
        assertThat(original.valueState()).isEqualTo(ValueState.AVAILABLE);
        assertThat(original.numericValue()).isEqualByComparingTo("500");
    }

    @AfterEach void preserveSyntheticBoundary() {
        fixture.clearIdentity();
        assertThat(fixture.productionWrites.getEnabled()).isFalse();
        assertThat(fixture.provider.calls).isEmpty();
    }

    @Test void sameInputReevaluationKeepsValueAndDigestAndAppendsSuccessfulProof() {
        String originalRow=valueRow(original.metricValueId());
        fixture.clock.at=fixture.start.plus(Duration.ofDays(2));
        UUID run=reevaluate();
        var current=costAt(fixture.clock.instant());
        assertThat(current.metricValueId()).isEqualTo(original.metricValueId());
        assertThat(current.inputDigest()).isEqualTo(original.inputDigest());
        assertThat(current.computedAt()).isEqualTo(original.computedAt());
        assertThat(current.verifiedAt()).isEqualTo(fixture.clock.instant());
        assertThat(current.verificationRunId()).isEqualTo(run);
        assertThat(valueRow(original.metricValueId())).isEqualTo(originalRow);
        assertThat(evaluationCount(original.metricValueId())).isEqualTo(2);
        assertThat(fixture.sql("SELECT count(*) FROM mart.metric_value WHERE organization_id=:org AND subject_id=:listing AND metric_code='UNIT_COST'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test void lateApplicableCostRevisionCreatesANewValueWithoutOverwritingItsPredecessor() {
        String originalRow=valueRow(original.metricValueId());
        fixture.clock.at=fixture.start.plus(Duration.ofDays(2));
        Instant correctedFrom=cohort.periodEnd().minusSeconds(3600);
        closeOriginalCost(correctedFrom);
        UUID source=UUID.randomUUID();
        fixture.sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note) VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:owner,'Synthetic accepted late applicable cost correction')")
                .param("id",source).update();
        fixture.sql("""
            INSERT INTO core.cost_version(id,organization_id,product_variant_id,cost_kind,currency_code,unit_cost,
                provenance_id,effective_from,status,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:variant,'PURCHASE','RUB',750,:published,:from,'ACTIVE',:at,:at)
            """).param("published",source).param("from",Timestamp.from(correctedFrom)).update();
        UUID run=reevaluate();
        var current=costAt(fixture.clock.instant());
        assertThat(current.metricValueId()).isNotEqualTo(original.metricValueId());
        assertThat(current.inputDigest()).isNotEqualTo(original.inputDigest());
        assertThat(current.numericValue()).isEqualByComparingTo("750");
        assertThat(current.periodStart()).isEqualTo(original.periodStart());
        assertThat(current.periodEnd()).isEqualTo(original.periodEnd());
        assertThat(current.verificationRunId()).isEqualTo(run);
        assertThat(valueRow(original.metricValueId())).isEqualTo(originalRow);
        assertThat(costAt(fixture.start).metricValueId()).isEqualTo(original.metricValueId());
    }

    @Test void latestUnavailableCohortCostCannotFallBackToAnOlderFavorableValue() {
        fixture.clock.at=fixture.start.plus(Duration.ofDays(2));
        closeOriginalCost(cohort.periodEnd().minusSeconds(3600));
        UUID run=reevaluate();
        var current=costAt(fixture.clock.instant());
        assertThat(current.metricValueId()).isNotEqualTo(original.metricValueId());
        assertThat(current.available()).isFalse();
        assertThat(current.numericValue()).isNull();
        assertThat(current.verificationRunId()).isEqualTo(run);
        assertThat(costAt(fixture.start).metricValueId()).isEqualTo(original.metricValueId());
        assertThat(costAt(fixture.start).numericValue()).isEqualByComparingTo("500");
    }

    @Test void failedRunCannotRefreshThePreviouslyVerifiedValue() {
        fixture.clock.at=fixture.start.plus(Duration.ofDays(2));
        UUID run=reevaluate();
        // An adverse synthetic lifecycle state exercises proof qualification;
        // the value and association were produced by the actual app writer.
        fixture.seed.sql("UPDATE mart.calculation_run SET state='FAILED',failure_code='SYNTHETIC_FAILURE' WHERE id=:id").param("id",run).update();
        var current=costAt(fixture.clock.instant());
        assertThat(current.metricValueId()).isEqualTo(original.metricValueId());
        assertThat(current.verifiedAt()).isEqualTo(original.verifiedAt());
        assertThat(current.verificationRunId()).isEqualTo(original.verificationRunId());
    }

    @Test void futureEvaluationDoesNotRefreshAnEarlierReadAndBecomesVisibleAtItsActualTime() {
        fixture.clock.at=fixture.start.plus(Duration.ofDays(2));
        UUID run=reevaluate();
        var earlier=costAt(fixture.start.plusSeconds(1));
        assertThat(earlier.verifiedAt()).isEqualTo(original.verifiedAt());
        assertThat(earlier.verificationRunId()).isEqualTo(original.verificationRunId());
        assertThat(costAt(fixture.clock.instant()).verificationRunId()).isEqualTo(run);
    }

    @Test void futureCompletionDoesNotRefreshBeforeTheSuccessfulRunIsComplete() {
        fixture.clock.at=fixture.start.plus(Duration.ofDays(2));
        UUID run=reevaluate();
        Instant completed=fixture.clock.instant().plusSeconds(60);
        fixture.seed.sql("UPDATE mart.calculation_run SET completed_at=:completed WHERE id=:id")
                .param("completed",Timestamp.from(completed)).param("id",run).update();
        assertThat(costAt(fixture.clock.instant()).verificationRunId()).isEqualTo(original.verificationRunId());
        assertThat(costAt(completed).verificationRunId()).isEqualTo(run);
    }

    @Test void unboundedCurrentReadUsesDatabaseTimeAndCannotBorrowAFutureSuccessfulProof() {
        var before=fixture.metrics.currentValues(SubjectKind.PLATFORM_LISTING_VARIANT,fixture.graph.id("listingVariant"),
                MetricWindow.D30).get(MetricCode.UNIT_COST);
        Instant databaseNow=fixture.jdbc.sql("SELECT statement_timestamp()")
                .query(Timestamp.class).single().toInstant();
        fixture.clock.at=databaseNow.plus(Duration.ofDays(2));
        UUID futureRun=reevaluate();
        assertThat(costAt(fixture.clock.instant()).verificationRunId()).isEqualTo(futureRun);
        var current=fixture.metrics.currentValues(SubjectKind.PLATFORM_LISTING_VARIANT,fixture.graph.id("listingVariant"),
                MetricWindow.D30).get(MetricCode.UNIT_COST);
        assertThat(current.metricValueId()).isEqualTo(original.metricValueId());
        assertThat(current.verifiedAt()).isEqualTo(before.verifiedAt());
        assertThat(current.verificationRunId()).isEqualTo(before.verificationRunId());
        assertThat(fixture.jdbc.sql("SELECT statement_timestamp()<:future")
                .param("future",Timestamp.from(fixture.clock.instant())).query(Boolean.class).single()).isTrue();
    }

    @Test void firstEvaluationHasNoVerificationBetweenComputationAndSuccessfulCompletion() {
        Instant completed=fixture.start.plusSeconds(60);
        fixture.seed.sql("UPDATE mart.calculation_run SET completed_at=:completed WHERE id=:id")
                .param("completed",Timestamp.from(completed)).param("id",original.verificationRunId()).update();
        var inFlight=costAt(fixture.start.plusSeconds(30));
        assertThat(inFlight.metricValueId()).isEqualTo(original.metricValueId());
        assertThat(inFlight.computedAt()).isEqualTo(original.computedAt());
        assertThat(inFlight.verifiedAt()).isNull();
        assertThat(costAt(completed).verifiedAt()).isEqualTo(original.computedAt());
    }

    @Test void latestNewValueWithFailedOriginalRunStaysSelectedWithoutVerification() {
        fixture.clock.at=fixture.start.plus(Duration.ofDays(2));
        closeOriginalCost(cohort.periodEnd().minusSeconds(3600));
        UUID run=reevaluate();
        var latest=costAt(fixture.clock.instant());
        assertThat(latest.metricValueId()).isNotEqualTo(original.metricValueId());
        assertThat(latest.available()).isFalse();
        fixture.seed.sql("UPDATE mart.calculation_run SET state='FAILED',failure_code='SYNTHETIC_FAILURE' WHERE id=:id")
                .param("id",run).update();
        var failed=costAt(fixture.clock.instant());
        assertThat(failed.metricValueId()).isEqualTo(latest.metricValueId());
        assertThat(failed.available()).isFalse();
        assertThat(failed.numericValue()).isNull();
        assertThat(failed.verifiedAt()).isNull();
        assertThat(costAt(fixture.start).metricValueId()).isEqualTo(original.metricValueId());
    }

    @ParameterizedTest @ValueSource(strings={"PERIOD","STORE"})
    void anUnrelatedRunCannotBorrowTheValuesVerificationAssociation(String mismatch) {
        fixture.clock.at=fixture.start.plus(Duration.ofDays(2));
        UUID run=UUID.randomUUID();
        UUID otherStore=UUID.randomUUID();
        fixture.seed.sql("""
            INSERT INTO core.store
            SELECT (jsonb_populate_record(NULL::core.store,to_jsonb(s)||jsonb_build_object(
              'id',CAST(:id AS uuid),'code',CAST(:code AS text),'native_store_key',CAST(:code AS text)))).*
            FROM core.store s WHERE s.id=:store
            """).param("id",otherStore).param("code",otherStore.toString()).param("store",fixture.graph.id("store")).update();
        fixture.seed.sql("""
            INSERT INTO mart.calculation_run
            SELECT (jsonb_populate_record(NULL::mart.calculation_run,to_jsonb(r)||jsonb_build_object(
              'id',CAST(:newRun AS uuid),'state','RUNNING','started_at',CAST(:at AS timestamptz),'completed_at',NULL,
              'period_start',CASE WHEN :mismatch='PERIOD' THEN r.period_start-interval '1 hour' ELSE r.period_start END,
              'period_end',CASE WHEN :mismatch='PERIOD' THEN r.period_end-interval '1 hour' ELSE r.period_end END,
              'store_ref_id',CASE WHEN :mismatch='STORE' THEN CAST(:otherStore AS uuid) ELSE r.store_ref_id END))).*
            FROM mart.calculation_run r WHERE r.id=:originalRun
        """).param("newRun",run).param("at",Timestamp.from(fixture.clock.instant())).param("mismatch",mismatch)
                .param("otherStore",otherStore).param("originalRun",original.verificationRunId()).update();
        assertThatThrownBy(()->fixture.jdbc.sql("INSERT INTO mart.metric_value_evaluation(metric_value_id,calculation_run_id,evaluated_at) VALUES(:value,:run,:at)")
                .param("value",original.metricValueId()).param("run",run).param("at",Timestamp.from(fixture.clock.instant())).update())
                .isInstanceOf(DataAccessException.class);
        assertThat(evaluationCount(original.metricValueId())).isEqualTo(1);
        assertThat(costAt(fixture.clock.instant()).verificationRunId()).isEqualTo(original.verificationRunId());
    }

    @Test void applicationCanReadButCannotUpdateOrDeleteEvaluationHistory() {
        assertThat(evaluationCount(original.metricValueId())).isEqualTo(1);
        assertThatThrownBy(()->fixture.jdbc.sql("UPDATE mart.metric_value_evaluation SET evaluated_at=evaluated_at+interval '1 day' WHERE metric_value_id=:id")
                .param("id",original.metricValueId()).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->fixture.jdbc.sql("DELETE FROM mart.metric_value_evaluation WHERE metric_value_id=:id")
                .param("id",original.metricValueId()).update()).isInstanceOf(DataAccessException.class);
        assertThat(evaluationCount(original.metricValueId())).isEqualTo(1);
        assertThat(costAt(fixture.start).verifiedAt()).isEqualTo(original.verifiedAt());
    }

    @Test void anAlreadyCompletedRunCannotAcquireAnEvaluationAssociationAfterTheFact() {
        UUID run=UUID.randomUUID();
        fixture.seed.sql("""
            INSERT INTO mart.calculation_run
            SELECT (jsonb_populate_record(NULL::mart.calculation_run,to_jsonb(r)||jsonb_build_object('id',CAST(:id AS uuid)))).*
            FROM mart.calculation_run r WHERE r.id=:originalRun
            """).param("id",run).param("originalRun",original.verificationRunId()).update();
        assertThatThrownBy(()->fixture.jdbc.sql("INSERT INTO mart.metric_value_evaluation(metric_value_id,calculation_run_id,evaluated_at) VALUES(:value,:run,:at)")
                .param("value",original.metricValueId()).param("run",run).param("at",Timestamp.from(fixture.start)).update())
                .isInstanceOf(DataAccessException.class);
        assertThat(evaluationCount(original.metricValueId())).isEqualTo(1);
    }

    private UUID reevaluate() {
        return fixture.analytics.runForWindow(fixture.graph.id("store"),MetricWindow.D30,cohort,"BACKFILL",null).calculationRunId();
    }
    private MetricValueView costAt(Instant at) {
        return fixture.metrics.currentValuesCoveringAt(SubjectKind.PLATFORM_LISTING_VARIANT,fixture.graph.id("listingVariant"),
                MetricWindow.D30,cohort.periodStart(),cohort.periodEnd(),at).get(MetricCode.UNIT_COST);
    }
    private void closeOriginalCost(Instant end) {
        fixture.sql("UPDATE core.cost_version SET effective_to=:end,updated_at=:at WHERE organization_id=:org AND effective_to IS NULL")
                .param("end",Timestamp.from(end)).update();
    }
    private String valueRow(UUID value) {
        return fixture.seed.sql("SELECT to_jsonb(v)::text FROM mart.metric_value v WHERE id=:id")
                .param("id",value).query(String.class).single();
    }
    private int evaluationCount(UUID value) {
        return fixture.jdbc.sql("SELECT count(*) FROM mart.metric_value_evaluation WHERE metric_value_id=:id")
                .param("id",value).query(Integer.class).single();
    }
}
