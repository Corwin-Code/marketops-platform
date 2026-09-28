package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.OwnedResource;
import com.mimococo.marketops.identityaccess.PeopleDirectory;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionView;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseIntake;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import com.mimococo.marketops.operationsworkflow.AvailabilityExceptionDelegationView;
import com.mimococo.marketops.operationsworkflow.AvailabilityExceptionGovernance;
import com.mimococo.marketops.operationsworkflow.CaseActionKind;
import com.mimococo.marketops.operationsworkflow.ExceptionReasonCode;
import com.mimococo.marketops.operationsworkflow.ExceptionScopeKind;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityCaseRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityCaseRepository.CaseSubject;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityCaseRepository.ConsoleCaseRow;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityCaseRepository.QueueView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityExceptionRepository;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.CaseItem;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.CasePage;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.CaseSummary;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.DecisionItem;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.DecisionTerms;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.ExceptionDetail;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.ExceptionItem;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.ExceptionOptions;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.ExceptionPreviewView;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.JournalEntry;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.Offer;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.ScopeOption;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.Subject;
import com.mimococo.marketops.operationsworkflow.internal.web.AvailabilityCaseConsoleViews.Viewer;
import com.mimococo.marketops.shared.ConsoleApi;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
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
 * What the console reads beside a case — what it is about, who did what, what the
 * person may do next — is advice for the page; every action is authorized and
 * checked again when it is taken.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/availability")
class AvailabilityCaseConsoleController {

    /** The largest page the console may ask for. */
    private static final int MAX_PAGE = 200;

    private final AvailabilityCaseIntake cases;
    private final AvailabilityExceptionGovernance exceptions;
    private final AvailabilityCaseRepository caseReads;
    private final AvailabilityExceptionRepository exceptionReads;
    private final AvailabilityCaseConsoleAccess access;
    private final BusinessAuthorization authorization;
    private final PeopleDirectory people;
    private final MetadataAuditRecorder audit;
    private final Clock clock;

