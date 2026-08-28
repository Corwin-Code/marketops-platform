package com.mimococo.marketops.aicopilot;

import com.mimococo.marketops.analyticsdecision.MetricWindow;
import java.util.Optional;
import java.util.UUID;

/**
 * Published access to model-assisted explanation.
 *
 * <p>The contract deliberately offers explanation and proposal, and nothing
 * else. There is no method here that writes a metric, approves anything or
 * reaches a marketplace, because a model in this product analyses and suggests
 * while every authority stays deterministic.
 *
 * <p>Every call is recorded whether or not a provider answered. An explanation
 * that is unavailable is a fact about the system's state, and the console shows
 * it as one rather than as an empty panel.
 */
public interface AiCopilot {

    /**
     * Ask a model to explain one subject's current diagnosis.
     *
     * <p>Returns a degraded result rather than failing when no eligible provider
     * exists, the provider does not answer, or the answer does not validate. The
     * deterministic diagnosis is unaffected in every one of those cases.
     */
    AiDiagnosis explain(UUID requestedByUserId,
                        UUID organizationId,
                        UUID listingVariantId,
                        MetricWindow window,
                        String lifecycleObjective);

    /** One recorded invocation and its claims. */
    Optional<AiDiagnosis> invocation(UUID invocationId);
}
