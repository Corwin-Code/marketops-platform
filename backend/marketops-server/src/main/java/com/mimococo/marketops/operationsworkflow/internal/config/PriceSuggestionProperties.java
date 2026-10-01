package com.mimococo.marketops.operationsworkflow.internal.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How far one price suggestion may go and how long its effect is watched. The floor is not here: no
 * suggestion goes below the target margin price, which keeps the Owner's minimum unit margin.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.price-suggestions")
public class PriceSuggestionProperties {

    /** The largest share by which one suggestion lowers today's buyer price. */
    @NotNull
    @DecimalMin("0.01")
    @DecimalMax("0.50")
    private BigDecimal maxStepRate = new BigDecimal("0.10");

    /** How many days after a change its effect is judged. */
    @Min(1)
    @Max(90)
    private int validationHorizonDays = 14;

    public BigDecimal getMaxStepRate() {
        return maxStepRate;
    }

    public void setMaxStepRate(BigDecimal maxStepRate) {
        this.maxStepRate = maxStepRate;
    }

    public int getValidationHorizonDays() {
        return validationHorizonDays;
    }

    public void setValidationHorizonDays(int validationHorizonDays) {
        this.validationHorizonDays = validationHorizonDays;
    }
}
