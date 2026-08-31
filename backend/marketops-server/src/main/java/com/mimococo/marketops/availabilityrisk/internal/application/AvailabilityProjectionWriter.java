package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.ChildKind;
import com.mimococo.marketops.availabilityrisk.internal.domain.ChildRisk;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandPolicyEngine;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandWindowEvidence;
import com.mimococo.marketops.availabilityrisk.internal.domain.ProofTerm;
import com.mimococo.marketops.availabilityrisk.internal.domain.RankFactor;
import com.mimococo.marketops.availabilityrisk.internal.domain.SupplyComponent;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityProjectionRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a calculated risk down.
 *
 * <p>The card and its children are refreshed in place; everything that explains
 * them is appended under this calculation's own identity. A reader asking why a
 * card looks the way it does reads the newest generation; a reviewer asking
 * what it looked like yesterday still has yesterday's.
 */
@Service
public class AvailabilityProjectionWriter {

    private final AvailabilityProjectionRepository projection;
    private final IdGenerator ids;
    private final Clock clock;

    public AvailabilityProjectionWriter(AvailabilityProjectionRepository projection,
                                        IdGenerator ids, Clock clock) {
        this.projection = projection;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Persist one variant's calculated risk.
     *
     * @param risk the calculated result
     * @param calculationKind {@code TARGETED} or {@code RECONCILIATION}
     * @param reconciliationRunId the sweep that produced it, or {@code null}
     * @return the card, its children and the run each child is now on
     */
    @Transactional
    public WrittenCard write(VariantRisk risk, String calculationKind, UUID reconciliationRunId) {
        var calculatedAt = clock.instant();
        UUID existingCardId = projection
                .findCardId(risk.organizationId(), risk.productVariantId())
                .orElse(null);

        // Every child's identity is settled first. A non-healthy card has to
        // name the child that produced its lane in the same statement that
        // creates it, so the identities cannot be discovered afterwards.
        //
        // The run each child was on is read in the same pass, because deciding
        // whether it continues or breaks needs the state before this write
        // overwrites it.
        Map<VariantRisk.ScoredChild, SustainedRun> runs = new LinkedHashMap<>();
        for (VariantRisk.ScoredChild scored : risk.children()) {
            var subject = scored.subject();
            var existing = projection.resolveChild(scored.risk().kind(), existingCardId,
                    subject == null ? null : subject.observation().platformListingVariantId(),
                    subject == null ? null : subject.observation().fulfillmentModeCode());
            runs.put(scored, continueRun(existing.orElse(null), scored, calculatedAt));
        }

        VariantRisk.ScoredChild trigger = risk.triggeringChild();
        UUID triggeringChildId =
                risk.parentLane() == com.mimococo.marketops.availabilityrisk.AvailabilityLane.HEALTHY
                        ? null
                        : runs.get(trigger).childId();

        UUID cardId = projection.upsertCard(new AvailabilityProjectionRepository.CardRow(
                existingCardId == null ? ids.newId() : existingCardId, risk.organizationId(),
                risk.productVariantId(), risk.parentLane().name(), triggeringChildId,
                risk.rankScore(), risk.policies().versionDigest(), risk.asOf(), calculatedAt,
                calculationKind, reconciliationRunId));

        List<WrittenChild> written = new ArrayList<>();
        for (VariantRisk.ScoredChild scored : risk.children()) {
            UUID calculationId = ids.newId();
            SustainedRun run = runs.get(scored);
            UUID childId = projection.upsertChild(childRow(risk, cardId, scored, calculationId,
                    calculatedAt, run));
            for (RankFactor factor : scored.ranking().factors()) {
                projection.insertFactor(ids.newId(), childId, risk.organizationId(), calculationId,
                        factor.code().name(), factor.value(), factor.weight(),
                        factor.contribution(), factor.displayNote());
            }
            for (DemandWindowEvidence window : scored.windows()) {
                projection.insertDemandWindow(demandWindowRow(risk, childId, calculationId, window,
                        scored.risk(), ids.newId()));
            }
            writeEvidence(risk, childId, calculationId, scored);
            written.add(new WrittenChild(childId, scored, run.cycles(), run.since()));
        }
        return new WrittenCard(cardId, risk.parentLane(), List.copyOf(written));
    }

    /**
     * Extend or restart the run of evaluations behind one child.
     *
     * <p>An unchanged lane extends the run and keeps its original start; any
     * other lane starts a new run of one. The count is what separates a HIGH
     * that has held from a HIGH that appeared in this cycle, and the two
     * deserve different treatment: activating on the first sighting fills the
     * queue with risks that resolve themselves before anyone opens them.
     */
    private SustainedRun continueRun(AvailabilityProjectionRepository.ExistingChild existing,
                                     VariantRisk.ScoredChild scored, java.time.Instant at) {
        String lane = scored.risk().lane().name();
        if (existing == null) {
            return new SustainedRun(ids.newId(), lane, 1, at);
        }
        if (lane.equals(existing.sustainedLane())) {
            return new SustainedRun(existing.id(), lane, existing.sustainedCycles() + 1,
                    existing.sustainedSince() == null ? at : existing.sustainedSince());
        }
        return new SustainedRun(existing.id(), lane, 1, at);
    }

    private AvailabilityProjectionRepository.ChildRow childRow(
            VariantRisk risk, UUID cardId, VariantRisk.ScoredChild scored, UUID calculationId,
            java.time.Instant calculatedAt, SustainedRun run) {
        ChildRisk child = scored.risk();
        var subject = scored.subject();
        return new AvailabilityProjectionRepository.ChildRow(
                run.childId(), cardId, risk.organizationId(), child.kind(),
                subject == null ? null : subject.observation().storeId(),
                subject == null ? null : subject.observation().platformListingVariantId(),
                subject == null ? null : subject.observation().fulfillmentModeCode(),
                child.lane().name(), child.evidenceState().name(), child.confidence().name(),
                child.cause().name(),
                subject == null ? child.supply().provenUnits()
                        : subject.observation().availableUnits(),
                child.demand().selectedRate(), child.daysOfCover(),
                child.leadTime().resolved() ? child.leadTime().coverageHorizonDays() : null,
                child.projectedStockoutAt(), child.profit().lane().name(),
                child.profit().perUnitAmount(), child.profit().currencyCode(),
                child.demand().reason(), proofJson(child),
                child.blockerCodes().toArray(String[]::new), calculationId, calculatedAt,
                run.lane(), run.cycles(), run.since());
    }

    /**
     * Render the conservative proof as the structured value the schema expects.
     *
     * <p>Built by hand rather than by a mapper because the constraint that
     * refuses a provisional child without terms reads exactly this shape, and a
     * serializer's idea of an empty list must not be able to change it.
     */
    private String proofJson(ChildRisk child) {
        if (!child.proof().established()) {
            return "{}";
        }
        StringBuilder json = new StringBuilder("{\"terms\":[");
        List<ProofTerm> terms = child.proof().terms();
        for (int index = 0; index < terms.size(); index++) {
            ProofTerm term = terms.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"code\":\"").append(escape(term.code()))
                    .append("\",\"label\":\"").append(escape(term.label())).append('"');
            if (term.value() != null) {
                json.append(",\"value\":").append(term.value().toPlainString());
            }
            json.append('}');
        }
        return json.append("]}").toString();
    }

