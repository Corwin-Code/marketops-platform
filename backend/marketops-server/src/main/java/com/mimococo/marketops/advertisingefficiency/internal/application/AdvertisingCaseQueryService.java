package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.AdvertisingCaseQuery;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCaseView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.AdvertisingEvidenceView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingRankFactorView;
import com.mimococo.marketops.advertisingefficiency.AdvertisingVariantView;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingQueryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the queue a person reads.
 *
 * <p>Four reads rather than one join, for the same reason the availability
 * queue does it: the detail rows are keyed on the case's current calculation, so
 * an older generation stays in the tables for audit and never renders, and a
 * join would either duplicate the parent row per factor or need a lateral that
 * hides the scoping.
 *
 * <p>The accountable role is derived here from the cause rather than stored,
 * because the cause is the authority and a stored copy could drift from it. A
 * cause the current code does not recognise yields no role rather than a guess.
 */
@Service
class AdvertisingCaseQueryService implements AdvertisingCaseQuery {

    /** The largest page anybody may ask for. */
    private static final int MAX_PAGE = 200;

    private final AdvertisingQueryRepository queries;

    AdvertisingCaseQueryService(AdvertisingQueryRepository queries) {
        this.queries = queries;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdvertisingCaseView> queue(
            UUID organizationId,
            List<UUID> permittedStoreIds,
            List<UUID> permittedProductVariantIds,
            String laneFilter,
            int limit,
            int offset) {
        if (permittedStoreIds.isEmpty()) {
            return List.of();
        }
        var rows = queries.queue(organizationId, permittedStoreIds, normaliseLane(laneFilter),
                Math.clamp(limit, 1, MAX_PAGE), Math.max(0, offset));
        return assemble(organizationId, rows, permittedProductVariantIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdvertisingCaseView> caseById(
            UUID organizationId,
            UUID caseId,
            List<UUID> permittedStoreIds,
            List<UUID> permittedProductVariantIds) {
        if (permittedStoreIds.isEmpty()) {
            return Optional.empty();
        }
        return queries.caseById(organizationId, caseId, permittedStoreIds)
                .map(row -> assemble(organizationId, List.of(row), permittedProductVariantIds))
                .filter(views -> !views.isEmpty())
                .map(List::getFirst);
    }

    /**
     * An unrecognised lane filter returns everything rather than nothing.
     *
     * <p>A filter the caller misspelled that silently returned an empty queue
     * would read as "there is no advertising work", which is the most dangerous
     * empty state this surface has.
     */
    private static String normaliseLane(String laneFilter) {
        if (laneFilter == null || laneFilter.isBlank()) {
            return null;
        }
        return switch (laneFilter) {
            case "PROTECTION", "DATA_REPAIR", "OPTIMIZATION", "WATCH" -> laneFilter;
            default -> null;
        };
    }

    private List<AdvertisingCaseView> assemble(
            UUID organizationId,
            List<AdvertisingQueryRepository.CaseRow> rows,
            List<UUID> permittedProductVariantIds) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> calculationIds = rows.stream()
                .map(AdvertisingQueryRepository.CaseRow::calculationId).distinct().toList();
        Map<UUID, List<AdvertisingQueryRepository.FactorRow>> factors =
                group(queries.factors(organizationId, calculationIds),
                        AdvertisingQueryRepository.FactorRow::calculationId);
        Map<UUID, List<AdvertisingQueryRepository.VariantRow>> variants =
                group(queries.variants(organizationId, calculationIds, permittedProductVariantIds),
                        AdvertisingQueryRepository.VariantRow::calculationId);
        Map<UUID, List<AdvertisingQueryRepository.EvidenceRow>> evidence =
                group(queries.evidence(organizationId, calculationIds),
                        AdvertisingQueryRepository.EvidenceRow::calculationId);

        List<AdvertisingCaseView> views = new ArrayList<>(rows.size());
        for (AdvertisingQueryRepository.CaseRow row : rows) {
            views.add(new AdvertisingCaseView(
                    row.id(), row.storeId(), row.platformCode(), row.adNativeObjectId(),
                    row.nativeObjectKind(), row.nativeObjectKey(), row.nativeCampaignKey(),
                    row.nativeObjectName(), row.biddingMode(), row.controlGranularityState(),
                    row.lineageGeneration(), row.lane(), row.protectionTier(), row.causeCode(),
                    accountableRole(row.causeCode()),
                    row.evidenceState(), row.confidenceState(), row.blockerCodes(),
                    row.contributionProfitState(), row.contributionProfitAmount(),
                    row.profitPerAdRubState(), row.profitPerAdRubValue(), row.profitCurrencyCode(),
                    row.officialSpendState(), row.officialSpendAmount(),
                    row.eligibleTrafficState(), row.eligibleTrafficCount(),
                    row.adLinkedConversionState(), row.adLinkedConversionValue(),
                    row.adLinkedConversionStage(), row.maxCpcState(), row.maxCpcAmount(),
                    row.attributionGapState(), row.attributionGapRatio(),
                    row.currentBidState(), row.currentBidAmount(), row.recoverableProfitAmount(),
                    row.rankScore(), row.policyVersionDigest(),
                    row.affectedSetDigest(), row.affectedSetResolution(),
                    row.affectedVariantCount(), row.asOf(), row.calculatedAt(),
                    row.sustainedLane(), row.sustainedCycles(), row.sustainedSince(),
                    factorViews(factors.get(row.calculationId())),
                    variantViews(variants.get(row.calculationId())),
                    evidenceViews(evidence.get(row.calculationId()))));
        }
        return List.copyOf(views);
    }

    /**
     * The role a task for this cause routes to.
     *
     * <p>Derived rather than stored. A cause this build does not recognise
     * returns {@code null}, which the console renders as an unrouted case, and
     * an unrouted case is visibly somebody's problem to fix rather than
     * invisibly nobody's.
     */
    private static String accountableRole(String causeCode) {
        try {
            var role = AdvertisingCause.valueOf(causeCode).accountableRole();
            return role == null ? null : role.name();
        } catch (IllegalArgumentException unknownCause) {
            return null;
        }
    }

    private static List<AdvertisingRankFactorView> factorViews(
            List<AdvertisingQueryRepository.FactorRow> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new AdvertisingRankFactorView(row.factorCode(), row.value(),
                        row.weight(), row.contribution(), row.displayNote()))
                .toList();
    }

    private static List<AdvertisingVariantView> variantViews(
            List<AdvertisingQueryRepository.VariantRow> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new AdvertisingVariantView(row.productVariantId(),
                        row.platformListingVariantId(), row.skuCode(), row.displayName(),
                        row.basis(), row.confidenceState(), row.spendAmount(), row.clicks(),
                        row.contributionProfitAmount(), row.currencyCode(),
                        row.sellabilityState(), row.availabilityState(), row.criticalSalesUnit()))
                .toList();
    }

    private static List<AdvertisingEvidenceView> evidenceViews(
            List<AdvertisingQueryRepository.EvidenceRow> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new AdvertisingEvidenceView(row.evidenceRole(), row.referenceId(),
                        row.observedAt(), row.note()))
                .toList();
    }

    private static <T> Map<UUID, List<T>> group(
            List<T> rows, java.util.function.Function<T, UUID> key) {
        return rows.stream().collect(java.util.stream.Collectors.groupingBy(key));
    }
}