    AvailabilityCaseConsoleController(AvailabilityCaseIntake cases,
                                      AvailabilityExceptionGovernance exceptions,
                                      AvailabilityCaseRepository caseReads,
                                      AvailabilityExceptionRepository exceptionReads,
                                      AvailabilityCaseConsoleAccess access,
                                      BusinessAuthorization authorization,
                                      PeopleDirectory people,
                                      MetadataAuditRecorder audit,
                                      Clock clock) {
        this.cases = cases;
        this.exceptions = exceptions;
        this.caseReads = caseReads;
        this.exceptionReads = exceptionReads;
        this.access = access;
        this.authorization = authorization;
        this.people = people;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * One page of the organization's availability work, most urgent first.
     *
     * <p>{@code view} narrows rather than reorders: live work, live work that
     * has been escalated, work with an acceptance request waiting for a
     * decision, or everything. Each case carries what it is about, the
     * acceptance occupying it, and what the person asking may do with it.
     */
    @GetMapping(value = "/cases", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    CasePage queue(AuthenticatedActor actor,
                   @RequestParam(defaultValue = "LIVE") QueueView view,
                   @RequestParam(required = false) UUID productVariantId,
                   @RequestParam(required = false) UUID assigneeUserId,
                   @RequestParam(defaultValue = "50") int limit,
                   @RequestParam(defaultValue = "0") int offset) {
        List<UUID> stores = authorization.permittedStoreIds(
                actor, ActionScopeCode.AVAILABILITY_VIEW);
        List<UUID> products = authorization.permittedProductVariantIds(
                actor, ActionScopeCode.AVAILABILITY_VIEW);
        if (stores.isEmpty() && products.isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        int page = Math.clamp(limit, 1, MAX_PAGE);
        int skip = Math.max(0, offset);
        UUID[] storeScope = stores.toArray(UUID[]::new);
        UUID[] productScope = products.toArray(UUID[]::new);
        List<ConsoleCaseRow> rows = caseReads.consoleQueue(actor.organizationId(), view,
                productVariantId, assigneeUserId, storeScope, productScope, page, skip);
        long total = caseReads.countConsoleQueue(actor.organizationId(), view, productVariantId,
                assigneeUserId, storeScope, productScope);
        AvailabilityCaseConsoleAccess.CaseGrants grants = access.caseGrants(actor);
        List<CaseItem> items = rows.stream()
                .map(row -> CaseItem.of(row, access.caseActions(grants, row)))
                .toList();
        auditRead(actor, "availability_case_queue", actor.organizationId(), "case_queue");
        return new CasePage(items, total, skip, page);
    }

    /** One case as it stands, with what it is about and what the viewer may do. */
    @GetMapping(value = "/cases/{caseId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    CaseItem one(AuthenticatedActor actor, @PathVariable UUID caseId) {
        readable(actor, caseId);
        ConsoleCaseRow row = consoleRow(caseId);
        Offer offer = access.caseActions(access.caseGrants(actor), row);
        auditRead(actor, "availability_case", caseId, "case");
        return CaseItem.of(row, offer);
    }

    /**
     * Everything that ever happened to one case, with the people named.
     *
     * <p>Including the reopens. A reviewer asking "is this the fourth time this
     * month" is asking the question the journal exists to answer, and a view
     * that showed only the current state could not answer it.
     */
    @GetMapping(value = "/cases/{caseId}/journal", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    List<JournalEntry> journal(AuthenticatedActor actor, @PathVariable UUID caseId) {
        readable(actor, caseId);
        List<AvailabilityCaseRepository.CaseJournalEntry> entries = caseReads.journal(caseId);
        Map<UUID, String> names = people.displayNames(actor.organizationId(),
                entries.stream().map(AvailabilityCaseRepository.CaseJournalEntry::actorUserId)
                        .filter(Objects::nonNull).toList());
        List<JournalEntry> result = entries.stream()
                .map(entry -> JournalEntry.of(entry,
                        entry.actorUserId() == null ? null : names.get(entry.actorUserId())))
                .toList();
        auditRead(actor, "availability_case_journal", caseId, "case_journal");
        return result;
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
        return cases.recordAction(caseId, actor.userId(), actingRole(actor, governed),
                request.actionKind(), request.evidenceReference(), request.reason());
    }

    /**
     * Raise a case to a higher authority, on this person's request.
     *
     * <p>The person and the role they act under are journalled. The escalation
     * itself is unchanged by who asked: one level up, at most three, with no
     * reassignment and no notification.
     */
    @PostMapping(value = "/cases/{caseId}/escalation", produces = MediaType.APPLICATION_JSON_VALUE)
    AvailabilityCaseView escalate(AuthenticatedActor actor, @PathVariable UUID caseId,
                                  @Valid @RequestBody ReasonRequest request) {
        AvailabilityCaseView governed = actionable(actor, caseId);
        return cases.escalate(caseId, actor.userId(), actingRole(actor, governed),
                request.reason(), clock.instant());
    }

    /** Every acceptance ever recorded against one case, with the requesters named. */
    @GetMapping(value = "/cases/{caseId}/exceptions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    List<ExceptionItem> exceptionsOf(AuthenticatedActor actor, @PathVariable UUID caseId) {
        readable(actor, caseId);
        List<AcceptedExceptionView> recorded = exceptions.forCase(caseId);
        Map<UUID, String> names = people.displayNames(actor.organizationId(),
                recorded.stream().map(AcceptedExceptionView::requestedByUserId).toList());
        List<ExceptionItem> result = recorded.stream()
                .map(view -> ExceptionItem.of(view, names.get(view.requestedByUserId())))
                .toList();
        auditRead(actor, "availability_case_exception", caseId, "case_exceptions");
        return result;
    }

    /**
     * Everything a request to accept this case's risk needs before anybody asks:
     * the scopes it may name, the closed reasons, and the published terms it
     * would be sized by.
     */
    @GetMapping(value = "/cases/{caseId}/exception-options",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    ExceptionOptions exceptionOptions(AuthenticatedActor actor, @PathVariable UUID caseId) {
        readable(actor, caseId);
        ConsoleCaseRow row = consoleRow(caseId);
        AvailabilityCaseView governed = row.view();
        AvailabilityExceptionGovernance.ExceptionTerms terms = exceptions.terms(
                actor.organizationId(), governed.childId(), governed.causeCode(),
                clock.instant());
        auditRead(actor, "availability_case_exception_options", caseId,
                "case_exception_options");
        return new ExceptionOptions(caseId, governed.causeCode(), governed.severity(),
                scopeOptions(governed, row.subject()),
                Arrays.stream(ExceptionReasonCode.values()).map(Enum::name).toList(),
                terms.policyInForce(), ExceptionOptions.days(terms.maxDuration()),
                ExceptionOptions.days(terms.materialDuration()), terms.materialProfitAtRisk(),
                terms.currencyCode(), terms.repeatOccurrenceCount(),
                ExceptionOptions.days(terms.repeatLookback()), terms.occurrenceCount(),
                row.subject().profitAtRiskAmount(), row.subject().profitAtRiskCurrency());
    }

    /**
     * How much authority a request would need if it were made now.
     *
     * <p>Nothing is recorded. A period longer than the published maximum is
     * refused with the same code the request would get, so the form can say so
     * before anybody submits.
     */
    @PostMapping(value = "/cases/{caseId}/exception-preview",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ExceptionPreviewView previewException(AuthenticatedActor actor, @PathVariable UUID caseId,
                                          @Valid @RequestBody PreviewBody request) {
        AvailabilityCaseView governed = owned(actor, caseId);
        authorization.requireOwned(actor, ActionScopeCode.AVAILABILITY_EXCEPTION_REQUEST,
                new OwnedResource(OwnedResource.Kind.AVAILABILITY_CASE, caseId));
        return ExceptionPreviewView.of(exceptions.preview(
                new AvailabilityExceptionGovernance.ExceptionSizing(actor.organizationId(),
                        caseId, governed.childId(), governed.causeCode(), governed.severity(),
                        request.consequenceAmount(), request.consequenceCurrency(),
                        request.effectiveFrom(), request.expiresAt(), clock.instant())));
    }

    /**
     * Ask the business to accept a calculated risk for a bounded period.
     *
     * <p>The scope must be one this case offers. A scope that does not match the
     * calculated child would be invalidated as soon as it was re-evaluated, so
     * it is refused here instead of being recorded.
     */
    @PostMapping(value = "/cases/{caseId}/exceptions", produces = MediaType.APPLICATION_JSON_VALUE)
    AcceptedExceptionView requestException(AuthenticatedActor actor, @PathVariable UUID caseId,
                                           @Valid @RequestBody ExceptionRequestBody request) {
        AvailabilityCaseView governed = readable(actor, caseId);
        authorization.requireOwned(actor, ActionScopeCode.AVAILABILITY_EXCEPTION_REQUEST,
                new OwnedResource(OwnedResource.Kind.AVAILABILITY_CASE, caseId));
        ConsoleCaseRow row = consoleRow(caseId);
        boolean offered = scopeOptions(governed, row.subject()).stream().anyMatch(option ->
                option.scopeKind().equals(request.scopeKind().name())
                        && option.reference().equals(request.scopeReference()));
        if (!offered) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
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
     * One acceptance request in full: every recorded field, every decision with
     * the people named, how a decision would be sized now, and what the person
     * asking may do about it.
     */
    @GetMapping(value = "/exceptions/{exceptionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    ExceptionDetail exception(AuthenticatedActor actor, @PathVariable UUID exceptionId) {
        AcceptedExceptionView existing = ownedException(actor, exceptionId);
        authorization.requireOwned(actor, ActionScopeCode.AVAILABILITY_VIEW,
                new OwnedResource(OwnedResource.Kind.AVAILABILITY_EXCEPTION, exceptionId));
        ConsoleCaseRow row = consoleRow(existing.caseId());
        Instant now = clock.instant();
        AvailabilityExceptionGovernance.ExceptionPreview terms =
                exceptions.previewDecision(exceptionId, now);
        List<AvailabilityExceptionRepository.RecordedDecision> decisions =
                exceptionReads.decisions(exceptionId);
        Map<UUID, String> names = people.displayNames(actor.organizationId(),
                Stream.concat(Stream.of(existing.requestedByUserId()),
                        decisions.stream()
                                .map(AvailabilityExceptionRepository.RecordedDecision::decidedByUserId))
                        .toList());
        AvailabilityCaseConsoleAccess.DecisionGrants grants = access.decisionGrants(actor);
        Offer offer = access.exceptionActions(grants, actor, existing, row.subject(), terms);
        auditRead(actor, "availability_accepted_exception", exceptionId, "exception");
        return new ExceptionDetail(
                ExceptionItem.of(existing, names.get(existing.requestedByUserId())),
                decisions.stream()
                        .map(decision -> DecisionItem.of(decision,
                                names.get(decision.decidedByUserId())))
                        .toList(),
                CaseSummary.of(row.view()), Subject.of(row.subject()), DecisionTerms.of(terms),
                new Viewer(actor.userId(),
                        grants.decidingRole() == null ? null : grants.decidingRole().name(),
                        actor.stepUpSatisfiedAt(now), actor.stepUpValidUntil()),
                offer.allowed(), offer.blocked());
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
        Instant at = clock.instant();
        if (request.delegationReference() == null
                || request.delegationReference().isBlank()) {
            authorization.requireOwned(actor, ActionScopeCode.AVAILABILITY_EXCEPTION_APPROVE,
                    new OwnedResource(OwnedResource.Kind.AVAILABILITY_EXCEPTION, exceptionId));
        } else {
            authorization.requireOwned(actor, ActionScopeCode.AVAILABILITY_VIEW,
                    new OwnedResource(OwnedResource.Kind.AVAILABILITY_EXCEPTION, exceptionId));
            if (!actor.stepUpSatisfiedAt(at)) {
                throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
            }
        }
        return exceptions.decide(new AvailabilityExceptionGovernance.ExceptionDecision(
                exceptionId, request.approved(), actor.userId(),
                request.delegationReference() == null || request.delegationReference().isBlank()
                        ? decidingRole(actor) : null,
                request.delegationReference(), actor.authenticatedAt(),
                actor.stepUpSatisfiedAt(at), request.reason(),
                "exception-decision-" + exceptionId, at));
    }

    /**
     * Withdraw a request nobody has decided yet.
     *
     * <p>Only the requester may, because the withdrawal is journalled as theirs:
     * letting anybody else withdraw it would put somebody's name on an act they
     * did not take. The request must still be waiting — requested or blocked —
     * and a reason is required, exactly as the governance already demands.
     */
    @PostMapping(value = "/exceptions/{exceptionId}/withdrawal",
            produces = MediaType.APPLICATION_JSON_VALUE)
    AcceptedExceptionView withdrawException(AuthenticatedActor actor,
                                            @PathVariable UUID exceptionId,
                                            @Valid @RequestBody ReasonRequest request) {
        AcceptedExceptionView existing = ownedException(actor, exceptionId);
        authorization.requireOwned(actor, ActionScopeCode.AVAILABILITY_EXCEPTION_REQUEST,
                new OwnedResource(OwnedResource.Kind.AVAILABILITY_EXCEPTION, exceptionId));
        if (!existing.requestedByUserId().equals(actor.userId())) {
            throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        }
        return exceptions.withdraw(exceptionId, request.reason(), clock.instant());
    }

    /** Grant one named person bounded accepted-risk decision authority. */
    @PostMapping(value = "/exception-delegations", produces = MediaType.APPLICATION_JSON_VALUE)
    AvailabilityExceptionDelegationView grantDelegation(
            AuthenticatedActor actor, @Valid @RequestBody DelegationGrantBody request) {
        authorization.require(actor, ActionScopeCode.AVAILABILITY_EXCEPTION_APPROVE,
                ResourceScope.organization(actor.organizationId()));
        Instant at = clock.instant();
        return exceptions.grantDelegation(
                new AvailabilityExceptionGovernance.ExceptionDelegationGrant(
                        actor.organizationId(), request.delegationReference(),
                        request.delegateUserId(), request.delegatedRole(), actor.userId(),
                        decidingRole(actor), request.effectiveFrom(), request.effectiveTo(),
                        request.evidenceReference(),
                        "exception-delegation-grant-" + request.delegationReference(), at));
    }

    /** Revoke one exact delegation without rewriting its grant history. */
    @PostMapping(value = "/exception-delegations/{reference}/revocation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    AvailabilityExceptionDelegationView revokeDelegation(
            AuthenticatedActor actor, @PathVariable String reference,
            @Valid @RequestBody DelegationRevocationBody request) {
        authorization.require(actor, ActionScopeCode.AVAILABILITY_EXCEPTION_APPROVE,
                ResourceScope.organization(actor.organizationId()));
        return exceptions.revokeDelegation(
                new AvailabilityExceptionGovernance.ExceptionDelegationRevocation(
                        actor.organizationId(), reference, actor.userId(), decidingRole(actor),
                        request.reason(), "exception-delegation-revoke-" + reference,
                        clock.instant()));
    }

    /**
     * The scopes a request on this case may name.
     *
     * <p>Each reference is exactly the one the re-evaluation of an acceptance
     * compares against, so an acceptance granted on an offered scope stays
     * matched for as long as the calculated child does. A company child has no
     * store or listing, so it offers only itself and its variant.
     */
    private static List<ScopeOption> scopeOptions(AvailabilityCaseView governed,
                                                  CaseSubject subject) {
        List<ScopeOption> options = new ArrayList<>();
        String variantLabel = join(subject.skuCode(), subject.displayName());
        String channelLabel = "COMPANY".equals(subject.childKind()) ? "COMPANY"
                : join(subject.platformCode(), subject.storeCode(),
                        subject.fulfillmentModeCode());
        options.add(option(ExceptionScopeKind.CHILD, governed.childId().toString(),
                join(variantLabel, channelLabel), subject));
        options.add(option(ExceptionScopeKind.VARIANT, subject.productVariantId().toString(),
                variantLabel, subject));
        if (subject.storeId() != null) {
            options.add(option(ExceptionScopeKind.STORE, subject.storeId().toString(),
                    join(subject.storeCode(), subject.storeName()), subject));
        }
        if (subject.platformListingVariantId() != null) {
            options.add(option(ExceptionScopeKind.CHANNEL,
                    subject.platformListingVariantId() + "|" + subject.fulfillmentModeCode(),
                    join(subject.platformCode(), subject.platformSkuKey(),
                            subject.fulfillmentModeCode()), subject));
        }
        return List.copyOf(options);
    }

    private static ScopeOption option(ExceptionScopeKind kind, String reference, String label,
                                      CaseSubject subject) {
        return new ScopeOption(kind.name(), reference, label, subject.platformCode(),
                subject.fulfillmentModeCode(), subject.storeId(), subject.storeCode(),
                subject.storeName(), subject.platformSkuKey(), subject.skuCode(),
                subject.displayName());
    }

    /** Present parts joined with a middle dot, for a language-neutral label. */
    private static String join(String... parts) {
        return String.join(" · ", Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank()).toList());
    }

    private static String actingRole(AuthenticatedActor actor, AvailabilityCaseView governed) {
        return AvailabilityCaseConsoleAccess.actingRole(actor, governed.accountableRoleCode())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED));
    }

    /**
     * The strongest acceptance authority this person holds.
     *
     * <p>Absent any of them the decision is refused here rather than recorded as
     * blocked: somebody with no acceptance authority at all has not made a
     * governance decision, they have called a route they may not call.
     */
    private static BusinessRoleCode decidingRole(AuthenticatedActor actor) {
        return AvailabilityCaseConsoleAccess.decidingRole(actor)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED));
    }

    private AvailabilityCaseView readable(AuthenticatedActor actor, UUID caseId) {
        AvailabilityCaseView governed = owned(actor, caseId);
        authorization.requireOwned(actor, ActionScopeCode.AVAILABILITY_VIEW,
                new OwnedResource(OwnedResource.Kind.AVAILABILITY_CASE, caseId));
        return governed;
    }

    private AvailabilityCaseView actionable(AuthenticatedActor actor, UUID caseId) {
        AvailabilityCaseView governed = owned(actor, caseId);
        authorization.requireOwned(actor, ActionScopeCode.AVAILABILITY_TASK_ACT,
                new OwnedResource(OwnedResource.Kind.AVAILABILITY_CASE, caseId));
        return governed;
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

    /** The acceptance, provided it belongs to this person's organization; absent otherwise. */
    private AcceptedExceptionView ownedException(AuthenticatedActor actor, UUID exceptionId) {
        return exceptions.find(exceptionId)
                .filter(found -> found.organizationId().equals(actor.organizationId()))
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** A case already authorized, with what the console reads beside it. */
    private ConsoleCaseRow consoleRow(UUID caseId) {
        return caseReads.consoleFind(caseId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void auditRead(AuthenticatedActor actor, String entityType,
                           UUID entityId, String reason) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                actor.userId().toString(), AuditAction.READ, entityType, entityId, null,
                Map.of(), reason, null));
    }

    record ActionRequest(@NotNull CaseActionKind actionKind,
                         @NotBlank String evidenceReference,
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

    /** What sizes a request: its exposure and its period. */
    record PreviewBody(BigDecimal consequenceAmount,
                       String consequenceCurrency,
                       @NotNull Instant effectiveFrom,
                       @NotNull Instant expiresAt) {
    }

    record DecisionRequestBody(boolean approved, String delegationReference,
                               @NotBlank String reason) {
    }

    record DelegationGrantBody(@NotBlank String delegationReference,
                               @NotNull UUID delegateUserId,
                               @NotNull BusinessRoleCode delegatedRole,
                               @NotNull Instant effectiveFrom,
                               @NotNull Instant effectiveTo,
                               @NotBlank String evidenceReference) {
    }

    record DelegationRevocationBody(@NotBlank String reason) {
    }
}
