package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.AdvertisingR1Fixture;
import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** One synthetic historical action: actual PostgreSQL precision and actual early-safety evaluation. */
@SpringBootTest @ActiveProfiles("ci") @Import(AdvertisingMixedOrchestrationCapacityIT.Runtime.class)
class AdvertisingMixedPreparationIT {
    static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    @Autowired DataSource application;
    @Autowired AdvertisingOutcomeService outcomes;
    @Autowired AdvertisingOutcomeRepository outcomeRows;
    @Autowired ObjectMapper mapper;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
    }
    @Test void unalignedHistoricalClockExposesRoundedFutureProofAndKeepsReservationActive() throws Exception {
        firstHistoricalEarlySafety(false);
    }
    @Test void oneMicrosecondAlignedPreparationClockPreservesCanonicalEarlySafetyRelease() throws Exception {
        firstHistoricalEarlySafety(true);
    }
    private void firstHistoricalEarlySafety(boolean aligned) throws Exception {
        DataSource migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        DataSource admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        JdbcClient seed=JdbcClient.create(migration);
        // A deterministic remainder reproduces the precision boundary on every OS.
        // This is an explicit synthetic historical INPUT, not a production clock override.
        Instant raw=seed.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant()
                .truncatedTo(ChronoUnit.SECONDS).plusNanos(123456789);
        Instant preparation=aligned?AdvertisingMixedCapacityFixture.preparationInstant(raw):raw;
        var graph=AdvertisingR1Fixture.seedOutcome(migration,AdvertisingMixedCapacityFixture::currentTemplate);
        UUID template;
        try(var app=application.getConnection()) {
            app.setAutoCommit(false);
            AdvertisingR1Fixture.seal(app,graph,AdvertisingR1Fixture.proof(admin,app,graph,graph.id("ownerUser"),null,graph.id("recommendation"),graph.id("approval")));
            template=AdvertisingR1Fixture.createCommand(app,graph);app.commit();
        }
        var fixture=new AdvertisingMixedCapacityFixture(seed,mapper,migration,graph,template);
        var row=fixture.historicalObject(0,preparation.minus(Duration.ofDays(109)));
        fixture.landedReadback(row);
        Instant to=row.from().plus(Duration.ofDays(1)),at=to.plusSeconds(1);
        fixture.companySale("COMPLETED",row.from(),at,"1000","precision-early-"+row.command());
        fixture.coverage(row.from(),to,at);fixture.spend(row,row.from(),to,at,100,null);
        Instant storedSource=seed.sql("""
                SELECT completed_source_updated_at FROM ledger.return_quality_evidence_snapshot
                WHERE organization_id=:org AND platform_listing_variant_id=:listing
                  AND report_window_start=:from AND report_window_end=:to
                """).param("org",graph.id("organization")).param("listing",graph.id("listingVariant"))
                .param("from",Timestamp.from(row.from())).param("to",Timestamp.from(to)).query(Timestamp.class).single().toInstant();
        assertThat(Duration.between(at,storedSource).toNanos()).isEqualTo(aligned?0L:211L);
        var due=outcomeRows.due(graph.id("organization"),row.graph().id("object"),at,10).stream()
                .filter(value->value.nextStage().equals("OPERATIONAL")).findFirst().orElseThrow();
        var result=outcomes.evaluate(due,at).orElseThrow();
        var snapshot=mapper.readTree(seed.sql("SELECT input_snapshot::text FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",result.observationId()).query(String.class).single());
        var company=java.util.stream.StreamSupport.stream(snapshot.path("observation").path("purposeEvidence").spliterator(),false)
                .filter(proof->proof.path("kind").asText().equals("COMPANY_COMPLETED_SALE")).findFirst().orElseThrow();
        assertThat(company.path("eligible").asBoolean()).isEqualTo(aligned);
        assertThat(snapshot.path("observation").path("companySales").path("valueState").asText())
                .isEqualTo(aligned?"AVAILABLE":"NOT_AVAILABLE");
        if(!aligned) assertThat(company.path("reasonCodes").toString())
                .contains("FRESHNESS_BOUND_UNMET:EARLY_COMPLETED_SALES_OUTCOME:COMPANY_COMPLETED_SALE");
        assertThat(result.evaluation().verdict().name()).isEqualTo(aligned?"UNCHANGED":"INDETERMINATE");
        assertThat(seed.sql("SELECT state FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",row.graph().id("reservation")).query(String.class).single()).isEqualTo(aligned?"RELEASED":"ACTIVE");
        assertThat(seed.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",graph.id("gate")).query(Boolean.class).single()).isFalse();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command_attempt WHERE command_id=:id")
                .param("id",row.command()).query(Integer.class).single()).isEqualTo(1);
    }
}
