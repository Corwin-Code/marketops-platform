package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionState;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionView;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseState;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import com.mimococo.marketops.operationsworkflow.AvailabilityExceptionGovernance;
import com.mimococo.marketops.operationsworkflow.ExceptionAuthorityLevel;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityCaseRepository;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.BlockReason;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.BlockedAction;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.CaseAction;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.ExceptionAction;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.Offer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the person asking may do with availability cases and acceptances.
 *
 * <p>Each answer repeats a check the route or the service makes when the action
 * is taken — the grant on the case's own variant and store, the role the person
 * acts under, the state machine, the escalation ceiling, the one live
 * acceptance, the sized authority — so the console can say why a button is
 * disabled instead of letting the person find out by being refused. Nothing
 * here authorizes: the routes still authorize, and the services still refuse,
 * on their own.
 *
 * <p>Grants are read through the permitted-scope lists rather than one
 * evaluation per row, so listing fifty cases asks the authority a handful of
 * questions and journals no refusal the person never attempted.
 */
@Component
class AvailabilityCaseConsoleAccess {

    /**
     * The roles that may act on availability work, in the order they are read.
     *
     * <p>Fixed rather than derived so the journal is deterministic: one person
     * holding two of these records the same role every time, and a review
     * comparing two of their actions is comparing like with like.
     */
    private static final List<BusinessRoleCode> ACTING_ROLES = List.of(
            BusinessRoleCode.MARKETPLACE_OPERATOR, BusinessRoleCode.PRODUCT_PROCUREMENT,
            BusinessRoleCode.TECH_DATA, BusinessRoleCode.FINANCE_ANALYST,
            BusinessRoleCode.OPS_LEAD, BusinessRoleCode.RISK_AUTHORITY,
            BusinessRoleCode.OWNER, BusinessRoleCode.OPERATIONS);

    /** Acceptance authorities, strongest first. */
    private static final List<BusinessRoleCode> DECIDING_ROLES = List.of(
            BusinessRoleCode.RISK_AUTHORITY, BusinessRoleCode.OWNER, BusinessRoleCode.OPS_LEAD,
            BusinessRoleCode.PRODUCT_PROCUREMENT, BusinessRoleCode.TECH_DATA,
            BusinessRoleCode.FINANCE_ANALYST);

    private final BusinessAuthorization authorization;

    AvailabilityCaseConsoleAccess(BusinessAuthorization authorization) {
        this.authorization = authorization;
    }

    /** Where one grant reaches: the stores and the variants it covers. */
    record Reach(Set<UUID> stores, Set<UUID> variants) {

        /**
         * Whether the grant covers a case's subject as the owned-resource check
         * does: the variant always, and the store when the child sits on one.
         */
        boolean covers(AvailabilityCaseRepository.CaseSubject subject) {
            return variants.contains(subject.productVariantId())
                    && (subject.storeId() == null || stores.contains(subject.storeId()));
        }
    }

    /** The person's reach for the case actions, asked once per request. */
    record CaseGrants(Reach act, Reach request, boolean actingRole) {
    }

    /** The person's reach for deciding acceptances, asked once per request. */
    record DecisionGrants(Reach request, Reach approve, BusinessRoleCode decidingRole) {
    }

    CaseGrants caseGrants(AuthenticatedActor actor) {
        return new CaseGrants(reach(actor, ActionScopeCode.AVAILABILITY_TASK_ACT),
                reach(actor, ActionScopeCode.AVAILABILITY_EXCEPTION_REQUEST),
                ACTING_ROLES.stream().anyMatch(actor::holds));
    }

    DecisionGrants decisionGrants(AuthenticatedActor actor) {
        return new DecisionGrants(reach(actor, ActionScopeCode.AVAILABILITY_EXCEPTION_REQUEST),
                reach(actor, ActionScopeCode.AVAILABILITY_EXCEPTION_APPROVE),
                decidingRole(actor).orElse(null));
    }

    /**
     * What the person may do to one case now.
     *
     * <p>Recording action and escalating follow the route's own checks: the
     * grant to act, a role to act under, and the state machine; escalation
     * also stops at level three. Asking to accept needs the request grant, a
     * live case whose state an approval could move to an acceptance, and no
     * acceptance already occupying the case.
     */
    Offer caseActions(CaseGrants grants, AvailabilityCaseRepository.ConsoleCaseRow row) {
        AvailabilityCaseView view = row.view();
        AvailabilityCaseState state = view.state();
        List<String> allowed = new ArrayList<>();
        List<BlockedAction> blocked = new ArrayList<>();
        boolean mayAct = grants.actingRole() && grants.act().covers(row.subject());

        BlockReason record = !mayAct ? BlockReason.NOT_PERMITTED
                : stateReason(state, AvailabilityCaseState.ACTION_RECORDED);
        offer(CaseAction.RECORD_ACTION.name(), record, allowed, blocked);

        BlockReason escalate = !mayAct ? BlockReason.NOT_PERMITTED
                : stateReason(state, AvailabilityCaseState.ESCALATED);
        if (escalate == null && view.escalationLevel() >= 3) {
            escalate = BlockReason.ESCALATION_LIMIT_REACHED;
        }
        offer(CaseAction.ESCALATE.name(), escalate, allowed, blocked);

        BlockReason request = !grants.request().covers(row.subject())
                ? BlockReason.NOT_PERMITTED
                : stateReason(state, AvailabilityCaseState.ACCEPTED_RISK);
        if (request == null && row.openException() != null) {
            request = BlockReason.EXCEPTION_OPEN;
        }
        offer(CaseAction.REQUEST_EXCEPTION.name(), request, allowed, blocked);
        return new Offer(allowed, blocked);
    }

