package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.marketplaceintegration.PriceCommandGateway;
import com.mimococo.marketops.marketplaceintegration.PriceCommandState;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carries a price command's outcome back to the proposal it executes (W1, 2026-10-01).
 *
 * <p>A proposal stayed COMMAND_CREATED whatever became of its command. Now a command that succeeded —
 * a readback observed the intended price — moves its proposal to EXECUTION_TRACKING, where the change's
 * effect is followed; one that ended without the price staying changed (failed, or restored) cancels
 * it. A command whose restore failed is left for a person: the price may or may not have moved back.
 */
@Service
public class CommandOutcomeReconciler {

    /** Audit actor of the transitions this makes. */
    static final String OPERATOR = "price-command-outcome";

    /** How many executing proposals one pass looks at per store. */
    private static final int BATCH = 50;

    private final RecommendationService recommendations;
    private final PriceCommandGateway commands;

    CommandOutcomeReconciler(RecommendationService recommendations, PriceCommandGateway commands) {
        this.recommendations = recommendations;
        this.commands = commands;
    }

    /**
     * Move the store's proposals whose price command has finished.
     *
     * @return how many proposals moved
     */
    @Transactional
    public int reconcile(UUID storeId) {
        int moved = 0;
        List<RecommendationView> executing = recommendations.queue(storeId,
                List.of(RecommendationState.COMMAND_CREATED), BATCH);
        for (RecommendationView proposal : executing) {
            if (proposal.actionKind() != ActionKind.PRICE_CHANGE) {
                continue;
            }
            Optional<PriceCommandState> finished = commands.forRecommendation(proposal.id())
                    .map(PriceCommandView::state)
                    .filter(state -> state == PriceCommandState.SUCCEEDED
                            || state == PriceCommandState.FAILED_FINAL
                            || state == PriceCommandState.COMPENSATED);
            if (finished.isEmpty()) {
                continue;
            }
            if (finished.get() == PriceCommandState.SUCCEEDED) {
                recommendations.transition(OPERATOR, proposal.id(), RecommendationState.EXECUTION_TRACKING,
                        null, proposal.version());
            } else {
                recommendations.transition(OPERATOR, proposal.id(), RecommendationState.CANCELLED,
                        finished.get() == PriceCommandState.COMPENSATED ? "COMMAND_COMPENSATED" : "COMMAND_FAILED",
                        proposal.version());
            }
            moved++;
        }
        return moved;
    }
}
