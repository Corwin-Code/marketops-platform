package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.listingconversion.PromotionContextObservation;
import com.mimococo.marketops.listingconversion.PromotionTerms;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Promotion context, staged release and shared-cause closure against isolated PostgreSQL. */
class PromotionClosureIT {

    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE = TestDatabase.isolatedContainer();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static DataSource migration;
    private static DataSource application;
    private static DataSource admin;

    @BeforeAll
    static void database() {
        migration = new DriverManagerDataSource(DATABASE.getJdbcUrl(), TestDatabase.migrationRole(),
                TestDatabase.migrationPassword());
        application = new DriverManagerDataSource(DATABASE.getJdbcUrl(), TestDatabase.applicationRole(),
                TestDatabase.applicationPassword());
        admin = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }

    private static ListingConversionFixture ready() throws Exception {
        return new ListingConversionFixture(migration, application, admin);
    }

    @Test
    void currentContextQualifiesOnlyTheLatestIndependentCompleteEnumeration() throws Exception {
        var f = ready();
        PromotionTerms terms = terms("context-only");
        Instant observed = databaseNow(f).minusSeconds(2);
        Instant periodStart = observed.minusSeconds(60);
        Instant periodEnd = observed.plusSeconds(600);
        Map<String, Object> request = PromotionContextFixture.completeSingleActivityRequest(
                terms, "NOT_PARTICIPATING", observed, periodStart, periodEnd,
                observed.plusSeconds(3600), "fixture://complete-promotion-context",
                "STOPPED", "CLEARED", null, null, Map.of());
        UUID complete = insertCompleteObservation(f, request, f.id("verifierUser"));

        Instant asOf = databaseNow(f);
        JsonNode first = context(f, periodStart, periodEnd, asOf);
        JsonNode repeated = context(f, periodStart, periodEnd, asOf);
        assertThat(first.path("coverage").asText()).isEqualTo("QUALIFIED_COMPLETE");
        assertThat(first.path("observationId").asText()).isEqualTo(complete.toString());
        assertThat(first.path("listingId").asText()).isEqualTo(f.id("listing").toString());
        assertThat(first.path("records").get(0).path("declaration").path("nativePromotionKey").asText())
                .isEqualTo(terms.nativePromotionKey());
        assertThat(first.path("records").get(0).path("newTransactionsState").asText()).isEqualTo("STOPPED");
        assertThat(first.path("records").get(0).path("residualObligationState").asText()).isEqualTo("CLEARED");
        assertThat(first.path("digest").asText()).hasSize(64).isEqualTo(repeated.path("digest").asText());

        insertSingleObservation(f, terms, "UNKNOWN", databaseNow(f), f.id("verifierUser"));
        JsonNode downgraded = context(f, periodStart, periodEnd, databaseNow(f));
        assertThat(downgraded.path("coverage").asText()).isEqualTo("KNOWN_RECORDS_ONLY");
        assertThat(downgraded.path("gaps").toString()).contains("COMPLETE_ENUMERATION_MISSING");
    }

