package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdRankFactor;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingProjectionRepository;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one calculation into the projection.
 *
 * <p>Ordering matters. Case identities and their sustained runs are resolved
 * first, because a case row cannot be written without knowing whether this lane
 * continues a run or starts one, and the sustained columns are coupled by a
 * check constraint that refuses a half-populated run.
 *
 * <p>Each case gets a fresh calculation id, and the detail rows hang off it.
 * That is what lets an older generation of factors and variants stay in the
 * tables for audit while never rendering: the read path joins on the case's
 * current calculation id and nothing else.
 */
@Service
class AdvertisingProjectionWriter {

    /** Which schedule produced a calculation. Stored so the two can be compared. */
    static final String TARGETED = "TARGETED";
    static final String RECONCILIATION = "RECONCILIATION";

    private final AdvertisingProjectionRepository projection;
    private final IdGenerator ids;

    AdvertisingProjectionWriter(AdvertisingProjectionRepository projection, IdGenerator ids) {
        this.projection = projection;
        this.ids = ids;
    }

    /** What was written, so the caller can report change without re-reading. */
    record WrittenCase(UUID caseId, UUID calculationId, String caseKey, String lane,
            boolean laneChanged) {
    }

    /** The whole write for one object. */
    record Written(List<WrittenCase> cases) {

        boolean anyLaneChanged() {
            return cases.stream().anyMatch(WrittenCase::laneChanged);
        }
    }

    @Transactional
    Written write(AdCaseCalculation calculation, String calculationKind, UUID reconciliationRunId) {
        Instant calculatedAt = calculation.asOf();
        List<WrittenCase> written = new ArrayList<>(calculation.cases().size());

        for (AdCaseCalculation.ScoredCase scored : calculation.cases()) {
            String caseKey = scored.identity().caseKey();
            Optional<AdvertisingProjectionRepository.ExistingCase> existing =
                    projection.findByKey(calculation.organizationId(), caseKey);
            UUID caseId = existing
                    .map(AdvertisingProjectionRepository.ExistingCase::id)
                    .orElseGet(ids::newId);
            UUID calculationId = ids.newId();
            String lane = scored.decision().lane().name();
            SustainedRun run = continueRun(existing.orElse(null), lane, calculatedAt);

            projection.upsertCase(new AdvertisingProjectionRepository.CaseRow(
                    caseId, calculation.organizationId(), calculation.storeId(),
                    calculation.platformCode(), calculation.adNativeObjectId(),
                    calculation.affectedSetId(), calculation.semanticProfileId(),
                    calculation.lineageGeneration(), caseKey, lane,
                    scored.decision().protectionTier() == null
                            ? null : scored.decision().protectionTier().name(),
                    scored.decision().cause().name(),
                    scored.decision().evidenceState().name(),
                    scored.decision().confidence().name(),
                    scored.decision().blockerCodes(),
                    state(scored.contributionProfit()), value(scored.contributionProfit()),
                    state(scored.profitPerAdRub()), value(scored.profitPerAdRub()),
                    currencyOrNull(scored),
                    state(scored.officialSpend()), value(scored.officialSpend()),
                    state(scored.eligibleTraffic()), longValue(scored.eligibleTraffic()),
                    conversionState(scored), conversionValue(scored), conversionStage(scored),
                    maxCpcState(scored), maxCpcValue(scored),
                    state(scored.attributionGap()), value(scored.attributionGap()),
                    state(scored.currentBid()), value(scored.currentBid()),
                    value(scored.recoverableProfit()),
                    scored.ranking().score(), calculation.policies().versionDigest(), null,
                    calculation.asOf(), calculatedAt, calculationKind, calculationId,
                    reconciliationRunId, run.lane(), run.cycles(), run.since()));

            for (AdRankFactor factor : scored.ranking().factors()) {
                projection.insertFactor(ids.newId(), caseId, calculation.organizationId(),
                        calculationId, factor.code().name(), factor.value(), factor.weight(),
                        factor.contribution(), factor.displayNote());
            }
            for (AdCaseCalculation.VariantDiagnostic variant : scored.variants()) {
                projection.insertVariant(ids.newId(), caseId, calculation.organizationId(),
                        calculationId, variant.productVariantId(),
                        variant.platformListingVariantId(), variant.basis(),
                        variant.confidenceState(), variant.spendAmount(), variant.clicks(),
                        variant.contributionProfitAmount(), variant.currencyCode(),
                        variant.sellabilityState(), variant.availabilityState(),
                        variant.criticalSalesUnit(), calculatedAt);
            }
            writeEvidence(calculation, scored, caseId, calculationId, calculatedAt);

            boolean laneChanged = existing
                    .map(row -> !lane.equals(row.lane()))
                    .orElse(true);
            written.add(new WrittenCase(caseId, calculationId, caseKey, lane, laneChanged));
        }
        // Anything this object still carries that this calculation did not
        // produce was true once and is not now.
        projection.supersedeCasesOtherThan(calculation.organizationId(),
                calculation.adNativeObjectId(),
                written.stream().map(WrittenCase::caseKey).toList(), calculatedAt);
        return new Written(List.copyOf(written));
    }

