package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.AuthorizationVerdict;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.PeopleDirectory;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.Actor;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.Catalogue;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.PackageDetail;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.PackageSummary;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.ScopeRights;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.StoreOption;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.Viewer;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ListingCalibrationRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ListingCalibrationRepository.PackageRow;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ListingCalibrationRepository.PackageScope;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ListingCalibrationRepository.Step;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ListingCalibrationRepository.StoreRow;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the console shows about calibration packages: the list with each
 * package's stage and next step, the category catalogue, and one package in
 * full.
 *
 * <p><b>Who sees what.</b> A package is visible to whoever holds the prepare,
 * validate or accept grant on its scope, as the existing single-package view
 * decides. A store package follows store grants (a grant on the organization or
 * account covers its stores); an organization or platform package needs a grant
 * at organization level, as {@code ops.lc_calibration_actor_scope} does. A grant
 * held behind a stale sign-in still counts: the page asks for a fresh sign-in
 * when the person acts, instead of hiding what they are responsible for.
 */
@Service
public class ListingCalibrationQueryService {

    private static final int ACCEPTOR_LIMIT = 20;

    private final ListingCalibrationRepository calibrations;
    private final BusinessAuthorization authorization;
    private final PeopleDirectory people;
    private final MetadataAuditRecorder audit;
    private final Clock clock;

    ListingCalibrationQueryService(ListingCalibrationRepository calibrations, BusinessAuthorization authorization,
                                   PeopleDirectory people, MetadataAuditRecorder audit, Clock clock) {
        this.calibrations = calibrations;
        this.authorization = authorization;
        this.people = people;
        this.audit = audit;
        this.clock = clock;
    }

    /** The caller's grants, asked once per request. */
    private record Access(ScopeRights organization, Set<UUID> prepareStores, Set<UUID> validateStores,
                          Set<UUID> acceptStores) {

        ScopeRights store(UUID storeId) {
            return new ScopeRights(prepareStores.contains(storeId), validateStores.contains(storeId),
                    acceptStores.contains(storeId));
        }

        ScopeRights scope(String scopeKind, UUID storeId) {
            return "STORE".equals(scopeKind) ? store(storeId) : organization;
        }

        boolean visible(String scopeKind, UUID storeId) {
            return any(scope(scopeKind, storeId));
        }

        boolean anything() {
            return any(organization) || !prepareStores.isEmpty() || !validateStores.isEmpty()
                    || !acceptStores.isEmpty();
        }

        static boolean any(ScopeRights rights) {
            return rights.prepare() || rights.validate() || rights.accept();
        }
    }

    // ------------------------------------------------------------------ list

