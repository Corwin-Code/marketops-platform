package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingContainmentRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Two interventions cannot hold the same product variants.
 *
 * <p>The property is the whole reason reservations exist. One advertising object
 * carries traffic for many product variants, so two objects changed at the same
 * time can move the same variants' sales, and afterwards nobody can say which
 * change did what. Overlap is therefore refused rather than ordered.
 *
 * <p>The refusal lives in the database, behind an advisory lock, and the
 * application role cannot insert a reservation row at all. That is what makes
 * this a property rather than a convention: there is no code path that skips it,
 * because there is no code path.
 */
@SpringBootTest
@ActiveProfiles("ci")
class AdvertisingReservationIT {

    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE =
            TestDatabase.isolatedContainer();

    private static JdbcClient seed;

    @Autowired
    private AdvertisingContainmentRepository reservations;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    /**
     * The one advertising semantic profile these fixtures share.
     *
     * <p>A profile is scoped to a platform and an object kind rather than to an
     * organization, so seeding one per test would be claiming several different
     * descriptions of the same marketplace. It is a SYNTHETIC_FIXTURE and
     * UNVERIFIED, which the schema will not let anything promote.
     */
    private static final UUID SEMANTIC_PROFILE =
            UUID.fromString("aaaaaaaa-0000-4000-8000-00000000ad01");

    /**
     * The one synthetic identity provider the fixture people belong to.
     *
     * <p>Deliberately RETIRED and UNVERIFIED. Nobody in this test authenticates;
     * the people exist only to be named as an activator, an endorser and an
     * approver, and an ACTIVE provider would have to claim a verification this
     * fixture has no evidence for.
     */
    private static final UUID IDENTITY_PROVIDER =
            UUID.fromString("aaaaaaaa-0000-4000-8000-0000000010d1");

