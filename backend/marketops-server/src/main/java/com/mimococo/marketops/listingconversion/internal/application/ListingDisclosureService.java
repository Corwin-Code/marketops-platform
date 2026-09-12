package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.listingconversion.SimulationView;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.EvaluationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** One current financial projection for simulation responses, including mutation responses. */
@Service
public class ListingDisclosureService {
    private final ListingScopeAuthorization scopes;
    private final BusinessAuthorization authorization;
    private final EvaluationRepository evaluations;

    ListingDisclosureService(ListingScopeAuthorization scopes,
                             BusinessAuthorization authorization, EvaluationRepository evaluations) {
        this.scopes = scopes;
        this.authorization = authorization;
        this.evaluations = evaluations;
    }

    public SimulationView simulation(AuthenticatedActor actor, UUID listingId, SimulationView value) {
        var listing = scopes.require(actor, listingId, ActionScopeCode.LISTING_CONVERSION_VIEW);
        var members = evaluations.simulationEvidenceScope(value.id());
        boolean full = authorization.evaluate(actor, ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                ResourceScope.store(listing.storeId())).permitted()
                && !members.isEmpty() && members.stream().allMatch(member -> authorization.evaluate(actor,
                        ActionScopeCode.LISTING_DECISION_EVIDENCE_VIEW,
                        ResourceScope.productVariant(member)).permitted());
        if (full) {
            return value;
        }
        // Inverse quantities and pass/fail conditions also reveal financial inputs.
        return new SimulationView(value.id(), value.candidateId(), List.of(), null,
                "MASKED", null, value.computedAt(), value.modelVersion(), null, null, value.qualificationState());
    }
}