    private AvailabilityProjectionRepository.DemandWindowRow demandWindowRow(
            VariantRisk risk, UUID childId, UUID calculationId, DemandWindowEvidence window,
            ChildRisk child, UUID id) {
        boolean sufficient = window.completedUnits() != null
                && window.completedUnits() >= risk.policies().demand().minimumSampleUnits();
        String eligibility = eligibilityOf(window, risk, sufficient);
        return new AvailabilityProjectionRepository.DemandWindowRow(id, childId,
                risk.organizationId(), calculationId, window.window().name(),
                window.periodStart(), window.periodEnd(), window.completedUnits(),
                window.dailyRate(), window.observedDays(), window.coverageRatio(), sufficient,
                window.censored(),
                window.censoringReason() == null ? null : window.censoringReason().name(),
                window.largestSingleDayShare(), eligibility);
    }

    private String eligibilityOf(DemandWindowEvidence window, VariantRisk risk,
                                 boolean sufficient) {
        if (!window.observed()) {
            return DemandPolicyEngine.WindowEligibility.DATA_BLOCKED.name();
        }
        if (window.coverageRatio().compareTo(risk.policies().demand().minimumCoverageRatio()) < 0) {
            return DemandPolicyEngine.WindowEligibility.CENSORED.name();
        }
        if (!sufficient) {
            return DemandPolicyEngine.WindowEligibility.LOW_SAMPLE.name();
        }
        BigDecimal share = window.largestSingleDayShare();
        if (share != null
                && share.compareTo(risk.policies().demand().outlierShareRatio()) > 0) {
            return DemandPolicyEngine.WindowEligibility.OUTLIER_REVIEW.name();
        }
        return DemandPolicyEngine.WindowEligibility.ELIGIBLE.name();
    }