    @BeforeAll
    static void openSeedConnection() {
        seed = JdbcClient.create(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword()));
    }

    @Test
    @DisplayName("TC-AD-RESERVE-001 the application role cannot write a reservation itself")
    void applicationRoleCannotWriteReservationsDirectly() {
        for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
            assertThat(Boolean.TRUE.equals(seed.sql(
                    "SELECT has_table_privilege(:role, 'ops.ad_action_reservation', :privilege)")
                    .param("role", TestDatabase.applicationRole())
                    .param("privilege", privilege)
                    .query(Boolean.class).single()))
                    .describedAs("%s on ops.ad_action_reservation", privilege)
                    .isFalse();
        }
        assertThat(Boolean.TRUE.equals(seed.sql(
                "SELECT has_table_privilege(:role, 'ops.ad_action_reservation', 'SELECT')")
                .param("role", TestDatabase.applicationRole())
                .query(Boolean.class).single())).isTrue();
    }

    @Test
    @DisplayName("TC-AD-RESERVE-002 an overlapping affected set is refused, naming the holder")
    void overlappingAffectedSetIsRefused() {
        Fixture fixture = seedFixture();
        UUID sharedVariant = fixture.variantOne();

        UUID held = reservations.take(UUID.randomUUID(), fixture.organizationId(),
                fixture.objectOne(), fixture.storeId(), fixture.affectedSetOne(),
                fixture.digestOne(), List.of(sharedVariant), "CONTROLLED_AD_BID_CHANGE",
                UUID.randomUUID(), "PROTECTION_DECREASE", "PROTECTION", "reservation-fixture");
        assertThat(held).isNotNull();

        assertThatThrownBy(() -> reservations.take(UUID.randomUUID(), fixture.organizationId(),
                fixture.objectTwo(), fixture.storeId(), fixture.affectedSetTwo(),
                fixture.digestTwo(), List.of(sharedVariant), "CONTROLLED_AD_BID_CHANGE",
                UUID.randomUUID(), "OPTIMIZATION_INCREASE", "OPTIMIZATION",
                "reservation-fixture"))
                .hasMessageContaining("PROTECTION")
                .hasMessageContaining("already holds");

        assertThat(reservations.blockingReservation(fixture.organizationId(),
                List.of(sharedVariant), fixture.objectTwo()))
                .hasValueSatisfying(blocking -> {
                    assertThat(blocking.reservationId()).isEqualTo(held);
                    assertThat(blocking.lane()).isEqualTo("PROTECTION");
                });
    }

    @Test
    @DisplayName("TC-AD-RESERVE-003 taking twice for one intervention returns the same reservation")
    void takingTwiceIsIdempotent() {
        Fixture fixture = seedFixture();
        UUID intervention = UUID.randomUUID();

        UUID first = reservations.take(UUID.randomUUID(), fixture.organizationId(),
                fixture.objectOne(), fixture.storeId(), fixture.affectedSetOne(),
                fixture.digestOne(), List.of(fixture.variantOne()), "CONTROLLED_AD_BID_CHANGE",
                intervention, "PROTECTION_DECREASE", "PROTECTION", "reservation-fixture");
        UUID again = reservations.take(UUID.randomUUID(), fixture.organizationId(),
                fixture.objectOne(), fixture.storeId(), fixture.affectedSetOne(),
                fixture.digestOne(), List.of(fixture.variantOne()), "CONTROLLED_AD_BID_CHANGE",
                intervention, "PROTECTION_DECREASE", "PROTECTION", "reservation-fixture");

        assertThat(again).isEqualTo(first);
    }

    @Test
    @DisplayName("TC-AD-RESERVE-004 a reservation is not released until all four conditions hold")
    void releaseNeedsAllFourConditions() {
        Fixture fixture = seedFixture();
        UUID reservation = reservations.take(UUID.randomUUID(), fixture.organizationId(),
                fixture.objectOne(), fixture.storeId(), fixture.affectedSetOne(),
                fixture.digestOne(), List.of(fixture.variantOne()), "CONTROLLED_AD_BID_CHANGE",
                UUID.randomUUID(), "PROTECTION_DECREASE", "PROTECTION", "reservation-fixture");

        assertThat(reservations.release(reservation, "nothing has been observed yet")).isFalse();
        assertThat(reservations.releasable(Instant.now(), 10)).doesNotContain(reservation);

        reservations.observeCondition(reservation, "CONFIGURATION_RESOLVED", true);
        assertThat(reservations.release(reservation, "configuration only")).isFalse();

        reservations.observeCondition(reservation, "EARLY_OBSERVATION_COMPLETE", true);
        assertThat(reservations.releasable(Instant.now(), 10)).contains(reservation);
        assertThat(reservations.release(reservation, "every condition observed")).isTrue();

        // A second release changes nothing and says so.
        assertThat(reservations.release(reservation, "again")).isFalse();
    }

    @Test
    @DisplayName("TC-AD-RESERVE-005 an open mismatch keeps a reservation held")
    void openMismatchKeepsTheReservationHeld() {
        Fixture fixture = seedFixture();
        UUID reservation = reservations.take(UUID.randomUUID(), fixture.organizationId(),
                fixture.objectOne(), fixture.storeId(), fixture.affectedSetOne(),
                fixture.digestOne(), List.of(fixture.variantOne()), "CONTROLLED_AD_BID_CHANGE",
                UUID.randomUUID(), "PROTECTION_DECREASE", "PROTECTION", "reservation-fixture");

        reservations.observeCondition(reservation, "CONFIGURATION_RESOLVED", true);
        reservations.observeCondition(reservation, "EARLY_OBSERVATION_COMPLETE", true);
        reservations.observeCondition(reservation, "UNKNOWN_OR_MISMATCH_OPEN", true);

        // The command whose outcome nobody knows is exactly the one whose
        // variants must stay held.
        assertThat(reservations.release(reservation, "premature")).isFalse();
        assertThat(reservations.releasable(Instant.now(), 10)).doesNotContain(reservation);
    }

    @Test
    @DisplayName("TC-AD-RESERVE-006 with nothing contained, no containment covers a scope")
    void noContainmentCoversAnEmptyScope() {
        Fixture fixture = seedFixture();

        assertThat(reservations.activeContainment(fixture.organizationId(), fixture.objectOne(),
                fixture.storeId(), "OZON", "ad-bid-change", fixture.digestOne())).isEmpty();
    }

    @Test
    @DisplayName("TC-AD-CONTAIN-001 a kill switch covers every scope beneath it")
    void killSwitchCoversTheCapabilityScope() {
        Fixture fixture = seedFixture();
        UUID kill = reservations.activate(UUID.randomUUID(), fixture.organizationId(),
                "KILL_SWITCH_ACTIVE", "PLATFORM_STORE_CAPABILITY", "OZON", null,
                fixture.storeId(), null, null, "ad-bid-change", null, "BUSINESS_HARM",
                "synthetic incident", "evidence://fixture/kill", null, "OPERATOR_DECISION",
                "containment-fixture");

        assertThat(kill).isNotNull();
        assertThat(reservations.activeContainment(fixture.organizationId(), fixture.objectOne(),
                fixture.storeId(), "OZON", "ad-bid-change", fixture.digestOne()))
                .containsExactly("KILL_SWITCH_ACTIVE");
    }

    @Test
    @DisplayName("TC-AD-CONTAIN-002 one person cannot lift their own stop")
    void onePersonCannotLiftTheirOwnStop() {
        Fixture fixture = seedFixture();
        UUID activator = seedUser(fixture.organizationId());
        UUID other = seedUser(fixture.organizationId());
        UUID containment = reservations.activate(UUID.randomUUID(), fixture.organizationId(),
                "EMERGENCY_ENTITY_HOLD", "ENTITY", null, null, null, fixture.objectOne(),
                null, null, null, "BUSINESS_HARM", "synthetic hold",
                "evidence://fixture/hold", activator, null, "containment-fixture");

        for (String condition : List.of("ROOT_CAUSE_CLASSIFIED", "UNKNOWNS_RESOLVED",
                "AUTHORITIES_REPLACED", "RESULTS_RECONCILED", "CAPABILITY_EVIDENCE_CURRENT")) {
            assertThat(reservations.observeReenablementCondition(containment, condition, true))
                    .isTrue();
        }

        // Endorser and approver both the activator: refused by the table.
        assertThatThrownBy(() -> reservations.reenable(containment, activator, activator, "{}"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // One person in both roles: still refused.
        assertThatThrownBy(() -> reservations.reenable(containment, other, other, "{}"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        UUID third = seedUser(fixture.organizationId());
        assertThat(reservations.reenable(containment, other, third, "{\"scope\":\"entity\"}"))
                .isTrue();
        assertThat(reservations.activeContainment(fixture.organizationId(), fixture.objectOne(),
                fixture.storeId(), "OZON", "ad-bid-change", fixture.digestOne())).isEmpty();
    }

    @Test
    @DisplayName("TC-AD-CONTAIN-003 a security cause needs an attestation before anything restarts")
    void securityCauseNeedsAnAttestation() {
        Fixture fixture = seedFixture();
        UUID activator = seedUser(fixture.organizationId());
        UUID endorser = seedUser(fixture.organizationId());
        UUID approver = seedUser(fixture.organizationId());
        UUID containment = reservations.activate(UUID.randomUUID(), fixture.organizationId(),
                "CAPABILITY_QUARANTINED", "PLATFORM_STORE_CAPABILITY", "OZON", null,
                fixture.storeId(), null, null, "ad-bid-change", null, "CREDENTIAL_OR_SECURITY",
                "synthetic credential incident", "evidence://fixture/security", activator, null,
                "containment-fixture");

        for (String condition : List.of("ROOT_CAUSE_CLASSIFIED", "UNKNOWNS_RESOLVED",
                "AUTHORITIES_REPLACED", "RESULTS_RECONCILED", "CAPABILITY_EVIDENCE_CURRENT")) {
            reservations.observeReenablementCondition(containment, condition, true);
        }

        assertThat(reservations.list(fixture.organizationId(), true, 10))
                .filteredOn(row -> row.id().equals(containment))
                .singleElement()
                .satisfies(row -> assertThat(row.outstandingConditions())
                        .containsExactly("SECURITY_ATTESTATION_PRESENT"));
        assertThatThrownBy(() -> reservations.reenable(containment, endorser, approver, "{}"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        reservations.observeReenablementCondition(containment, "SECURITY_ATTESTATION_PRESENT",
                true);
        assertThat(reservations.reenable(containment, endorser, approver, "{}")).isTrue();
    }

    /** One synthetic person who can endorse or approve. */
    private UUID seedUser(UUID organizationId) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO iam.identity_provider (id, code, display_name, issuer,
                        max_auth_age_seconds, verification_state, owner_label, status,
                        created_at, updated_at)
                VALUES (:id, 'fixture-idp', 'Fixture provider',
                        'https://fixture.invalid/issuer', 3600, 'UNVERIFIED', 'fixture',
                        'RETIRED', now(), now())
                ON CONFLICT (id) DO NOTHING
                """).param("id", IDENTITY_PROVIDER).update();
        seed.sql("""
                INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
                        external_subject, display_name, status, credentials_valid_from,
                        created_at, updated_at)
                VALUES (:id, :organization, :provider, :subject, 'Fixture person', 'ACTIVE',
                        now(), now(), now())
                """).param("id", id).param("organization", organizationId)
                .param("provider", IDENTITY_PROVIDER)
                .param("subject", "subject-" + id)
                .update();
        return id;
    }

    private record Fixture(UUID organizationId, UUID storeId, UUID objectOne, UUID objectTwo,
                           UUID affectedSetOne, UUID affectedSetTwo, String digestOne,
                           String digestTwo, UUID variantOne) {
    }

    /**
     * One organization, one store, two advertising objects sharing a variant.
     *
     * <p>Seeded through the migration role because the point of the test above
     * is that the application role cannot write these rows.
     */
    private Fixture seedFixture() {
        seed.sql("""
                INSERT INTO platform.ad_semantic_profile (id, platform_code, profile_version,
                        native_object_kind, control_level, bidding_mode, bid_field_present,
                        bid_currency_code, bid_unit_code, bid_precision, bid_step, bid_minimum,
                        bid_maximum, idempotency_semantics, propagation_semantics,
                        readback_semantics, correction_behaviour, source_maturity,
                        verification_state, owner_label, status, created_at, updated_at)
                VALUES (:id, 'OZON', 901, 'KEYWORD', 'KEYWORD', 'MANUAL_BID', true,
                        'RUB', 'CURRENCY_MAJOR', 2, 0.5, 1.0, 500.0, 'VERIFIED_NATIVE_KEY',
                        'EVENTUAL_BOUNDED', 'EXACT_FIELD', 'APPEND_ONLY_CORRECTION',
                        'SYNTHETIC_FIXTURE', 'UNVERIFIED', 'fixture', 'ACTIVE', now(), now())
                ON CONFLICT (id) DO NOTHING
                """).param("id", SEMANTIC_PROFILE).update();

        UUID organization = UUID.randomUUID();
        UUID legalEntity = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        UUID productVariant = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID objectOne = UUID.randomUUID();
        UUID objectTwo = UUID.randomUUID();
        UUID setOne = UUID.randomUUID();
        UUID setTwo = UUID.randomUUID();
        String digestOne = "1".repeat(64);
        String digestTwo = "2".repeat(64);

        seed.sql("""
                INSERT INTO core.organization (id, code, display_name, status, created_at,
                        updated_at)
                VALUES (:id, :code, 'Reservation fixture', 'ACTIVE', now(), now())
                """).param("id", organization)
                .param("code", "resv-" + organization.toString().substring(0, 8)).update();
        seed.sql("""
                INSERT INTO core.legal_entity (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES (:id, :organization, :code, 'Fixture entity', 'ACTIVE', now(), now())
                """).param("id", legalEntity).param("organization", organization)
                .param("code", "le-" + legalEntity.toString().substring(0, 8))
                .update();
        seed.sql("""
                INSERT INTO core.marketplace_account (id, organization_id, legal_entity_id,
                        platform_code, code, display_name, status, created_at, updated_at)
                VALUES (:id, :organization, :legalEntity, 'OZON', :code, 'Fixture account',
                        'ACTIVE', now(), now())
                """).param("id", account).param("organization", organization)
                .param("legalEntity", legalEntity)
                .param("code", "acct-" + account.toString().substring(0, 8)).update();
        seed.sql("""
                INSERT INTO core.store (id, organization_id, marketplace_account_id, code,
                        display_name, status, created_at, updated_at)
                VALUES (:id, :organization, :account, :code, 'Fixture store', 'ACTIVE',
                        now(), now())
                """).param("id", store).param("organization", organization)
                .param("account", account)
                .param("code", "store-" + store.toString().substring(0, 8)).update();
        seed.sql("""
                INSERT INTO core.product (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES (:id, :organization, :code, 'Fixture product', 'ACTIVE', now(), now())
                """).param("id", product).param("organization", organization)
                .param("code", "sku-" + product.toString().substring(0, 8)).update();
        seed.sql("""
                INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                        display_name, status, created_at, updated_at)
                VALUES (:id, :organization, :product, :sku, 'Fixture variant', 'ACTIVE',
                        now(), now())
                """).param("id", productVariant).param("organization", organization)
                .param("product", product)
                .param("sku", "var-" + productVariant.toString().substring(0, 8)).update();
        for (UUID object : List.of(objectOne, objectTwo)) {
            seed.sql("""
                    INSERT INTO core.ad_native_object (id, organization_id, store_id,
                            platform_code, semantic_profile_id, native_object_kind,
                            native_object_key, native_campaign_key, bidding_mode,
                            control_granularity_state, lineage_key, lineage_generation,
                            observation_state, status, first_observed_at, last_observed_at,
                            created_at, updated_at)
                    VALUES (:id, :organization, :store, 'OZON', :profile, 'KEYWORD',
                            :key, :campaign, 'MANUAL_BID', 'UNKNOWN', :key, 1, 'OBSERVED',
                            'ACTIVE', now(), now(), now(), now())
                    """).param("id", object).param("organization", organization)
                    .param("store", store).param("profile", SEMANTIC_PROFILE)
                    .param("key", "obj-" + object.toString().substring(0, 8))
                    .param("campaign", "camp-" + object.toString().substring(0, 8)).update();
        }
        seed.sql("""
                INSERT INTO core.ad_affected_set (id, organization_id, ad_native_object_id,
                        affected_set_digest, product_variant_ids, platform_listing_variant_ids,
                        resolution_state, resolved_at, created_at)
                VALUES (:id, :organization, :object, :digest, ARRAY[:variant]::uuid[],
                        ARRAY[]::uuid[], 'COMPLETE', now(), now())
                """).param("id", setOne).param("organization", organization)
                .param("object", objectOne).param("digest", digestOne)
                .param("variant", productVariant).update();
        seed.sql("""
                INSERT INTO core.ad_affected_set (id, organization_id, ad_native_object_id,
                        affected_set_digest, product_variant_ids, platform_listing_variant_ids,
                        resolution_state, resolved_at, created_at)
                VALUES (:id, :organization, :object, :digest, ARRAY[:variant]::uuid[],
                        ARRAY[]::uuid[], 'COMPLETE', now(), now())
                """).param("id", setTwo).param("organization", organization)
                .param("object", objectTwo).param("digest", digestTwo)
                .param("variant", productVariant).update();

        return new Fixture(organization, store, objectOne, objectTwo, setOne, setTwo,
                digestOne, digestTwo, productVariant);
    }
}