    @Test
    void adoptedActivityNeedsExactAuthorityAndReleasesNewWorkBeforeResidualObligations() throws Exception {
        var f = ready();
        PromotionTerms terms = terms("adopted-lifecycle");
        Instant observed = databaseNow(f).minusSeconds(2);
        Instant effectiveFrom = observed.minusSeconds(60);
        Instant effectiveTo = observed.plusSeconds(3600);
        Instant authorityUntil = observed.plusSeconds(7200);
        String originalAuthority = "fixture://promotion/original-authority";
        Map<String, PromotionContextObservation.AxisDemand> demands = Map.of(
                "CONCURRENT_LISTINGS", demand("1", "COUNT", "fixture://demand/listings"),
                "AFFECTED_VARIANTS", demand("1", "COUNT", "fixture://demand/variants"),
                "REVENUE_EXPOSURE", demand("500.0000", "RUB", "fixture://demand/revenue"),
                "CATEGORY_SHARE", demand("0.0500", "RATIO", "fixture://demand/category"));
        Map<String, Object> activeRequest = PromotionContextFixture.completeSingleActivityRequest(
                terms, "PARTICIPATING", observed, effectiveFrom, effectiveTo, authorityUntil,
                "fixture://complete-active-promotion", "OPEN", "OUTSTANDING",
                originalAuthority, authorityUntil, demands);
        UUID sourceObservation = insertCompleteObservation(f, activeRequest, f.id("verifierUser"));
        UUID engagement = UUID.randomUUID();
        f.app.sql("""
                INSERT INTO ops.lc_promotion_engagement(id,organization_id,store_id,platform_listing_id,action_id,
                    engagement_kind,native_promotion_key,terms,price_freeze,auto_participation,terms_evidence_reference,
                    adopted,obligations,state,created_at,updated_at,version,source_context_observation_id,
                    original_authority_reference,original_authority_valid_until,responsible_user_id)
                VALUES(:id,:org,:store,:listing,NULL,:kind,:native,CAST(:terms AS jsonb),:freeze,:auto,:evidence,
                    true,CAST(:obligations AS jsonb),'ACTIVE',clock_timestamp(),clock_timestamp(),0,
                    :observation,:authority,:authorityUntil,:responsible)
                """).param("id", engagement).param("org", f.id("organization")).param("store", f.id("store"))
                .param("listing", f.id("listing")).param("kind", terms.engagementKind())
                .param("native", terms.nativePromotionKey()).param("terms", JSON.writeValueAsString(terms.terms()))
                .param("freeze", terms.priceFreeze()).param("auto", terms.autoParticipation())
                .param("evidence", terms.termsEvidenceReference())
                .param("obligations", JSON.writeValueAsString(terms.obligations()))
                .param("observation", sourceObservation).param("authority", originalAuthority)
                .param("authorityUntil", Timestamp.from(authorityUntil)).param("responsible", f.id("ownerUser")).update();
        assertThat(f.app.sql("SELECT adoption_qualification_state FROM ops.lc_promotion_engagement WHERE id=:id")
                .param("id", engagement).query(String.class).single()).isEqualTo("QUALIFIED_CURRENT_STEWARDSHIP");

        authorizeOwnerExit(f, engagement, sourceObservation, "fixture://exit/owner-decision");
        assertThat(engagementState(f, engagement)).isEqualTo("EXITING");

        UUID stopObservation = lifecycleObservation(f, terms, "OUTSTANDING", f.id("verifierUser"));
        release(f, engagement, stopObservation, "NEW_TRANSACTIONS_STOPPED");
        assertThat(engagementState(f, engagement)).isEqualTo("STOPPED");
        assertThat(f.app.sql("SELECT new_transactions_stopped_at IS NOT NULL AND obligations_cleared_at IS NULL "
                        + "FROM ops.lc_promotion_engagement WHERE id=:id")
                .param("id", engagement).query(Boolean.class).single()).isTrue();

        assertThatThrownBy(() -> release(f, engagement, stopObservation, "OBLIGATIONS_CLEARED"))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO093"));
        UUID clearedObservation = lifecycleObservation(f, terms, "CLEARED", f.id("verifierUser"));
        release(f, engagement, clearedObservation, "OBLIGATIONS_CLEARED");
        assertThat(engagementState(f, engagement)).isEqualTo("CLEARED");
        assertThat(f.app.sql("SELECT stop_evidence_observation_id=:stop AND obligation_evidence_observation_id=:clear "
                        + "FROM ops.lc_promotion_engagement WHERE id=:id")
                .param("stop", stopObservation).param("clear", clearedObservation).param("id", engagement)
                .query(Boolean.class).single()).isTrue();
        assertThat(f.app.sql("SELECT has_column_privilege('marketops_app','ops.lc_promotion_engagement','state','UPDATE')")
                .query(Boolean.class).single()).isFalse();
    }

