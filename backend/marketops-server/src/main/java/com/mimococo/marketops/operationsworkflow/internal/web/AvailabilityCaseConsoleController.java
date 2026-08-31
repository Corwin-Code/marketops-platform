package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionView;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseIntake;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import com.mimococo.marketops.operationsworkflow.AvailabilityExceptionGovernance;
import com.mimococo.marketops.operationsworkflow.CaseActionKind;
import com.mimococo.marketops.operationsworkflow.CaseVerificationOutcome;
import com.mimococo.marketops.operationsworkflow.ExceptionReasonCode;
import com.mimococo.marketops.operationsworkflow.ExceptionScopeKind;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityCaseRepository;
import com.mimococo.marketops.shared.ConsoleApi;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The accountable work behind an availability risk, and its governed exceptions.
 *
 * <p>Three different grants guard three different things, because they are
 * three different decisions. Reading the queue needs only the availability view;
 * recording action or verification needs the grant to act; approving an
 * acceptance needs the approval grant, which is a step-up action — holding it is
 * not enough, the person must have authenticated recently enough for their
 * identity provider's recorded maximum authentication age.
 *
 * <p>Nothing here trusts a caller's claim about their own scope, and every route
 * refuses a case belonging to another organization before it does anything else.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/availability")
class AvailabilityCaseConsoleController {

    private final AvailabilityCaseIntake cases;
    private final AvailabilityExceptionGovernance exceptions;
    private final AvailabilityCaseRepository caseReads;
    private final BusinessAuthorization authorization;
    private final Clock clock;

    AvailabilityCaseConsoleController(AvailabilityCaseIntake cases,
                                      AvailabilityExceptionGovernance exceptions,
                                      AvailabilityCaseRepository caseReads,
                                      BusinessAuthorization authorization,
                                      Clock clock) {
        this.cases = cases;
        this.exceptions = exceptions;
        this.caseReads = caseReads;
        this.authorization = authorization;
        this.clock = clock;
    }

