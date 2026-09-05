package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import com.mimococo.marketops.analyticsdecision.DecisionFreshness;
import com.mimococo.marketops.analyticsdecision.FeeFamily;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsProfile;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsResolution;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.GuardrailReason;
import com.mimococo.marketops.operationsworkflow.internal.domain.GuardrailOutcome;
import com.mimococo.marketops.operationsworkflow.internal.domain.PolicyLimits;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The append-only record of every guardrail verdict.
 *
 * <p>Passes are recorded as well as blocks. A journal that held only refusals
 * could not answer the question that matters after an unwanted price change:
 * what did the system believe at the moment it decided this was acceptable.
 */
@Repository
public class GuardrailRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    GuardrailRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Capture one database instant and the exact authority resolved at that instant.
     *
     * <p>The typed values below are parsed from the same JSON document that is
     * persisted with the verdict. There is therefore no second Java query for
     * mapping, policy, prior price, fulfilment mode, profile, components or
     * watermarks that could cross an effective boundary independently.
     */
    public AuthoritySnapshot captureAuthority(UUID recommendationId) {
        return jdbc.sql("""
                        SELECT evaluation_as_of, authority_snapshot::text AS authority_snapshot
                          FROM ops.capture_price_authority_snapshot(:id)
                        """)
                .param("id", recommendationId)
                .query((rows, rowNumber) -> parseAuthority(
                        rows.getTimestamp("evaluation_as_of").toInstant(),
                        rows.getString("authority_snapshot")))
                .single();
    }

    /**
     * The advertising authority and the instant it describes.
     *
     * <p>Returns the raw document rather than a parsed snapshot. The price
     * authority is parsed because the engine reads price, economics and
     * freshness out of it; the advertising verdict reads its inputs from the
     * case projection instead, so the document here is evidence rather than
     * input, and parsing it would invent a second reader of the same facts.
     */
    public AdvertisingAuthority captureAdBidAuthority(UUID recommendationId) {
        return jdbc.sql("""
                        SELECT evaluation_as_of, authority_snapshot::text AS authority_snapshot
                          FROM ops.capture_ad_bid_authority_snapshot(:id)
                        """)
                .param("id", recommendationId)
                .query((rows, rowNumber) -> new AdvertisingAuthority(
                        rows.getTimestamp("evaluation_as_of").toInstant(),
                        rows.getString("authority_snapshot")))
                .single();
    }

    /** What the advertising authority was, and when. */
    public record AdvertisingAuthority(Instant evaluationAsOf, String document) {
    }

    /** Record one verdict. */
    public void insert(UUID id, UUID organizationId, UUID recommendationId, UUID policyId,
                       Integer policyVersion, GuardrailPurpose purpose, boolean passed,
                       List<GuardrailReason> reasons, Map<String, String> detail,
                       String inputDigest, String authoritySnapshot, Instant evaluatedAt, String correlationId) {
        insert(id, organizationId, recommendationId, policyId, policyVersion, null, null,
                purpose, passed, reasons, detail, inputDigest, authoritySnapshot, evaluatedAt,
                correlationId);
    }

    /**
     * Record one verdict, naming whichever policy authority let it pass.
     *
     * <p>A price verdict names a commercial policy and an advertising one names
     * a decision policy bundle. The schema admits exactly one of the two on a
     * PASS, because a verdict two authorities could each claim is a verdict
     * neither owns.
     */
    public void insert(UUID id, UUID organizationId, UUID recommendationId, UUID policyId,
                       Integer policyVersion, UUID adDecisionBundleId, Integer adBundleVersion,
                       GuardrailPurpose purpose, boolean passed,
                       List<GuardrailReason> reasons, Map<String, String> detail,
                       String inputDigest, String authoritySnapshot, Instant evaluatedAt, String correlationId) {
        jdbc.sql("""
                        INSERT INTO ops.guardrail_evaluation (
                            id, organization_id, recommendation_id, policy_id, policy_version,
                            ad_decision_bundle_id, ad_bundle_version,
                            purpose, outcome, reason_codes, detail, input_digest, evaluated_at,
                            correlation_id, authority_snapshot)
                        VALUES (:id, :organizationId, :recommendationId, :policyId,
                            :policyVersion, :adDecisionBundleId, :adBundleVersion,
                            :purpose, :outcome, :reasonCodes,
                            CAST(:detail AS jsonb), :inputDigest, :evaluatedAt,
                            :correlationId, CAST(:authoritySnapshot AS jsonb))
                        """)
                .param("id", id)
                .param("adDecisionBundleId", adDecisionBundleId)
                .param("adBundleVersion", adBundleVersion)
                .param("organizationId", organizationId)
                .param("recommendationId", recommendationId)
                .param("policyId", policyId)
                .param("policyVersion", policyVersion)
                .param("purpose", purpose.name())
                .param("outcome", passed ? "PASS" : "BLOCK")
                .param("reasonCodes", reasons.stream().map(Enum::name).toArray(String[]::new))
                .param("detail", objectMapper.writeValueAsString(detail))
                .param("inputDigest", inputDigest)
                .param("authoritySnapshot", authoritySnapshot)
                .param("evaluatedAt", Timestamp.from(evaluatedAt))
                .param("correlationId", correlationId)
                .update();
    }

    /** Every verdict about one proposal, newest first. */
    public List<EvaluationRow> history(UUID recommendationId, int limit) {
        return jdbc.sql("""
                        SELECT id, policy_id, policy_version, purpose, outcome, reason_codes,
                               input_digest, evaluated_at
                          FROM ops.guardrail_evaluation
                         WHERE recommendation_id = :recommendationId
                         ORDER BY evaluated_at DESC
                         LIMIT :limit
                        """)
                .param("recommendationId", recommendationId)
                .param("limit", limit)
                .query(GuardrailRepository::map)
                .list();
    }

    /** Whether an execution verdict for this proposal currently passes. */
    public boolean executionPassRecorded(UUID recommendationId) {
        return !jdbc.sql("""
                        SELECT id FROM ops.guardrail_evaluation
                         WHERE recommendation_id = :recommendationId
                           AND purpose = 'EXECUTION' AND outcome = 'PASS'
                         LIMIT 1
                        """)
                .param("recommendationId", recommendationId)
                .query(UUID.class)
                .list()
                .isEmpty();
    }

    private static EvaluationRow map(ResultSet rows, int rowNumber) throws SQLException {
        Object reasonArray = rows.getArray("reason_codes").getArray();
        List<String> reasons = reasonArray instanceof String[] codes
                ? List.of(codes) : Arrays.stream((Object[]) reasonArray)
                        .map(String::valueOf).toList();
        Integer policyVersion = rows.getInt("policy_version");
        if (rows.wasNull()) {
            policyVersion = null;
        }
        return new EvaluationRow(
                rows.getObject("id", UUID.class),
                rows.getObject("policy_id", UUID.class),
                policyVersion,
                GuardrailPurpose.valueOf(rows.getString("purpose")),
                "PASS".equals(rows.getString("outcome")),
                reasons,
                rows.getString("input_digest"),
                rows.getTimestamp("evaluated_at").toInstant());
    }

    private AuthoritySnapshot parseAuthority(Instant evaluationAsOf, String document) {
        JsonNode root = com.mimococo.marketops.shared.JsonValues.read(objectMapper, document);
        JsonNode economicsNode = required(root, "economics");
        PolicyLimits policy = policy(root);
        PriceEconomicsResolution economics = economics(economicsNode, evaluationAsOf);
        DecisionFreshness freshness = freshness(economicsNode);
        JsonNode prior = root.get("prior");
        BigDecimal price = absent(prior) ? null : decimal(prior, "price");
        String currency = absent(prior) ? null : text(prior, "currency");
        boolean mappingResolved = !absent(root.get("mapping"))
                && text(root.get("mapping"), "id") != null;
        boolean mappingConflictOpen = root.path("mappingConflictOpen").asBoolean(false);
        List<UUID> selectedComponentIds = new ArrayList<>();
        for (JsonNode component : required(economicsNode, "selectedComponents")) {
            selectedComponentIds.add(uuid(component, "id"));
        }
        return new AuthoritySnapshot(evaluationAsOf, document, policy, price, currency,
                text(economicsNode, "fulfillmentModeCode"), economics, freshness,
                mappingResolved, mappingConflictOpen, text(root, "currentEntityDigest"),
                List.copyOf(selectedComponentIds),
                economicsNode.path("parameterContractValid").asBoolean(false));
    }

    private static PolicyLimits policy(JsonNode root) {
        JsonNode header = root.get("policy");
        if (absent(header)) {
            return null;
        }
        Map<String, BigDecimal> rates = new HashMap<>();
        Map<String, BigDecimal> amounts = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Long> durations = new HashMap<>();
        for (JsonNode limit : required(root, "policyLimits")) {
            String code = requiredText(limit, "limit_code");
            putDecimal(limit, "rate_value", code, rates);
            putDecimal(limit, "amount_value", code, amounts);
            JsonNode count = limit.get("count_value");
            if (!absent(count)) {
                counts.put(code, count.intValue());
            }
            JsonNode duration = limit.get("duration_seconds");
            if (!absent(duration)) {
                durations.put(code, duration.longValue());
            }
        }
        return new PolicyLimits(uuid(header, "id"), header.path("policy_version").intValue(),
                requiredText(header, "currency_code"),
                requiredText(header, "lifecycle_objective"),
                rates, amounts, counts, durations);
    }

    private static void putDecimal(JsonNode node, String field, String code,
                                   Map<String, BigDecimal> target) {
        JsonNode value = node.get(field);
        if (!absent(value)) {
            target.put(code, value.decimalValue());
        }
    }

    private static PriceEconomicsResolution economics(JsonNode economics,
                                                       Instant evaluationAsOf) {
        boolean validContract = economics.path("parameterContractValid").asBoolean(false);
        int selectedModeCount = economics.path("selectedModeActiveCount").intValue();
        int profileCount = economics.path("profileCount").intValue();
        String mode = text(economics, "fulfillmentModeCode");
        if (!validContract || selectedModeCount != 1 || mode == null
                || "UNKNOWN".equals(mode)) {
            return PriceEconomicsResolution.unavailable(
                    PriceEconomicsResolution.Status.MISSING,
                    validContract ? "fulfillment-mode-not-explicitly-active"
                            : "invalid-price-change-parameter-contract");
        }
        if (profileCount > 1) {
            return PriceEconomicsResolution.unavailable(
                    PriceEconomicsResolution.Status.AMBIGUOUS,
                    "overlapping-current-profiles=" + profileCount);
        }
        JsonNode profile = economics.get("profile");
        if (profileCount != 1 || absent(profile)) {
            return PriceEconomicsResolution.unavailable(
                    PriceEconomicsResolution.Status.MISSING,
                    "profile-absent-at-evaluation-as-of");
        }
        PriceEconomicsProfile.VerificationState verification =
                PriceEconomicsProfile.VerificationState.valueOf(
                        requiredText(profile, "verificationState"));
        if (!verification.usableForEngineeringDecision()) {
            return PriceEconomicsResolution.unavailable(
                    PriceEconomicsResolution.Status.UNVERIFIED,
                    "profile-verification-state=" + verification);
        }
        Instant verifiedAt = instant(profile, "verifiedAt");
        Instant effectiveFrom = instant(profile, "effectiveFrom");
        Instant effectiveTo = instant(profile, "effectiveTo");
        Instant verificationExpiresAt = instant(profile, "verificationExpiresAt");
        if (verifiedAt.isAfter(evaluationAsOf) || effectiveFrom.isAfter(evaluationAsOf)
                || effectiveTo != null && !effectiveTo.isAfter(evaluationAsOf)
                || verificationExpiresAt != null
                        && !verificationExpiresAt.isAfter(evaluationAsOf)) {
            return PriceEconomicsResolution.unavailable(
                    PriceEconomicsResolution.Status.EXPIRED,
                    "profile-not-current-at-evaluation-as-of");
        }

        Map<FeeFamily, PriceEconomicsProfile.Applicability> families =
                new EnumMap<>(FeeFamily.class);
        for (JsonNode family : required(economics, "familyContract")) {
            families.put(FeeFamily.valueOf(requiredText(family, "familyCode")),
                    PriceEconomicsProfile.Applicability.valueOf(
                            requiredText(family, "applicability")));
        }
        List<PriceEconomicsProfile.Component> components = new ArrayList<>();
        for (JsonNode component : required(economics, "allComponents")) {
            components.add(new PriceEconomicsProfile.Component(
                    uuid(component, "id"), requiredText(component, "componentCode"),
                    FeeFamily.valueOf(requiredText(component, "familyCode")),
                    PriceEconomicsProfile.ComponentKind.valueOf(
                            requiredText(component, "componentKind")),
                    decimal(component, "fixedAmount"), decimal(component, "rateValue"),
                    decimal(component, "lowerPriceInclusive"),
                    decimal(component, "upperPriceExclusive"),
                    requiredText(component, "evidenceReference")));
        }
        PriceEconomicsProfile typed = new PriceEconomicsProfile(
                uuid(profile, "id"), profile.path("version").intValue(),
                uuid(profile, "organizationId"), requiredText(profile, "platformCode"),
                uuid(profile, "accountId"), uuid(profile, "storeId"),
                requiredText(profile, "fulfillmentModeCode"),
                requiredText(profile, "currencyCode"), effectiveFrom, effectiveTo,
                verification, verifiedAt, verificationExpiresAt,
                requiredText(profile, "evidenceReference"),
                decimal(profile, "minimumSupportedPrice"),
                decimal(profile, "maximumSupportedPrice"), families, components);
        return new PriceEconomicsResolution(PriceEconomicsResolution.Status.AVAILABLE,
                typed, "single-snapshot-profile");
    }

    private static DecisionFreshness freshness(JsonNode economics) {
        Map<DecisionFreshness.Feed, DecisionFreshness.Watermark> watermarks =
                new EnumMap<>(DecisionFreshness.Feed.class);
        for (JsonNode item : required(economics, "watermarks")) {
            DecisionFreshness.Feed feed = DecisionFreshness.Feed.valueOf(
                    requiredText(item, "feedCode"));
            watermarks.put(feed, new DecisionFreshness.Watermark(uuid(item, "id"), feed,
                    instant(item, "sourceUpdatedAt"), instant(item, "ingestedAt"),
                    instant(item, "reconciledAt"),
                    requiredText(item, "evidenceReference")));
        }
        return new DecisionFreshness(watermarks,
                List.of(DecisionFreshness.Feed.values()));
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (absent(value)) {
            throw new IllegalStateException("authority snapshot missing " + field);
        }
        return value;
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private static String text(JsonNode node, String field) {
        if (absent(node)) {
            return null;
        }
        JsonNode value = node.get(field);
        return absent(value) ? null : value.asString();
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalStateException("authority snapshot missing " + field);
        }
        return value;
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(requiredText(node, field));
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return absent(value) ? null : value.decimalValue();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : OffsetDateTime.parse(value).toInstant();
    }

    /** Exact DB-captured time-sensitive input used by one Guardrail decision. */
    public record AuthoritySnapshot(
            Instant evaluationAsOf,
            String document,
            PolicyLimits policy,
            BigDecimal currentPrice,
            String currentPriceCurrency,
            String fulfillmentModeCode,
            PriceEconomicsResolution economics,
            DecisionFreshness freshness,
            boolean mappingResolved,
            boolean mappingConflictOpen,
            String currentEntityDigest,
            List<UUID> selectedComponentIds,
            boolean parameterContractValid) {

        public AuthoritySnapshot {
            Objects.requireNonNull(evaluationAsOf, "evaluationAsOf");
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(economics, "economics");
            Objects.requireNonNull(freshness, "freshness");
            selectedComponentIds = List.copyOf(selectedComponentIds);
        }

        /** Ensure the deterministic calculator selected the stored profile/tier identity. */
        public void requireOutcomeIdentity(GuardrailOutcome outcome) {
            if (!economics.available()) {
                return;
            }
            PriceEconomicsProfile profile = economics.profile();
            Set<UUID> expected = new HashSet<>(selectedComponentIds);
            Set<UUID> evaluated = new HashSet<>(outcome.projectedComponentIds());
            if (!Objects.equals(profile.profileId(), outcome.economicsProfileId())
                    || profile.profileVersion() != outcome.economicsProfileVersion()
                    || !Objects.equals(fulfillmentModeCode, outcome.fulfillmentModeCode())
                    || !expected.equals(evaluated)
                    || expected.size() != selectedComponentIds.size()
                    || evaluated.size() != outcome.projectedComponentIds().size()) {
                throw new IllegalStateException(
                        "evaluated economics identity does not match authority snapshot");
            }
        }
    }

    /**
     * One recorded verdict.
     *
     * @param id the evaluation
     * @param policyId policy it was decided under, or {@code null}
     * @param policyVersion version of that policy, or {@code null}
     * @param purpose why it ran
     * @param passed whether the action was permitted
     * @param reasonCodes every blocking condition
     * @param inputDigest digest of the inputs
     * @param evaluatedAt when it ran
     */
    public record EvaluationRow(UUID id, UUID policyId, Integer policyVersion,
                                GuardrailPurpose purpose, boolean passed,
                                List<String> reasonCodes, String inputDigest,
                                Instant evaluatedAt) {
    }
}
