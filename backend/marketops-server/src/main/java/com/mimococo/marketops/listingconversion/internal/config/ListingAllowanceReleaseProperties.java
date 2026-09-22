package com.mimococo.marketops.listingconversion.internal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The matured-outcome allowance release timer. Off unless an environment says otherwise; the
 * loopback maintenance endpoint can still run one pass on demand while it is off.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.listing-conversion.allowance-release")
public class ListingAllowanceReleaseProperties {

    private boolean enabled;

    @Min(1)
    @Max(500)
    private int actionsPerPass = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getActionsPerPass() {
        return actionsPerPass;
    }

    public void setActionsPerPass(int actionsPerPass) {
        this.actionsPerPass = actionsPerPass;
    }
}
