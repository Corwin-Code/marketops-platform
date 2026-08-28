package com.mimococo.marketops.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.marketplaceintegration.RawCustody;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.JdbcAuthorizedAcquisitionGateway;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import com.mimococo.marketops.marketplaceintegration.port.InMemoryObjectStoragePort;
import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.marketplaceintegration.port.RecordedAcquisitionPort;
import com.mimococo.marketops.operatingfacts.internal.application.NormalizationRunner;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.NormalizationDeclarationRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PG17 Raw-to-fact recovery. Only external acquisition/storage and the crash are synthetic. */
@SpringBootTest
@ActiveProfiles("ci")
@Import(StoredRawReplayIT.Storage.class)
class StoredRawReplayIT {
    private static PostgreSQLContainer database;
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;
    @Autowired RawCustody custody;
    @Autowired NormalizationRunner normalizer;
    @Autowired FaultObjects objects;
    @MockitoSpyBean NormalizationDeclarationRepository declarations;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        database = TestDatabase.isolatedContainer();
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @Test
    void missingBytesAndParserFailureRetainProgressAndCrashReplayProducesNoDuplicateFactOrSourceCall() throws Exception {
        var owner = new DriverManagerDataSource(database.getJdbcUrl(), TestDatabase.migrationRole(), TestDatabase.migrationPassword());
        try (var connection = owner.getConnection()) { IngestionControlPlaneFixture.seed(connection); }
        var arrange = JdbcClient.create(owner);
        arrange.sql("UPDATE platform.ingestion_job SET store_id=:store,dataset_kind='PRICE' WHERE id=:job")
                .param("store", IngestionControlPlaneFixture.STORE).param("job", IngestionControlPlaneFixture.JOB).update();
        UUID mapping = UUID.randomUUID();
        arrange.sql("""
                INSERT INTO staging.normalization_mapping(id,platform_code,dataset_kind,mapping_version,
                    record_pointer,verification_state,last_verified_at,evidence_ref,verified_source_title,
                    owner_label,status,created_at,updated_at)
                VALUES(:id,'OZON','PRICE',1,'/wrong','VERIFIED',now(),'fixture://stored-raw-replay',
                    'Synthetic parser declaration','fixture','ACTIVE',now(),now())
                """).param("id", mapping).update();
        for (String field : java.util.List.of("nativeListingKey","nativeVariantKey","observedAt","currencyCode","sellingPrice")) {
            arrange.sql("INSERT INTO staging.normalization_field(mapping_id,dataset_kind,field_name,source_pointer) VALUES(:id,'PRICE',:field,:pointer)")
                    .param("id", mapping).param("field", field).param("pointer", "/" + field).update();
        }
        var source = new RecordedAcquisitionPort("""
                {"rows":[{"nativeListingKey":"replay-listing","nativeVariantKey":"replay-variant",
                "observedAt":"2026-08-27T00:00:00Z","currencyCode":"RUB","sellingPrice":"123.4500",
                "newField":"retained in Raw"}]}
                """, "200 OK", AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES);
        var result = new JdbcAuthorizedAcquisitionGateway(dataSource, source).acquire(
                IngestionControlPlaneFixture.RUN, 1, "worker-a", IngestionControlPlaneFixture.SCOPE_GRANT,
                Duration.ofSeconds(30), "stored-raw-replay");
        assertThat(result.outcome()).isEqualTo(AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES);
        var content = custody.store("marketplace-raw", result.body());
        UUID unit = UUID.randomUUID(), observation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO raw.raw_logical_unit(id,job_id,marketplace_account_id,unit_kind,source_unit_key,source_time)
                VALUES(:id,:job,:account,'ORDER_PAGE','replay-page',TIMESTAMPTZ '2026-08-27T00:00:00Z')
                """).param("id", unit).param("job", IngestionControlPlaneFixture.JOB).param("account", IngestionControlPlaneFixture.ACCOUNT).update();
        jdbc.sql("""
                INSERT INTO raw.raw_acquisition_observation(id,run_id,logical_unit_id,content_id,call_seq,native_status,outcome_class,pagination_outcome)
                VALUES(:id,:run,:unit,:content,1,'200 OK','SUCCESS_BYTES','END')
                """).param("id", observation).param("run", IngestionControlPlaneFixture.RUN).param("unit", unit).param("content", content.contentId()).update();
        assertThat(normalizer.runOnce(IngestionControlPlaneFixture.JOB).reason()).isEqualTo("PAYLOAD_UNREADABLE");
        assertThat(declarations.progress(IngestionControlPlaneFixture.JOB)).isEmpty();
        assertThat(priceCount()).isZero();
        arrange.sql("UPDATE staging.normalization_mapping SET record_pointer='/rows' WHERE id=:id").param("id", mapping).update();
        objects.readable = false;
        assertThat(normalizer.runOnce(IngestionControlPlaneFixture.JOB).reason()).isEqualTo("RAW_UNVERIFIABLE");
        assertThat(declarations.progress(IngestionControlPlaneFixture.JOB)).isEmpty();
        objects.readable = true;
        // Crash exactly after the real fact transaction committed, before the progress acknowledgement.
        doThrow(new IllegalStateException("synthetic process crash before checkpoint")).doCallRealMethod()
                .when(declarations).advanceProgress(any(),any(),any(),anyLong(),any(),anyLong());
        assertThatThrownBy(() -> normalizer.runOnce(IngestionControlPlaneFixture.JOB)).isInstanceOf(IllegalStateException.class);
        assertThat(priceCount()).isEqualTo(1);
        assertThat(declarations.progress(IngestionControlPlaneFixture.JOB)).isEmpty();
        int readsBeforeReplay = objects.reads;
        assertThat(normalizer.runOnce(IngestionControlPlaneFixture.JOB).reason()).isEqualTo("PROCESSED");
        assertThat(objects.reads).isGreaterThan(readsBeforeReplay);
        assertThat(priceCount()).isEqualTo(1);
        assertThat(declarations.progress(IngestionControlPlaneFixture.JOB).orElseThrow().lastObservationId()).isEqualTo(observation);
        assertThat(normalizer.runOnce(IngestionControlPlaneFixture.JOB).reason()).isEqualTo("NOTHING_TO_PROCESS");
        assertThat(source.recorded()).hasSize(1); // Only the initial acquisition, never a replay call.
        assertThat(jdbc.sql("SELECT last_call_seq FROM ops.ingestion_run WHERE id=:id").param("id",IngestionControlPlaneFixture.RUN).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.price_command").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT selling_price FROM core.listing_price_observation").query(java.math.BigDecimal.class).single()).isEqualByComparingTo("123.4500");
        assertThat(jdbc.sql("SELECT p.raw_observation_id FROM core.listing_price_observation f JOIN core.fact_provenance p ON p.id=f.provenance_id").query(UUID.class).single()).isEqualTo(observation);
        assertThat(jdbc.sql("SELECT first_observation_id FROM staging.schema_drift_observation WHERE job_id=:id").param("id",IngestionControlPlaneFixture.JOB).query(UUID.class).single()).isEqualTo(observation);
        assertThat(custody.readById(content.contentId()).orElseThrow()).containsExactly(result.body());
    }

    private long priceCount() { return jdbc.sql("SELECT count(*) FROM core.listing_price_observation").query(Long.class).single(); }

    @TestConfiguration
    static class Storage {
        @Bean @Primary FaultObjects objects() { return new FaultObjects(); }
    }
    static class FaultObjects implements ObjectStoragePort {
        private final InMemoryObjectStoragePort delegate = new InMemoryObjectStoragePort();
        boolean readable = true;
        int reads;
        @Override public PutOutcome putIfAbsent(String ref, byte[] body) { return delegate.putIfAbsent(ref, body); }
        @Override public Optional<byte[]> read(String ref) { reads++; return readable ? delegate.read(ref) : Optional.empty(); }
        @Override public boolean verify(String ref, String digest) { return readable && delegate.verify(ref,digest); }
    }
}