    @Transactional
    public ListingCalibrationView overview(AuthenticatedActor actor) {
        UUID org = actor.organizationId();
        Access access = access(actor);
        if (!access.anything()) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        Instant now = calibrations.databaseNow();
        boolean stepUp = actor.stepUpSatisfiedAt(clock.instant());

        List<StoreOption> stores = new ArrayList<>();
        for (StoreRow store : calibrations.stores(org)) {
            ScopeRights rights = access.store(store.storeId());
            if (Access.any(rights)) {
                stores.add(new StoreOption(store.storeId(), store.code(), store.displayName(), store.platformCode(),
                        store.currencyCode(), rights));
            }
        }
        List<PackageSummary> packages = new ArrayList<>();
        for (PackageRow row : calibrations.packages(org, null)) {
            if (access.visible(row.scopeKind(), row.storeId())) {
                packages.add(summary(row, access, actor, stepUp, now));
            }
        }
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.READ, "lc-calibration", org, null, Map.of(), "calibrations", null));
        return new ListingCalibrationView(now, viewer(actor, stepUp), access.organization(), stores,
                calibrations.platforms(), packages);
    }

    // ------------------------------------------------------------------ catalogue

    /** The closed category list and the categories each purpose requires; reference data, not audited. */
    @Transactional
    public Catalogue catalogue(AuthenticatedActor actor) {
        if (!access(actor).anything()) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        return new Catalogue(calibrations.categories(), calibrations.requirements());
    }

    // ------------------------------------------------------------------ one package

    @Transactional
    public PackageDetail detail(AuthenticatedActor actor, UUID packageId) {
        PackageScope target = calibrations.scope(packageId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!actor.organizationId().equals(target.organizationId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        Access access = access(actor);
        if (!access.visible(target.scopeKind(), target.storeId())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        Instant now = calibrations.databaseNow();
        boolean stepUp = actor.stepUpSatisfiedAt(clock.instant());
        PackageRow row = calibrations.packages(actor.organizationId(), packageId).stream().findFirst()
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        PackageSummary summary = summary(row, access, actor, stepUp, now);

        List<String> acceptors = new ArrayList<>();
        if (CalibrationStepRules.DRAFTED.equals(summary.stage())
                || CalibrationStepRules.VALIDATED.equals(summary.stage())) {
            ResourceScope scope = "STORE".equals(row.scopeKind())
                    ? ResourceScope.store(row.storeId()) : ResourceScope.organization(actor.organizationId());
            for (PeopleDirectory.Person person : people.peopleWhoMay(actor.organizationId(),
                    ActionScopeCode.LISTING_CALIBRATION_ACCEPT, scope, ACCEPTOR_LIMIT)) {
                if (!person.userId().equals(row.drafted().userId())
                        && (row.validated() == null || !person.userId().equals(row.validated().userId()))) {
                    acceptors.add(person.displayName());
                }
            }
        }
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.READ, "lc-calibration", packageId, null, Map.of("event", new FieldChange(null, "READ")),
                null, null));
        return new PackageDetail(summary, row.evidenceReference(), row.rationale(), row.impact(), row.differences(),
                row.currentDigest(), row.requiredCategories(), calibrations.values(packageId),
                calibrations.events(packageId), acceptors);
    }

    // ------------------------------------------------------------------ helpers

    private Access access(AuthenticatedActor actor) {
        ResourceScope organization = ResourceScope.organization(actor.organizationId());
        ScopeRights rights = new ScopeRights(
                holds(actor, ActionScopeCode.LISTING_CALIBRATION_PREPARE, organization),
                holds(actor, ActionScopeCode.LISTING_CALIBRATION_VALIDATE, organization),
                holds(actor, ActionScopeCode.LISTING_CALIBRATION_ACCEPT, organization));
        return new Access(rights,
                Set.copyOf(authorization.permittedStoreIds(actor, ActionScopeCode.LISTING_CALIBRATION_PREPARE)),
                Set.copyOf(authorization.permittedStoreIds(actor, ActionScopeCode.LISTING_CALIBRATION_VALIDATE)),
                Set.copyOf(authorization.permittedStoreIds(actor, ActionScopeCode.LISTING_CALIBRATION_ACCEPT)));
    }

    /** Holding the grant, even when a fresh sign-in is still needed before using it. */
    private boolean holds(AuthenticatedActor actor, ActionScopeCode action, ResourceScope scope) {
        AuthorizationVerdict verdict = authorization.evaluate(actor, action, scope);
        return verdict == AuthorizationVerdict.PERMITTED || verdict == AuthorizationVerdict.STEP_UP_REQUIRED;
    }

    private static Viewer viewer(AuthenticatedActor actor, boolean stepUp) {
        return new Viewer(actor.userId(), actor.displayName(), stepUp, actor.stepUpValidUntil());
    }

    private static PackageSummary summary(PackageRow row, Access access, AuthenticatedActor actor, boolean stepUp,
                                          Instant now) {
        String stage = CalibrationStepRules.stage(row, now);
        ScopeRights rights = access.scope(row.scopeKind(), row.storeId());
        boolean draft = "DRAFT".equals(row.status());
        return new PackageSummary(row.id(), row.code(), row.version(), row.purposeCode(), row.scopeKind(),
                row.platformCode(), row.storeId(), row.storeName(), row.storePlatformCode(), row.status(), stage,
                row.effectiveFrom(), row.effectiveTo(), row.replacesPackageId(), row.replacedByPackageId(),
                row.latestVersionOfCode(), row.latestVersionInScope(),
                draft && row.latestVersionInScope() > row.version(),
                draft && row.effectiveTo() != null && !row.effectiveTo().isAfter(now),
                actor(row.drafted()), actor(row.validated()), actor(row.accepted()), actor(row.activated()),
                actor(row.retired()), row.missingCategories(), CalibrationStepRules.combinationFailures(row),
                row.drafted() != null && row.currentDigest() != null
                        && row.currentDigest().equals(row.drafted().digest()),
                row.activeOverlapIds(), rights,
                CalibrationStepRules.nextStep(row, stage, rights, actor.userId(), stepUp, now));
    }

    private static Actor actor(Step step) {
        return step == null ? null
                : new Actor(step.userId(), step.displayName(), step.at(), step.reference(), step.digest());
    }
}
