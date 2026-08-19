package com.mimococo.marketops.identityaccess;

import java.util.UUID;

/**
 * Published evaluation contract for service accounts.
 *
 * <p>Background-job access resolves the service account here at every point of
 * use. A verdict other than {@link ServiceAccountEvaluation#ACTIVE} refuses the
 * use.
 */
public interface AccessMetadataDirectory {

    /** Evaluate a service account's current usability. */
    ServiceAccountEvaluation evaluate(UUID serviceAccountId);
}
