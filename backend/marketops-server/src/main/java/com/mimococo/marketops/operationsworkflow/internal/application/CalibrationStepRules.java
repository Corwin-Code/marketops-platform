package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.NextStep;
import com.mimococo.marketops.operationsworkflow.ListingCalibrationView.ScopeRights;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ListingCalibrationRepository.PackageRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Where a calibration package stands and what stops the next step, stated the
 * way the database functions check it.
 *
 * <p>The checks mirror {@code ops.validate_lc_calibration},
 * {@code ops.accept_lc_calibration} and {@code ops.activate_lc_calibration}
 * (V0001) so the console can say why a step is unavailable before anybody tries
 * it. They decide nothing: the functions still refuse on their own at the
 * moment of the write, with the database clock.
 */
final class CalibrationStepRules {

    static final String DRAFTED = "DRAFTED";
    static final String VALIDATED = "VALIDATED";
    static final String ACCEPTED = "ACCEPTED";
    static final String ACTIVE = "ACTIVE";
    static final String ENDED = "ENDED";
    static final String RETIRED = "RETIRED";

    private CalibrationStepRules() {
    }

    /**
     * The lifecycle stage: a draft is waiting for validation, acceptance or
     * activation; an active package whose period is over has ended.
     */
    static String stage(PackageRow row, Instant now) {
        return switch (row.status()) {
            case "RETIRED" -> RETIRED;
            case "ACTIVE" -> row.effectiveTo() != null && !row.effectiveTo().isAfter(now) ? ENDED : ACTIVE;
            default -> row.accepted() != null ? ACCEPTED : row.validated() != null ? VALIDATED : DRAFTED;
        };
    }

    /**
     * The failures of the category combination without the missing categories,
     * each once, in the order the database reported them. The wrapper function
     * starts from the missing categories and only sorts and deduplicates on one
     * of its paths.
     */
    static List<String> combinationFailures(PackageRow row) {
        if (row.combinationFailures() == null) {
            return null;
        }
        Set<String> missing = Set.copyOf(row.missingCategories());
        Set<String> out = new LinkedHashSet<>();
        for (String failure : row.combinationFailures()) {
            if (!missing.contains(failure)) {
                out.add(failure);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The step the package waits for, the digest that step must carry, and every
     * reason the caller cannot take it now. A stale sign-in is reported apart
     * from the blockers: the caller can fix it by signing in again.
     */
    static NextStep nextStep(PackageRow row, String stage, ScopeRights rights, UUID caller,
                             boolean stepUpSatisfied, Instant now) {
        List<String> blockers = new ArrayList<>();
        List<String> combination = combinationFailures(row);
        boolean incomplete = !row.missingCategories().isEmpty();
        boolean inconsistent = combination != null && !combination.isEmpty();
        boolean ended = row.effectiveTo() != null && !row.effectiveTo().isAfter(now);
        switch (stage) {
            case DRAFTED -> {
                String digest = row.drafted().digest();
                if (!Objects.equals(digest, row.currentDigest())) blockers.add("DIGEST_CHANGED");
                if (incomplete) blockers.add("MISSING_CATEGORIES");
                if (inconsistent) blockers.add("COMBINATION_FAILURES");
                if (!rights.validate()) blockers.add("NOT_GRANTED");
                return new NextStep("VALIDATE", digest, blockers, !stepUpSatisfied);
            }
            case VALIDATED -> {
                String digest = row.validated().digest();
                if (!Objects.equals(digest, row.currentDigest())) blockers.add("DIGEST_CHANGED");
                if (ended) blockers.add("EFFECTIVE_PERIOD_ENDED");
                if (!rights.accept()) blockers.add("NOT_GRANTED");
                if (caller.equals(row.drafted().userId())) blockers.add("SAME_AS_DRAFTER");
                if (caller.equals(row.validated().userId())) blockers.add("SAME_AS_VALIDATOR");
                return new NextStep("ACCEPT", digest, blockers, !stepUpSatisfied);
            }
            case ACCEPTED -> {
                String digest = row.accepted().digest();
                if (!Objects.equals(digest, row.currentDigest())) blockers.add("DIGEST_CHANGED");
                if (incomplete) blockers.add("MISSING_CATEGORIES");
                if (inconsistent) blockers.add("COMBINATION_FAILURES");
                if (row.effectiveFrom().isAfter(now)) blockers.add("NOT_YET_EFFECTIVE");
                if (ended) blockers.add("EFFECTIVE_PERIOD_ENDED");
                if (row.activeOverlapIds().size() > 1) {
                    blockers.add("ACTIVE_RANGE_CONFLICT");
                } else if (!Objects.equals(row.activeOverlapIds().isEmpty() ? null : row.activeOverlapIds().getFirst(),
                        row.replacesPackageId())) {
                    blockers.add("REPLACEMENT_MISMATCH");
                }
                if (!rights.accept()) blockers.add("NOT_GRANTED");
                return new NextStep("ACTIVATE", digest, blockers, !stepUpSatisfied);
            }
            default -> {
                return null;
            }
        }
    }
}
