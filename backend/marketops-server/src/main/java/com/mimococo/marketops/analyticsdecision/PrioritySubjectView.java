package com.mimococo.marketops.analyticsdecision;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One subject in a store's daily work list.
 *
 * <p>The score is derived from what the deterministic rules found and from the
 * money the subject moved, not from a model. A queue whose order nobody can
 * explain is a queue operators stop trusting.
 *
 * @param subjectKind what the entry is about
 * @param subjectId identifier of the subject
 * @param storeId store the subject belongs to
 * @param priorityScore the ordering score, higher first
 * @param criticalFindingCount how many rules triggered at critical severity
 * @param warningFindingCount how many triggered at warning severity
 * @param declinedRuleCount how many rules could not answer
 * @param netSales money the subject moved in the window, or {@code null}
 * @param contributionProfit profit the subject made in the window, or {@code null}
 * @param currencyCode currency of the amounts, or {@code null}
 * @param blockingRuleCodes rules whose findings block a platform write
 */
public record PrioritySubjectView(
        SubjectKind subjectKind,
        UUID subjectId,
        UUID storeId,
        BigDecimal priorityScore,
        int criticalFindingCount,
        int warningFindingCount,
        int declinedRuleCount,
        BigDecimal netSales,
        BigDecimal contributionProfit,
        String currencyCode,
        List<String> blockingRuleCodes) {

    public PrioritySubjectView {
        blockingRuleCodes =
                List.copyOf(Objects.requireNonNull(blockingRuleCodes, "blockingRuleCodes"));
    }

    /** Whether anything currently blocks a platform write for this subject. */
    public boolean writeBlocked() {
        return !blockingRuleCodes.isEmpty();
    }
}
