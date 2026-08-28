package com.mimococo.marketops.operationsworkflow;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What the deterministic guardrail decided about one proposed action.
 *
 * <p>A pass names the policy version it passed under, so an operator can see
 * which rules were applied and a later reviewer can reconstruct the decision
 * from the recorded version rather than from today's policy. A block names
 * every reason at once rather than the first one found: fixing one condition
 * only to be refused for the next is how an operator loses a day.
 *
 * @param evaluationId the recorded evaluation
 * @param purpose why the evaluation ran
 * @param passed whether the action may proceed
 * @param reasons every condition that blocks it, empty when it passed
 * @param policyId the policy in force, or {@code null} when none was
 * @param policyVersion version of that policy, or {@code null}
 * @param detail the comparisons made, in operator-readable terms
 * @param inputDigest digest of the inputs, making the verdict reproducible
 */
public record GuardrailVerdict(
        UUID evaluationId,
        GuardrailPurpose purpose,
        boolean passed,
        List<GuardrailReason> reasons,
        UUID policyId,
        Integer policyVersion,
        Map<String, String> detail,
        String inputDigest) {

    public GuardrailVerdict {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        detail = Map.copyOf(Objects.requireNonNull(detail, "detail"));
    }

    /** Whether the verdict names a specific condition. */
    public boolean blockedBy(GuardrailReason reason) {
        return reasons.contains(reason);
    }
}
