package com.mimococo.marketops.operationsworkflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;

/**
 * What the listing conversion module answers when the workflow decides a listing action.
 *
 * <p>Implemented by the listing module and consumed by the approval, guardrail
 * and execution services. The workflow never reads the listing tables itself.
 */
public interface ListingActionDecisionAuthority {

    Optional<ListingDecisionScope> decisionScope(UUID recommendationId);

    /** Current exposure proof for the shared Guardrail consumer, after caller authorization. */
    Optional<ListingDecisionScope> recheckedDecisionScope(UUID recommendationId);

    /** Require permission to inspect every member whose evidence is needed for this decision. */
    void requireDecisionEvidence(AuthenticatedActor actor, UUID recommendationId);

    /** Deterministic refusals in the listing module's own vocabulary; empty means none. */
    List<String> unresolvedReasons(UUID recommendationId);

    /**
     * The database's description of the decision authority for one recommendation,
     * as JSON text, with or without a prepared action behind it.
     */
    String authorityDocument(UUID recommendationId);

    /**
     * Freeze the binding of an approval that was just recorded.
     *
     * @return the binding identifier
     */
    UUID bindApproval(UUID recommendationId, UUID approvalDecisionId, UUID guardrailEvaluationId,
                      Instant approvalScopeExpiresAt);

    /** A person decided the proposal may not proceed. */
    void recordRejection(UUID recommendationId, UUID approvalDecisionId);

    /** The command for a launched description action was created. */
    void recordCommandCreated(UUID recommendationId, UUID commandId);
}
