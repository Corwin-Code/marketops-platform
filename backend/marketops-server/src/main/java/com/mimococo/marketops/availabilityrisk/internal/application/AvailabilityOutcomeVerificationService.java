package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.RiskCause;
import com.mimococo.marketops.availabilityrisk.internal.domain.OutcomeCondition;
import com.mimococo.marketops.availabilityrisk.internal.domain.WorkActivationPolicy;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseIntake;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Observes, from the calculation itself, whether the risk actually improved.
 *
 * <p>This is what makes the second stage real. Recording an action is somebody
 * saying they did something; verifying an outcome is the same evidence pipeline
 * that raised the case looking again and reporting what it now sees. If a
 * person had to click to close a case, the completion rate would measure
 * clicking.
 *
 * <p>Cases are found by the child they were raised against rather than by their
 * cause. By the time a cause is repaired the recalculated child no longer
 * carries it, so a cause-keyed lookup would find nothing at exactly the moment
 * the good news arrived.
 */
@Service
public class AvailabilityOutcomeVerificationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(AvailabilityOutcomeVerificationService.class);

    private final AvailabilityCaseIntake intake;

    public AvailabilityOutcomeVerificationService(AvailabilityCaseIntake intake) {
        this.intake = intake;
    }

    /**
     * Report what this calculation shows about every case awaiting an outcome.
     *
     * @param risk the calculation the card was written from
     * @param written the card and children exactly as persisted
     * @return every case this observation moved or left where it was
     */
    public List<AvailabilityCaseView> observe(VariantRisk risk,
                                              AvailabilityProjectionWriter.WrittenCard written) {
        WorkActivationPolicy policy = risk.policies().activation();
        if (policy == null) {
            // Without a published version there is no governed window, and a
            // window somebody invented is not one the organization agreed to.
            return List.of();
        }

        List<AvailabilityCaseView> observed = new ArrayList<>();
        for (AvailabilityProjectionWriter.WrittenChild child : written.children()) {
            for (AvailabilityCaseView waiting : intake.awaitingOutcome(child.childId())) {
                RiskCause raised = causeOf(waiting);
                if (raised == null) {
                    continue;
                }
                boolean holds = OutcomeCondition.holds(raised, child.scored().risk());
                observed.add(intake.observeCondition(waiting.id(), raised.verification().name(),
                        holds, risk.asOf(), policy.verificationWindow()));
            }
        }
        return List.copyOf(observed);
    }

    /**
     * The cause a case was raised for, when this module still recognises it.
     *
     * <p>An unrecognised cause is left alone rather than guessed at. A case
     * raised under a vocabulary this build no longer has is a person's problem
     * to look at, and closing it on a guess would be worse than leaving it
     * open.
     */
    private static RiskCause causeOf(AvailabilityCaseView waiting) {
        try {
            return RiskCause.valueOf(waiting.causeCode());
        } catch (IllegalArgumentException unknown) {
            LOG.warn("case {} carries cause {} which this build does not recognise;"
                    + " no automatic outcome was recorded", waiting.id(), waiting.causeCode());
            return null;
        }
    }
}