    @Test
    void sharedCauseContainsOnlyItsProvenCurrentConsumersAndCannotBeReleasedWhileUsed() throws Exception {
        var f = ready();
        String reference = commonCalibrationReference(f);
        UUID dependency = UUID.randomUUID();
        recordDependency(f, dependency, reference);

        UUID independentK9Consumer = insertIndependentVariantConsumer(f);
        String independentK9Reference = "lc-product-variant:" + f.id("productVariantTwo");
        recordDependency(f, UUID.randomUUID(), f.id("listingTwo"), independentK9Consumer,
                "SHARED_VARIANT_SET", independentK9Reference);
        assertThat(f.app.sql("SELECT platform_listing_id FROM ops.lc_current_isolation_scope(:org,:source) ORDER BY 1")
                .param("org", f.id("organization")).param("source", f.id("listingTwo"))
                .query(UUID.class).list()).containsExactlyInAnyOrder(f.id("listingTwo"), independentK9Consumer);

        assertThat(f.app.sql("SELECT platform_listing_id FROM ops.lc_current_isolation_scope(:org,:source) ORDER BY 1")
                .param("org", f.id("organization")).param("source", f.id("listing"))
                .query(UUID.class).list()).containsExactlyInAnyOrder(f.id("listing"), f.id("listingTwo"));
        assertThatThrownBy(() -> recordDependency(f, UUID.randomUUID(), reference + "-wrong"))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));

        UUID containment = recordSharedContainment(f, reference);
        assertThat(f.actionState(f.id("actionOne"))).isEqualTo("CONTAINED");
        assertThat(f.actionState(f.id("actionTwo"))).isEqualTo("CONTAINED");
        assertThat(f.app.sql("SELECT ops.lc_scope_contained(:org,:listing)")
                .param("org", f.id("organization")).param("listing", f.id("listingTwo"))
                .query(Boolean.class).single()).isTrue();
        assertThat(f.app.sql("SELECT ops.lc_scope_contained(:org,:listing)")
                .param("org", f.id("organization")).param("listing", independentK9Consumer)
                .query(Boolean.class).single()).isFalse();

        f.attest(UUID.randomUUID(), containment, f.id("ownerUser"), "REPAIR_ATTESTATION");
        f.attest(UUID.randomUUID(), containment, f.id("verifierUser"), "BUSINESS_CONSENT");
        assertThatThrownBy(() -> f.reenable(containment, f.id("ownerUser")))
                .satisfies(failure -> assertThat(ListingConversionFixture.sqlState(failure)).isEqualTo("MO092"));
        assertThat(f.app.sql("SELECT state FROM ops.lc_containment WHERE id=:id")
                .param("id", containment).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(f.app.sql("SELECT has_table_privilege('marketops_app','ops.lc_isolation_dependency','INSERT')")
                .query(Boolean.class).single()).isFalse();
    }

    private static PromotionTerms terms(String suffix) {
        return new PromotionTerms("OFFICIAL_PROMOTION_PARTICIPATION", "fixture-promotion-" + suffix,
                Map.of("finalPrice", "200.0000", "period", "fixture-window"), false, false,
                "fixture://promotion/terms", Map.of("fixedFee", "10.0000",
                "exit.OWNER_DECISION", "fixture://exit/owner-decision"));
    }

    private static PromotionContextObservation.AxisDemand demand(String value, String unit, String reference) {
        return new PromotionContextObservation.AxisDemand(new BigDecimal(value), unit, reference);
    }

    @SuppressWarnings("unchecked")
    private static UUID insertCompleteObservation(ListingConversionFixture f, Map<String, Object> request,
                                                   UUID recorder) throws Exception {
        PromotionTerms declaration = (PromotionTerms) request.get("declaration");
        PromotionContextObservation context = (PromotionContextObservation) request.get("context");
        Instant observed = (Instant) request.get("observedAt");
        Instant acquired = databaseNow(f);
        UUID provenance = insertProvenance(f, recorder, observed, acquired);
        UUID id = UUID.randomUUID();
        f.app.sql("""
                INSERT INTO core.lc_promotion_observation(id,organization_id,provenance_id,platform_listing_id,
                    observed_at,acquired_at,participation_state,engagement_kind,native_promotion_key,declaration,
                    evidence_reference,context_coverage,coverage_from,coverage_until,verification_expires_at,context_snapshot)
                VALUES(:id,:org,:provenance,:listing,:observed,:acquired,:state,:kind,:native,CAST(:declaration AS jsonb),
                    :evidence,'COMPLETE_ENUMERATION',:coverageFrom,:coverageUntil,:expires,CAST(:snapshot AS jsonb))
                """).param("id", id).param("org", f.id("organization")).param("provenance", provenance)
                .param("listing", f.id("listing")).param("observed", Timestamp.from(observed))
                .param("acquired", Timestamp.from(acquired)).param("state", request.get("participationState"))
                .param("kind", declaration.engagementKind()).param("native", declaration.nativePromotionKey())
                .param("declaration", JSON.writeValueAsString(declaration)).param("evidence", request.get("evidenceReference"))
                .param("coverageFrom", Timestamp.from(context.coverageStart()))
                .param("coverageUntil", Timestamp.from(context.coverageEnd()))
                .param("expires", Timestamp.from(context.verificationExpiresAt()))
                .param("snapshot", JSON.writeValueAsString(context.records())).update();
        return id;
    }

    private static void insertSingleObservation(ListingConversionFixture f, PromotionTerms terms, String state,
                                                Instant observed, UUID recorder) throws Exception {
        Instant acquired = databaseNow(f);
        UUID provenance = insertProvenance(f, recorder, observed, acquired);
        f.app.sql("""
                INSERT INTO core.lc_promotion_observation(id,organization_id,provenance_id,platform_listing_id,
                    observed_at,acquired_at,participation_state,engagement_kind,native_promotion_key,declaration,evidence_reference)
                VALUES(gen_random_uuid(),:org,:provenance,:listing,:observed,:acquired,:state,:kind,:native,
                    CAST(:declaration AS jsonb),'fixture://single-known-promotion')
                """).param("org", f.id("organization")).param("provenance", provenance)
                .param("listing", f.id("listing")).param("observed", Timestamp.from(observed))
                .param("acquired", Timestamp.from(acquired)).param("state", state)
                .param("kind", terms.engagementKind()).param("native", terms.nativePromotionKey())
                .param("declaration", JSON.writeValueAsString(terms)).update();
    }

    private static UUID insertProvenance(ListingConversionFixture f, UUID recorder, Instant observed, Instant acquired) {
        UUID id = UUID.randomUUID();
        f.app.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,
                    recorded_by_user_id,evidence_note)
                VALUES(:id,:org,'MANUAL_ENTRY',:observed,:acquired,:recorder,'finite independent fixture enumeration')
                """).param("id", id).param("org", f.id("organization"))
                .param("observed", Timestamp.from(observed)).param("acquired", Timestamp.from(acquired))
                .param("recorder", recorder).update();
        return id;
    }

    private static UUID lifecycleObservation(ListingConversionFixture f, PromotionTerms terms,
                                             String residualState, UUID recorder) throws Exception {
        Instant observed = databaseNow(f);
        Map<String, Object> request = PromotionContextFixture.completeSingleActivityRequest(
                terms, "NOT_PARTICIPATING", observed, observed.minusSeconds(1), observed.plusSeconds(600),
                observed.plusSeconds(3600), "fixture://promotion/lifecycle-observation",
                "STOPPED", residualState, null, null, Map.of());
        return insertCompleteObservation(f, request, recorder);
    }

    private static JsonNode context(ListingConversionFixture f, Instant from, Instant until, Instant at) {
        return JSON.readTree(f.app.sql(
                        "SELECT ops.lc_current_promotion_context(:org,:listing,:from,:until,:at)::text")
                .param("org", f.id("organization")).param("listing", f.id("listing"))
                .param("from", Timestamp.from(from)).param("until", Timestamp.from(until))
                .param("at", Timestamp.from(at)).query(String.class).single());
    }

    private static Instant databaseNow(ListingConversionFixture f) {
        return f.app.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
    }

    private static String engagementState(ListingConversionFixture f, UUID engagement) {
        return f.app.sql("SELECT state FROM ops.lc_promotion_engagement WHERE id=:id")
                .param("id", engagement).query(String.class).single();
    }

    private static void authorizeOwnerExit(ListingConversionFixture f, UUID engagement, UUID evidence,
                                           String authority) throws Exception {
        try (Connection connection = f.transaction()) {
            String proof = f.proof(connection, f.id("ownerUser"), "LISTING_PROMOTION_EXIT", engagement, engagement);
            try (var statement = connection.prepareStatement(
                    "SELECT ops.authorize_lc_promotion_exit(?,?,?,?,?,?)")) {
                statement.setObject(1, engagement);
                statement.setObject(2, f.id("ownerUser"));
                statement.setString(3, proof);
                statement.setString(4, "OWNER_DECISION");
                statement.setString(5, authority);
                statement.setObject(6, evidence);
                statement.execute();
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void release(ListingConversionFixture f, UUID engagement, UUID observation,
                                String kind) throws Exception {
        try (Connection connection = f.transaction()) {
            String proof = f.proof(connection, f.id("ownerUser"), "LISTING_OCCUPATION_RELEASE", engagement, engagement);
            try (var statement = connection.prepareStatement(
                    "SELECT ops.release_lc_promotion_engagement(?,?,?,?,?,?)")) {
                statement.setObject(1, engagement);
                statement.setObject(2, f.id("ownerUser"));
                statement.setString(3, proof);
                statement.setString(4, kind);
                statement.setObject(5, observation);
                statement.setString(6, "fixture://promotion/release/" + kind.toLowerCase(java.util.Locale.ROOT));
                statement.execute();
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static String commonCalibrationReference(ListingConversionFixture f) {
        return f.app.sql("""
                SELECT 'lc-calibration-value:'||left_value.key||':'||
                       encode(sha256(convert_to(left_value.value::text,'UTF8')),'hex')
                  FROM ops.lc_action left_action
                  CROSS JOIN LATERAL jsonb_each(left_action.calibration_dependencies->'values') left_value
                  JOIN ops.lc_action right_action ON right_action.id=:right
                 WHERE left_action.id=:left
                   AND right_action.calibration_dependencies->'values'->left_value.key=left_value.value
                 ORDER BY left_value.key LIMIT 1
                """).param("left", f.id("actionOne")).param("right", f.id("actionTwo"))
                .query(String.class).single();
    }

    private static UUID insertIndependentVariantConsumer(ListingConversionFixture f) {
        UUID listing = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        String listingKey = "fixture-independent-k9-" + listing;
        String variantKey = "fixture-independent-k9-" + variant;
        f.seed.sql("""
                INSERT INTO core.platform_listing(id,organization_id,store_id,marketplace_account_id,platform_code,
                    native_listing_key,native_product_key,title,native_status,first_seen_at,last_seen_at,status,
                    created_at,updated_at,version)
                SELECT :id,organization_id,store_id,marketplace_account_id,platform_code,:native,native_product_key,
                    title,native_status,first_seen_at,last_seen_at,status,created_at,updated_at,0
                  FROM core.platform_listing WHERE id=:source
                """).param("id", listing).param("native", listingKey).param("source", f.id("listingTwo")).update();
        f.seed.sql("""
                INSERT INTO core.platform_listing_variant(id,organization_id,platform_listing_id,native_variant_key,
                    native_sku_key,native_barcode,native_color_label,native_size_label,native_status,first_seen_at,
                    last_seen_at,status,created_at,updated_at,version)
                SELECT :id,organization_id,:listing,:native,native_sku_key,native_barcode,native_color_label,
                    native_size_label,native_status,first_seen_at,last_seen_at,status,created_at,updated_at,0
                  FROM core.platform_listing_variant WHERE id=:source
                """).param("id", variant).param("listing", listing).param("native", variantKey)
                .param("source", f.id("listingVariantTwo")).update();
        f.seed.sql("""
                INSERT INTO core.listing_mapping(id,organization_id,platform_listing_variant_id,product_variant_id,
                    effective_from,effective_to,status,confirmed_by_user_id,reason,created_at,updated_at,version)
                SELECT gen_random_uuid(),organization_id,:variant,product_variant_id,effective_from,effective_to,status,
                    confirmed_by_user_id,'independent K9 fixture consumer',created_at,updated_at,0
                  FROM core.listing_mapping WHERE platform_listing_variant_id=:source AND status='ACTIVE'
                """).param("variant", variant).param("source", f.id("listingVariantTwo")).update();
        Instant observed = databaseNow(f);
        UUID provenance = insertProvenance(f, f.id("verifierUser"), observed, observed);
        f.seed.sql("""
                INSERT INTO core.platform_listing_scope_observation(id,organization_id,platform_listing_id,provenance_id,
                    scope_kind,native_scope_key,native_variant_keys,coverage_state,expected_member_count,
                    source_reference,scope_basis_reference,observed_at,recorded_at,verification_expires_at)
                VALUES(gen_random_uuid(),:org,:listing,:provenance,'WHOLE_LISTING',:listingKey,
                    ARRAY[CAST(:variantKey AS text)],'COMPLETE',1,'fixture://independent-k9-enumeration',
                    'fixture://independent-k9-scope',:observed,:observed,:expires)
                """).param("org", f.id("organization")).param("listing", listing).param("provenance", provenance)
                .param("listingKey", listingKey).param("variantKey", variantKey)
                .param("observed", Timestamp.from(observed)).param("expires", Timestamp.from(observed.plusSeconds(3600))).update();
        f.seed.sql("""
                INSERT INTO core.lc_affected_set(id,organization_id,platform_listing_id,affected_set_digest,
                    platform_listing_variant_ids,product_variant_ids,resolution_state,unresolved_reason_codes,
                    resolved_at,created_at)
                VALUES(gen_random_uuid(),:org,:listing,core.lc_listing_affected_set_digest(:listing),
                    ARRAY[CAST(:variant AS uuid)],ARRAY[CAST(:product AS uuid)],'COMPLETE','{}',
                    statement_timestamp(),statement_timestamp())
                """).param("org", f.id("organization")).param("listing", listing).param("variant", variant)
                .param("product", f.id("productVariantTwo")).update();
        return listing;
    }

    private static void recordDependency(ListingConversionFixture f, UUID id, String reference) throws Exception {
        recordDependency(f, id, f.id("listing"), f.id("listingTwo"), "SHARED_TEMPLATE", reference);
    }

    private static void recordDependency(ListingConversionFixture f, UUID id, UUID from, UUID to,
                                         String kind, String reference) throws Exception {
        try (Connection connection = f.transaction()) {
            String proof = f.proof(connection, f.id("ownerUser"),
                    "LISTING_ISOLATION_DEPENDENCY_RECORD", id, id);
            try (var statement = connection.prepareStatement(
                    "SELECT ops.record_lc_isolation_dependency(?,?,?,?,?,?,?,?)")) {
                statement.setObject(1, id);
                statement.setObject(2, f.id("ownerUser"));
                statement.setObject(3, f.id("organization"));
                statement.setString(4, proof);
                statement.setObject(5, from);
                statement.setObject(6, to);
                statement.setString(7, kind);
                statement.setString(8, reference);
                statement.execute();
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static UUID recordSharedContainment(ListingConversionFixture f, String reference) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = f.transaction()) {
            String proof = f.proof(connection, f.id("ownerUser"), "LISTING_CONTAINMENT_STOP", id, id);
            try (var statement = connection.prepareStatement("""
                    SELECT ops.record_lc_containment(?,?,?,?,'LISTING',?,NULL,NULL,NULL,
                        'SHARED_VERSION','OWNER','fixture shared cause',?)
                    """)) {
                statement.setObject(1, id);
                statement.setObject(2, f.id("ownerUser"));
                statement.setObject(3, f.id("organization"));
                statement.setString(4, proof);
                statement.setObject(5, f.id("listing"));
                statement.setString(6, reference);
                statement.execute();
                connection.commit();
                return id;
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

}
