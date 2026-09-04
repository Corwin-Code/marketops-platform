package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.shared.Digest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A graph in which the advertising write path is actually satisfiable.
 *
 * <p>A sibling of {@code AdvertisingGraphFixture} rather than a widening of it.
 * That fixture's whole value is its baseline — a DRAFT bundle, an UNVERIFIED
 * capability, an object nobody has proven controllable — and five suites assert
 * exactly those absences. This one is the mirror image: every fact the gate
 * reads is present, so a test can ask what the machinery does when it is asked
 * to work rather than what it does when it refuses.
 *
 * <p><strong>Why a fixture platform.</strong> The schema will not activate a
 * decision bundle whose semantic profile is a {@code SYNTHETIC_FIXTURE}, and it
 * is right not to: a fixture is never evidence about a marketplace. The only
 * other way to reach an active bundle is a profile marked
 * {@code OFFICIAL_VERIFIED}/{@code VERIFIED} — and marking an <em>Ozon</em>
 * profile that way would be this repository asserting that somebody verified
 * Ozon's real bid semantics, which nobody has and which this repository forbids
 * inventing. So the whole write-capable registry hangs off a separate platform
 * whose code says what it is, {@code FIXTURE_ADS}. Its bid semantics — a step,
 * a precision, a readback that returns the exact field — are a protocol this
 * repository specifies itself, in the operation rows a few lines below, and a
 * verified profile for a protocol we wrote is an honest statement. A verified
 * profile for Ozon would not be.
 *
 * <p>Nothing is weakened to get here. No constraint is dropped, no trigger
 * disabled, no grant widened. Everything is seeded as data through
 * {@code TestDatabase.migrationRole()}, exactly as {@code PriceCommandFixture}
 * has done for the price path since it shipped; the one exemption used is the
 * one written into {@code platform.guard_verified_registry_writer()} itself,
 * which forbids the <em>application</em> role from writing verified registry
 * facts and says nothing about the migrating role.
 *
 * <p>Two seeds a reviewer should stop on are marked at the point they are made:
 * the second protection case (see {@link #seedProtectionCase}).
 */
final class AdvertisingWriteEnabledFixture {

    /**
     * The platform this fixture's write protocol belongs to.
     *
     * <p>Satisfies {@code marketplace_platform_code_ck}
     * ({@code ^[A-Z][A-Z0-9_]{1,62}$}) and names itself, so nothing that reads
     * a platform code can mistake it for a marketplace.
     */
    static final String PLATFORM_CODE = "FIXTURE_ADS";

    /** The bid the object is observed to hold before anything is proposed. */
    static final String CURRENT_BID = "30.0000";

    /**
     * The economic ceiling the case records: what one click may be worth.
     *
     * <p>Seeded on the case rather than computed, for the reason
     * {@link #seedProtectionCase} sets out at length.
     */
    static final String MAX_CPC = "25.0000";

    /** Where the bounded decrease lands: the ceiling less its ten-per-cent headroom. */
    static final String TARGET_BID = "22.5000";

    /** The cause the protection case carries. */
    static final String CAUSE_CODE = "PROMOTED_VARIANT_NOT_SELLABLE";

    private AdvertisingWriteEnabledFixture() {
    }

    /**
     * Everything the vertical-path test needs to name, resolved once.
     *
     * @param organizationId owning organization
     * @param accountId the marketplace account on the fixture platform
     * @param storeId the store every scoped policy and grant is written against
     * @param productVariantId the one internal variant the object promotes
     * @param listingVariantId the platform listing variant it is mapped to
     * @param objectId the advertising object whose bid moves
     * @param affectedSetId the resolved affected set
     * @param affectedSetDigest that set's digest, as the schema computes identity
     * @param semanticProfileId the write-capable profile for the fixture protocol
     * @param capabilityId the verified {@code ad-bid-change} write capability
     * @param targetPolicyId the bid target policy that bounds the candidate
     * @param conversionDefinitionId the conversion definition the bundle names
     * @param bundleId the active, validated decision bundle
     * @param approverUserId the person who approves the bid change
     * @param provenanceId provenance every seeded fact is attributed to
     * @param calculationRunId a real recorded run for the recommendation to cite
     * @param identityProviderId the issuer registration the approver belongs to
     * @param issuer that provider's exact issuer identifier
     */
    record Graph(UUID organizationId, UUID accountId, UUID storeId, UUID productVariantId,
                 UUID listingVariantId, UUID objectId, UUID affectedSetId,
                 String affectedSetDigest, UUID semanticProfileId, UUID capabilityId,
                 UUID targetPolicyId, UUID conversionDefinitionId, UUID bundleId,
                 UUID approverUserId, UUID provenanceId, UUID calculationRunId,
                 UUID identityProviderId, String issuer) {
    }

    /** The three distinct people an activation record needs, plus the approver. */
    private record People(UUID approver, UUID endorser, UUID activator, UUID bundleApprover) {
    }

    /**
     * Seed the whole graph, in dependency order, through the migration role.
     *
     * <p>The order below is the order the schema requires and nothing else:
     * platform, topology, identity, the object and its facts, the policies that
     * govern it, the registry that describes the protocol, and last the bundle,
     * which validates the whole combination and therefore has to be able to see
     * all of it.
     */
    static Graph seed(JdbcClient seed) {
        UUID organization = UUID.randomUUID();
        UUID legalEntity = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID productVariant = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        UUID listingVariant = UUID.randomUUID();
        UUID semanticProfile = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        UUID affectedSet = UUID.randomUUID();
        UUID provenance = UUID.randomUUID();
        UUID capability = UUID.randomUUID();
        UUID calculationRun = UUID.randomUUID();
        String suffix = object.toString().substring(0, 8);
        String digest = Digest.ofText("affected-set-" + object);
        String issuer = "https://fixture.invalid/ads/" + suffix;

        seedFixturePlatform(seed);
        seedTopology(seed, organization, legalEntity, account, store, product, productVariant,
                listing, listingVariant, suffix);
        UUID identityProvider = UUID.randomUUID();
        People people = seedPeople(seed, organization, identityProvider, issuer, suffix);
        seedListingMapping(seed, organization, listingVariant, productVariant, people.approver());
        seedApprovalAuthority(seed, organization, people.approver());
        seedSemanticProfile(seed, semanticProfile);
        seedAdvertisingObject(seed, organization, store, semanticProfile, object, suffix);
        seedProvenance(seed, organization, provenance, people.approver());
        seedAffectedSet(seed, organization, object, affectedSet, digest, productVariant,
                listingVariant);
        seedConfiguration(seed, organization, object, provenance, semanticProfile);

        UUID conversion = seedConversionDefinition(seed, organization, people.approver());
        UUID allowableCpa = seedAllowableCpaDefinition(seed, organization, people.approver());
        seedQualificationTiers(seed, organization, people.approver());
        UUID priority = seedPriorityPolicy(seed, organization, people.approver());
        UUID humanSlo = seedHumanSloProfile(seed, organization, people.approver());
        UUID approvalLease = seedApprovalLeasePolicy(seed, organization, people.approver());
        UUID exposure = seedExposureEnvelope(seed, organization, people.approver());
        UUID materiality = seedMaterialityPolicy(seed, organization, people.approver());
        UUID outcome = seedOutcomePolicy(seed, organization, people.approver());
        UUID targetPolicy = seedBidTargetPolicy(seed, organization, people.approver());

        seedRegistry(seed, organization, account, store, capability, suffix);
        seedPilotAllowlist(seed, organization, store, object, people.approver());
        seedCalculationRun(seed, organization, store, calculationRun);

        UUID bundle = activateBundle(seed, organization, account, store, semanticProfile,
                conversion, allowableCpa, targetPolicy, priority, humanSlo, approvalLease,
                exposure, materiality, outcome, people);

        return new Graph(organization, account, store, productVariant, listingVariant, object,
                affectedSet, digest, semanticProfile, capability, targetPolicy, conversion,
                bundle, people.approver(), provenance, calculationRun, identityProvider, issuer);
    }

    // ------------------------------------------------------------------
    // The fixture platform
    // ------------------------------------------------------------------

    /**
     * Add the fixture platform and its serialization guard in one statement.
     *
     * <p>Not two statements. {@code core.marketplace_platform} carries a
     * GLOBAL_FANOUT statement trigger that enumerates every platform and takes
     * each one's {@code PLATFORM_JOB_SET} guard when the insert ends; a platform
     * whose guard did not yet exist would fail that acquisition, and the deferred
     * totality trigger would refuse the transaction anyway. V0007 says so in
     * words and this is what it is asking for: the platform and its guard are
     * created together or not at all.
     */
    private static void seedFixturePlatform(JdbcClient seed) {
        seed.sql("""
                WITH added AS (
                    INSERT INTO core.marketplace_platform (code, display_name, status)
                    VALUES (:code, 'Fixture advertising protocol', 'ACTIVE')
                    ON CONFLICT (code) DO NOTHING
                    RETURNING code)
                INSERT INTO platform.control_epoch_membership_guard
                        (guard_kind, platform_code, generation)
                SELECT 'PLATFORM_JOB_SET', code, 1 FROM added
                """).param("code", PLATFORM_CODE).update();
    }

    // ------------------------------------------------------------------
    // Topology and identity
    // ------------------------------------------------------------------

    private static void seedTopology(JdbcClient seed, UUID organization, UUID legalEntity,
                                     UUID account, UUID store, UUID product, UUID productVariant,
                                     UUID listing, UUID listingVariant, String suffix) {
        seed.sql("""
                INSERT INTO core.organization (id, code, display_name, status, created_at,
                        updated_at)
                VALUES (:id, :code, 'Advertising write fixture', 'ACTIVE', now(), now())
                """).param("id", organization).param("code", "adwx-" + suffix).update();
        seed.sql("""
                INSERT INTO core.legal_entity (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES (:id, :organization, :code, 'Fixture entity', 'ACTIVE', now(), now())
                """).param("id", legalEntity).param("organization", organization)
                .param("code", "adwx-le-" + suffix).update();
        seed.sql("""
                INSERT INTO core.marketplace_account (id, organization_id, legal_entity_id,
                        platform_code, code, display_name, status, created_at, updated_at)
                VALUES (:id, :organization, :legalEntity, :platform, :code, 'Fixture account',
                        'ACTIVE', now(), now())
                """).param("id", account).param("organization", organization)
                .param("legalEntity", legalEntity).param("platform", PLATFORM_CODE)
                .param("code", "adwx-acct-" + suffix).update();
        seed.sql("""
                INSERT INTO core.store (id, organization_id, marketplace_account_id, code,
                        display_name, status, created_at, updated_at)
                VALUES (:id, :organization, :account, :code, 'Fixture store', 'ACTIVE',
                        now(), now())
                """).param("id", store).param("organization", organization)
                .param("account", account).param("code", "adwx-store-" + suffix).update();
        seed.sql("""
                INSERT INTO core.product (id, organization_id, code, display_name, status,
                        created_at, updated_at)
                VALUES (:id, :organization, :code, 'Fixture product', 'ACTIVE', now(), now())
                """).param("id", product).param("organization", organization)
                .param("code", "adwx-prod-" + suffix).update();
        seed.sql("""
                INSERT INTO core.product_variant (id, organization_id, product_id, sku_code,
                        display_name, status, created_at, updated_at)
                VALUES (:id, :organization, :product, :sku, 'Fixture variant', 'ACTIVE',
                        now(), now())
                """).param("id", productVariant).param("organization", organization)
                .param("product", product).param("sku", "adwx-sku-" + suffix).update();
        seed.sql("""
                INSERT INTO core.platform_listing (id, organization_id, store_id,
                        marketplace_account_id, platform_code, native_listing_key, title,
                        first_seen_at, last_seen_at, status, created_at, updated_at)
                VALUES (:id, :organization, :store, :account, :platform, :key, 'Fixture listing',
                        now(), now(), 'OBSERVED', now(), now())
                """).param("id", listing).param("organization", organization)
                .param("store", store).param("account", account).param("platform", PLATFORM_CODE)
                .param("key", "adwx-listing-" + suffix).update();
        seed.sql("""
                INSERT INTO core.platform_listing_variant (id, organization_id,
                        platform_listing_id, native_variant_key, first_seen_at, last_seen_at,
                        status, created_at, updated_at)
                VALUES (:id, :organization, :listing, :key, now(), now(), 'OBSERVED',
                        now(), now())
                """).param("id", listingVariant).param("organization", organization)
                .param("listing", listing).param("key", "adwx-variant-" + suffix).update();
    }

    /**
     * Four people, because the activation record needs three distinct ones.
     *
     * <p>{@code ad_decision_policy_bundle_separation_ck} refuses a bundle whose
     * endorser, approver and activator are not three different people. That is
     * the Maker-Checker rule as a constraint, and a fixture with one person in
     * it could not express a compliant bundle at all.
     */
    private static People seedPeople(JdbcClient seed, UUID organization, UUID provider,
                                     String issuer, String suffix) {
        seed.sql("""
                INSERT INTO iam.identity_provider (id, code, display_name, issuer,
                        mfa_claim_name, mfa_claim_value, max_auth_age_seconds,
                        verification_state, last_verified_at, evidence_ref,
                        verified_source_title, owner_label, status, created_at, updated_at)
                VALUES (:id, :code, 'Fixture IdP', :issuer, 'amr', 'mfa', 900, 'VERIFIED',
                        now(), 'evidence://fixture/idp', 'Fixture provider document',
                        'test-fixture', 'ACTIVE', now(), now())
                """).param("id", provider).param("code", "adwx-idp-" + suffix)
                .param("issuer", issuer).update();
        return new People(
                seedUser(seed, organization, provider, "approver"),
                seedUser(seed, organization, provider, "endorser"),
                seedUser(seed, organization, provider, "activator"),
                seedUser(seed, organization, provider, "bundle-approver"));
    }

    private static UUID seedUser(JdbcClient seed, UUID organization, UUID provider, String role) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO iam.user_account (id, organization_id, identity_provider_id,
                        external_subject, display_name, status, credentials_valid_from,
                        created_at, updated_at)
                VALUES (:id, :organization, :provider, :subject, :name, 'ACTIVE',
                        now() - interval '30 days', now(), now())
                """).param("id", id).param("organization", organization)
                .param("provider", provider).param("subject", role + "-" + id)
                .param("name", "Fixture " + role).update();
        return id;
    }

    private static void seedListingMapping(JdbcClient seed, UUID organization, UUID listingVariant,
                                           UUID productVariant, UUID confirmedBy) {
        seed.sql("""
                INSERT INTO core.listing_mapping (id, organization_id, platform_listing_variant_id,
                        product_variant_id, effective_from, status, confirmed_by_user_id, reason,
                        created_at, updated_at)
                VALUES (gen_random_uuid(), :organization, :listingVariant, :productVariant,
                        now() - interval '30 days', 'ACTIVE', :confirmedBy,
                        'seeded advertising write fixture', now(), now())
                """).param("organization", organization).param("listingVariant", listingVariant)
                .param("productVariant", productVariant).param("confirmedBy", confirmedBy).update();
    }

    /**
     * The role and the grant {@code ops.create_ad_bid_command} demands.
     *
     * <p>The function checks the actor's authority itself rather than leaving it
     * to the gate, so a command created by somebody who could not have approved
     * it is unrepresentable. OWNER already carries AD_BID_CHANGE_APPROVE in the
     * reviewed matrix V0036 seeds; only the assignment and the scope grant are
     * fixture data.
     */
    private static void seedApprovalAuthority(JdbcClient seed, UUID organization, UUID user) {
        seed.sql("""
                INSERT INTO iam.user_role_assignment (id, organization_id, user_id, role_code,
                        effective_from, status, created_at, updated_at)
                VALUES (gen_random_uuid(), :organization, :user, 'OWNER',
                        now() - interval '1 hour', 'ACTIVE', now(), now())
                """).param("organization", organization).param("user", user).update();
        seed.sql("""
                INSERT INTO iam.user_scope_grant (id, organization_id, user_id, action_code,
                        organization_ref_id, effective_from, status, created_at, updated_at)
                VALUES (gen_random_uuid(), :organization, :user, 'AD_BID_CHANGE_APPROVE',
                        :organization, now() - interval '1 hour', 'ACTIVE', now(), now())
                """).param("organization", organization).param("user", user).update();
    }

    // ------------------------------------------------------------------
    // The object, its semantics and its facts
    // ------------------------------------------------------------------

    /**
     * The write-capable semantic profile, for the fixture protocol only.
     *
     * <p>{@code OFFICIAL_VERIFIED} and {@code VERIFIED} because the protocol
     * this profile describes is the one specified in {@link #seedRegistry}
     * below: a synchronous accept, an exact-field readback, a half-rouble step.
     * Those are facts about a protocol this repository wrote, and pointing the
     * evidence reference at the fixture itself is the honest citation. The same
     * row against {@code OZON} would be a claim nobody has earned, and
     * {@code ad_semantic_profile_fixture_ck} makes the alternative — promoting
     * the synthetic profile — impossible for every role including this one.
     */
    private static void seedSemanticProfile(JdbcClient seed, UUID profile) {
        seed.sql("""
                INSERT INTO platform.ad_semantic_profile (id, platform_code, profile_version,
                        native_object_kind, control_level, bidding_mode, bid_field_present,
                        bid_currency_code, bid_unit_code, bid_precision, bid_step, bid_minimum,
                        bid_maximum, idempotency_semantics, propagation_semantics,
                        readback_semantics, correction_behaviour, source_maturity,
                        verification_state, last_verified_at, evidence_ref,
                        verified_source_title, owner_label, status, created_at, updated_at)
                VALUES (:id, :platform, 1, 'KEYWORD', 'KEYWORD', 'MANUAL_BID', true,
                        'RUB', 'CURRENCY_MAJOR', 2, 0.5000, 1.0000, 500.0000,
                        'VERIFIED_NATIVE_KEY', 'SYNCHRONOUS', 'EXACT_FIELD',
                        'APPEND_ONLY_CORRECTION', 'OFFICIAL_VERIFIED', 'VERIFIED', now(),
                        'evidence://fixture/ad-bid-protocol',
                        'MarketOps advertising bid protocol fixture', 'test-fixture', 'ACTIVE',
                        now(), now())
                """).param("id", profile).param("platform", PLATFORM_CODE).update();
    }

    private static void seedAdvertisingObject(JdbcClient seed, UUID organization, UUID store,
                                              UUID profile, UUID object, String suffix) {
        seed.sql("""
                INSERT INTO core.ad_native_object (id, organization_id, store_id, platform_code,
                        semantic_profile_id, native_object_kind, native_object_key,
                        native_campaign_key, native_object_name, bidding_mode,
                        control_granularity_state, control_evidence_ref, lineage_key,
                        lineage_generation, observation_state, first_observed_at,
                        last_observed_at, status, created_at, updated_at)
                VALUES (:id, :organization, :store, :platform, :profile, 'KEYWORD', :key,
                        :campaign, 'Fixture keyword', 'MANUAL_BID', 'PROVEN_INDEPENDENT',
                        'evidence://fixture/independent-control', :key, 1, 'OBSERVED',
                        now() - interval '90 days', now(), 'ACTIVE', now(), now())
                """).param("id", object).param("organization", organization).param("store", store)
                .param("platform", PLATFORM_CODE).param("profile", profile)
                .param("key", "adwx-obj-" + suffix).param("campaign", "adwx-camp-" + suffix)
                .update();
    }

    /**
     * Provenance for every fact this fixture records.
     *
     * <p>{@code MANUAL_ENTRY} rather than {@code MARKETPLACE_RAW}: a
     * marketplace-sourced fact has to name the stored bytes it came from, and
     * this fixture contacts nothing and therefore has none to name.
     */
    private static void seedProvenance(JdbcClient seed, UUID organization, UUID provenance,
                                       UUID recordedBy) {
        seed.sql("""
                INSERT INTO core.fact_provenance (id, organization_id, source_kind, source_time,
                        ingestion_time, recorded_by_user_id, evidence_note)
                VALUES (:id, :organization, 'MANUAL_ENTRY', now() - interval '2 hours',
                        now() - interval '1 hour', :recordedBy,
                        'synthetic advertising fixture; no provider was contacted')
                """).param("id", provenance).param("organization", organization)
                .param("recordedBy", recordedBy).update();
    }

    private static void seedAffectedSet(JdbcClient seed, UUID organization, UUID object,
                                        UUID affectedSet, String digest, UUID productVariant,
                                        UUID listingVariant) {
        seed.sql("""
                INSERT INTO core.ad_affected_set (id, organization_id, ad_native_object_id,
                        affected_set_digest, product_variant_ids, platform_listing_variant_ids,
                        resolution_state, unresolved_reason_codes, resolved_at, created_at)
                VALUES (:id, :organization, :object, :digest, ARRAY[:variant]::uuid[],
                        ARRAY[:listingVariant]::uuid[], 'COMPLETE', '{}', now(), now())
                """).param("id", affectedSet).param("organization", organization)
                .param("object", object).param("digest", digest)
                .param("variant", productVariant).param("listingVariant", listingVariant).update();
    }

    private static void seedConfiguration(JdbcClient seed, UUID organization, UUID object,
                                          UUID provenance, UUID profile) {
        seed.sql("""
                INSERT INTO core.ad_object_configuration_observation (id, organization_id,
                        ad_native_object_id, provenance_id, semantic_profile_id,
                        lineage_generation, observed_bid_amount, bid_currency_code, bid_unit_code,
                        observed_status, observed_bidding_mode, evidence_grade, observed_at,
                        source_time, created_at)
                VALUES (gen_random_uuid(), :organization, :object, :provenance, :profile, 1,
                        CAST(:bid AS numeric), 'RUB', 'CURRENCY_MAJOR', 'RUNNING', 'MANUAL_BID',
                        'OFFICIAL_API_READBACK', now() - interval '1 hour',
                        now() - interval '2 hours', now())
                """).param("organization", organization).param("object", object)
                .param("provenance", provenance).param("profile", profile)
                .param("bid", CURRENT_BID).update();
    }

    /**
     * One window of official spend and traffic, complete and no longer
     * correctable.
     *
     * <p>Recorded through {@code core.fact_provenance} rather than as a bare
     * row, because a fact whose origin nobody can name is not evidence and the
     * schema will not accept one.
     */
    static UUID seedObjectFact(JdbcClient seed, Graph graph, String key, String periodStart,
                               String periodEnd, String spend, long clicks, long providerOrders) {
        return seedObjectFact(seed, graph, key, periodStart, periodEnd, spend, clicks,
                providerOrders, null);
    }

    /**
     * The same window, restated by a fact that arrived later.
     *
     * <p>A restatement supersedes rather than replaces. The earlier row stays
     * exactly as it was recorded, and every window read filters on the absence
     * of a successor, so what a report said on the day can still be re-derived
     * after the correction.
     */
    static UUID seedObjectFact(JdbcClient seed, Graph graph, String key, String periodStart,
                               String periodEnd, String spend, long clicks, long providerOrders,
                               UUID supersedes) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO ledger.ad_object_fact (id, organization_id, provenance_id,
                        ad_native_object_id, store_id, source_fact_key, period_start, period_end,
                        currency_code, spend_amount, impressions, views, clicks,
                        provider_attributed_orders, provider_attributed_units,
                        provider_attributed_revenue, attribution_window_code,
                        attribution_model_native, report_window_complete, correction_window_open,
                        supersedes_fact_id, adjustment_kind, source_time, recorded_at)
                VALUES (:id, :organization, :provenance, :object, :store, :key,
                        CAST(:periodStart AS timestamptz), CAST(:periodEnd AS timestamptz),
                        'RUB', CAST(:spend AS numeric), :impressions, :views, :clicks,
                        :orders, :orders, 0.0000, 'D7', 'last-click', true, false,
                        :supersedes, :adjustment, now(), now())
                """).param("id", id).param("organization", graph.organizationId())
                .param("supersedes", supersedes)
                .param("adjustment", supersedes == null ? null : "LATE_ARRIVAL")
                .param("provenance", graph.provenanceId()).param("object", graph.objectId())
                .param("store", graph.storeId()).param("key", key)
                .param("periodStart", periodStart).param("periodEnd", periodEnd)
                .param("spend", spend).param("impressions", clicks * 40)
                .param("views", clicks * 30).param("clicks", clicks)
                .param("orders", providerOrders).update();
        return id;
    }

    /**
     * One window of sales linked to the advertising object, at one stage.
     *
     * <p>Orders placed and sales that survived are separate rows rather than one
     * row with two counts, because they are separate facts that arrive at
     * different times and can be restated independently. The settled coverage
     * ratio is exactly the relationship between them, and a schema that carried
     * both in one row could not express a window where the orders are known and
     * the retentions are not yet.
     */
    static UUID seedLinkedSale(JdbcClient seed, Graph graph, String saleStage, String occurredAt,
                               String periodStart, String periodEnd, int events,
                               String netSales) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO ledger.ad_linked_sale_event (id, organization_id, provenance_id,
                        ad_native_object_id, affected_set_id, platform_listing_variant_id,
                        conversion_definition_id, sale_stage, linkage_basis,
                        linkage_evidence_ref, event_count, net_sales_amount, currency_code,
                        occurred_at, period_start, period_end, source_time, recorded_at)
                VALUES (:id, :organization, :provenance, :object, :affectedSet, :listingVariant,
                        :definition, :stage, 'DETERMINISTIC_OBJECT_LINKAGE',
                        'evidence://fixture/linked-sale', :events, CAST(:netSales AS numeric),
                        'RUB', CAST(:occurredAt AS timestamptz),
                        CAST(:periodStart AS timestamptz), CAST(:periodEnd AS timestamptz),
                        now(), now())
                """).param("id", id).param("organization", graph.organizationId())
                .param("provenance", graph.provenanceId()).param("object", graph.objectId())
                .param("affectedSet", graph.affectedSetId())
                .param("listingVariant", graph.listingVariantId())
                .param("definition", graph.conversionDefinitionId())
                .param("stage", saleStage).param("events", events).param("netSales", netSales)
                .param("occurredAt", occurredAt).param("periodStart", periodStart)
                .param("periodEnd", periodEnd).update();
        return id;
    }

    /**
     * Move the instant the write was proven to have landed further into the past.
     *
     * <p>The completed-sales guard and both outcome windows are measured from
     * that instant against {@code clock_timestamp()}, so a test that wanted to
     * see a settled outcome would otherwise have to wait a real day. Moving the
     * recorded observation is honest about what is being simulated — elapsed
     * time, and nothing else. Nothing about the readback's content, its match
     * state or the bytes behind it is touched, and the guard still evaluates
     * against a real clock rather than a mocked one.
     *
     * <p>Written with the migration role, because the application role holds no
     * {@code UPDATE} on this table and this fixture must not ask for one.
     */
    static void backdateTheLanding(JdbcClient seed, UUID commandId, java.time.Duration age) {
        seed.sql("""
                UPDATE ops.ad_bid_command_readback
                   SET observed_at = now() - make_interval(mins => :minutes)
                 WHERE command_id = :commandId
                """).param("commandId", commandId)
                .param("minutes", (int) age.toMinutes()).update();
    }

    // ------------------------------------------------------------------
    // Advertising policy
    // ------------------------------------------------------------------

    private static UUID seedConversionDefinition(JdbcClient seed, UUID organization, UUID owner) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_conversion_definition (id, organization_id,
                        definition_version, scope_kind, sale_stage, traffic_denominator_kind,
                        linkage_basis, minimum_linkage_coverage_ratio,
                        minimum_affected_set_coverage_ratio, minimum_sample_events,
                        maximum_attribution_gap_ratio, observation_window_days, owner_user_id,
                        reason, evidence_reference, effective_from, status, created_at)
                VALUES (:id, :organization, 1, 'ORGANIZATION',
                        'CANONICAL_AD_LINKED_COMPLETED_SALE', 'CLICKS',
                        'DETERMINISTIC_OBJECT_LINKAGE', 0.80000, 0.80000, 5, 1.00000, 30,
                        :owner, 'agreed advertising conversion definition',
                        'evidence://fixture/conversion', now() - interval '7 days', 'ACTIVE',
                        now())
                """).param("id", id).param("organization", organization).param("owner", owner)
                .update();
        return id;
    }

    /**
     * The Allowable CPA, at the same sale stage as the conversion.
     *
     * <p>The stage has to match: {@code ad_bundle_validation_failures} refuses a
     * bundle whose two economic definitions answer different questions, and that
     * is the pairing a Max CPC is built from.
     */
    private static UUID seedAllowableCpaDefinition(JdbcClient seed, UUID organization,
                                                   UUID owner) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_allowable_cpa_definition (id, organization_id,
                        definition_version, scope_kind, sale_stage, currency_code,
                        contribution_basis, target_contribution_retention_ratio,
                        return_loss_treatment, owner_user_id, reason, evidence_reference,
                        effective_from, status, created_at)
                VALUES (:id, :organization, 1, 'ORGANIZATION',
                        'CANONICAL_AD_LINKED_COMPLETED_SALE', 'RUB',
                        'OPERATIONAL_CONTRIBUTION', 0.60000, 'APPLIED_ONCE_ON_TOP', :owner,
                        'agreed allowable acquisition cost', 'evidence://fixture/allowable-cpa',
                        now() - interval '7 days', 'ACTIVE', now())
                """).param("id", id).param("organization", organization).param("owner", owner)
                .update();
        return id;
    }

    /**
     * The four qualification tiers, each strictly harder than the one below.
     *
     * <p>WATCH, OPTIMIZATION_TASK, OPTIMIZATION_RECOMMENDATION and
     * OPTIMIZATION_BID_WRITE — the ordering
     * {@code core.ad_qualification_tier_is_monotonic} exists to check.
     *
     * <p>Four rows, and only four. Writing this test found that the function
     * self-joined the live tiers on {@code higher.rank = lower.rank + 1} and
     * then asserted {@code count(*) = 4} over the <em>join</em>, where four
     * tiers make three adjacent pairs — so the check was unsatisfiable with one
     * row per tier and every bundle activation failed with
     * {@code QUALIFICATION_TIER_MONOTONICITY_VIOLATED}. Nothing had noticed
     * because no bundle in this repository had ever been activated. The
     * candidate migration was corrected rather than the fixture padded with a
     * fifth row: a fixture shaped around a defect proves the defect.
     */
    private static void seedQualificationTiers(JdbcClient seed, UUID organization, UUID owner) {
        record Tier(String name, int completed, int retained, long traffic, String spend,
                    String recoverable, boolean correction, boolean baseline, String confidence) {
        }
        List<Tier> tiers = List.of(
                new Tier("WATCH", 0, 0, 0L, "0.0000", "0.0000", false, false, "UNKNOWN"),
                new Tier("OPTIMIZATION_TASK", 1, 0, 10L, "10.0000", "10.0000", false, false,
                        "ESTIMATED_EXPLAINED"),
                new Tier("OPTIMIZATION_RECOMMENDATION", 5, 1, 100L, "100.0000", "100.0000",
                        false, true, "CANONICAL_PENDING_SETTLEMENT"),
                new Tier("OPTIMIZATION_BID_WRITE", 10, 5, 1000L, "1000.0000", "500.0000",
                        true, true, "CANONICAL_CONFIRMED"));
        for (Tier tier : tiers) {
            insertQualificationTier(seed, organization, owner, tier.name(), 2, tier.completed(),
                    tier.retained(), tier.traffic(), tier.spend(), tier.recoverable(),
                    tier.correction(), tier.baseline(), tier.confidence(), "ACTIVE");
        }
    }

    private static void insertQualificationTier(JdbcClient seed, UUID organization, UUID owner,
                                                String tier, int version, int completed,
                                                int retained, long traffic, String spend,
                                                String recoverable, boolean correction,
                                                boolean baseline, String confidence,
                                                String status) {
        seed.sql("""
                INSERT INTO core.ad_optimization_qualification_policy (id, organization_id,
                        policy_version, purpose_tier, scope_kind,
                        eligible_observation_window_days, minimum_source_coverage_ratio,
                        minimum_affected_set_coverage_ratio, minimum_traffic_denominator,
                        minimum_completed_sale_events, minimum_retained_sale_events,
                        minimum_spend_amount, currency_code, minimum_sustained_periods,
                        minimum_recoverable_amount, requires_correction_window_closed,
                        requires_comparable_baseline, minimum_confidence_state,
                        boundary_inclusive, owner_user_id, reason, evidence_reference,
                        effective_from, status, created_at)
                VALUES (gen_random_uuid(), :organization, :version, :tier, 'ORGANIZATION', 30,
                        0.80000, 0.80000, :traffic, :completed, :retained,
                        CAST(:spend AS numeric), 'RUB', 1, CAST(:recoverable AS numeric),
                        :correction, :baseline, :confidence, true, :owner,
                        'agreed optimization qualification', 'evidence://fixture/qualification',
                        now() - interval '7 days', :status, now())
                """).param("organization", organization).param("version", version)
                .param("tier", tier).param("traffic", traffic).param("completed", completed)
                .param("retained", retained).param("spend", spend)
                .param("recoverable", recoverable).param("correction", correction)
                .param("baseline", baseline).param("confidence", confidence)
                .param("owner", owner).param("status", status).update();
    }

    private static UUID seedPriorityPolicy(JdbcClient seed, UUID organization, UUID owner) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_priority_policy (id, organization_id, policy_version,
                        profit_loss_weight, spend_exposure_weight, critical_sales_weight,
                        recoverable_profit_weight, evidence_maturity_weight, age_weight,
                        confidence_weight, owner_user_id, reason, evidence_reference,
                        effective_from, status, created_at)
                VALUES (:id, :organization, 1, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, -1.0, :owner,
                        'agreed advertising priority weights', 'evidence://fixture/priority',
                        now() - interval '7 days', 'ACTIVE', now())
                """).param("id", id).param("organization", organization).param("owner", owner)
                .update();
        return id;
    }

    private static UUID seedHumanSloProfile(JdbcClient seed, UUID organization, UUID owner) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_human_slo_profile (id, organization_id, policy_version, lane,
                        acknowledgement_minutes, action_minutes, escalation_minutes,
                        staffed_coverage_enabled, out_of_coverage_visible_from_minutes,
                        owner_user_id, reason, evidence_reference, effective_from, status,
                        created_at)
                VALUES (:id, :organization, 1, 'PROTECTION', 15, 60, 120, false, 30, :owner,
                        'agreed advertising service level', 'evidence://fixture/slo',
                        now() - interval '7 days', 'ACTIVE', now())
                """).param("id", id).param("organization", organization).param("owner", owner)
                .update();
        return id;
    }

    private static UUID seedApprovalLeasePolicy(JdbcClient seed, UUID organization, UUID owner) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_approval_lease_policy (id, organization_id, policy_version,
                        scope_kind, direction, lease_seconds, material_lease_seconds,
                        owner_user_id, reason, evidence_reference, effective_from, status,
                        created_at)
                VALUES (:id, :organization, 1, 'ORGANIZATION', 'PROTECTION_DECREASE', 3600, 1800,
                        :owner, 'agreed approval lease', 'evidence://fixture/lease',
                        now() - interval '7 days', 'ACTIVE', now())
                """).param("id", id).param("organization", organization).param("owner", owner)
                .update();
        return id;
    }

    private static UUID seedExposureEnvelope(JdbcClient seed, UUID organization, UUID owner) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_exposure_envelope (id, organization_id, policy_version,
                        scope_kind, currency_code, max_active_interventions,
                        max_affected_retained_sales_share, max_associated_spend_amount,
                        max_cumulative_bid_change_amount, cumulative_window_hours,
                        max_unresolved_transmitted_writes, reserved_recovery_headroom_count,
                        owner_user_id, reason, evidence_reference, effective_from, status,
                        created_at)
                VALUES (:id, :organization, 1, 'ORGANIZATION', 'RUB', 10, 0.20000, 100000.0000,
                        500.0000, 24, 2, 2, :owner, 'agreed aggregate exposure envelope',
                        'evidence://fixture/exposure', now() - interval '7 days', 'ACTIVE', now())
                """).param("id", id).param("organization", organization).param("owner", owner)
                .update();
        return id;
    }

    /**
     * The materiality envelope, with an ordinary allowance of zero.
     *
     * <p>Zero is the shipped position rather than a fixture convenience: every
     * nonzero change is Material, so every command takes the reviewed route.
     * The gate refuses an Ordinary-routed command outright because this Slice
     * creates no promotion record, so a wider envelope here would make the
     * command unexecutable rather than easier.
     */
    private static UUID seedMaterialityPolicy(JdbcClient seed, UUID organization, UUID owner) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_materiality_policy (id, organization_id, policy_version,
                        scope_kind, currency_code, ordinary_nonzero_envelope_amount,
                        ordinary_relative_envelope_ratio, material_absolute_change_amount,
                        material_relative_change_ratio, material_spend_exposure_amount,
                        material_affected_variant_count, material_critical_sales_amount,
                        material_cumulative_change_amount, material_cumulative_window_hours,
                        owner_user_id, reason, evidence_reference, effective_from, status,
                        created_at)
                VALUES (:id, :organization, 1, 'ORGANIZATION', 'RUB', 0.0000, 0.00000, 1.0000,
                        0.00100, 100.0000, 1, 100.0000, 100.0000, 24, :owner,
                        'agreed materiality envelope', 'evidence://fixture/materiality',
                        now() - interval '7 days', 'ACTIVE', now())
                """).param("id", id).param("organization", organization).param("owner", owner)
                .update();
        return id;
    }

    /**
     * The outcome plan, with the shortest windows the schema will accept.
     *
     * <p>Six hours of operational observation, a day of settlement and the
     * minimum twenty-four-hour completed-sales guard. The schema floors all
     * three, so this is not a fixture loosening anything; it is the tightest
     * plan a real deployment could publish. It is here because the bundle must
     * name an outcome plan to be coherent at all, and because the guard reads it
     * — nothing in this suite reaches the two outcome stages, which sit
     * downstream of a readback the schema cannot currently record.
     */
    private static UUID seedOutcomePolicy(JdbcClient seed, UUID organization, UUID owner) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_outcome_policy (id, organization_id, policy_version,
                        scope_kind, direction, observation_starts_minutes,
                        operational_window_hours, settlement_window_hours,
                        completed_sales_guard_hours, minimum_settled_coverage_ratio,
                        primary_metric_code, comparison_basis, improvement_threshold_ratio,
                        regression_threshold_ratio, minimum_traffic_count, owner_user_id, reason,
                        evidence_reference, effective_from, status, created_at)
                VALUES (:id, :organization, 1, 'ORGANIZATION', 'PROTECTION_DECREASE', 5, 6, 24,
                        24, 0.80000, 'AD_SPEND', 'PRE_CHANGE_SAME_OBJECT', 0.10000, 0.05000, 1,
                        :owner, 'agreed advertising outcome plan', 'evidence://fixture/outcome',
                        now() - interval '7 days', 'ACTIVE', now())
                """).param("id", id).param("organization", organization).param("owner", owner)
                .update();
        return id;
    }

    /**
     * The bid target policy: an economically bounded decrease.
     *
     * <p>{@code MAX_CPC_BOUNDED} rather than the cause-bound route, because a
     * candidate bounded by what a click is worth can support a claim about
     * profitability and a cause-bound step cannot. The headroom is what keeps
     * the target below the ceiling rather than exactly on it, which the schema
     * insists on for this basis: a bid sitting on the value at which a click
     * stops being worth anything leaves no margin for the ceiling's own estimate
     * error.
     */
    private static UUID seedBidTargetPolicy(JdbcClient seed, UUID organization, UUID owner) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_bid_target_policy (id, organization_id, policy_version,
                        scope_kind, native_object_kind, direction, candidate_basis,
                        candidate_count, max_relative_change_ratio, max_absolute_change_amount,
                        currency_code, ceiling_headroom_ratio, cause_bound_step_enabled,
                        cause_bound_step_ratio, cause_bound_causes, owner_user_id, reason,
                        evidence_reference, effective_from, status, created_at)
                VALUES (:id, :organization, 1, 'ORGANIZATION', 'KEYWORD', 'PROTECTION_DECREASE',
                        'MAX_CPC_BOUNDED', 1, 0.30000, 50.0000, 'RUB', 0.10000, false, NULL,
                        '{}', :owner, 'agreed bounded protection decrease',
                        'evidence://fixture/target-policy', now() - interval '7 days', 'ACTIVE',
                        now())
                """).param("id", id).param("organization", organization)
                .param("owner", owner).update();
        return id;
    }

    // ------------------------------------------------------------------
    // The registry: a verified write shape for the fixture protocol
    // ------------------------------------------------------------------

    /**
     * The capability, its endpoints, its operations and the credential to use.
     *
     * <p>All of it VERIFIED, all of it written by the migration role. The rule
     * {@code platform.guard_verified_registry_writer()} carries is that the
     * <em>application</em> role may never promote a registry fact; it is
     * indifferent to the migrating role, and {@code PriceCommandFixture} has
     * relied on exactly that since the price path shipped. The privilege
     * boundary this preserves is still asserted by its own suites.
     *
     * <p>The operation templates carry {@code {targetBid}}, {@code {currencyCode}}
     * and {@code {bidUnitCode}} and never {@code {targetPrice}}, because
     * {@code capability_operation_matches_write_model} refuses a verified
     * advertising operation without the first three or with the last one. No
     * STATUS_ENQUIRY row exists: the capability answers synchronously, and the
     * same trigger refuses an enquiry about a task that cannot exist.
     */
    private static void seedRegistry(JdbcClient seed, UUID organization, UUID account,
                                     UUID store, UUID capability, String suffix) {
        seed.sql("""
                INSERT INTO platform.platform_api_profile (platform_code, base_url,
                        request_timeout_ms, max_response_bytes, verification_state,
                        last_verified_at, evidence_ref, verified_source_title, owner_label,
                        status, created_at, updated_at)
                VALUES (:platform, 'https://fixture.invalid', 5000, 1048576, 'VERIFIED', now(),
                        'evidence://fixture/ad-bid-protocol',
                        'MarketOps advertising bid protocol fixture', 'test-fixture', 'ACTIVE',
                        now(), now())
                ON CONFLICT (platform_code) DO NOTHING
                """).param("platform", PLATFORM_CODE).update();
        seed.sql("""
                INSERT INTO platform.platform_auth_header (id, platform_code, header_name,
                        value_source, value_template, credential_purpose, ordinal,
                        verification_state, last_verified_at, evidence_ref,
                        verified_source_title, owner_label, status, created_at, updated_at)
                VALUES (gen_random_uuid(), :platform, 'Authorization', 'RESOLVED_SECRET',
                        'Bearer {value}', 'ADS_WRITE', 1, 'VERIFIED', now(),
                        'evidence://fixture/ad-bid-protocol',
                        'MarketOps advertising bid protocol fixture', 'test-fixture', 'ACTIVE',
                        now(), now())
                """).param("platform", PLATFORM_CODE).update();
        seed.sql("""
                INSERT INTO platform.platform_capability (id, platform_code, capability_code,
                        display_name, applies_to, read_write_class, subscription_required,
                        verification_state, last_verified_at, evidence_ref,
                        verified_source_title, owner_label, contract_test_status, status,
                        write_result_model, created_at, updated_at)
                VALUES (:id, :platform, 'ad-bid-change', 'Advertising bid change', 'STORE',
                        'WRITE', 'NO', 'VERIFIED', now(), 'evidence://fixture/ad-bid-protocol',
                        'MarketOps advertising bid protocol fixture', 'test-fixture', 'PASSING',
                        'ACTIVE', 'SYNCHRONOUS', now(), now())
                """).param("id", capability).param("platform", PLATFORM_CODE).update();

        seedOperation(seed, capability, "APPLY", suffix);
        seedOperation(seed, capability, "READBACK", suffix);
        seedOperation(seed, capability, "RESTORE", suffix);

        seed.sql("""
                INSERT INTO platform.credential_metadata (id, organization_id,
                        marketplace_account_id, code, display_name, purpose_code, scope_mode,
                        secret_reference, effective_from, expires_at, status, custodian_label,
                        verification_state, created_at, updated_at)
                VALUES (gen_random_uuid(), :organization, :account, :code,
                        'Fixture advertising write credential', 'ADS_WRITE', 'ACCOUNT',
                        'secret-ref://fixture/ads-write', now() - interval '1 day',
                        now() + interval '30 days', 'ACTIVE', 'test-fixture', 'UNVERIFIED',
                        now(), now())
                """).param("organization", organization).param("account", account)
                .param("code", "adwx-cred-" + suffix).update();
        seed.sql("""
                INSERT INTO platform.capability_subject_status (id, organization_id,
                        platform_code, capability_id, store_id, availability, last_verified_at,
                        evidence_ref, verified_source_title, created_at, updated_at)
                VALUES (gen_random_uuid(), :organization, :platform, :capability, :store,
                        'AVAILABLE', now(), 'evidence://fixture/ad-bid-protocol',
                        'MarketOps advertising bid protocol fixture', now(), now())
                """).param("organization", organization).param("platform", PLATFORM_CODE)
                .param("capability", capability).param("store", store).update();
        seed.sql("""
                INSERT INTO platform.feature_flag (id, flag_code, flag_kind, scope_kind, state,
                        status, reason, created_at, updated_at)
                SELECT gen_random_uuid(), 'ad-bid-change-write', 'WRITE_CAPABILITY', 'GLOBAL',
                       'ENABLED', 'ACTIVE', 'advertising vertical path fixture', now(), now()
                 WHERE NOT EXISTS (SELECT 1 FROM platform.feature_flag
                                    WHERE flag_code = 'ad-bid-change-write'
                                      AND scope_kind = 'GLOBAL')
                """).update();
        seed.sql("""
                INSERT INTO platform.feature_flag (id, flag_code, flag_kind, scope_kind,
                        capability_id, state, status, reason, created_at, updated_at)
                VALUES (gen_random_uuid(), 'ad-bid-change-write', 'WRITE_CAPABILITY',
                        'CAPABILITY', :capability, 'ENABLED', 'ACTIVE',
                        'advertising vertical path fixture', now(), now())
                """).param("capability", capability).update();
    }

    private static void seedOperation(JdbcClient seed, UUID capability, String operation,
                                      String suffix) {
        UUID endpoint = UUID.randomUUID();
        boolean writing = "APPLY".equals(operation) || "RESTORE".equals(operation);
        seed.sql("""
                INSERT INTO platform.platform_endpoint (id, platform_code, endpoint_code,
                        api_version, http_method, path_template, query_template,
                        operation_function, capability_id, read_write_class, pagination_model,
                        idempotency_support, verification_state, last_verified_at, evidence_ref,
                        verified_source_title, owner_label, contract_test_status, status,
                        created_at, updated_at)
                VALUES (:id, :platform, :code, 'v1', :method, :path, :query, :function,
                        :capability, :kind, 'NONE', 'YES', 'VERIFIED', now(),
                        'evidence://fixture/ad-bid-protocol',
                        'MarketOps advertising bid protocol fixture', 'test-fixture', 'PASSING',
                        'ACTIVE', now(), now())
                """).param("id", endpoint).param("platform", PLATFORM_CODE)
                .param("code", "adwx." + operation.toLowerCase(java.util.Locale.ROOT) + "."
                        + suffix)
                .param("method", writing ? "POST" : "GET")
                .param("path", "/fixture/ad-bid/" + operation.toLowerCase(java.util.Locale.ROOT))
                .param("query", writing ? null : "object={nativeObjectKey}")
                .param("function", "AD_BID_" + operation).param("capability", capability)
                .param("kind", writing ? "WRITE" : "READ").update();
        seed.sql("""
                INSERT INTO platform.capability_operation (id, capability_id, platform_code,
                        operation, endpoint_id, request_template, accepted_pointer,
                        accepted_value, observed_price_pointer, observed_currency_pointer,
                        conditional_write_header, version_token_header, verification_state,
                        last_verified_at, evidence_ref, verified_source_title, owner_label,
                        status, created_at, updated_at)
                VALUES (gen_random_uuid(), :capability, :platform, :operation, :endpoint,
                        :template, '/accepted', 'true'::jsonb, '/price', '/currency',
                        :conditional, :versionHeader, 'VERIFIED', now(),
                        'evidence://fixture/ad-bid-protocol',
                        'MarketOps advertising bid protocol fixture', 'test-fixture', 'ACTIVE',
                        now(), now())
                """).param("capability", capability).param("platform", PLATFORM_CODE)
                .param("operation", operation).param("endpoint", endpoint)
                .param("template", writing
                        ? "{\"bid\":\"{targetBid}\",\"currency\":\"{currencyCode}\","
                                + "\"unit\":\"{bidUnitCode}\",\"object\":\"{nativeObjectKey}\"}"
                        : "")
                .param("conditional", "RESTORE".equals(operation) ? "If-Match" : null)
                .param("versionHeader", "READBACK".equals(operation) ? "etag" : null).update();
    }

    /**
     * The Pilot allowlist entry naming the exact object a write may touch.
     *
     * <p>The variant column stays null: {@code pilot_allowlist_entry_entity_shape_ck}
     * requires an advertising entry to name an advertising object and no listing
     * variant, which is what keeps one allowlist serving both actions without
     * either being able to name the other's entity.
     */
    private static void seedPilotAllowlist(JdbcClient seed, UUID organization, UUID store,
                                           UUID object, UUID grantedBy) {
        seed.sql("""
                INSERT INTO ops.pilot_allowlist_entry (id, organization_id, action_kind,
                        platform_code, store_id, platform_listing_variant_id,
                        ad_native_object_id, valid_from, valid_until, status,
                        granted_by_user_id, reason, created_at, updated_at)
                VALUES (gen_random_uuid(), :organization, 'AD_BID_CHANGE', :platform, :store,
                        NULL, :object, now() - interval '1 hour', now() + interval '7 days',
                        'ACTIVE', :grantedBy, 'advertising vertical path pilot cohort',
                        now(), now())
                """).param("organization", organization).param("platform", PLATFORM_CODE)
                .param("store", store).param("object", object).param("grantedBy", grantedBy)
                .update();
    }

    /**
     * A recorded calculation run for the recommendation to cite.
     *
     * <p>{@code ops.recommendation.calculation_run_id} has a foreign key into
     * {@code mart.calculation_run}, so a case identifier will not do.
     */
    private static void seedCalculationRun(JdbcClient seed, UUID organization, UUID store,
                                           UUID run) {
        seed.sql("""
                INSERT INTO mart.calculation_run (id, organization_id, trigger_kind, scope_kind,
                        store_ref_id, window_code, period_start, period_end,
                        definition_set_digest, state, subject_count, value_count, started_at,
                        completed_at, correlation_id)
                VALUES (:id, :organization, 'SCHEDULED', 'STORE', :store, 'D30',
                        now() - interval '30 days', now(), :digest, 'SUCCEEDED', 1, 1, now(),
                        now(), 'ad-vertical-path')
                """).param("id", run).param("organization", organization).param("store", store)
                .param("digest", Digest.ofText("ad-vertical-path-definitions")).update();
    }

    // ------------------------------------------------------------------
    // The bundle, and the case the write path hangs off
    // ------------------------------------------------------------------

    /**
     * Activate the decision bundle, with three distinct people behind it.
     *
     * <p>Inserted directly at {@code ACTIVE} so the activation trigger runs on
     * the row as written: it re-derives {@code ops.ad_bundle_validation_failures}
     * and refuses anything incoherent, whatever the {@code validation_state}
     * column claims. Nothing here is asserted twice — the endorser, the approver
     * and the activator are three separate user accounts, which is the only shape
     * {@code ad_decision_policy_bundle_separation_ck} admits.
     */
    private static UUID activateBundle(JdbcClient seed, UUID organization, UUID account,
                                       UUID store, UUID profile, UUID conversion,
                                       UUID allowableCpa, UUID targetPolicy, UUID priority,
                                       UUID humanSlo, UUID approvalLease, UUID exposure,
                                       UUID materiality, UUID outcome, People people) {
        UUID id = UUID.randomUUID();
        UUID qualification = seed.sql("""
                SELECT id FROM core.ad_optimization_qualification_policy
                 WHERE organization_id = :organization AND purpose_tier = 'OPTIMIZATION_BID_WRITE'
                   AND status = 'ACTIVE'
                """).param("organization", organization).query(UUID.class).single();
        seed.sql("""
                INSERT INTO ops.ad_decision_policy_bundle (id, organization_id, bundle_version,
                        platform_code, marketplace_account_id, store_id, capability_code,
                        direction, candidate_basis, native_object_kind, lifecycle_scope,
                        semantic_profile_id, conversion_definition_id,
                        allowable_cpa_definition_id, qualification_policy_id, target_policy_id,
                        priority_policy_id, human_slo_profile_id, approval_lease_policy_id,
                        exposure_envelope_id, materiality_policy_id, outcome_policy_id,
                        validation_state, validation_failure_codes, activated_by_user_id,
                        endorsed_by_user_id, approved_by_user_id, gate_scope_reference,
                        effective_from, status, reason, evidence_reference, correlation_id,
                        created_at, updated_at)
                VALUES (:id, :organization, 1, :platform, :account, :store, 'ad-bid-change',
                        'PROTECTION_DECREASE', 'MAX_CPC_BOUNDED', 'KEYWORD', 'ALL',
                        :profile, :conversion, :cpa, :qualification, :targetPolicy, :priority,
                        :slo, :lease, :exposure, :materiality, :outcome, 'VALIDATED', '{}',
                        :activator, :endorser, :approver, 'gate://fixture/ad-bid-vertical-path',
                        now() - interval '1 day', 'ACTIVE',
                        'advertising vertical path decision authority',
                        'evidence://fixture/bundle', 'ad-vertical-path', now(), now())
                """).param("id", id).param("organization", organization)
                .param("platform", PLATFORM_CODE).param("account", account).param("store", store)
                .param("profile", profile).param("conversion", conversion)
                .param("cpa", allowableCpa).param("qualification", qualification)
                .param("targetPolicy", targetPolicy).param("priority", priority)
                .param("slo", humanSlo).param("lease", approvalLease).param("exposure", exposure)
                .param("materiality", materiality).param("outcome", outcome)
                .param("activator", people.activator()).param("endorser", people.endorser())
                .param("approver", people.bundleApprover()).update();
        return id;
    }

    /**
     * A protection case for the write path to hang off, seeded rather than
     * calculated, and this is the fixture's largest honest gap.
     *
     * <p>{@code AdvertisingCaseCalculationService.profitOf} passes the
     * promotion-cost component as unconditionally absent — there is no promotion
     * feed in this Slice — so {@code AdvertisingContributionProfit} can never
     * resolve, so {@code dataDefectOf} always returns
     * {@code PROFIT_ECONOMICS_BLOCKED} and always adds a blocker code. Every
     * calculated case therefore carries at least one blocker, and both
     * {@code AdvertisingProposalService} (which refuses to propose against a
     * blocked case) and {@code GuardrailService} (which refuses a preview whose
     * projection carries blockers) read that. No candidate the product generates
     * for itself can currently reach an approval.
     *
     * <p>So the case the write path consumes is written here: a Protection case
     * at P1, with no blockers, carrying the observed bid the configuration
     * observation carries and an economic ceiling the candidate is then bounded
     * by. That ceiling is the second thing a reviewer should question — it is
     * the number a resolved contribution profit would have produced, stated
     * rather than derived, for the same reason the blocker list is empty here.
     * The calculated case for the same object is left standing beside this one
     * and asserted on separately, so the test says what the calculator really
     * produces rather than hiding it.
     */
    static UUID seedProtectionCase(JdbcClient seed, Graph graph, UUID caseId) {
        seed.sql("""
                INSERT INTO mart.ad_case (id, organization_id, store_id, platform_code,
                        ad_native_object_id, affected_set_id, semantic_profile_id,
                        lineage_generation, case_key, lane, protection_tier, cause_code,
                        evidence_state, confidence_state, blocker_codes,
                        contribution_profit_state, profit_per_ad_rub_state, profit_currency_code,
                        official_spend_state, official_spend_amount, eligible_traffic_state,
                        eligible_traffic_count, ad_linked_conversion_state, max_cpc_state,
                        max_cpc_amount, attribution_gap_state, current_bid_state,
                        current_bid_amount, rank_score,
                        policy_version_digest, bundle_id, as_of, calculated_at, calculation_kind,
                        calculation_id, created_at, updated_at)
                VALUES (:id, :organization, :store, :platform, :object, :affectedSet, :profile,
                        1, :caseKey, 'PROTECTION', 'P1', :cause, 'CANONICAL_CONFIRMED', 'MEDIUM',
                        '{}', 'NOT_AVAILABLE', 'NOT_AVAILABLE', 'RUB', 'AVAILABLE', 100.0000,
                        'AVAILABLE', 600, 'NOT_AVAILABLE', 'AVAILABLE',
                        CAST(:maxCpc AS numeric), 'NOT_AVAILABLE',
                        'AVAILABLE', CAST(:bid AS numeric), 600100, :policyDigest, :bundle,
                        now(), now(), 'TARGETED', gen_random_uuid(), now(), now())
                """).param("id", caseId).param("organization", graph.organizationId())
                .param("store", graph.storeId()).param("platform", PLATFORM_CODE)
                .param("object", graph.objectId()).param("affectedSet", graph.affectedSetId())
                .param("profile", graph.semanticProfileId())
                .param("caseKey", graph.objectId() + ":1:" + CAUSE_CODE)
                .param("cause", CAUSE_CODE).param("bid", CURRENT_BID).param("maxCpc", MAX_CPC)
                .param("policyDigest", Digest.ofText("ad-vertical-path-policy"))
                .param("bundle", graph.bundleId()).update();
        return caseId;
    }

}
