package com.mimococo.marketops.listingconversion.internal.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** One interpretation of the reviewed scope for current admission and frozen Outcome. */
public record ProtectionScopeBasis(String evidenceReference, List<LinkedProfitScope> linkedProfitScopes,
                                   List<UUID> criticalReturnVariantIds) {
    public ProtectionScopeBasis {
        linkedProfitScopes=List.copyOf(linkedProfitScopes);
        criticalReturnVariantIds=List.copyOf(criticalReturnVariantIds);
    }
    public record LinkedProfitScope(String code, UUID storeId, List<UUID> listingVariantIds, String evidenceReference) {
        public LinkedProfitScope { listingVariantIds=List.copyOf(listingVariantIds); }
    }
    public static Optional<ProtectionScopeBasis> parse(JsonNode value,List<UUID> directMembers) {
        if (value==null || !value.isObject() || !reference(value.path("evidenceReference"))
                || !value.path("linkedProfitScopes").isArray() || !value.path("criticalReturnVariantIds").isArray())
            return Optional.empty();
        try {
            var linkedScopes=new ArrayList<LinkedProfitScope>();
            var codes=new HashSet<String>();
            for (var linked:value.path("linkedProfitScopes")) {
                String code=linked.path("code").asText("");
                if (!code.matches("[A-Z][A-Z0-9_]{0,63}") || !codes.add(code)
                        || !reference(linked.path("evidenceReference")) || !linked.path("listingVariantIds").isArray()
                        || linked.path("listingVariantIds").isEmpty()) return Optional.empty();
                var members=new ArrayList<UUID>();
                for (var member:linked.path("listingVariantIds")) members.add(id(member));
                if (new HashSet<>(members).size()!=members.size()) return Optional.empty();
                linkedScopes.add(new LinkedProfitScope(code,id(linked.path("storeId")),members,
                        linked.path("evidenceReference").asText()));
            }
            var critical=new ArrayList<UUID>();
            for (var member:value.path("criticalReturnVariantIds")) critical.add(id(member));
            if (new HashSet<>(critical).size()!=critical.size() || !directMembers.containsAll(critical)) return Optional.empty();
            return Optional.of(new ProtectionScopeBasis(value.path("evidenceReference").asText(),linkedScopes,critical));
        } catch (IllegalArgumentException malformedIdentity) { return Optional.empty(); }
    }
    private static UUID id(JsonNode value) {
        if (!value.isTextual()) throw new IllegalArgumentException("Identity must be text");
        UUID result=UUID.fromString(value.asText());
        if (!result.toString().equals(value.asText())) throw new IllegalArgumentException("Identity must be canonical");
        return result;
    }
    private static boolean reference(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank() && value.asText().length()<=512;
    }
}
