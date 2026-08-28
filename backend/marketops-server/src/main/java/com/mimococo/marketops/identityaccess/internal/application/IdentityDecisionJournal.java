package com.mimococo.marketops.identityaccess.internal.application;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.identityaccess.internal.domain.IdentityDecisionOutcome;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.IdentityDecisionEventRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what the identity boundary decided.
 *
 * <p>A refusal is written in its own transaction. The request it refused has no
 * business transaction to join, and a denial that vanished because the rejected
 * operation rolled back would leave exactly the events an investigation needs
 * missing from the journal.
 */
@Service
public class IdentityDecisionJournal {

    private final IdentityDecisionEventRepository events;
    private final IdGenerator idGenerator;

    IdentityDecisionJournal(IdentityDecisionEventRepository events, IdGenerator idGenerator) {
        this.events = events;
        this.idGenerator = idGenerator;
    }

    /** Record that a token was accepted and resolved to a profile. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthentication(String issuer,
                                     UUID providerId,
                                     String subjectDigest,
                                     String sessionDigest,
                                     UUID userId,
                                     Instant authenticatedAt,
                                     boolean multiFactorPresent) {
        events.append(idGenerator.newId(), providerId, issuer, subjectDigest, sessionDigest,
                userId, IdentityDecisionOutcome.AUTHENTICATED.name(), null, null, null, null,
                authenticatedAt, multiFactorPresent, CorrelationId.current());
    }

    /** Record that a token was refused before any profile could be resolved. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthenticationDenial(String issuer,
                                           UUID providerId,
                                           String subjectDigest,
                                           String sessionDigest,
                                           String denialCode,
                                           Instant authenticatedAt,
                                           boolean multiFactorPresent) {
        events.append(idGenerator.newId(), providerId, issuer, subjectDigest, sessionDigest,
                null, IdentityDecisionOutcome.DENIED.name(), denialCode, null, null, null,
                authenticatedAt, multiFactorPresent, CorrelationId.current());
    }

    /** Record an authorization outcome for one action against one resource. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthorization(AuthenticatedActor actor,
                                    ActionScopeCode action,
                                    ResourceScope resource,
                                    IdentityDecisionOutcome outcome,
                                    String denialCode) {
        events.append(idGenerator.newId(), actor.identityProviderId(), actor.issuer(),
                actor.subjectDigest(), actor.sessionDigest(), actor.userId(), outcome.name(),
                denialCode, action.name(), resource.type().name(), resource.resourceId(),
                actor.authenticatedAt(), actor.multiFactorPresent(), CorrelationId.current());
    }
}