    /**
     * Record what the child was derived from.
     *
     * <p>Excluded supply is recorded alongside counted supply. An operator
     * asking why a company total looks low needs to see the four hundred units
     * that were refused and the reason, not only the twelve that counted.
     */
    private void writeEvidence(VariantRisk risk, UUID childId, UUID calculationId,
                               VariantRisk.ScoredChild scored) {
        ChildRisk child = scored.risk();
        List<String> written = new ArrayList<>();
        for (SupplyComponent component : child.supply().components()) {
            if (component.provenanceId() == null) {
                continue;
            }
            String key = component.source().name() + component.provenanceId();
            if (written.contains(key)) {
                continue;
            }
            written.add(key);
            projection.insertEvidence(ids.newId(), childId, risk.organizationId(), calculationId,
                    evidenceRole(component), component.provenanceId(), null, null, null,
                    component.observedAt(),
                    component.counted()
                            ? "counted towards proven supply"
                            : "observed and not counted: " + component.reason().name());
        }
        if (child.profit().metricValueId() != null) {
            projection.insertEvidence(ids.newId(), childId, risk.organizationId(), calculationId,
                    "PROFIT", null, child.profit().metricValueId(), null, null, null,
                    child.profit().reason());
        }
        if (child.leadTime().resolved()) {
            projection.insertEvidence(ids.newId(), childId, risk.organizationId(), calculationId,
                    "LEAD_TIME_POLICY", null, null, child.leadTime().policyId(), null, null,
                    "lead time and safety resolved at scope " + child.leadTime().scopeKind());
        }
        projection.insertEvidence(ids.newId(), childId, risk.organizationId(), calculationId,
                "DEMAND_POLICY", null, null, risk.policies().demand().policyId(), null, null,
                "demand window selection: " + child.demand().reason());
    }

    private static String evidenceRole(SupplyComponent component) {
        return switch (component.source()) {
            case INTERNAL_WAREHOUSE -> "INTERNAL_STOCK";
            case PLATFORM_VISIBLE -> "PLATFORM_OWNED_STOCK";
            case ELIGIBLE_INBOUND -> "INBOUND";
        };
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Whether a child is a channel child, for callers deciding how to render it. */
    static boolean channel(ChildKind kind) {
        return kind == ChildKind.CHANNEL;
    }

    /**
     * What was written for one variant.
     *
     * <p>Returned rather than re-read because activation needs the child
     * identities and their runs, and re-reading them would let a concurrent
     * calculation substitute a different generation between the write and the
     * decision taken on it.
     *
     * @param cardId the card
     * @param lane the card's lane
     * @param children every child written, in calculation order
     */
    public record WrittenCard(UUID cardId,
                              com.mimococo.marketops.availabilityrisk.AvailabilityLane lane,
                              List<WrittenChild> children) {
    }

    /**
     * One written child and the run it is now on.
     *
     * @param childId the child
     * @param scored the calculated child it was written from
     * @param sustainedCycles consecutive calculations that produced this lane
     * @param sustainedSince when the run started
     */
    public record WrittenChild(UUID childId, VariantRisk.ScoredChild scored, int sustainedCycles,
                               java.time.Instant sustainedSince) {
    }

    /** A child identity and the lane run being carried into this write. */
    private record SustainedRun(UUID childId, String lane, int cycles, java.time.Instant since) {
    }
}
