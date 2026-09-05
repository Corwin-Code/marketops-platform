package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.AdBidCommandRepository;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWritePort;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWriteResult;
import com.mimococo.marketops.marketplaceintegration.port.InMemoryObjectStoragePort;
import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.shared.internal.config.ProductionWriteProperties;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/** Real creator, Java worker, repository, durable attempts/custody and frozen classification; the transport port is synthetic. */
@SpringBootTest(properties={"marketops.ad-bid-write.retry-delay-seconds=1","marketops.ad-bid-write.worker-enabled=false"})
@ActiveProfiles("ci")
@Import(AdvertisingRetryWorkerIT.Runtime.class)
class AdvertisingRetryWorkerIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    @Autowired DataSource application;
    @Autowired ApplicationContext context;
    @Autowired AdBidCommandRepository commands;
    @Autowired ScriptedPort transport;
    @Autowired ProductionWriteProperties productionWrites;
    private DataSource migration,admin;
    private AdvertisingControlProofFixture f;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DATABASE::getJdbcUrl);
        r.add("spring.datasource.username",TestDatabase::applicationRole);
        r.add("spring.datasource.password",TestDatabase::applicationPassword);
        r.add("spring.flyway.user",TestDatabase::migrationRole);
        r.add("spring.flyway.password",TestDatabase::migrationPassword);
    }
    @BeforeEach void setup() {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        transport.reset();
    }
    private void fixture(boolean nativeKey) throws Exception {
        f=new AdvertisingControlProofFixture(migration,application,admin,nativeKey);
        assertThat(state()).isEqualTo("PENDING");
        assertThat(commands.getClass().getName()).contains("AdBidCommandRepository");
        assertThat(f.reasons()).isEmpty();
    }

    @Test void knownNativeTaskStatusTimeoutRemainsReadOnlyThenResolvedStatusAndPriorPermitSameKeyRetry() throws Exception {
        fixture(true);
        transport.add("APPLY","{\"accepted\":true,\"taskId\":\"original-task\"}","original-task");
        transport.add("STATUS_ENQUIRY",null,null);
        transport.add("STATUS_ENQUIRY","{\"state\":\"SUCCESS\"}",null);
        transport.add("READBACK",f.prior(),null);
        successfulRetryFrames();
        advanceDue();assertThat(state()).isEqualTo("PLATFORM_PENDING");
        assertIdleObservationHasNoTransportLease();
        advanceDue();assertThat(state()).isEqualTo("PLATFORM_PENDING");
        assertIdleObservationHasNoTransportLease();
        assertThat(operations()).containsExactly("APPLY","STATUS_ENQUIRY");
        assertThat(f.retryProven()).isFalse();
        advanceDue();assertThat(state()).isEqualTo("RETRY_WAIT");
        assertThat(operations()).containsExactly("APPLY","STATUS_ENQUIRY","STATUS_ENQUIRY","READBACK");
        assertThat(transport.requests.stream().filter(r->r.operation()==AdBidWriteRequest.Operation.STATUS_ENQUIRY)
                .map(AdBidWriteRequest::nativeTaskKey).toList()).containsExactly("original-task","original-task");
        // SUCCESS plus current prior is the governed verified-key reentry condition;
        // it is not a claim that the earlier APPLY never occurred.
        assertThat(f.retryProven()).isTrue();
        finishRetry();
        assertSameCommandAndApplyIdentity();
    }

    @Test void exactNotAppliedBytesAndIndependentPriorDriveTheActualWorkerIntoGovernedRetry() throws Exception {
        fixture(false);
        transport.add("APPLY","{\"notApplied\":true}",null);
        transport.add("READBACK",f.prior(),null);
        successfulRetryFrames();
        advanceDue();
        assertThat(state()).isEqualTo("RETRY_WAIT");
        assertThat(f.app.sql("SELECT error_code FROM ops.ad_bid_command_attempt WHERE command_id=:id AND purpose='APPLY'")
                .param("id",f.command).query(String.class).single()).isEqualTo("provider_explicit_not_applied");
        assertThat(f.retryProven()).isTrue();
        finishRetry();assertSameCommandAndApplyIdentity();
    }

    @Test void anUnaddressableApplyTimeoutWithoutNativeIdempotencyCannotBeRetriedAfterPriorReadback() throws Exception {
        fixture(false);
        transport.add("APPLY",null,null);transport.add("READBACK",f.prior(),null);
        advanceDue();assertThat(state()).isEqualTo("UNKNOWN_REQUIRES_READBACK");
        assertThat(runOnce()).isZero();assertThat(operations()).containsExactly("APPLY");
        var unresolved=commands.row(f.command).orElseThrow();
        commands.requestReadback(f.command,unresolved.fenceToken());
        advanceDue();assertThat(state()).isEqualTo("READBACK_MISMATCH");
        assertThat(f.retryProven()).isFalse();
        assertThat(runOnce()).isZero();assertThat(operations()).containsExactly("APPLY","READBACK");
        assertThat(commands.nativeTaskKey(f.command)).isEmpty();
        assertBoundary();
    }

    @Test void aThirdCurrentBidRoutesTheActualWorkerToInvestigationAndNeverAnotherApply() throws Exception {
        fixture(false);
        transport.add("APPLY","{\"notApplied\":true}",null);
        transport.add("READBACK","{\"bid\":25,\"currency\":\"RUB\",\"unit\":\"CURRENCY_MAJOR\"}",null);
        advanceDue();
        assertThat(state()).isEqualTo("LATER_CHANGE_OR_MISMATCH_INVESTIGATION");
        assertThat(f.retryProven()).isFalse();assertThat(runOnce()).isZero();
        assertThat(operations()).containsExactly("APPLY","READBACK");
        assertBoundary();
    }

    @Test void aThirdBidObservedAtTheNewRetryFenceCancelsThePreviouslyProvenReentry() throws Exception {
        fixture(false);
        transport.add("APPLY","{\"notApplied\":true}",null);
        transport.add("READBACK",f.prior(),null);
        transport.add("READBACK","{\"bid\":25,\"currency\":\"RUB\",\"unit\":\"CURRENCY_MAJOR\"}",null);
        advanceDue();assertThat(state()).isEqualTo("RETRY_WAIT");
        long priorProofFence=commands.row(f.command).orElseThrow().fenceToken();
        assertThat(f.retryProven()).isTrue();
        advanceDue();assertThat(state()).isEqualTo("LATER_CHANGE_OR_MISMATCH_INVESTIGATION");
        assertThat(commands.row(f.command).orElseThrow().fenceToken()).isGreaterThan(priorProofFence);
        assertThat(f.retryProven()).isFalse();assertThat(runOnce()).isZero();
        assertThat(operations()).containsExactly("APPLY","READBACK","READBACK");
        assertPreflightCleared();assertBoundary();
    }

    @Test void expiredRetryPreflightLeaseBecomesUnknownAndCannotReuseTheOldProof() throws Exception {
        fixture(false);
        transport.add("APPLY","{\"notApplied\":true}",null);transport.add("READBACK",f.prior(),null);
        advanceDue();assertThat(state()).isEqualTo("RETRY_WAIT");assertThat(f.retryProven()).isTrue();
        awaitClaimable();
        long fence=commands.lease(f.command,"retry-crash-fixture",60);
        assertThat(f.app.sql("SELECT retry_preflight_fence FROM ops.ad_bid_command WHERE id=:id")
                .param("id",f.command).query(Long.class).single()).isEqualTo(fence);
        assertThat(f.retryProven()).isFalse();
        assertSqlState(()->commands.transition(f.command,fence,"retry-crash-fixture","EXECUTING",null,null,null),"MO092");
        assertSqlState(()->f.app.sql("UPDATE ops.ad_bid_command SET retry_preflight_fence=NULL WHERE id=:id")
                .param("id",f.command).update(),"42501");
        expireCurrentLease();
        assertThat(commands.recoverExpiredLeases()).isEqualTo(1);
        assertThat(state()).isEqualTo("UNKNOWN_REQUIRES_READBACK");
        assertPreflightCleared();assertThat(f.retryProven()).isFalse();
        assertSqlState(()->commands.lease(f.command,"cannot-rewrite",60),"MO091");
        assertThat(runOnce()).isZero();assertBoundary();
    }

    @Test void anExactTargetAtTheNewRetryFenceCompletesWithoutAnotherApply() throws Exception {
        fixture(false);
        transport.add("APPLY","{\"notApplied\":true}",null);transport.add("READBACK",f.prior(),null);
        transport.add("READBACK","{\"bid\":20,\"currency\":\"RUB\",\"unit\":\"CURRENCY_MAJOR\"}",null);
        advanceDue();assertThat(state()).isEqualTo("RETRY_WAIT");
        advanceDue();assertThat(state()).isEqualTo("READBACK_MATCHED");
        assertThat(operations()).containsExactly("APPLY","READBACK","READBACK");
        assertThat(runOnce()).isZero();assertPreflightCleared();assertBoundary();
    }

    @Test void anUnreadableNewRetryFenceCannotReuseThePreviousPriorProof() throws Exception {
        fixture(false);
        transport.add("APPLY","{\"notApplied\":true}",null);transport.add("READBACK",f.prior(),null);
        transport.add("READBACK",null,null);
        advanceDue();assertThat(state()).isEqualTo("RETRY_WAIT");
        advanceDue();assertThat(state()).isEqualTo("UNKNOWN_REQUIRES_READBACK");
        assertThat(f.retryProven()).isFalse();assertThat(runOnce()).isZero();
        assertThat(operations()).containsExactly("APPLY","READBACK","READBACK");
        assertPreflightCleared();assertBoundary();
    }

    @Test void anExpiredFirstApplyLeaseStillReturnsToPendingWithoutRetryAuthority() throws Exception {
        fixture(false);
        long fence=commands.lease(f.command,"first-claim-crash-fixture",60);
        assertPreflightCleared();expireCurrentLease();
        assertThat(commands.recoverExpiredLeases()).isEqualTo(1);
        assertThat(state()).isEqualTo("PENDING");assertPreflightCleared();
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_bid_command_attempt WHERE command_id=:id")
                .param("id",f.command).query(Integer.class).single()).isZero();
        commands.transition(f.command,fence,null,"TERMINATED_WITHOUT_PROVIDER_CALL","synthetic_fixture_finished",null,null);
        assertBoundary();
    }

    private void successfulRetryFrames() {
        transport.add("READBACK",f.prior(),null);
        transport.add("APPLY","{\"accepted\":true,\"taskId\":\"retry-task\"}","retry-task");
        transport.add("STATUS_ENQUIRY","{\"state\":\"SUCCESS\"}",null);
        transport.add("READBACK","{\"bid\":20,\"currency\":\"RUB\",\"unit\":\"CURRENCY_MAJOR\"}",null);
    }
    private void finishRetry() throws Exception {
        advanceDue();assertThat(state()).isEqualTo("PLATFORM_PENDING");
        advanceDue();assertThat(state()).isEqualTo("READBACK_MATCHED");
        assertThat(runOnce()).isZero();
        assertBoundary();
    }
    private void assertSameCommandAndApplyIdentity() {
        var applies=transport.requests.stream().filter(r->r.operation()==AdBidWriteRequest.Operation.APPLY).toList();
        assertThat(applies).hasSize(2);
        assertThat(applies.get(1).idempotencyKey()).isEqualTo(applies.get(0).idempotencyKey());
        assertThat(applies.get(1).targetBid()).isEqualTo(applies.get(0).targetBid());
        assertThat(applies.get(1).nativeObjectKey()).isEqualTo(applies.get(0).nativeObjectKey());
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org")
                .param("org",f.graph.id("organization")).query(Integer.class).single()).isEqualTo(1);
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_bid_command_attempt WHERE command_id=:id AND purpose='APPLY'")
                .param("id",f.command).query(Integer.class).single()).isEqualTo(2);
        assertThat(f.app.sql("""
            SELECT retry_apply.fence_token=retry_read.fence_token
              AND first_read.fence_token<retry_read.fence_token
              AND retry_read.match_state='MATCHES_PRIOR'
            FROM (SELECT * FROM ops.ad_bid_command_attempt WHERE command_id=:id AND purpose='APPLY'
              ORDER BY attempt_no DESC LIMIT 1) retry_apply
            CROSS JOIN LATERAL (SELECT read_attempt.fence_token,rb.match_state
              FROM ops.ad_bid_command_readback rb JOIN ops.ad_bid_command_attempt read_attempt ON read_attempt.id=rb.attempt_id
              WHERE rb.command_id=:id AND read_attempt.completed_at<retry_apply.started_at
              ORDER BY rb.observed_at DESC,rb.id DESC LIMIT 1) retry_read
            CROSS JOIN LATERAL (SELECT fence_token FROM ops.ad_bid_command_attempt
              WHERE command_id=:id AND purpose='READBACK' ORDER BY attempt_no LIMIT 1) first_read
            """).param("id",f.command).query(Boolean.class).single()).isTrue();
        assertPreflightCleared();
    }
    private void assertIdleObservationHasNoTransportLease() {
        var row=commands.row(f.command).orElseThrow();
        assertThat(f.app.sql("SELECT lease_owner IS NULL AND lease_expires_at IS NULL FROM ops.ad_bid_command WHERE id=:id")
                .param("id",f.command).query(Boolean.class).single()).isTrue();
        assertSqlState(()->commands.openAttempt(UUID.randomUUID(),f.command,"STATUS_ENQUIRY",row.fenceToken(),
                "not-leased","a".repeat(64),"synthetic-idle-probe"),"MO090");
    }
    private void assertPreflightCleared() {
        assertThat(f.app.sql("SELECT retry_preflight_fence IS NULL FROM ops.ad_bid_command WHERE id=:id")
                .param("id",f.command).query(Boolean.class).single()).isTrue();
    }
    private void expireCurrentLease() {
        // Inject only the clock failure; the phase and marker came from the real lease function.
        f.seed.sql("UPDATE ops.ad_bid_command SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id",f.command).update();
    }
    private void awaitClaimable() throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(!commands.claimable(Instant.now(),10).contains(f.command)) {
            if(System.nanoTime()>=deadline)throw new AssertionError("Governed retry never became due");
            Thread.sleep(50);
        }
    }
    private void assertSqlState(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String expected) {
        Throwable refusal=catchThrowable(action);
        assertThat(refusal).isNotNull();
        while(refusal.getCause()!=null)refusal=refusal.getCause();
        assertThat(refusal).isInstanceOf(SQLException.class);
        assertThat(((SQLException)refusal).getSQLState()).isEqualTo(expected);
    }
    private void assertBoundary() {
        assertThat(transport.frames).isEmpty();
        assertThat(productionWrites.getEnabled()).isFalse();
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_bid_command WHERE platform_code IN('OZON','WILDBERRIES')")
                .query(Integer.class).single()).isZero();
        assertThat(f.app.sql("SELECT count(*) FROM raw.ad_bid_response_observation WHERE command_id=:id AND evidence_class<>'PROTOCOL_FIXTURE'")
                .param("id",f.command).query(Integer.class).single()).isZero();
        assertThat(f.app.sql("SELECT count(*) FROM ops.ad_bid_command_attempt WHERE command_id=:id AND completed_at IS NULL")
                .param("id",f.command).query(Integer.class).single()).isZero();
    }
    private String state() { return commands.row(f.command).orElseThrow().state(); }
    private List<String> operations() { return transport.requests.stream().map(r->r.operation().name()).toList(); }
    private int runOnce() { return ReflectionTestUtils.invokeMethod(context.getBean("adBidCommandWorker"),"runOnce",Instant.now(),10); }
    private void advanceDue() throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        do {
            if(runOnce()==1)return;
            Thread.sleep(50);
        } while(System.nanoTime()<deadline);
        throw new AssertionError("Real worker did not advance due command; current state="+state());
    }

    static final class ScriptedPort implements AdBidWritePort {
        record Frame(String operation,String body,String task) { }
        final ArrayDeque<Frame> frames=new ArrayDeque<>();
        final List<AdBidWriteRequest> requests=new ArrayList<>();
        void add(String operation,String body,String task) { frames.add(new Frame(operation,body,task)); }
        void reset() { frames.clear();requests.clear(); }
        @Override public AdBidWriteResult perform(AdBidWriteRequest request) {
            Frame frame=frames.removeFirst();assertThat(request.operation().name()).isEqualTo(frame.operation());
            requests.add(request);
            if(frame.body()==null) return new AdBidWriteResult(AdBidWriteResult.Outcome.TIMEOUT,null,null,null,null,null,null,
                    Instant.now(),"synthetic_timeout",null);
            // Deliberately propose TIMEOUT even for conclusive bytes: the actual
            // repository must route on the DB's frozen-shape classification.
            return new AdBidWriteResult(AdBidWriteResult.Outcome.TIMEOUT,null,frame.task(),null,null,null,
                    frame.body().getBytes(StandardCharsets.UTF_8),Instant.now(),"synthetic_proposal",
                    new AdBidWriteResult.Response(200,Map.of(),request.digest(),"PROTOCOL_FIXTURE",true));
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Runtime {
        @Bean @Primary ScriptedPort scriptedAdBidPort() { return new ScriptedPort(); }
        @Bean @Primary ObjectStoragePort fixtureObjects() { return new InMemoryObjectStoragePort(); }
    }
}
