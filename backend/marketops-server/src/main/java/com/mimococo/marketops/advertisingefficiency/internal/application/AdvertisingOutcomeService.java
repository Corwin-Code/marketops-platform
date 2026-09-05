package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Frozen pre-action evidence is immutable; only the observation is revised. */
@Service
class AdvertisingOutcomeService {
    private final AdvertisingOutcomeRepository outcomes;
    private final AdvertisingOutcomeEvidenceService evidence;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final com.mimococo.marketops.operationsworkflow.AdvertisingOutcomeReviewIntake reviews;
    AdvertisingOutcomeService(AdvertisingOutcomeRepository outcomes, AdvertisingOutcomeEvidenceService evidence,
            ObjectMapper json, IdGenerator ids, com.mimococo.marketops.operationsworkflow.AdvertisingOutcomeReviewIntake reviews) {
        this.outcomes = outcomes; this.evidence = evidence; this.json = json; this.ids = ids; this.reviews = reviews;
    }
    record Result(UUID observationId, String stage, int revisionNo,
                  OutcomeEvaluation evaluation, UUID reopenedContainmentId) { }

    @Transactional
    UUID evaluateManual(UUID organization,UUID packet,Instant at) {
        return outcomes.manualEarly(organization,packet,at).flatMap(due -> evaluate(due,at))
                .map(Result::observationId).orElse(null);
    }

    @Transactional
    Optional<Result> evaluate(AdvertisingOutcomeRepository.DueRow due, Instant now) {
        String stage = due.nextStage().replace("_REVISED", "");
        var frozen = due.manualPacketId() == null ? outcomes.frozenBaseline(due.commandId(), stage) : outcomes.frozenBaseline(null,due.manualPacketId(),stage);
        if (frozen.isEmpty()) { return Optional.empty(); }
        var baseline = json.readValue(frozen.get().snapshotJson(), AdvertisingOutcomeEvidenceService.Snapshot.class);
        var policy = json.readValue(frozen.get().policyJson(), AdvertisingOutcomePlanningService.Policy.class);
        Instant from = due.windowStartsAt();
        Instant to = due.windowEndsAt(stage);
        var observed = evidence.snapshot(due.organizationId(), due.adNativeObjectId(), frozen.get().affectedSetId(), stage,
                baseline.units().stream().map(AdvertisingOutcomeEvidenceService.UnitSales::unit).toList(),
                from, to, now, baseline.freshnessProfile());
        var assessment = AdvertisingOutcomeAssessment.evaluate(baseline, observed, policy, !now.isBefore(to));
        boolean revised = due.nextStage().endsWith("_REVISED");
        int revision = revised ? due.latestSettledRevision() + 1 : 1;
        UUID id = ids.newId();
        String input = json.writeValueAsString(Map.of("baseline", baseline, "observation", observed,
                "dualAxis", assessment.dualAxis(), "salesPreservation", assessment.sales()));
        outcomes.record(id, due, due.nextStage(), revision, revised ? due.latestSettledId() : null,
                revised ? "canonical evidence for the frozen observation window was restated" : null,
                from, to, baseline.profit().absoluteProfit(), observed.profit().absoluteProfit(), observed.traffic(),
                observed.coverage(), assessment.evaluation(), now,
                Digest.ofComponents(List.of(frozen.get().id().toString(), (due.commandId() == null ? due.manualPacketId() : due.commandId()).toString(), stage,
                        Integer.toString(revision), input)), CorrelationId.current());
        outcomes.recordAxes(id, frozen.get().id(), assessment.dualAxis().outcome().name(), assessment.sales().verdict().name(),
                baseline.profit().absoluteProfit().orElse(null), observed.profit().absoluteProfit().orElse(null),
                baseline.profit().profitPerAdRub().orElse(null), observed.profit().profitPerAdRub().orElse(null),
                baseline.companySales().orElse(null), observed.companySales().orElse(null), observed.profit().currencyCode(), input,
                AdvertisingOutcomeAssessment.businessOutcome(due.causeCode(),due.direction().startsWith("PROTECTION"),baseline,observed,policy,assessment,!now.isBefore(to)));
        for (var unit : assessment.critical()) {
            outcomes.recordCriticalGuard(frozen.get().id(), unit.unit().productVariantId(), unit.unit().listingVariantId(),
                    unit.state(), now, id, unit.baseline().orElse(null), unit.observed() == null ? null : unit.observed().orElse(null));
        }
        reviews.record(id);
        outcomes.tryReleaseReservation(id);
        UUID containment = assessment.evaluation().verdict() == OutcomeEvaluation.Verdict.REGRESSED
                ? outcomes.reopenAfterRegression(ids.newId(), id, "OPS_LEAD", CorrelationId.current()) : null;
        return Optional.of(new Result(id, due.nextStage(), revision, assessment.evaluation(), containment));
    }
}