    /** The organization's open availability work, most urgent first. */
    @GetMapping(value = "/cases", produces = MediaType.APPLICATION_JSON_VALUE)
    List<AvailabilityCaseView> queue(AuthenticatedActor actor,
                                     @RequestParam(required = false) UUID assigneeUserId,
                                     @RequestParam(defaultValue = "true") boolean liveOnly,
                                     @RequestParam(defaultValue = "50") int limit) {
        authorization.require(actor, ActionScopeCode.AVAILABILITY_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return caseReads.queue(actor.organizationId(), liveOnly, assigneeUserId,
                Math.clamp(limit, 1, 200));
    }

    /** One case as it stands. */
    @GetMapping(value = "/cases/{caseId}", produces = MediaType.APPLICATION_JSON_VALUE)
    AvailabilityCaseView one(AuthenticatedActor actor, @PathVariable UUID caseId) {
        return readable(actor, caseId);
    }

    /**
     * Everything that ever happened to one case.
     *
     * <p>Including the reopens. A reviewer asking "is this the fourth time this
     * month" is asking the question the journal exists to answer, and a view
     * that showed only the current state could not answer it.
     */
    @GetMapping(value = "/cases/{caseId}/journal", produces = MediaType.APPLICATION_JSON_VALUE)
    List<AvailabilityCaseRepository.CaseJournalEntry> journal(AuthenticatedActor actor,
                                                              @PathVariable UUID caseId) {
        readable(actor, caseId);
        return caseReads.journal(caseId);
    }

    /**
     * Record accountable structured action.
     *
     * <p>The request has no field for a free-text acknowledgement, which is the
     * point: the action stage takes a named action kind and the reference to the
     * artefact behind it, and there is nothing else it will accept.
     */
    @PostMapping(value = "/cases/{caseId}/action", produces = MediaType.APPLICATION_JSON_VALUE)
    AvailabilityCaseView recordAction(AuthenticatedActor actor, @PathVariable UUID caseId,
                                      @Valid @RequestBody ActionRequest request) {
        AvailabilityCaseView governed = actionable(actor, caseId);
        return cases.recordAction(caseId, actor.userId(), governed.accountableRoleCode(),
                request.actionKind(), request.evidenceReference(), request.reason());
    }

    /** Record what a fresh cause-specific observation showed. */
    @PostMapping(value = "/cases/{caseId}/verification",
            produces = MediaType.APPLICATION_JSON_VALUE)
    AvailabilityCaseView observeVerification(AuthenticatedActor actor, @PathVariable UUID caseId,
                                             @Valid @RequestBody VerificationRequest request) {
        actionable(actor, caseId);
        return cases.observeVerification(caseId, request.verificationKind(), request.outcome(),
                request.observedAt() == null ? clock.instant() : request.observedAt(),
                request.reason());
    }

    /** Raise a case to a higher authority under policy. */
    @PostMapping(value = "/cases/{caseId}/escalation", produces = MediaType.APPLICATION_JSON_VALUE)
    AvailabilityCaseView escalate(AuthenticatedActor actor, @PathVariable UUID caseId,
                                  @Valid @RequestBody ReasonRequest request) {
        actionable(actor, caseId);
        return cases.escalate(caseId, request.reason(), clock.instant());
    }

    /** Every acceptance ever recorded against one case. */
    @GetMapping(value = "/cases/{caseId}/exceptions", produces = MediaType.APPLICATION_JSON_VALUE)
    List<AcceptedExceptionView> exceptionsOf(AuthenticatedActor actor,
                                             @PathVariable UUID caseId) {
        readable(actor, caseId);
        return exceptions.forCase(caseId);
    }

    /** Ask the business to accept a calculated risk for a bounded period. */
    @PostMapping(value = "/cases/{caseId}/exceptions", produces = MediaType.APPLICATION_JSON_VALUE)
    AcceptedExceptionView requestException(AuthenticatedActor actor, @PathVariable UUID caseId,
                                           @Valid @RequestBody ExceptionRequestBody request) {
        AvailabilityCaseView governed = readable(actor, caseId);
        authorization.require(actor, ActionScopeCode.AVAILABILITY_EXCEPTION_REQUEST,
                ResourceScope.organization(actor.organizationId()));
        return exceptions.request(new AvailabilityExceptionGovernance.ExceptionRequest(
                actor.organizationId(), caseId, governed.childId(), governed.causeCode(),
                governed.severity(), request.scopeKind(), request.scopeReference(),
                request.reasonCode(), request.rationale(), request.expectedConsequence(),
                request.consequenceAmount(), request.consequenceCurrency(),
                request.evidenceReference(), actor.userId(), governed.accountableRoleCode(),
                request.effectiveFrom(), request.expiresAt(), request.reviewAt(),
                "exception-request-" + caseId, clock.instant()));
    }

    /**
     * Decide one acceptance request.
     *
     * <p>The role the decision is made under is the strongest one the person
     * actually holds at request time, read from their live grants rather than
     * taken from the request body. A caller naming their own authority would be
     * deciding how much authority they need.
     */
    @PostMapping(value = "/exceptions/{exceptionId}/decision",
            produces = MediaType.APPLICATION_JSON_VALUE)
    AcceptedExceptionView decideException(AuthenticatedActor actor,
                                          @PathVariable UUID exceptionId,
                                          @Valid @RequestBody DecisionRequestBody request) {
        authorization.require(actor, ActionScopeCode.AVAILABILITY_EXCEPTION_APPROVE,
                ResourceScope.organization(actor.organizationId()));
        return exceptions.decide(new AvailabilityExceptionGovernance.ExceptionDecision(
                exceptionId, request.approved(), actor.userId(), decidingRole(actor),
                request.delegationReference(), actor.authenticatedAt(), true, request.reason(),
                "exception-decision-" + exceptionId, clock.instant()));
    }

    /**
     * The strongest acceptance authority this person holds.
     *
     * <p>Absent any of them the decision is refused here rather than recorded as
     * blocked: somebody with no acceptance authority at all has not made a
     * governance decision, they have called a route they may not call.
     */
    private static BusinessRoleCode decidingRole(AuthenticatedActor actor) {
        if (actor.holds(BusinessRoleCode.RISK_AUTHORITY)) {
            return BusinessRoleCode.RISK_AUTHORITY;
        }
        if (actor.holds(BusinessRoleCode.OWNER)) {
            return BusinessRoleCode.OWNER;
        }
        if (actor.holds(BusinessRoleCode.OPS_LEAD)) {
            return BusinessRoleCode.OPS_LEAD;
        }
        for (BusinessRoleCode role : List.of(BusinessRoleCode.PRODUCT_PROCUREMENT,
                BusinessRoleCode.TECH_DATA, BusinessRoleCode.FINANCE_ANALYST)) {
            if (actor.holds(role)) {
                return role;
            }
        }
        throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
    }

    private AvailabilityCaseView readable(AuthenticatedActor actor, UUID caseId) {
        authorization.require(actor, ActionScopeCode.AVAILABILITY_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return owned(actor, caseId);
    }

    private AvailabilityCaseView actionable(AuthenticatedActor actor, UUID caseId) {
        authorization.require(actor, ActionScopeCode.AVAILABILITY_TASK_ACT,
                ResourceScope.organization(actor.organizationId()));
        return owned(actor, caseId);
    }

    /**
     * The case, provided it belongs to this person's organization.
     *
     * <p>A case in another organization is reported as absent rather than as
     * forbidden. Distinguishing the two would let an outsider learn which case
     * identities exist by the shape of the refusal.
     */
    private AvailabilityCaseView owned(AuthenticatedActor actor, UUID caseId) {
        AvailabilityCaseView governed = caseReads.find(caseId)
                .filter(found -> found.organizationId().equals(actor.organizationId()))
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        return governed;
    }

    record ActionRequest(@NotNull CaseActionKind actionKind,
                         @NotBlank String evidenceReference,
                         @NotBlank String reason) {
    }

    record VerificationRequest(@NotBlank String verificationKind,
                               @NotNull CaseVerificationOutcome outcome,
                               Instant observedAt,
                               @NotBlank String reason) {
    }

    record ReasonRequest(@NotBlank String reason) {
    }

    record ExceptionRequestBody(@NotNull ExceptionScopeKind scopeKind,
                                @NotBlank String scopeReference,
                                @NotNull ExceptionReasonCode reasonCode,
                                @NotBlank String rationale,
                                @NotBlank String expectedConsequence,
                                BigDecimal consequenceAmount,
                                String consequenceCurrency,
                                @NotBlank String evidenceReference,
                                @NotNull Instant effectiveFrom,
                                @NotNull Instant expiresAt,
                                @NotNull Instant reviewAt) {
    }

    record DecisionRequestBody(boolean approved, String delegationReference,
                               @NotBlank String reason) {
    }
}
