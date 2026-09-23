package com.mimococo.marketops.operationsworkflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The calibration packages of one organization as the console shows them: every
 * package the caller may see, where each stands in its lifecycle, and the next
 * governed step with the reasons it cannot be taken yet.
 *
 * <p>Nothing here decides anything. The next step and its blockers repeat the
 * checks the database functions make at the moment of the write, so the console
 * can say why a button is disabled; the database still refuses on its own.
 *
 * <p>Decimals travel as plain strings so no bound is rounded by a JSON number on
 * its way to the screen. Rights mean "holds the grant": a person whose sign-in is
 * too old for the write still holds it, and the page asks for a fresh sign-in.
 *
 * @param asOf database time the lifecycle and blockers were evaluated at
 * @param viewer the person asking
 * @param organizationRights rights at organization level, which also govern platform scopes
 * @param stores stores the caller may see, with the rights held on each
 * @param platforms platforms a platform scope may name
 * @param packages every package visible to the caller
 */
public record ListingCalibrationView(Instant asOf, Viewer viewer, ScopeRights organizationRights,
                                     List<StoreOption> stores, List<String> platforms,
                                     List<PackageSummary> packages) {

    public ListingCalibrationView {
        stores = List.copyOf(stores);
        platforms = List.copyOf(platforms);
        packages = List.copyOf(packages);
    }

    /**
     * The person asking. The identifier lets the page mark "you" on a timeline;
     * it is never used by the page to decide what the person may do.
     */
    public record Viewer(UUID userId, String displayName, boolean stepUpSatisfied, Instant stepUpValidUntil) {
    }

    /** Whether the caller holds the prepare, validate and accept grants for one scope. */
    public record ScopeRights(boolean prepare, boolean validate, boolean accept) {
    }

    /** One store a store-scoped package may name. */
    public record StoreOption(UUID storeId, String code, String displayName, String platformCode,
                              String currencyCode, ScopeRights rights) {
    }

    /** Who took one lifecycle step, when, on what evidence and over which digest. */
    public record Actor(UUID userId, String displayName, Instant at, String reference, String digest) {
    }

    /**
     * The step the package waits for.
     *
     * @param step VALIDATE, ACCEPT or ACTIVATE
     * @param digest the exact digest this step must be submitted with
     * @param blockers why the caller cannot take the step now; empty when it can
     * @param stepUpRequired whether the caller must sign in again first (not a blocker)
     */
    public record NextStep(String step, String digest, List<String> blockers, boolean stepUpRequired) {
        public NextStep {
            blockers = List.copyOf(blockers);
        }
    }

    /**
     * One package in the list.
     *
     * @param stage DRAFTED, VALIDATED, ACCEPTED, ACTIVE, ENDED or RETIRED at {@code asOf}
     * @param latestVersionOfCode the highest version of this package code in the organization,
     *        which is where the code and version are unique
     * @param latestVersionInScope the highest version of this package code in this package's own
     *        scope, which is where one package supersedes another
     * @param superseded a draft for which a later version of the same code exists in the same
     *        scope; a package of another scope replaces nothing here
     * @param expired a draft whose effective period has already ended
     * @param missingCategories categories the purpose requires that the package lacks
     * @param combinationFailures failures of the category combination, without the missing
     *        categories; evaluated for drafts and for a single-package read, null otherwise
     * @param digestIntact whether the package still has the digest it was drafted with
     * @param activeOverlapIds active packages of the same scope and purpose whose period overlaps
     * @param rights the caller's rights on the package's scope
     * @param nextStep the step the package waits for, or null when none remains
     */
    public record PackageSummary(UUID id, String code, int version, String purposeCode, String scopeKind,
                                 String platformCode, UUID storeId, String storeName, String storePlatformCode,
                                 String status, String stage, Instant effectiveFrom, Instant effectiveTo,
                                 UUID replacesPackageId, UUID replacedByPackageId, int latestVersionOfCode,
                                 int latestVersionInScope,
                                 boolean superseded, boolean expired, Actor drafted, Actor validated,
                                 Actor accepted, Actor activated, Actor retired, List<String> missingCategories,
                                 List<String> combinationFailures, boolean digestIntact, List<UUID> activeOverlapIds,
                                 ScopeRights rights, NextStep nextStep) {
        public PackageSummary {
            missingCategories = List.copyOf(missingCategories);
            combinationFailures = combinationFailures == null ? null : List.copyOf(combinationFailures);
            activeOverlapIds = List.copyOf(activeOverlapIds);
        }
    }

    /** The closed category list and what each purpose requires. */
    public record Catalogue(List<Category> categories, List<PurposeRequirement> purposes) {
        public Catalogue {
            categories = List.copyOf(categories);
            purposes = List.copyOf(purposes);
        }
    }

    /** One calibration category; the value shape decides which value field it carries. */
    public record Category(String code, String displayName, String valueShape, int ordinal) {
    }

    /** The categories a package of one purpose must carry, in catalogue order. */
    public record PurposeRequirement(String purposeCode, List<String> requiredCategories) {
        public PurposeRequirement {
            requiredCategories = List.copyOf(requiredCategories);
        }
    }

    /** One value of a package, exactly as stored. */
    public record Value(String categoryCode, String valueShape, String numeric, String text, JsonNode json,
                        String unitCode, Integer windowDays, String scopeNote, String evidenceReference) {
    }

    /** One lifecycle event of a package. */
    public record Event(UUID id, String kind, UUID actorUserId, String actorName, Instant occurredAt,
                        String packageDigest, String evidenceReference) {
    }

    /**
     * Everything about one package.
     *
     * @param currentDigest the digest the database computes now over the package's content
     * @param eligibleAcceptors names of people who could accept the package, drafter and
     *        validator excluded; empty once it is accepted
     */
    public record PackageDetail(PackageSummary summary, String evidenceReference, String rationale, String impact,
                                String differences, String currentDigest, List<String> requiredCategories,
                                List<Value> values, List<Event> events, List<String> eligibleAcceptors) {
        public PackageDetail {
            requiredCategories = List.copyOf(requiredCategories);
            values = List.copyOf(values);
            events = List.copyOf(events);
            eligibleAcceptors = List.copyOf(eligibleAcceptors);
        }
    }
}
