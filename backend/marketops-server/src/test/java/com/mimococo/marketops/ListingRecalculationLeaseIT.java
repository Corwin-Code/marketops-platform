package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.listingconversion.RecalculationClass;
import com.mimococo.marketops.listingconversion.internal.application.ListingHealthService;
import com.mimococo.marketops.listingconversion.internal.application.RecalculationService;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.GovernanceRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Isolated real PostgreSQL claims and atomic result publication; no marketplace worker is enabled. */
@SpringBootTest
@ActiveProfiles("ci")
class ListingRecalculationLeaseIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    @Autowired GovernanceRepository queue;
    @Autowired RecalculationService worker;
    @Autowired ListingHealthService health;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    private ListingConversionFixture fixture;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
    }

    @BeforeEach void fixture() throws Exception {
        fixture=new ListingConversionFixture(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword()),
                new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword()),
                new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()));
    }

    private UUID enqueue(RecalculationClass kind) {
        UUID id=UUID.randomUUID();
        queue.enqueue(id,fixture.id("organization"),fixture.id("listing"),kind,
                "synthetic-lease/"+id,Instant.now().minusSeconds(3600),Instant.now().minusSeconds(1));
        return id;
    }
    private void expire(UUID id) {
        jdbc.sql("UPDATE ops.lc_recalculation_queue SET leased_until=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id",id).update();
    }
    private long results() {
        return jdbc.sql("SELECT count(*) FROM mart.lc_listing_health WHERE platform_listing_id=:id")
                .param("id",fixture.id("listing")).query(Long.class).single();
    }

    @Test void futureAcceptedTimeIsNotClaimedOrRewritten() {
        UUID id=UUID.randomUUID();
        Instant future=Instant.now().plusSeconds(86400);
        queue.enqueue(id,fixture.id("organization"),fixture.id("listing"),RecalculationClass.RISK,
                "synthetic future accepted clock",Instant.now(),future);
        assertThat(queue.claim(1)).isEmpty();
        assertThat(jdbc.sql("SELECT state='QUEUED' AND started_at IS NULL AND accepted_at>clock_timestamp() FROM ops.lc_recalculation_queue WHERE id=:id")
                .param("id",id).query(Boolean.class).single()).isTrue();
    }

    @Test void workerPublishesExactResultsOnceAndPreservesTheSourceClock() {
        var risk=enqueue(RecalculationClass.RISK);
        var ordinary=enqueue(RecalculationClass.ORDINARY);
        long before=results();
        assertThat(worker.runOnce(10)).isEqualTo(2);
        assertThat(results()).isEqualTo(before+2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ops.lc_recalculation_queue q JOIN mart.lc_listing_health h ON h.id=q.health_result_id
                WHERE q.id IN (:risk,:ordinary) AND q.state='FINISHED' AND q.calculation_run_id=h.calculation_run_id
                  AND h.platform_listing_id=q.platform_listing_id AND h.organization_id=q.organization_id
                  AND q.source_time<q.accepted_at-interval '50 minutes' AND q.leased_until IS NULL
                """).param("risk",risk).param("ordinary",ordinary).query(Long.class).single()).isEqualTo(2);
        assertThat(worker.runOnce(10)).isZero();
        assertThat(results()).isEqualTo(before+2);
        assertThatThrownBy(()->expire(risk)).hasMessageContaining("terminal recalculation receipt is immutable");
    }

    @Test void concurrentWorkersClaimOneRowExactlyOnce() throws Exception {
        UUID id=enqueue(RecalculationClass.RISK);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return queue.claim(1);});
            var second=pool.submit(()->{start.await();return queue.claim(1);});
            start.countDown();
            var claims=new java.util.ArrayList<>(first.get(15,TimeUnit.SECONDS));
            claims.addAll(second.get(15,TimeUnit.SECONDS));
            assertThat(claims).hasSize(1);
            assertThat(claims.getFirst().id()).isEqualTo(id);
            assertThat(queue.finish(claims.getFirst(),null,"synthetic_end_of_claim_test")).isTrue();
        }
    }

    @Test void crashedClaimIsRecoveredAndPreviousGenerationCannotAcknowledgeOrFailIt() {
        UUID id=enqueue(RecalculationClass.RISK);
        var stale=queue.claim(1).getFirst();
        assertThatThrownBy(()->jdbc.sql("UPDATE ops.lc_recalculation_queue SET lease_generation=NULL WHERE id=:id")
                .param("id",id).update()).hasMessageContaining("cannot be erased or rewound");
        expire(id);
        var current=queue.claim(1).getFirst();
        assertThat(current.leaseGeneration()).isEqualTo(stale.leaseGeneration()+1);
        assertThat(queue.lockClaim(stale)).isFalse();
        assertThat(queue.finish(stale,null,"stale_worker")).isFalse();
        var transaction=new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status->{
            assertThat(queue.lockClaim(current)).isTrue();
            var result=health.recompute(current.listingId(),"SCHEDULED",null);
            assertThat(queue.finish(current,result.id(),null)).isTrue();
        });
        assertThat(queue.finish(stale,null,"stale_worker")).isFalse();
        assertThat(worker.runOnce(1)).isZero();
    }

    @Test void expirationDuringComputationRollsBackTheResultAndAllowsRecovery() {
        UUID id=enqueue(RecalculationClass.ORDINARY);
        var claim=queue.claim(1).getFirst();
        long before=results();
        var transaction=new TransactionTemplate(transactions);
        assertThatThrownBy(()->transaction.executeWithoutResult(status->{
            assertThat(queue.lockClaim(claim)).isTrue();
            var result=health.recompute(claim.listingId(),"SCHEDULED",null);
            expire(id);
            assertThat(queue.finish(claim,result.id(),null)).isFalse();
            throw new IllegalStateException("synthetic expired publication rollback");
        })).hasMessageContaining("synthetic expired publication rollback");
        assertThat(results()).isEqualTo(before);
        expire(id);
        assertThat(worker.runOnce(1)).isEqualTo(1);
        assertThat(results()).isEqualTo(before+1);
    }

    @Test void completionWithoutActualResultIsRejectedAndFailureIsNotSuccessfulLatency() {
        enqueue(RecalculationClass.FULL_REVIEW);
        var claim=queue.claim(1).getFirst();
        assertThatThrownBy(()->queue.finish(claim,null,null)).hasMessageContaining("actual listing result");
        assertThat(queue.finish(claim,null,"synthetic_failure")).isTrue();
        var view=worker.queue(fixture.id("organization"),10).getFirst();
        assertThat(view.state()).isEqualTo("FAILED");
        assertThat(view.withinTarget()).isFalse();
    }
}
