package com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc;

import com.mimococo.marketops.availabilityrisk.ChildKind;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes the rebuildable availability projection.
 *
 * <p>Cards and children are upserted, because they are a projection of the
 * present. Their supporting detail — the rank factors, the evidence links and
 * the demand windows — is appended per calculation instead, so rebuilding a
 * card adds a generation rather than erasing the evidence of the one before it.
 * Nothing here is granted DELETE, which is why the append is the only shape
 * available.
 */
@Repository
public class AvailabilityProjectionRepository {

    private final JdbcClient jdbc;

    public AvailabilityProjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert or refresh the parent card and return its identity. */
    public UUID upsertCard(CardRow card) {
        jdbc.sql("""
                        INSERT INTO mart.availability_risk_card
                            (id, organization_id, product_variant_id, lane, triggering_child_id,
                             rank_score, policy_version_digest, as_of, calculated_at,
                             calculation_kind, reconciliation_run_id, created_at, updated_at)
                        VALUES (:id, :organizationId, :productVariantId, :lane, :triggeringChildId,
                                :rankScore, :digest, :asOf, :calculatedAt, :calculationKind,
                                :runId, :now, :now)
                        ON CONFLICT (organization_id, product_variant_id) DO UPDATE
                           SET lane = excluded.lane,
                               triggering_child_id = excluded.triggering_child_id,
                               rank_score = excluded.rank_score,
                               policy_version_digest = excluded.policy_version_digest,
                               as_of = excluded.as_of,
                               calculated_at = excluded.calculated_at,
                               calculation_kind = excluded.calculation_kind,
                               reconciliation_run_id = excluded.reconciliation_run_id,
                               updated_at = excluded.updated_at,
                               version = mart.availability_risk_card.version + 1
                        """)
                .param("id", card.id())
                .param("organizationId", card.organizationId())
                .param("productVariantId", card.productVariantId())
                .param("lane", card.lane())
                .param("triggeringChildId", card.triggeringChildId())
                .param("rankScore", card.rankScore())
                .param("digest", card.policyVersionDigest())
                .param("asOf", Timestamp.from(card.asOf()))
                .param("calculatedAt", Timestamp.from(card.calculatedAt()))
                .param("calculationKind", card.calculationKind())
                .param("runId", card.reconciliationRunId())
                .param("now", Timestamp.from(card.calculatedAt()))
                .update();
        return findCardId(card.organizationId(), card.productVariantId()).orElseThrow();
    }

    /** The card identity for a variant, when one exists. */
    public Optional<UUID> findCardId(UUID organizationId, UUID productVariantId) {
        return jdbc.sql("""
                        SELECT id FROM mart.availability_risk_card
                         WHERE organization_id = :organizationId
                           AND product_variant_id = :productVariantId
                        """)
                .param("organizationId", organizationId)
                .param("productVariantId", productVariantId)
                .query(UUID.class)
                .optional();
    }

    /** Point a card at the child that produced its lane. */
    public void setTriggeringChild(UUID cardId, UUID childId) {
        jdbc.sql("""
                        UPDATE mart.availability_risk_card
                           SET triggering_child_id = :childId,
                               version = version + 1
                         WHERE id = :cardId
                        """)
                .param("cardId", cardId)
                .param("childId", childId)
                .update();
    }

