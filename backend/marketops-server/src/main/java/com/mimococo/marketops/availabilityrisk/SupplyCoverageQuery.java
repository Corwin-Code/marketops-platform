package com.mimococo.marketops.availabilityrisk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Conditional supply projection owned by availability, not a demand forecast or write authority. */
public interface SupplyCoverageQuery {
    record Scenario(UUID organizationId, UUID productVariantId, BigDecimal companyDailyFulfillmentUnits,
                    int coverageDays, Instant asOf) {
        public Scenario {
            Objects.requireNonNull(organizationId);
            Objects.requireNonNull(productVariantId);
            Objects.requireNonNull(companyDailyFulfillmentUnits);
            Objects.requireNonNull(asOf);
            if (companyDailyFulfillmentUnits.signum()<0 || coverageDays<=0)
                throw new IllegalArgumentException("Supply scenario needs nonnegative demand and a finite positive horizon");
        }
    }

    record SupplyEvidence(UUID provenanceId, String source, String disposition, int units, Instant observedAt) { }

    record Projection(Scenario scenario, String verdict, Instant requiredThrough,
                      BigDecimal currentCompanyDailyFulfillmentUnits, Instant projectedStockoutAt,
                      List<String> gaps, List<SupplyEvidence> supplyEvidence,
                      Map<String,String> policyVersions, String sourceDigest) {
        public Projection {
            gaps=List.copyOf(gaps);
            supplyEvidence=List.copyOf(supplyEvidence);
            policyVersions=Map.copyOf(policyVersions);
        }
    }

    /** The consumer must separately qualify and freeze the proposed scenario's evidence. */
    Projection project(Scenario scenario);
}
