package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.*;

import com.mimococo.marketops.advertisingefficiency.AdvertisingOutcomeView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real append-only observations and app-role readers; legacy metadata is a synthetic import oracle. */
@SpringBootTest
@ActiveProfiles("ci")
class AdvertisingOutcomeInferenceScopeIT {
    @Autowired ApplicationContext context;
    AdvertisingFrozenOutcomeIT fixture;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        AdvertisingFrozenOutcomeIT.properties(registry);
    }
    @BeforeEach void setup(TestInfo info) throws Exception {
        fixture=new AdvertisingFrozenOutcomeIT();
        context.getAutowireCapableBeanFactory().autowireBean(fixture);
        fixture.fixture(info);
    }
    @Test void realObservationPersistsAssociationAndDoesNotAllowHistoricalMarkerRewrite() {
        UUID id=observe();fixture.assertAssociationMarker(id);
        assertThatThrownBy(()->fixture.seed.sql("UPDATE ops.ad_outcome_axes SET input_snapshot=input_snapshot-'inferenceScope' WHERE observation_id=:id")
                .param("id",id).update()).hasMessageContaining("permanent record");
        fixture.assertAssociationMarker(id);
    }
    @ParameterizedTest
    @ValueSource(strings={"{}","{\"inferenceScope\":null}","{\"inferenceScope\":\"CAUSAL_INCREMENTALITY\"}","{\"inferenceScope\":123}"})
    void legacyOrUnsupportedStoredMarkerRemainsUnknownWithoutChangingTheVerdict(String metadata) {
        UUID original=observe();
        UUID revision=appendSyntheticLegacyRevision(original,metadata,true);
        var view=read(revision);
        assertThat(view.inferenceScope()).isEqualTo("UNKNOWN");
        assertThat(view.verdict()).isEqualTo(read(original).verdict());
        assertThat(view.axes()).isNotNull();
        assertThat(view.supersedesObservationId()).isEqualTo(original);
        fixture.assertAssociationMarker(original);
    }
    @Test void legacyObservationWithoutAxesRemainsUnknown() {
        UUID original=observe();UUID revision=appendSyntheticLegacyRevision(original,"{}",false);
        assertThat(read(revision).axes()).isNull();
        assertThat(read(revision).inferenceScope()).isEqualTo("UNKNOWN");
        fixture.assertAssociationMarker(original);
    }
    @Test void actualOutcomeInstantsRemainIdenticalAcrossUtcAndStoreDatabaseSessions() throws Exception {
        UUID id=observe();
        var expected=List.of(fixture.from,fixture.to,fixture.read);
        // The actual Outcome service wrote this row. Session presentation must not shift
        // stored instants or their epoch value; no generic SELECT NOW surrogate is used.
        try(var connection=fixture.application.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                for(String zone:List.of("UTC","Europe/Moscow")) {
                    statement.execute("SET LOCAL TIME ZONE '"+zone+"'");
                    try(var query=connection.prepareStatement("""
                            SELECT window_starts_at,window_ends_at,evaluated_at,
                              extract(epoch FROM window_starts_at),extract(epoch FROM window_ends_at),
                              extract(epoch FROM evaluated_at),extract(timezone FROM evaluated_at),current_user
                            FROM ops.ad_outcome_observation WHERE id=?
                            """)) {
                        query.setObject(1,id);
                        try(var row=query.executeQuery()) {
                            assertThat(row.next()).isTrue();
                            assertThat(row.getString(8)).isEqualTo(com.mimococo.marketops.TestDatabase.applicationRole());
                            assertThat(row.getInt(7)).isEqualTo(zone.equals("UTC")?0:10800);
                            for(int index=0;index<expected.size();index++) {
                                var instant=expected.get(index);
                                assertThat(row.getTimestamp(index+1).toInstant()).as("%s stored clock %s",zone,index).isEqualTo(instant);
                                var epoch=java.math.BigDecimal.valueOf(instant.getEpochSecond())
                                        .add(java.math.BigDecimal.valueOf(instant.getNano(),9));
                                assertThat(row.getBigDecimal(index+4)).as("%s epoch %s",zone,index).isEqualByComparingTo(epoch);
                            }
                            assertThat(row.next()).isFalse();
                        }
                    }
                }
                try(var columns=statement.executeQuery("""
                        SELECT column_name,data_type FROM information_schema.columns
                        WHERE table_schema='ops' AND table_name='ad_outcome_observation'
                          AND column_name IN('window_starts_at','window_ends_at','evaluated_at') ORDER BY column_name
                        """)) {
                    var names=new java.util.ArrayList<String>();
                    while(columns.next()) {
                        names.add(columns.getString(1));
                        assertThat(columns.getString(2)).isEqualTo("timestamp with time zone");
                    }
                    assertThat(names).containsExactly("evaluated_at","window_ends_at","window_starts_at");
                }
                try(var naive=statement.executeQuery("""
                        SELECT schema.nspname||'.'||relation.relname||'.'||attribute.attname AS naive_clock
                        FROM pg_catalog.pg_attribute attribute JOIN pg_catalog.pg_class relation ON relation.oid=attribute.attrelid
                        JOIN pg_catalog.pg_namespace schema ON schema.oid=relation.relnamespace
                        WHERE schema.nspname IN('core','mart','ops','ledger','platform') AND left(relation.relname,3)='ad_'
                          AND relation.relkind IN('r','p') AND attribute.attnum>0 AND NOT attribute.attisdropped
                          AND attribute.atttypid='timestamp without time zone'::regtype
                        ORDER BY naive_clock
                        """)) {
                    var naiveColumns=new java.util.ArrayList<String>();
                    while(naive.next()) naiveColumns.add(naive.getString(1));
                    assertThat(naiveColumns).as("canonical advertising tables cannot store naive clocks").isEmpty();
                }
            }
            connection.rollback();
        }
        assertThat(read(id).windowStartsAt()).isEqualTo(fixture.from);
        assertThat(read(id).windowEndsAt()).isEqualTo(fixture.to);
        assertThat(read(id).evaluatedAt()).isEqualTo(fixture.read);
        fixture.assertAssociationMarker(id);
    }
    private UUID observe() {
        fixture.observedSales("1000",null);fixture.coverage();
        var result=fixture.service.evaluate(fixture.due(),fixture.read).orElseThrow();
        assertThat(result.evaluation().verdict().name()).isEqualTo("UNCHANGED");
        return result.observationId();
    }
    private AdvertisingOutcomeView read(UUID id) {
        return fixture.outcomes.forCommand(fixture.graph.id("organization"),fixture.command,List.of(fixture.graph.id("store")))
                .stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();
    }
    /** Inserts a separate legal revision using migration-role synthetic history; never changes an old row. */
    private UUID appendSyntheticLegacyRevision(UUID original,String metadata,boolean axes) {
        UUID id=UUID.randomUUID();
        fixture.seed.sql("""
                INSERT INTO ops.ad_outcome_observation
                SELECT (jsonb_populate_record(NULL::ops.ad_outcome_observation,to_jsonb(o)||jsonb_build_object(
                  'id',CAST(:id AS uuid),'outcome_stage','OPERATIONAL_REVISED','revision_no',o.revision_no+1,
                  'supersedes_observation_id',o.id,'adjustment_reason','synthetic legacy inference metadata',
                  'input_digest',:digest))).*
                FROM ops.ad_outcome_observation o WHERE o.id=:original
                """).param("id",id).param("original",original)
                .param("digest",com.mimococo.marketops.shared.Digest.ofText(id.toString())).update();
        if(axes) fixture.seed.sql("""
                INSERT INTO ops.ad_outcome_axes
                SELECT (jsonb_populate_record(NULL::ops.ad_outcome_axes,to_jsonb(a)||jsonb_build_object(
                  'observation_id',CAST(:id AS uuid),'input_snapshot',(a.input_snapshot-'inferenceScope')||CAST(:metadata AS jsonb)))).*
                FROM ops.ad_outcome_axes a WHERE a.observation_id=:original
                """).param("id",id).param("original",original).param("metadata",metadata).update();
        return id;
    }
}
