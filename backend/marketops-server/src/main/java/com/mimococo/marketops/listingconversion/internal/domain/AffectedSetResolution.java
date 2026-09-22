package com.mimococo.marketops.listingconversion.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Resolve only the native scope and mappings retained by the existing identity authority. */
public final class AffectedSetResolution {

    private AffectedSetResolution() {
    }

    public record Member(UUID listingVariantId, UUID productVariantId, boolean conflictOpen, boolean identityActive) {
        public Member(UUID listingVariantId, UUID productVariantId, boolean conflictOpen) {
            this(listingVariantId,productVariantId,conflictOpen,true);
        }
    }

    public record Resolution(String state, List<UUID> listingVariantIds, List<UUID> productVariantIds,
                             List<String> reasonCodes) {
        public Resolution {
            listingVariantIds = List.copyOf(listingVariantIds);
            productVariantIds = List.copyOf(productVariantIds);
            reasonCodes = List.copyOf(reasonCodes);
        }
    }

    public static Resolution resolve(List<Member> members, String nativeScopeState, List<String> nativeScopeReasons) {
        List<UUID> listingVariants = new ArrayList<>();
        List<UUID> productVariants = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        boolean conflicted = false;
        boolean unmapped = false;
        boolean inactive = false;
        for (Member member : members) {
            listingVariants.add(member.listingVariantId());
            if (member.conflictOpen()) {
                conflicted = true;
            } else if (member.productVariantId() == null) {
                unmapped = true;
            } else {
                productVariants.add(member.productVariantId());
                inactive |= !member.identityActive();
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
        if (inactive) reasons.add("IDENTITY_NOT_ACTIVE");
        if (!"COMPLETE".equals(nativeScopeState)) {
            reasons.addAll(nativeScopeReasons.isEmpty()?List.of("NATIVE_SCOPE_UNPROVEN"):nativeScopeReasons);
        }
        return new Resolution("CONFLICTED".equals(nativeScopeState)?"CONFLICTED":reasons.isEmpty()?"COMPLETE":"INCOMPLETE",
                listingVariants, productVariants.stream().distinct().toList(), reasons);
    }
}
