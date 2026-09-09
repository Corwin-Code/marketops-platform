package com.mimococo.marketops.operationsworkflow;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one launch route for an approved listing action.
 *
 * <p>Approval never sends. Launching requires the launch grant with step-up,
 * consumes a one-use authenticated invocation proof, and acquires every axis of
 * the exposure allowance independently inside the database function. Nothing
 * external happens before the allowance is held.
 */
public interface ListingActionLaunch {

    LaunchResult launch(AuthenticatedActor actor, UUID actionId, Map<String, BigDecimal> requestedAxes);

    void releaseOccupation(AuthenticatedActor actor, UUID occupationId, String basis,
                           UUID evidenceId, String evidenceReference);

    /**
     * What a launch attempt produced.
     *
     * @param launched whether every axis was acquired and the action is launched
     * @param launchId the launch, when launched
     * @param occupationIds the occupations acquired, when launched
     * @param insufficientAxes the axes that could not absorb the launch, otherwise empty
     */
    record LaunchResult(boolean launched, UUID launchId, List<UUID> occupationIds,
                        List<String> insufficientAxes) {
        public LaunchResult {
            occupationIds = List.copyOf(occupationIds == null ? List.of() : occupationIds);
            insufficientAxes = List.copyOf(insufficientAxes == null ? List.of() : insufficientAxes);
        }
    }
}
