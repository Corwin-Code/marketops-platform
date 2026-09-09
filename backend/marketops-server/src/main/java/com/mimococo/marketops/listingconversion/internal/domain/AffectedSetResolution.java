package com.mimococo.marketops.listingconversion.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The complete affected set of a listing, resolved from what the platform says.
 *
 * <p>Complete when every observed variant has an internal identity and no
 * conflict; incomplete when a variant is unmapped; conflicted when a mapping
 * conflict is open. An incomplete set blocks only the purposes that need
 * completeness and stays visible with its gap.
 */
public final class AffectedSetResolution {

    private AffectedSetResolution() {
    }

    public record Member(UUID listingVariantId, UUID productVariantId, boolean conflictOpen) {
    }

    public record Resolution(String state, List<UUID> listingVariantIds, List<UUID> productVariantIds,
                             List<String> reasonCodes) {
        public Resolution {
            listingVariantIds = List.copyOf(listingVariantIds);
            productVariantIds = List.copyOf(productVariantIds);
            reasonCodes = List.copyOf(reasonCodes);
        }
    }

    public static Resolution resolve(List<Member> members) {
        List<UUID> listingVariants = new ArrayList<>();
        List<UUID> productVariants = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        boolean conflicted = false;
        boolean unmapped = false;
        for (Member member : members) {
            listingVariants.add(member.listingVariantId());
            if (member.conflictOpen()) {
                conflicted = true;
            } else if (member.productVariantId() == null) {
                unmapped = true;
            } else {
                productVariants.add(member.productVariantId());
            }
        }
        if (members.isEmpty()) {
            reasons.add("NO_OBSERVED_VARIANTS");
            return new Resolution("INCOMPLETE", listingVariants, productVariants, reasons);
        }
        if (conflicted) {
            reasons.add("MAPPING_CONFLICT_OPEN");
            return new Resolution("CONFLICTED", listingVariants, productVariants, reasons);
        }
        if (unmapped) {
            reasons.add("VARIANT_UNMAPPED");
            return new Resolution("INCOMPLETE", listingVariants, productVariants, reasons);
        }
        return new Resolution("COMPLETE", listingVariants, productVariants, reasons);
    }
}