    /** Which policy versions and facts this conclusion rests on. */
    private void writeEvidence(AdCaseCalculation calculation, AdCaseCalculation.ScoredCase scored,
            UUID caseId, UUID calculationId, Instant at) {
        var policies = calculation.policies();
        record Reference(String role, UUID id) {
        }
        List<Reference> references = new ArrayList<>();
        if (policies.conversionDefinitionId() != null) {
            references.add(new Reference("CONVERSION_DEFINITION", policies.conversionDefinitionId()));
        }
        if (policies.allowableCpaDefinitionId() != null) {
            references.add(new Reference("ALLOWABLE_CPA_DEFINITION",
                    policies.allowableCpaDefinitionId()));
        }
        if (policies.qualificationPolicyId() != null) {
            references.add(new Reference("QUALIFICATION_POLICY", policies.qualificationPolicyId()));
        }
        if (policies.priorityPolicyId() != null) {
            references.add(new Reference("PRIORITY_POLICY", policies.priorityPolicyId()));
        }
        if (policies.semanticProfileId() != null) {
            references.add(new Reference("SEMANTIC_PROFILE", policies.semanticProfileId()));
        }
        for (Reference reference : references) {
            projection.insertEvidence(ids.newId(), caseId, calculation.organizationId(),
                    calculationId, reference.role(), null, null, reference.id(),
                    null, null, null, at, null);
        }
        // A conclusion with no traceable input cannot be persisted, so a case
        // resting only on absence still records the affected set it was about.
        if (references.isEmpty()) {
            projection.insertEvidence(ids.newId(), caseId, calculation.organizationId(),
                    calculationId, "AFFECTED_SET", null, null, calculation.affectedSetId(),
                    null, null, null, at, "no governing policy version resolved");
        }
    }

    /** A sustained run: the same lane repeating, counted from when it started. */
    private record SustainedRun(String lane, int cycles, Instant since) {
    }

    private static SustainedRun continueRun(
            AdvertisingProjectionRepository.ExistingCase existing, String lane, Instant at) {
        if (existing == null || existing.sustainedLane() == null) {
            return new SustainedRun(lane, 1, at);
        }
        if (lane.equals(existing.sustainedLane())) {
            return new SustainedRun(lane, existing.sustainedCycles() + 1,
                    existing.sustainedSince() == null ? at : existing.sustainedSince());
        }
        return new SustainedRun(lane, 1, at);
    }

    private static String state(AdMeasure measure) {
        return measure == null ? ValueState.NOT_AVAILABLE.name() : measure.valueState().name();
    }

    private static BigDecimal value(AdMeasure measure) {
        return measure == null ? null : measure.value();
    }

    private static Long longValue(AdMeasure measure) {
        return measure == null || !measure.present() ? null : measure.value().longValue();
    }

    private static String currencyOrNull(AdCaseCalculation.ScoredCase scored) {
        boolean anyMoney = scored.contributionProfit().present()
                || scored.officialSpend().present()
                || (scored.maxCpc() != null && scored.maxCpc().ceiling() != null)
                || scored.currentBid().present()
                || scored.recoverableProfit().present();
        return anyMoney ? scored.currencyCode() : null;
    }

    private static String conversionState(AdCaseCalculation.ScoredCase scored) {
        return scored.conversion() == null
                ? ValueState.NOT_AVAILABLE.name()
                : scored.conversion().rate().valueState().name();
    }

    private static BigDecimal conversionValue(AdCaseCalculation.ScoredCase scored) {
        return scored.conversion() == null ? null : scored.conversion().rate().value();
    }

    private static String conversionStage(AdCaseCalculation.ScoredCase scored) {
        return scored.conversion() != null && scored.conversion().rate().present()
                ? scored.conversion().stage().name() : null;
    }

    private static String maxCpcState(AdCaseCalculation.ScoredCase scored) {
        if (scored.maxCpc() == null || scored.maxCpc().ceiling() == null) {
            return ValueState.NOT_AVAILABLE.name();
        }
        return ValueState.AVAILABLE.name();
    }

    private static BigDecimal maxCpcValue(AdCaseCalculation.ScoredCase scored) {
        return scored.maxCpc() == null || scored.maxCpc().ceiling() == null
                ? null : scored.maxCpc().ceiling().amount();
    }
}
