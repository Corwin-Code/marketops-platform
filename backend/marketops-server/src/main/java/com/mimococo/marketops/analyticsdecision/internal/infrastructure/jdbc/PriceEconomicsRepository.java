package com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc;

import com.mimococo.marketops.analyticsdecision.DecisionFreshness;
import com.mimococo.marketops.analyticsdecision.FeeFamily;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsProfile;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsQuery;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsResolution;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational projection-profile and source-watermark authority. */
@Repository
class PriceEconomicsRepository implements PriceEconomicsQuery {

    private static final List<DecisionFreshness.Feed> REQUIRED_FEEDS =
            List.of(DecisionFreshness.Feed.values());

    private final JdbcClient jdbc;

    PriceEconomicsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PriceEconomicsResolution resolveProfile(UUID organizationId,
                                                    String platformCode,
                                                    UUID marketplaceAccountId,
                                                    UUID storeId,
                                                    String fulfillmentModeCode,
                                                    Instant at) {
        if (organizationId == null || platformCode == null || marketplaceAccountId == null
                || storeId == null || fulfillmentModeCode == null) {
            return PriceEconomicsResolution.unavailable(
                    PriceEconomicsResolution.Status.MISSING, "scope-incomplete");
        }
        List<ProfileRow> current = jdbc.sql(PROFILE_SELECT + """
                         WHERE profile.organization_id = :organizationId
                           AND profile.platform_code = :platformCode
                           AND profile.marketplace_account_id = :marketplaceAccountId
                           AND profile.store_id = :storeId
                           AND profile.fulfillment_mode_code = :fulfillmentModeCode
                           AND profile.status = 'ACTIVE'
                           AND profile.effective_from <= :at
                           AND (profile.effective_to IS NULL OR profile.effective_to > :at)
                         ORDER BY profile.profile_version DESC, profile.id
                        """)
                .param("organizationId", organizationId)
                .param("platformCode", platformCode)
                .param("marketplaceAccountId", marketplaceAccountId)
                .param("storeId", storeId)
                .param("fulfillmentModeCode", fulfillmentModeCode)
                .param("at", Timestamp.from(at))
                .query(PriceEconomicsRepository::mapProfileRow)
                .list();
        if (current.size() > 1) {
            return PriceEconomicsResolution.unavailable(
                    PriceEconomicsResolution.Status.AMBIGUOUS,
                    "overlapping-current-profiles=" + current.size());
        }
        if (current.isEmpty()) {
            boolean existed = jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM core.economics_projection_profile profile
                                 WHERE profile.organization_id = :organizationId
                                   AND profile.platform_code = :platformCode
                                   AND profile.marketplace_account_id = :marketplaceAccountId
                                   AND profile.store_id = :storeId
                                   AND profile.fulfillment_mode_code = :fulfillmentModeCode)
                            """)
                    .param("organizationId", organizationId)
                    .param("platformCode", platformCode)
                    .param("marketplaceAccountId", marketplaceAccountId)
                    .param("storeId", storeId)
                    .param("fulfillmentModeCode", fulfillmentModeCode)
                    .query(Boolean.class)
                    .single();
            return PriceEconomicsResolution.unavailable(existed
                            ? PriceEconomicsResolution.Status.EXPIRED
                            : PriceEconomicsResolution.Status.MISSING,
                    existed ? "no-profile-current-at-evaluation" : "profile-absent");
        }

        ProfileRow row = current.getFirst();
        PriceEconomicsProfile.VerificationState verificationState =
                PriceEconomicsProfile.VerificationState.valueOf(row.verificationState());
        if (!verificationState.usableForEngineeringDecision()) {
            return PriceEconomicsResolution.unavailable(
                    PriceEconomicsResolution.Status.UNVERIFIED,
                    "profile-verification-state=" + verificationState);
        }
        if (row.verificationExpiresAt() != null
                && !row.verificationExpiresAt().isAfter(at)) {
            return PriceEconomicsResolution.unavailable(
                    PriceEconomicsResolution.Status.EXPIRED,
                    "profile-verification-expired");
        }

        Map<FeeFamily, PriceEconomicsProfile.Applicability> families =
                new EnumMap<>(FeeFamily.class);
        jdbc.sql("""
                        SELECT family_code, applicability_state
                          FROM core.economics_projection_family
                         WHERE profile_id = :profileId
                         ORDER BY family_code
                        """)
                .param("profileId", row.id())
                .query((result, number) -> families.put(
                        FeeFamily.valueOf(result.getString("family_code")),
                        PriceEconomicsProfile.Applicability.valueOf(
                                result.getString("applicability_state"))))
                .list();

        List<PriceEconomicsProfile.Component> components = jdbc.sql("""
                        SELECT id, component_code, family_code, component_kind,
                               fixed_amount, rate_value, lower_price_inclusive,
                               upper_price_exclusive, evidence_reference
                          FROM core.economics_projection_component
                         WHERE profile_id = :profileId
                         ORDER BY family_code, component_code,
                                  lower_price_inclusive NULLS FIRST, id
                        """)
                .param("profileId", row.id())
                .query((result, number) -> new PriceEconomicsProfile.Component(
                        result.getObject("id", UUID.class),
                        result.getString("component_code"),
                        FeeFamily.valueOf(result.getString("family_code")),
                        PriceEconomicsProfile.ComponentKind.valueOf(
                                result.getString("component_kind")),
                        result.getBigDecimal("fixed_amount"),
                        result.getBigDecimal("rate_value"),
                        result.getBigDecimal("lower_price_inclusive"),
                        result.getBigDecimal("upper_price_exclusive"),
                        result.getString("evidence_reference")))
                .list();

        PriceEconomicsProfile profile = new PriceEconomicsProfile(row.id(), row.version(),
                row.organizationId(), row.platformCode(), row.marketplaceAccountId(),
                row.storeId(), row.fulfillmentModeCode(), row.currencyCode(),
                row.effectiveFrom(), row.effectiveTo(), verificationState, row.verifiedAt(),
                row.verificationExpiresAt(), row.evidenceReference(), row.minimumPrice(),
                row.maximumPrice(), families, components);
        return new PriceEconomicsResolution(PriceEconomicsResolution.Status.AVAILABLE,
                profile, "single-current-profile");
    }

    @Override
    public List<String> activeFulfillmentModes(UUID storeId, Instant at) {
        return jdbc.sql("""
                        SELECT fulfillment_mode_code
                          FROM core.store_fulfillment_declaration
                         WHERE store_id = :storeId AND status = 'ACTIVE'
                           AND effective_from <= :at
                           AND (effective_to IS NULL OR effective_to > :at)
                         ORDER BY fulfillment_mode_code
                        """)
                .param("storeId", storeId)
                .param("at", Timestamp.from(at))
                .query(String.class)
                .list();
    }

    @Override
    public DecisionFreshness decisionFreshness(UUID organizationId,
                                               String platformCode,
                                               UUID marketplaceAccountId,
                                               UUID storeId,
                                               Instant at) {
        if (organizationId == null || platformCode == null || marketplaceAccountId == null
                || storeId == null) {
            return DecisionFreshness.unavailable();
        }
        Map<DecisionFreshness.Feed, DecisionFreshness.Watermark> watermarks =
                new EnumMap<>(DecisionFreshness.Feed.class);
        jdbc.sql("""
                        SELECT DISTINCT ON (watermark.feed_code)
                               watermark.id, watermark.feed_code,
                               watermark.source_updated_at, watermark.ingested_at,
                               watermark.reconciled_at, watermark.evidence_reference
                          FROM core.source_feed_watermark watermark
                         WHERE watermark.organization_id = :organizationId
                           AND watermark.platform_code = :platformCode
                           AND watermark.marketplace_account_id = :marketplaceAccountId
                           AND watermark.store_id = :storeId
                           AND watermark.verification_state = 'VERIFIED'
                           AND watermark.recorded_at <= :at
                         ORDER BY watermark.feed_code, watermark.recorded_at DESC,
                                  watermark.id DESC
                        """)
                .param("organizationId", organizationId)
                .param("platformCode", platformCode)
                .param("marketplaceAccountId", marketplaceAccountId)
                .param("storeId", storeId)
                .param("at", Timestamp.from(at))
                .query((result, number) -> {
                    DecisionFreshness.Feed feed = DecisionFreshness.Feed.valueOf(
                            result.getString("feed_code"));
                    watermarks.put(feed, new DecisionFreshness.Watermark(
                            result.getObject("id", UUID.class), feed,
                            instant(result, "source_updated_at"),
                            result.getTimestamp("ingested_at").toInstant(),
                            instant(result, "reconciled_at"),
                            result.getString("evidence_reference")));
                    return feed;
                }).list();
        return new DecisionFreshness(watermarks, REQUIRED_FEEDS);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static ProfileRow mapProfileRow(ResultSet result, int rowNumber)
            throws SQLException {
        return new ProfileRow(
                result.getObject("id", UUID.class),
                result.getInt("profile_version"),
                result.getObject("organization_id", UUID.class),
                result.getString("platform_code"),
                result.getObject("marketplace_account_id", UUID.class),
                result.getObject("store_id", UUID.class),
                result.getString("fulfillment_mode_code"),
                result.getString("currency_code"),
                result.getTimestamp("effective_from").toInstant(),
                instant(result, "effective_to"),
                result.getString("verification_state"),
                result.getTimestamp("verified_at").toInstant(),
                instant(result, "verification_expires_at"),
                result.getString("evidence_reference"),
                result.getBigDecimal("minimum_supported_price"),
                result.getBigDecimal("maximum_supported_price"));
    }

    private static final String PROFILE_SELECT = """
            SELECT profile.id, profile.profile_version, profile.organization_id,
                   profile.platform_code, profile.marketplace_account_id, profile.store_id,
                   profile.fulfillment_mode_code, profile.currency_code,
                   profile.effective_from, profile.effective_to,
                   profile.verification_state, profile.verified_at,
                   profile.verification_expires_at, profile.evidence_reference,
                   profile.minimum_supported_price, profile.maximum_supported_price
              FROM core.economics_projection_profile profile
            """;

    private record ProfileRow(
            UUID id,
            int version,
            UUID organizationId,
            String platformCode,
            UUID marketplaceAccountId,
            UUID storeId,
            String fulfillmentModeCode,
            String currencyCode,
            Instant effectiveFrom,
            Instant effectiveTo,
            String verificationState,
            Instant verifiedAt,
            Instant verificationExpiresAt,
            String evidenceReference,
            java.math.BigDecimal minimumPrice,
            java.math.BigDecimal maximumPrice) {
    }
}
