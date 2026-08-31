package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.ChildKind;
import com.mimococo.marketops.availabilityrisk.internal.domain.ChildRisk;
import com.mimococo.marketops.availabilityrisk.internal.domain.WorkActivationPolicy;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseIntake;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a written risk into accountable work.
 *
 * <p>Three rules do all the work here.
 *
 * <p>A cause is the case identity. The key names the exact subject and the
 * exact cause, so two different failures on one variant are two pieces of work
 * for two different people, and one failure recalculated a thousand times is
 * one case with a thousand pieces of evidence appended to it.
 *
 * <p>The lane decides whether a cause is work yet, and the published activation
 * policy decides what the lane means. Nothing here holds a threshold of its
 * own.
 *
 * <p>A case that is already live is refreshed whatever the gate says. The gate
 * governs raising work, not maintaining it: a HIGH that dipped to WATCH and
 * came back has broken its run, but the case somebody is already holding must
 * still show the severity the latest calculation found.
 */
@Service
public class AvailabilityCaseActivationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(AvailabilityCaseActivationService.class);

    private final AvailabilityCaseIntake intake;

    public AvailabilityCaseActivationService(AvailabilityCaseIntake intake) {
        this.intake = intake;
    }

    /**
     * Raise or refresh the cases one written card calls for.
     *
     * @param risk the calculation the card was written from
     * @param written the card and children exactly as persisted
     * @param correlationId the calculation run's own identity, never a case's
     * @return what was raised, refreshed and deliberately left alone
     */
    public ActivationResult activate(VariantRisk risk,
                                     AvailabilityProjectionWriter.WrittenCard written,
                                     String correlationId) {
        WorkActivationPolicy policy = risk.policies().activation();
        if (policy == null) {
            // No published rule means no deadline anybody agreed to. The risk
            // is still calculated and still visible; it simply raises nothing,
            // and the caller reports the gap rather than inventing a default.
            LOG.warn("no work-activation policy version is in force for organization {};"
                    + " {} calculated children raised no case", risk.organizationId(),
                    written.children().size());
            return new ActivationResult(List.of(), List.of(), true);
        }

        List<AvailabilityCaseView> raised = new ArrayList<>();
        List<AvailabilityCaseView> refreshed = new ArrayList<>();
        for (AvailabilityProjectionWriter.WrittenChild child : written.children()) {
            ChildRisk childRisk = child.scored().risk();
            String causeKey = causeKey(risk.productVariantId(), child);
            Optional<AvailabilityCaseView> live =
                    intake.liveCase(risk.organizationId(), causeKey);
            Optional<WorkActivationPolicy.Activation> decision =
                    policy.decide(childRisk, child.sustainedCycles(), risk.asOf());

            if (decision.isEmpty()) {
                live.ifPresent(existing -> refreshed.add(refresh(risk, written, child, causeKey,
                        policy, existing, correlationId)));
                continue;
            }
            WorkActivationPolicy.Activation activation = decision.get();
            AvailabilityCaseView view = intake.activate(new AvailabilityCaseIntake.CaseActivation(
                    risk.organizationId(), written.cardId(), child.childId(),
                    childRisk.kind().name(), childRisk.cause().name(), causeKey,
                    childRisk.lane().name(), childRisk.cause().accountableRole().name(),
                    policy.policyId(), activation.actionDueAt(), activation.outcomeDueAt(),
                    correlationId, risk.asOf()));
            (live.isPresent() ? refreshed : raised).add(view);
        }
        return new ActivationResult(List.copyOf(raised), List.copyOf(refreshed), false);
    }

    /**
     * Append the latest evidence to a case the gate would not have raised.
     *
     * <p>The deadlines come from the policy for the lane the case now carries,
     * so a case that got worse gets the shorter clock. It never gets a longer
     * one from this path: the activation the case was raised under already
     * set its deadline, and the refresh reports severity rather than reprieve.
     */
    private AvailabilityCaseView refresh(VariantRisk risk,
                                         AvailabilityProjectionWriter.WrittenCard written,
                                         AvailabilityProjectionWriter.WrittenChild child,
                                         String causeKey, WorkActivationPolicy policy,
                                         AvailabilityCaseView existing, String correlationId) {
        ChildRisk childRisk = child.scored().risk();
        return intake.activate(new AvailabilityCaseIntake.CaseActivation(
                risk.organizationId(), written.cardId(), child.childId(),
                childRisk.kind().name(), childRisk.cause().name(), causeKey,
                childRisk.lane().name(), childRisk.cause().accountableRole().name(),
                policy.policyId(), existing.actionDueAt(), existing.outcomeDueAt(),
                correlationId, risk.asOf()));
    }

    /**
     * The deduplication identity of one cause on one subject.
     *
     * <p>Built from the subject's own business key rather than from its row
     * identity: a projection rebuild that gave a child a new row must not be
     * able to raise a second case for a cause somebody is already working.
     *
     * <p>The company key names the internal variant, because a company cause is
     * about the variant rather than about any one channel, and a key that left
     * the variant out would deduplicate two unrelated shortages into one.
     */
    static String causeKey(UUID productVariantId,
                           AvailabilityProjectionWriter.WrittenChild child) {
        ChildRisk risk = child.scored().risk();
        if (risk.kind() == ChildKind.COMPANY) {
            return "COMPANY:" + productVariantId + ':' + risk.cause().name();
        }
        var observation = child.scored().subject().observation();
        return "CHANNEL:" + observation.platformListingVariantId()
                + ':' + observation.fulfillmentModeCode()
                + ':' + risk.cause().name();
    }

    /**
     * What one card's activation did.
     *
     * @param raised cases that did not exist before this calculation
     * @param refreshed live cases this calculation appended evidence to
     * @param activationPolicyMissing whether no published rule was in force
     */
    public record ActivationResult(List<AvailabilityCaseView> raised,
                                   List<AvailabilityCaseView> refreshed,
                                   boolean activationPolicyMissing) {
    }
}