    /**
     * Insert or refresh one child and return its identity.
     *
     * <p>Identity is the child's own business key — the exact listing variant
     * and mode for a channel, the card for a company — so recalculating updates
     * one row instead of accumulating a history of contradictory children.
     */
    public UUID upsertChild(ChildRow child) {
        String conflict = child.childKind() == ChildKind.CHANNEL
                ? "(platform_listing_variant_id, fulfillment_mode_code)"
                    + " WHERE child_kind = 'CHANNEL'"
                : "(card_id) WHERE child_kind = 'COMPANY'";
        jdbc.sql("""
                        INSERT INTO mart.availability_risk_child
                            (id, card_id, organization_id, child_kind, store_id,
                             platform_listing_variant_id, fulfillment_mode_code, lane,
                             evidence_state, confidence_state, cause_code, available_units,
                             daily_demand_rate, days_of_cover, coverage_horizon_days,
                             projected_stockout_at, profit_lane, profit_at_risk_amount,
                             profit_at_risk_currency, demand_selection_reason,
                             conservative_proof, blocker_codes, calculation_id, calculated_at,
                             sustained_lane, sustained_cycles, sustained_since,
                             created_at, updated_at)
                        VALUES (:id, :cardId, :organizationId, :childKind, :storeId,
                                :listingVariantId, :fulfillmentModeCode, :lane, :evidenceState,
                                :confidenceState, :causeCode, :availableUnits, :dailyDemandRate,
                                :daysOfCover, :horizonDays, :stockoutAt, :profitLane,
                                :profitAmount, :profitCurrency, :demandReason,
                                CAST(:proof AS jsonb), :blockerCodes, :calculationId,
                                :calculatedAt, :sustainedLane, :sustainedCycles, :sustainedSince,
                                :calculatedAt, :calculatedAt)
                        ON CONFLICT %s DO UPDATE
                           SET lane = excluded.lane,
                               evidence_state = excluded.evidence_state,
                               confidence_state = excluded.confidence_state,
                               cause_code = excluded.cause_code,
                               available_units = excluded.available_units,
                               daily_demand_rate = excluded.daily_demand_rate,
                               days_of_cover = excluded.days_of_cover,
                               coverage_horizon_days = excluded.coverage_horizon_days,
                               projected_stockout_at = excluded.projected_stockout_at,
                               profit_lane = excluded.profit_lane,
                               profit_at_risk_amount = excluded.profit_at_risk_amount,
                               profit_at_risk_currency = excluded.profit_at_risk_currency,
                               demand_selection_reason = excluded.demand_selection_reason,
                               conservative_proof = excluded.conservative_proof,
                               blocker_codes = excluded.blocker_codes,
                               calculation_id = excluded.calculation_id,
                               calculated_at = excluded.calculated_at,
                               sustained_lane = excluded.sustained_lane,
                               sustained_cycles = excluded.sustained_cycles,
                               sustained_since = excluded.sustained_since,
                               updated_at = excluded.updated_at,
                               version = mart.availability_risk_child.version + 1
                        """.formatted(conflict))
                .param("id", child.id())
                .param("cardId", child.cardId())
                .param("organizationId", child.organizationId())
                .param("childKind", child.childKind().name())
                .param("storeId", child.storeId())
                .param("listingVariantId", child.platformListingVariantId())
                .param("fulfillmentModeCode", child.fulfillmentModeCode())
                .param("lane", child.lane())
                .param("evidenceState", child.evidenceState())
                .param("confidenceState", child.confidenceState())
                .param("causeCode", child.causeCode())
                .param("availableUnits", child.availableUnits())
                .param("dailyDemandRate", child.dailyDemandRate())
                .param("daysOfCover", child.daysOfCover())
                .param("horizonDays", child.coverageHorizonDays())
                .param("stockoutAt", child.projectedStockoutAt() == null
                        ? null : Timestamp.from(child.projectedStockoutAt()))
                .param("profitLane", child.profitLane())
                .param("profitAmount", child.profitAtRiskAmount())
                .param("profitCurrency", child.profitAtRiskCurrency())
                .param("demandReason", child.demandSelectionReason())
                .param("proof", child.conservativeProof())
                .param("blockerCodes", child.blockerCodes())
                .param("calculationId", child.calculationId())
                .param("calculatedAt", Timestamp.from(child.calculatedAt()))
                .param("sustainedLane", child.sustainedLane())
                .param("sustainedCycles", child.sustainedCycles())
                .param("sustainedSince", child.sustainedSince() == null
                        ? null : Timestamp.from(child.sustainedSince()))
                .update();
        return findChildId(child).orElseThrow();
    }