    /**
     * What the person may do to one acceptance request now.
     *
     * <p>A decision is offered only to somebody the decision would not block:
     * the approval grant on the request's scope, an acceptance authority at or
     * above the level the rules require now, and a materiality version in
     * force. Deciding without any of those would move the request to
     * {@code AUTHORITY_BLOCKED}, which is a real governance outcome the person
     * should never produce by clicking a button they were offered. A stale
     * sign-in does not withhold the offer; the page asks for a fresh one.
     *
     * <p>Withdrawing is the requester's own act, on a request nobody has
     * decided.
     */
    Offer exceptionActions(DecisionGrants grants, AuthenticatedActor actor,
                           AcceptedExceptionView exception,
                           AvailabilityCaseRepository.CaseSubject subject,
                           AvailabilityExceptionGovernance.ExceptionPreview terms) {
        List<String> allowed = new ArrayList<>();
        List<BlockedAction> blocked = new ArrayList<>();

        BlockReason decide;
        if (exception.state() != AcceptedExceptionState.REQUESTED) {
            decide = BlockReason.EXCEPTION_NOT_PENDING;
        } else if (grants.decidingRole() == null || !grants.approve().covers(subject)) {
            decide = BlockReason.NOT_PERMITTED;
        } else if (!terms.policyInForce()) {
            decide = BlockReason.MATERIALITY_POLICY_MISSING;
        } else if (!ExceptionAuthorityLevel.levelsFor(grants.decidingRole())
                .contains(terms.requiredAuthority())) {
            decide = BlockReason.AUTHORITY_INSUFFICIENT;
        } else {
            decide = null;
        }
        BlockReason approve = decide;
        if (approve == null && terms.separationRequired()
                && exception.requestedByUserId().equals(actor.userId())) {
            approve = BlockReason.SEPARATION_REQUIRED;
        }
        if (approve == null && terms.periodExceedsMaximum()) {
            approve = BlockReason.PERIOD_EXCEEDS_MAXIMUM;
        }
        offer(ExceptionAction.APPROVE.name(), approve, allowed, blocked);
        offer(ExceptionAction.REJECT.name(), decide, allowed, blocked);

        BlockReason withdraw;
        if (exception.state() != AcceptedExceptionState.REQUESTED
                && exception.state() != AcceptedExceptionState.AUTHORITY_BLOCKED) {
            withdraw = BlockReason.EXCEPTION_NOT_PENDING;
        } else if (!exception.requestedByUserId().equals(actor.userId())) {
            withdraw = BlockReason.NOT_REQUESTER;
        } else if (!grants.request().covers(subject)) {
            withdraw = BlockReason.NOT_PERMITTED;
        } else {
            withdraw = null;
        }
        offer(ExceptionAction.WITHDRAW.name(), withdraw, allowed, blocked);
        return new Offer(allowed, blocked);
    }

    /**
     * The role this person is actually acting as.
     *
     * <p>The case's accountable role only when they hold it. Recording the
     * cause's owner as the actor's role for somebody who is not that owner
     * would put a fabricated attribution in the journal, which is exactly what
     * the journal exists to prevent — a data owner who repaired a mapping must
     * not appear in the record as procurement.
     */
    static Optional<String> actingRole(AuthenticatedActor actor, String accountableRoleCode) {
        for (BusinessRoleCode role : BusinessRoleCode.values()) {
            if (role.name().equals(accountableRoleCode) && actor.holds(role)) {
                return Optional.of(role.name());
            }
        }
        return ACTING_ROLES.stream().filter(actor::holds).findFirst().map(Enum::name);
    }

    /**
     * The strongest acceptance authority this person holds.
     *
     * <p>Read from their live grants rather than taken from a request body. A
     * caller naming their own authority would be deciding how much authority
     * they need.
     */
    static Optional<BusinessRoleCode> decidingRole(AuthenticatedActor actor) {
        return DECIDING_ROLES.stream().filter(actor::holds).findFirst();
    }

    /**
     * Why a case in this state cannot move to the one an action needs, or
     * {@code null} when it can.
     */
    private static BlockReason stateReason(AvailabilityCaseState state,
                                           AvailabilityCaseState next) {
        if (state.allowedNext().contains(next)) {
            return null;
        }
        if (state.terminal()) {
            return BlockReason.CASE_CLOSED;
        }
        return switch (state) {
            case ACTION_RECORDED, VERIFYING -> BlockReason.AWAITING_VERIFICATION;
            case ACCEPTED_RISK -> BlockReason.RISK_ACCEPTED;
            case ESCALATED -> next == AvailabilityCaseState.ESCALATED
                    ? BlockReason.ALREADY_ESCALATED : BlockReason.STATE_NOT_ALLOWED;
            default -> BlockReason.STATE_NOT_ALLOWED;
        };
    }

    private static void offer(String action, BlockReason reason, List<String> allowed,
                              List<BlockedAction> blocked) {
        if (reason == null) {
            allowed.add(action);
        } else {
            blocked.add(new BlockedAction(action, reason));
        }
    }

    private Reach reach(AuthenticatedActor actor, ActionScopeCode action) {
        return new Reach(Set.copyOf(authorization.permittedStoreIds(actor, action)),
                Set.copyOf(authorization.permittedProductVariantIds(actor, action)));
    }
}
