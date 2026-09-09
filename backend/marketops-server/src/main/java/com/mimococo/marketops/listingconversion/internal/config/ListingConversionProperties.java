package com.mimococo.marketops.listingconversion.internal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The recalculation loop's own bounds. Off unless an environment says otherwise. */
@ConfigurationProperties(prefix = "marketops.listing-conversion")
public class ListingConversionProperties {

    private boolean workerEnabled;

    @Min(1)
    @Max(500)
    private int listingsPerPass = 50;

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public int getListingsPerPass() {
        return listingsPerPass;
    }

    public void setListingsPerPass(int listingsPerPass) {
        this.listingsPerPass = listingsPerPass;
    }
}
