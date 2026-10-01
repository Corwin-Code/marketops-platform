package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.ListingUnitEconomics;
import com.mimococo.marketops.operatingfacts.CostSnapshot;
import com.mimococo.marketops.operatingfacts.ListingPriceTerms;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a listing's unit economics are computed from, read the same way wherever a price is judged:
 * the newest stated tariffs, the store's fulfilment scheme and the unit cost of the mapped product.
 */
@Component
class ListingEconomicsInputs {

    private final OperatingFactQuery facts;
    private final ListingIdentityDirectory listings;

    ListingEconomicsInputs(OperatingFactQuery facts, ListingIdentityDirectory listings) {
        this.facts = facts;
        this.listings = listings;
    }

    /**
     * The terms and cost of one listing.
     *
     * @param modes the store's active fulfilment modes
     */
    Inputs of(UUID listingVariantId, List<String> modes, Instant asOf) {
        List<String> missing = new ArrayList<>();
        Optional<ListingPriceTerms> stated = facts.latestPriceTerms(listingVariantId, asOf);
        Optional<ListingUnitEconomics.Scheme> scheme = MetricEngine.scheme(modes,
                facts.latestStock(listingVariantId, asOf));
        Optional<ListingUnitEconomics.Terms> terms = stated.isEmpty() || scheme.isEmpty() ? Optional.empty()
                : ListingUnitEconomics.terms(stated.get(), scheme.get());
        if (scheme.isEmpty()) {
            missing.add("FULFILLMENT_SCHEME");
        }
        if (terms.isEmpty()) {
            missing.add("MARKETPLACE_TARIFFS");
        }
        Optional<CostSnapshot> cost = listings.variantContext(listingVariantId, asOf)
                .filter(context -> context.mapped() && !context.conflictOpen())
                .flatMap(context -> facts.unitCost(context.productVariantId(), asOf))
                .filter(snapshot -> snapshot.costVersionId() != null && snapshot.unitCost() != null
                        && snapshot.unitCost().amount().signum() >= 0 && snapshot.effectiveFrom() != null
                        && snapshot.effectiveFrom().isBefore(asOf));
        if (cost.isEmpty()) {
            missing.add("UNIT_COST");
        }
        return new Inputs(stated.orElse(null), terms.orElse(null), cost.orElse(null), List.copyOf(missing));
    }

    /** What one listing's economics are computed from; {@code missing} names what was not there. */
    record Inputs(ListingPriceTerms stated, ListingUnitEconomics.Terms terms, CostSnapshot cost,
                  List<String> missing) {
    }
}
