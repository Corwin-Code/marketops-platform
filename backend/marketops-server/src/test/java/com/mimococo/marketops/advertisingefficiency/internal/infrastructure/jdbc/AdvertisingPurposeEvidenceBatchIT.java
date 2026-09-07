package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.AdvertisingR1Fixture;
import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation.PurposeEvidence;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual application role, constraints and expiry triggers; no provider or transmission path. */
@SpringBootTest @ActiveProfiles("ci") @Import(AdvertisingPurposeEvidenceBatchIT.Runtime.class)
class AdvertisingPurposeEvidenceBatchIT {
    static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    @Autowired AdvertisingProjectionRepository projection;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WriteTrace trace;
    AdvertisingR1Fixture.Graph graph;
    UUID calculation, profile;
    Instant at;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username",TestDatabase::applicationRole);
        properties.add("spring.datasource.password",TestDatabase::applicationPassword);
        properties.add("spring.flyway.user",TestDatabase::migrationRole);
        properties.add("spring.flyway.password",TestDatabase::migrationPassword);
    }

    @BeforeEach void topology() throws Exception {
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        graph=AdvertisingR1Fixture.seed(migration);
        calculation=UUID.randomUUID();
        profile=JdbcClient.create(migration).sql("SELECT id FROM core.ad_freshness_profile WHERE organization_id=:org ORDER BY id LIMIT 1")
                .param("org",graph.id("organization")).query(UUID.class).single();
        at=jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
        trace.writes.set(0);
    }

    @Test void fortyEightPurposeRowsAndAllExpiryDeadlinesMatchTheOriginalScalarWriter() {
        var rows=new ArrayList<PurposeEvidence>();
        for (String purpose:List.of("QUEUE_OBSERVATION","TASK_ACTIVATION","PROTECTION_RECOMMENDATION",
                "OPTIMIZATION_RECOMMENDATION","PROTECTION_BID_WRITE","OPTIMIZATION_BID_WRITE")) {
            for (String kind:List.of("OFFICIAL_AD_SPEND","OFFICIAL_AD_TRAFFIC","AD_LINKED_SALE_EVENT","COST_AND_FEE",
                    "AD_OBJECT_CONFIGURATION","AFFECTED_SET","SELLABILITY","AVAILABILITY")) {
                int state=rows.size()%4;
                rows.add(new PurposeEvidence(purpose,kind,state==1?null:profile,
                        state==1?null:at.minusSeconds(5),state==1?null:at,
                        state==1?null:at.plusSeconds(state==3?-1:7200),state==0,
                        state==0?List.of():List.of("UNRESOLVED","SOURCE_OR_PURPOSE_NOT_CURRENT")));
            }
        }
        Snapshot scalar=writeAndRollback(rows,false);
        Snapshot batch=writeAndRollback(rows,true);
        assertThat(scalar.evidence()).hasSize(48);
        assertThat(batch.evidence()).containsExactlyElementsOf(scalar.evidence());
        assertThat(batch.deadlines()).containsExactlyElementsOf(scalar.deadlines());
        assertThat(batch.deadlines()).hasSize(24);
        assertThat(scalar.statements()).isEqualTo(48);
        assertThat(batch.statements()).isEqualTo(1);
    }

    @Test void boundedChunksKeepEveryRowAndDeadlineWithoutTruncatingLargerInputs() {
        // Synthetic transport boundary values; this does not introduce new product evidence kinds.
        var rows=largeTransportInput(257);
        Snapshot scalar=writeAndRollback(rows,false), batch=writeAndRollback(rows,true);
        assertThat(batch.evidence()).hasSize(257).containsExactlyElementsOf(scalar.evidence());
        assertThat(batch.deadlines()).hasSize(257).containsExactlyElementsOf(scalar.deadlines());
        assertThat(batch.statements()).isEqualTo(3);
        assertThat(scalar.statements()).isEqualTo(257);
    }

    @Test void duplicateInLaterChunkRollsBackAllRowsAndTriggerSideEffects() {
        var rows=new ArrayList<>(largeTransportInput(128));
        rows.add(rows.getFirst());
        var before=deadlines();
        assertThatThrownBy(()->projection.recordPurposeEvidenceBatch(graph.id("caseId"),graph.id("organization"),calculation,rows))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(stored()).isEmpty();
        assertThat(deadlines()).containsExactlyElementsOf(before);
        assertThat(trace.writes.get()).isEqualTo(2);
    }

    @Test void invalidEligibleEvidenceStillFailsAndEmptyInputMakesNoStatement() {
        projection.recordPurposeEvidenceBatch(graph.id("caseId"),graph.id("organization"),calculation,List.of());
        assertThat(trace.writes.get()).isZero();
        var invalid=new PurposeEvidence("PROTECTION_BID_WRITE","SELLABILITY",null,null,null,null,true,List.of());
        assertThatThrownBy(()->projection.recordPurposeEvidenceBatch(graph.id("caseId"),graph.id("organization"),calculation,List.of(invalid)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(stored()).isEmpty();
    }

    private List<PurposeEvidence> largeTransportInput(int count) {
        return java.util.stream.IntStream.range(0,count).mapToObj(i->new PurposeEvidence(
                "QUEUE_OBSERVATION","SYNTHETIC_TRANSPORT_KIND_"+i,null,null,at,at.plusSeconds(7200),false,List.of("UNKNOWN"))).toList();
    }

    private Snapshot writeAndRollback(List<PurposeEvidence> rows,boolean batch) {
        trace.writes.set(0);
        return new TransactionTemplate(transactions).execute(status->{
            if(batch) projection.recordPurposeEvidenceBatch(graph.id("caseId"),graph.id("organization"),calculation,rows);
            else for(var row:rows) projection.recordPurposeEvidence(graph.id("caseId"),graph.id("organization"),calculation,row);
            Snapshot result=new Snapshot(stored(),deadlines(),trace.writes.get());
            status.setRollbackOnly();
            return result;
        });
    }

    private List<String> stored() {
        return jdbc.sql("SELECT to_jsonb(e)::text FROM mart.ad_case_purpose_evidence e WHERE case_id=:case AND calculation_id=:calculation ORDER BY decision_purpose,evidence_kind")
                .param("case",graph.id("caseId")).param("calculation",calculation).query(String.class).list();
    }

    private List<String> deadlines() {
        return jdbc.sql("""
                SELECT jsonb_build_object('organization',organization_id,'object',ad_native_object_id,
                    'trigger',trigger_class,'source',source_reference,'due',due_at)::text
                FROM ops.ad_recalculation_due WHERE organization_id=:org AND source_reference LIKE :prefix
                    AND due_at>=:future ORDER BY source_reference,due_at
                """).param("org",graph.id("organization")).param("prefix","purpose:"+graph.id("caseId")+":%")
                .param("future",Timestamp.from(at.plusSeconds(7100))).query(String.class).list();
    }

    record Snapshot(List<String> evidence,List<String> deadlines,int statements) { }

    @TestConfiguration static class Runtime {
        @Bean WriteTrace writeTrace(){return new WriteTrace();}
        @Bean @Primary JdbcClient tracedJdbc(DataSource source,WriteTrace trace) {
            return JdbcClient.create(new DelegatingDataSource(new TransactionAwareDataSourceProxy(source)) {
                @Override public Connection getConnection() throws java.sql.SQLException {return trace.connection(super.getConnection());}
                @Override public Connection getConnection(String user,String password) throws java.sql.SQLException {return trace.connection(super.getConnection(user,password));}
            });
        }
    }

    static class WriteTrace {
        final AtomicInteger writes=new AtomicInteger();
        Connection connection(Connection delegate) {
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try {
                    Object result=method.invoke(delegate,args);
                    if(method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            && sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("INSERT INTO MART.AD_CASE_PURPOSE_EVIDENCE")) {
                        var statement=(PreparedStatement)result;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                            if(m.getName().equals("executeUpdate"))writes.incrementAndGet();
                            try{return m.invoke(statement,a);}catch(InvocationTargetException error){throw error.getCause();}
                        });
                    }
                    return result;
                } catch(InvocationTargetException error){throw error.getCause();}
            });
        }
    }
}
