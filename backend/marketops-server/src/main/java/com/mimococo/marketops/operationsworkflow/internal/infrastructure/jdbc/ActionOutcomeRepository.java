package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Executed actions followed for their effect, and their before/after readings (P10, V0032).
 *
 * <p>Append-only: an action is registered once, each stage's reading is written once, and both
 * inserts are idempotent, so two passes racing on one store leave one row each.
 */
@Repository
public class ActionOutcomeRepository {

    private final JdbcClient jdbc;

    ActionOutcomeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Register one action.
     *
     * @return whether it was registered now; {@code false} when it already was
     */
    public boolean register(UUID id, UUID organizationId, UUID storeId, String actionSource, UUID sourceId,
                            UUID recommendationId, UUID listingVariantId, String actionKind, Instant actedAt,
                            BigDecimal priorPrice, BigDecimal targetPrice, String currencyCode, Instant createdAt) {
        return jdbc.sql("""
                        INSERT INTO ops.action_outcome (
                            id, organization_id, store_id, action_source, source_id, recommendation_id,
                            platform_listing_variant_id, action_kind, acted_at, acted_on, prior_price,
                            target_price, currency_code, created_at)
                        VALUES (:id, :organizationId, :storeId, :actionSource, :sourceId, :recommendationId,
                            :listingVariantId, :actionKind, :actedAt, :actedOn, :priorPrice,
                            :targetPrice, :currencyCode, :createdAt)
                        ON CONFLICT (action_source, source_id) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("actionSource", actionSource)
                .param("sourceId", sourceId)
                .param("recommendationId", recommendationId)
                .param("listingVariantId", listingVariantId)
                .param("actionKind", actionKind)
                .param("actedAt", Timestamp.from(actedAt))
                .param("actedOn", LocalDate.ofInstant(actedAt, ZoneOffset.UTC))
                .param("priorPrice", priorPrice)
                .param("targetPrice", targetPrice)
                .param("currencyCode", currencyCode)
                .param("createdAt", Timestamp.from(createdAt))
                .update() == 1;
    }

    /** Whether an action was registered. */
    public boolean registered(String actionSource, UUID sourceId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ops.action_outcome WHERE action_source = :source "
                        + "AND source_id = :sourceId)")
                .param("source", actionSource)
                .param("sourceId", sourceId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Prices changed by hand in the seller office that are not followed yet, with the buyer price the
     * suggestion started from when it was stated in the same currency.
     */
    public List<DecisionSource> unregisteredPriceDecisions(UUID storeId) {
        return jdbc.sql("""
                        SELECT decision.id, decision.organization_id, decision.recommendation_id,
                               decision.platform_listing_variant_id, decision.applied_price,
                               decision.currency_code, decision.decided_at,
                               CASE WHEN proposal.expected_effect ->> 'currencyCode' = decision.currency_code
                                     AND proposal.expected_effect ->> 'currentPrice' ~ '^[0-9]{1,14}([.][0-9]{1,4})?$'
                                    THEN CAST(proposal.expected_effect ->> 'currentPrice' AS numeric)
                               END AS prior_price
                          FROM ops.price_decision AS decision
                          JOIN ops.recommendation AS proposal ON proposal.id = decision.recommendation_id
                         WHERE decision.store_id = :storeId
                           AND decision.decision = 'APPLIED_IN_SELLER_OFFICE'
                           AND NOT EXISTS (SELECT 1 FROM ops.action_outcome AS outcome
                                            WHERE outcome.action_source = 'PRICE_DECISION'
                                              AND outcome.source_id = decision.id)
                         ORDER BY decision.decided_at, decision.id
                        """)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new DecisionSource(
                        rows.getObject("id", UUID.class),
                        rows.getObject("organization_id", UUID.class),
                        rows.getObject("recommendation_id", UUID.class),
                        rows.getObject("platform_listing_variant_id", UUID.class),
                        "PRICE_CHANGE",
                        rows.getBigDecimal("prior_price"),
                        rows.getBigDecimal("applied_price"),
                        rows.getString("currency_code"),
                        rows.getTimestamp("decided_at").toInstant()))
                .list();
    }

    /** Promotions joined or left that are not followed yet. */
    public List<DecisionSource> unregisteredPromotionDecisions(UUID storeId) {
        return jdbc.sql("""
                        SELECT decision.id, decision.organization_id, decision.platform_listing_variant_id,
                               decision.decision, decision.action_price, decision.currency_code, decision.decided_at
                          FROM ops.promotion_decision AS decision
                         WHERE decision.store_id = :storeId
                           AND decision.decision IN ('JOINED', 'LEFT')
                           AND NOT EXISTS (SELECT 1 FROM ops.action_outcome AS outcome
                                            WHERE outcome.action_source = 'PROMOTION_DECISION'
                                              AND outcome.source_id = decision.id)
                         ORDER BY decision.decided_at, decision.id
                        """)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new DecisionSource(
                        rows.getObject("id", UUID.class),
                        rows.getObject("organization_id", UUID.class),
                        null,
                        rows.getObject("platform_listing_variant_id", UUID.class),
                        "JOINED".equals(rows.getString("decision")) ? "PROMOTION_JOINED" : "PROMOTION_LEFT",
                        null,
                        rows.getBigDecimal("action_price"),
                        rows.getString("currency_code"),
                        rows.getTimestamp("decided_at").toInstant()))
                .list();
    }

    /** The store's followed actions without a final reading yet, oldest first. */
    public List<Outcome> awaitingFinal(UUID storeId, int limit) {
        return jdbc.sql(SELECT_OUTCOME + """
                         WHERE outcome.store_id = :storeId
                           AND NOT EXISTS (SELECT 1 FROM ops.action_outcome_reading AS reading
                                            WHERE reading.action_outcome_id = outcome.id AND reading.stage = 'FINAL')
                         ORDER BY outcome.acted_at, outcome.id
                         LIMIT :limit
                        """)
                .param("storeId", storeId)
                .param("limit", limit)
                .query(ActionOutcomeRepository::outcome)
                .list();
    }

    /** The store's followed actions, newest first. */
    public List<Outcome> forStore(UUID organizationId, UUID storeId, int limit) {
        return jdbc.sql(SELECT_OUTCOME + """
                         WHERE outcome.organization_id = :organizationId AND outcome.store_id = :storeId
                         ORDER BY outcome.acted_at DESC, outcome.id
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("limit", limit)
                .query(ActionOutcomeRepository::outcome)
                .list();
    }

    /** The followed action registered for one command or decision, if any. */
    public java.util.Optional<Outcome> bySource(String actionSource, UUID sourceId) {
        return jdbc.sql(SELECT_OUTCOME + """
                         WHERE outcome.action_source = :source AND outcome.source_id = :sourceId
                        """)
                .param("source", actionSource)
                .param("sourceId", sourceId)
                .query(ActionOutcomeRepository::outcome)
                .optional();
    }

    /** The followed action that executed one proposal, if any. */
    public java.util.Optional<Outcome> forRecommendation(UUID recommendationId) {
        return jdbc.sql(SELECT_OUTCOME + """
                         WHERE outcome.recommendation_id = :recommendationId
                         ORDER BY outcome.acted_at DESC, outcome.id
                         LIMIT 1
                        """)
                .param("recommendationId", recommendationId)
                .query(ActionOutcomeRepository::outcome)
                .optional();
    }

    /** Whether another followed action on the listing took effect on a day inside an inclusive span. */
    public boolean otherActionBetween(UUID outcomeId, UUID listingVariantId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM ops.action_outcome
                                        WHERE platform_listing_variant_id = :listingVariantId
                                          AND id <> :outcomeId
                                          AND acted_on BETWEEN :from AND :to)
                        """)
                .param("listingVariantId", listingVariantId)
                .param("outcomeId", outcomeId)
                .param("from", from)
                .param("to", to)
                .query(Boolean.class)
                .single();
    }

    /**
     * Record one stage's reading.
     *
     * @return whether it was recorded now; {@code false} when that stage already was
     */
    public boolean recordReading(Reading reading) {
        return jdbc.sql("""
                        INSERT INTO ops.action_outcome_reading (
                            id, action_outcome_id, organization_id, stage, window_days, baseline_from, baseline_to,
                            observation_from, observation_to, baseline_days_covered, observation_days_covered,
                            baseline_ordered_units, observation_ordered_units, baseline_search_users,
                            observation_search_users, baseline_price_index, observation_price_index,
                            observation_buyer_price_min, observation_buyer_price_max, stockout_observed,
                            promotion_observed, price_held, other_action_observed, verdict, leading_signal,
                            reason_codes, rule_version, computed_at)
                        VALUES (:id, :outcomeId, :organizationId, :stage, :windowDays, :baselineFrom, :baselineTo,
                            :observationFrom, :observationTo, :baselineDays, :observationDays,
                            :baselineUnits, :observationUnits, :baselineSearch,
                            :observationSearch, :baselineIndex, :observationIndex,
                            :priceMin, :priceMax, :stockout,
                            :promotion, :priceHeld, :otherAction, :verdict, :leadingSignal,
                            CAST(:reasons AS text[]), :ruleVersion, :computedAt)
                        ON CONFLICT (action_outcome_id, stage) DO NOTHING
                        """)
                .param("id", reading.id())
                .param("outcomeId", reading.outcomeId())
                .param("organizationId", reading.organizationId())
                .param("stage", reading.stage())
                .param("windowDays", reading.windowDays())
                .param("baselineFrom", reading.baselineFrom())
                .param("baselineTo", reading.baselineTo())
                .param("observationFrom", reading.observationFrom())
                .param("observationTo", reading.observationTo())
                .param("baselineDays", reading.baselineDaysCovered())
                .param("observationDays", reading.observationDaysCovered())
                .param("baselineUnits", reading.baselineOrderedUnits())
                .param("observationUnits", reading.observationOrderedUnits())
                .param("baselineSearch", reading.baselineSearchUsers())
                .param("observationSearch", reading.observationSearchUsers())
                .param("baselineIndex", reading.baselinePriceIndex())
                .param("observationIndex", reading.observationPriceIndex())
                .param("priceMin", reading.observationBuyerPriceMin())
                .param("priceMax", reading.observationBuyerPriceMax())
                .param("stockout", reading.stockoutObserved())
                .param("promotion", reading.promotionObserved())
                .param("priceHeld", reading.priceHeld())
                .param("otherAction", reading.otherActionObserved())
                .param("verdict", reading.verdict())
                .param("leadingSignal", reading.leadingSignal())
                .param("reasons", "{" + String.join(",", reading.reasonCodes()) + "}")
                .param("ruleVersion", reading.ruleVersion())
                .param("computedAt", Timestamp.from(reading.computedAt()))
                .update() == 1;
    }

    /** The readings of some followed actions, by stage. */
    public List<Reading> readings(List<UUID> outcomeIds) {
        if (outcomeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, action_outcome_id, organization_id, stage, window_days, baseline_from, baseline_to,
                               observation_from, observation_to, baseline_days_covered, observation_days_covered,
                               baseline_ordered_units, observation_ordered_units, baseline_search_users,
                               observation_search_users, baseline_price_index, observation_price_index,
                               observation_buyer_price_min, observation_buyer_price_max, stockout_observed,
                               promotion_observed, price_held, other_action_observed, verdict, leading_signal,
                               reason_codes, rule_version, computed_at
                          FROM ops.action_outcome_reading
                         WHERE action_outcome_id IN (:ids)
                         ORDER BY action_outcome_id, window_days
                        """)
                .param("ids", outcomeIds)
                .query(ActionOutcomeRepository::reading)
                .list();
    }

    private static final String SELECT_OUTCOME = """
            SELECT outcome.id, outcome.organization_id, outcome.store_id, outcome.action_source, outcome.source_id,
                   outcome.recommendation_id, outcome.platform_listing_variant_id, outcome.action_kind,
                   outcome.acted_at, outcome.acted_on, outcome.prior_price, outcome.target_price,
                   outcome.currency_code, variant.native_sku_key, listing.title
              FROM ops.action_outcome AS outcome
              JOIN core.platform_listing_variant AS variant ON variant.id = outcome.platform_listing_variant_id
              JOIN core.platform_listing AS listing ON listing.id = variant.platform_listing_id
            """;

    private static Outcome outcome(ResultSet rows, int rowNumber) throws SQLException {
        return new Outcome(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getString("action_source"),
                rows.getObject("source_id", UUID.class),
                rows.getObject("recommendation_id", UUID.class),
                rows.getObject("platform_listing_variant_id", UUID.class),
                rows.getString("action_kind"),
                rows.getTimestamp("acted_at").toInstant(),
                rows.getObject("acted_on", LocalDate.class),
                rows.getBigDecimal("prior_price"),
                rows.getBigDecimal("target_price"),
                rows.getString("currency_code"),
                rows.getString("native_sku_key"),
                rows.getString("title"));
    }

    private static Reading reading(ResultSet rows, int rowNumber) throws SQLException {
        Array reasons = rows.getArray("reason_codes");
        Object held = rows.getObject("price_held");
        return new Reading(
                rows.getObject("id", UUID.class),
                rows.getObject("action_outcome_id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getString("stage"),
                rows.getInt("window_days"),
                rows.getObject("baseline_from", LocalDate.class),
                rows.getObject("baseline_to", LocalDate.class),
                rows.getObject("observation_from", LocalDate.class),
                rows.getObject("observation_to", LocalDate.class),
                rows.getInt("baseline_days_covered"),
                rows.getInt("observation_days_covered"),
                (Long) rows.getObject("baseline_ordered_units"),
                (Long) rows.getObject("observation_ordered_units"),
                (Long) rows.getObject("baseline_search_users"),
                (Long) rows.getObject("observation_search_users"),
                rows.getString("baseline_price_index"),
                rows.getString("observation_price_index"),
                rows.getBigDecimal("observation_buyer_price_min"),
                rows.getBigDecimal("observation_buyer_price_max"),
                rows.getBoolean("stockout_observed"),
                rows.getBoolean("promotion_observed"),
                held == null ? null : (Boolean) held,
                rows.getBoolean("other_action_observed"),
                rows.getString("verdict"),
                rows.getString("leading_signal"),
                reasons == null ? List.of() : Arrays.asList((String[]) reasons.getArray()),
                rows.getInt("rule_version"),
                rows.getTimestamp("computed_at").toInstant());
    }

    /**
     * A command or decision that becomes a followed action.
     *
     * @param id the decision
     * @param organizationId its organization
     * @param recommendationId the proposal it answered, if any
     * @param listingVariantId the listing it changed
     * @param actionKind PRICE_CHANGE, PROMOTION_JOINED or PROMOTION_LEFT
     * @param priorPrice the buyer price before, when known
     * @param targetPrice the buyer price set, when there was one
     * @param currencyCode the currency of both
     * @param actedAt when it was decided
     */
    public record DecisionSource(UUID id, UUID organizationId, UUID recommendationId, UUID listingVariantId,
                                 String actionKind, BigDecimal priorPrice, BigDecimal targetPrice,
                                 String currencyCode, Instant actedAt) {
    }

    /** One followed action, with the listing it is about. */
    public record Outcome(UUID id, UUID organizationId, UUID storeId, String actionSource, UUID sourceId,
                          UUID recommendationId, UUID listingVariantId, String actionKind, Instant actedAt,
                          LocalDate actedOn, BigDecimal priorPrice, BigDecimal targetPrice, String currencyCode,
                          String offerId, String title) {
    }

    /** One stage's before/after reading. */
    public record Reading(UUID id, UUID outcomeId, UUID organizationId, String stage, int windowDays,
                          LocalDate baselineFrom, LocalDate baselineTo, LocalDate observationFrom,
                          LocalDate observationTo, int baselineDaysCovered, int observationDaysCovered,
                          Long baselineOrderedUnits, Long observationOrderedUnits, Long baselineSearchUsers,
                          Long observationSearchUsers, String baselinePriceIndex, String observationPriceIndex,
                          BigDecimal observationBuyerPriceMin, BigDecimal observationBuyerPriceMax,
                          boolean stockoutObserved, boolean promotionObserved, Boolean priceHeld,
                          boolean otherActionObserved, String verdict, String leadingSignal,
                          List<String> reasonCodes, int ruleVersion, Instant computedAt) {

        public Reading {
            reasonCodes = List.copyOf(reasonCodes);
        }
    }
}