    /**
     * What a child already is, when it already exists.
     *
     * <p>Resolved before the card is written, because the card must name its
     * triggering child at the moment it is inserted: the constraint that a
     * non-healthy card discloses which child produced its lane admits no
     * window in which it does not.
     *
     * <p>The sustained run comes back with it, because deciding whether a HIGH
     * has held long enough to become work needs the run this calculation is
     * either continuing or breaking.
     */
    public Optional<ExistingChild> resolveChild(ChildKind kind, UUID cardId,
                                                UUID platformListingVariantId,
                                                String fulfillmentModeCode) {
        String predicate = kind == ChildKind.CHANNEL
                ? "child_kind = 'CHANNEL' AND platform_listing_variant_id = :listingVariantId"
                        + " AND fulfillment_mode_code = :fulfillmentModeCode"
                : "child_kind = 'COMPANY' AND card_id = :cardId";
        if (kind == ChildKind.COMPANY && cardId == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT id, lane, sustained_lane, sustained_cycles, sustained_since
                          FROM mart.availability_risk_child
                         WHERE %s
                        """.formatted(predicate))
                .param("listingVariantId", platformListingVariantId)
                .param("fulfillmentModeCode", fulfillmentModeCode)
                .param("cardId", cardId)
                .query((rows, rowNumber) -> new ExistingChild(
                        rows.getObject("id", UUID.class),
                        rows.getString("lane"),
                        rows.getString("sustained_lane"),
                        rows.getInt("sustained_cycles"),
                        rows.getTimestamp("sustained_since") == null
                                ? null : rows.getTimestamp("sustained_since").toInstant()))
                .optional();
    }

    private Optional<UUID> findChildId(ChildRow child) {
        if (child.childKind() == ChildKind.CHANNEL) {
            return jdbc.sql("""
                            SELECT id FROM mart.availability_risk_child
                             WHERE child_kind = 'CHANNEL'
                               AND platform_listing_variant_id = :listingVariantId
                               AND fulfillment_mode_code = :fulfillmentModeCode
                            """)
                    .param("listingVariantId", child.platformListingVariantId())
                    .param("fulfillmentModeCode", child.fulfillmentModeCode())
                    .query(UUID.class)
                    .optional();
        }
        return jdbc.sql("""
                        SELECT id FROM mart.availability_risk_child
                         WHERE child_kind = 'COMPANY' AND card_id = :cardId
                        """)
                .param("cardId", child.cardId())
                .query(UUID.class)
                .optional();
    }

    /** Append the visible factors behind one calculation's rank. */
    public void insertFactor(UUID id, UUID childId, UUID organizationId, UUID calculationId,
                             String factorCode, BigDecimal value, BigDecimal weight,
                             BigDecimal contribution, String displayNote) {
        jdbc.sql("""
                        INSERT INTO mart.availability_risk_factor
                            (id, child_id, organization_id, calculation_id, factor_code,
                             factor_value, factor_weight, contribution, display_note)
                        VALUES (:id, :childId, :organizationId, :calculationId, :factorCode,
                                :value, :weight, :contribution, :note)
                        """)
                .param("id", id).param("childId", childId).param("organizationId", organizationId)
                .param("calculationId", calculationId).param("factorCode", factorCode)
                .param("value", value).param("weight", weight).param("contribution", contribution)
                .param("note", displayNote)
                .update();
    }

    /** Append one demand window's coverage evidence. */
    public void insertDemandWindow(DemandWindowRow row) {
        jdbc.sql("""
                        INSERT INTO mart.demand_window_observation
                            (id, child_id, organization_id, calculation_id, window_code,
                             period_start, period_end, completed_units, daily_rate, observed_days,
                             coverage_ratio, sample_sufficient, censored, censoring_reason,
                             outlier_share, eligibility)
                        VALUES (:id, :childId, :organizationId, :calculationId, :windowCode,
                                :periodStart, :periodEnd, :completedUnits, :dailyRate,
                                :observedDays, :coverageRatio, :sampleSufficient, :censored,
                                :censoringReason, :outlierShare, :eligibility)
                        """)
                .param("id", row.id()).param("childId", row.childId())
                .param("organizationId", row.organizationId())
                .param("calculationId", row.calculationId())
                .param("windowCode", row.windowCode())
                .param("periodStart", Timestamp.from(row.periodStart()))
                .param("periodEnd", Timestamp.from(row.periodEnd()))
                .param("completedUnits", row.completedUnits())
                .param("dailyRate", row.dailyRate())
                .param("observedDays", row.observedDays())
                .param("coverageRatio", row.coverageRatio())
                .param("sampleSufficient", row.sampleSufficient())
                .param("censored", row.censored())
                .param("censoringReason", row.censoringReason())
                .param("outlierShare", row.outlierShare())
                .param("eligibility", row.eligibility())
                .update();
    }

    /** Append one evidence reference behind a child. */
    public void insertEvidence(UUID id, UUID childId, UUID organizationId, UUID calculationId,
                               String role, UUID provenanceId, UUID metricValueId,
                               UUID policyReferenceId, UUID attestationVersionId,
                               Instant observedAt, String note) {
        jdbc.sql("""
                        INSERT INTO mart.availability_risk_evidence
                            (id, child_id, organization_id, calculation_id, evidence_role,
                             provenance_id, metric_value_id, policy_reference_id,
                             attestation_version_id, observed_at, note)
                        VALUES (:id, :childId, :organizationId, :calculationId, :role,
                                :provenanceId, :metricValueId, :policyReferenceId,
                                :attestationVersionId, :observedAt, :note)
                        """)
                .param("id", id).param("childId", childId).param("organizationId", organizationId)
                .param("calculationId", calculationId).param("role", role)
                .param("provenanceId", provenanceId).param("metricValueId", metricValueId)
                .param("policyReferenceId", policyReferenceId)
                .param("attestationVersionId", attestationVersionId)
                .param("observedAt", observedAt == null ? null : Timestamp.from(observedAt))
                .param("note", note)
                .update();
    }

    /**
     * The last demand answer that was eligible for one child subject.
     *
     * <p>The answer carry-forward needs is by definition not among the current
     * windows: if one of those were eligible the policy would select it and
     * carry-forward would not arise. It therefore comes from what is stored,
     * keyed by the child's own identity so a channel carries its own record
     * rather than the variant's.
     */
    public Optional<CarriedForwardRow> lastEligibleDemand(UUID organizationId,
                                                          ChildKind childKind,
                                                          UUID productVariantId,
                                                          UUID platformListingVariantId,
                                                          String fulfillmentModeCode) {
        return jdbc.sql("""
                        SELECT observation.daily_rate, observation.window_code,
                               observation.period_end
                          FROM mart.demand_window_observation AS observation
                          JOIN mart.availability_risk_child AS child
                            ON child.id = observation.child_id
                           AND child.organization_id = observation.organization_id
                          JOIN mart.availability_risk_card AS card
                            ON card.id = child.card_id
                           AND card.organization_id = child.organization_id
                         WHERE observation.organization_id = :organizationId
                           AND observation.eligibility = 'ELIGIBLE'
                           AND observation.daily_rate IS NOT NULL
                           AND child.child_kind = :childKind
                           AND card.product_variant_id = :productVariantId
                           AND (child.child_kind = 'COMPANY'
                                OR (child.platform_listing_variant_id = :listingVariantId
                                    AND child.fulfillment_mode_code = :fulfillmentModeCode))
                         ORDER BY observation.period_end DESC, observation.window_code DESC
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("childKind", childKind.name())
                .param("productVariantId", productVariantId)
                .param("listingVariantId", platformListingVariantId)
                .param("fulfillmentModeCode", fulfillmentModeCode)
                .query((rows, rowNumber) -> new CarriedForwardRow(
                        rows.getBigDecimal("daily_rate"),
                        rows.getString("window_code"),
                        rows.getTimestamp("period_end").toInstant()))
                .optional();
    }

    /** Children of one card, most severe first. */
    public List<UUID> childIds(UUID cardId) {
        return jdbc.sql("""
                        SELECT id FROM mart.availability_risk_child
                         WHERE card_id = :cardId ORDER BY child_kind, id
                        """)
                .param("cardId", cardId)
                .query(UUID.class)
                .list();
    }

    /** The last eligible demand answer stored for a child subject. */
    public record CarriedForwardRow(BigDecimal dailyRate, String windowCode, Instant periodEnd) {
    }

    /** The parent card to write. */
    public record CardRow(UUID id, UUID organizationId, UUID productVariantId, String lane,
                          UUID triggeringChildId, BigDecimal rankScore, String policyVersionDigest,
                          Instant asOf, Instant calculatedAt, String calculationKind,
                          UUID reconciliationRunId) {
    }

    /** One child to write. */
    public record ChildRow(UUID id, UUID cardId, UUID organizationId, ChildKind childKind,
                           UUID storeId, UUID platformListingVariantId, String fulfillmentModeCode,
                           String lane, String evidenceState, String confidenceState,
                           String causeCode, Integer availableUnits, BigDecimal dailyDemandRate,
                           BigDecimal daysOfCover, Integer coverageHorizonDays,
                           Instant projectedStockoutAt, String profitLane,
                           BigDecimal profitAtRiskAmount, String profitAtRiskCurrency,
                           String demandSelectionReason, String conservativeProof,
                           String[] blockerCodes, UUID calculationId, Instant calculatedAt,
                           String sustainedLane, int sustainedCycles, Instant sustainedSince) {
    }

    /**
     * A child that already exists, and the run of evaluations behind it.
     *
     * @param id its identity
     * @param lane the lane it currently carries
     * @param sustainedLane the lane that has been repeating, or {@code null}
     * @param sustainedCycles how many consecutive calculations produced it
     * @param sustainedSince when the run started, or {@code null}
     */
    public record ExistingChild(UUID id, String lane, String sustainedLane, int sustainedCycles,
                                Instant sustainedSince) {
    }

    /** One demand window's coverage evidence to write. */
    public record DemandWindowRow(UUID id, UUID childId, UUID organizationId, UUID calculationId,
                                  String windowCode, Instant periodStart, Instant periodEnd,
                                  Integer completedUnits, BigDecimal dailyRate,
                                  BigDecimal observedDays, BigDecimal coverageRatio,
                                  boolean sampleSufficient, boolean censored,
                                  String censoringReason, BigDecimal outlierShare,
                                  String eligibility) {
    }
}
