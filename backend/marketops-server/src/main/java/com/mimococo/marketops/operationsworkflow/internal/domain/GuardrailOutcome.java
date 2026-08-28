package com.mimococo.marketops.operationsworkflow.internal.domain;

import com.mimococo.marketops.operationsworkflow.GuardrailReason;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the engine concluded, before anything is recorded.
 *
 * <p>The projected figures travel with the verdict because the preview and the
 * verdict must be built from one evaluation. Computing them separately would
 * let an operator see one projection and the gate check another.
 *
 * @param reasons every blocking condition, empty when the action may proceed
 * @param detail the comparisons made, in operator-readable terms
 * @param changeRate the proportional change, or {@code null}
 * @param breakEvenPrice the price below which the unit loses money, or {@code null}
 * @param currentUnitProfit unit contribution profit now, or {@code null}
 * @param projectedUnitProfit unit contribution profit after the change, or {@code null}
 * @param currentMargin contribution margin now, or {@code null}
 * @param projectedMargin contribution margin after the change, or {@code null}
 */
public record GuardrailOutcome(
        List<GuardrailReason> reasons,
        Map<String, String> detail,
        BigDecimal changeRate,
        BigDecimal breakEvenPrice,
        BigDecimal currentUnitProfit,
        BigDecimal projectedUnitProfit,
        BigDecimal currentMargin,
        BigDecimal projectedMargin) {

    public GuardrailOutcome {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        detail = Map.copyOf(Objects.requireNonNull(detail, "detail"));
    }

    /** Whether the proposed action may proceed. */
    public boolean passed() {
        return reasons.isEmpty();
    }
}
