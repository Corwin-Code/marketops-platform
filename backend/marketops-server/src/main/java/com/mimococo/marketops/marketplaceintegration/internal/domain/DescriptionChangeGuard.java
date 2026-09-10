package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Checks the complete rendered body against the verified operation's closed schema. */
public final class DescriptionChangeGuard {
    private static final Set<String> FIELDS=Set.of("schema","evidenceRef","mutationSemantics","markingPolicy","body");
    private static final Set<String> BINDINGS=Set.of("LISTING_KEY","ATTRIBUTE_KEY","DESCRIPTION_TEXT","KIZ_MARKED");
    private static final int MAX_DEPTH=32;
    private static final int MAX_NODES=4096;

    private DescriptionChangeGuard() { }

    public record Expected(String nativeListingKey,String attributeKey,String descriptionText,
                           boolean kizMarkedDeclared,int minimumLength,int maximumLength) { }

    public static Optional<String> refusal(JsonNode body,JsonNode schema,Expected expected) {
        if (expected==null || expected.nativeListingKey()==null || expected.nativeListingKey().isBlank()) {
            return Optional.of("native_listing_identity_unbound");
        }
        String text=expected.descriptionText();
        if (text==null || text.isBlank() || !validUnicode(text)) return Optional.of("description_text_invalid");
        if (expected.minimumLength()<0 || expected.maximumLength()<expected.minimumLength()
                || expected.maximumLength()>65536) return Optional.of("description_bounds_unverified");
        int length=text.codePointCount(0,text.length());
        if (length<expected.minimumLength() || length>expected.maximumLength()) return Optional.of("description_length_out_of_bounds");
        if (expected.attributeKey()==null || expected.attributeKey().isBlank()) return Optional.of("description_attribute_unverified");
        if (schema==null || !schema.isObject() || !keys(schema).equals(FIELDS)
                || !string(schema.get("schema"),"DESCRIPTION_REQUEST_V1")
                || schema.get("evidenceRef")==null || !schema.get("evidenceRef").isString()
                || schema.get("evidenceRef").asString().isBlank()) return Optional.of("description_request_schema_unverified");
        String semantics=schema.path("mutationSemantics").asString("");
        if (!Set.of("PARTIAL_ATTRIBUTE","PARTIAL_FIELD").contains(semantics)) {
            return Optional.of("whole_card_preservation_or_update_semantics_unproved");
        }
        String marking=schema.path("markingPolicy").asString("");
        if (!Set.of("REQUIRED","NOT_APPLICABLE").contains(marking)) return Optional.of("marking_applicability_unverified");
        var counts=new HashMap<String,Integer>();
        var nodes=new int[] {0};
        String gap=match(body,schema.get("body"),expected,counts,nodes,0,"",semantics);
        if (gap!=null) return Optional.of(gap);
        if (counts.getOrDefault("LISTING_KEY",0)!=1 || counts.getOrDefault("DESCRIPTION_TEXT",0)!=1
                || counts.getOrDefault("ATTRIBUTE_KEY",0)!=(semantics.equals("PARTIAL_ATTRIBUTE")?1:0)
                || counts.getOrDefault("KIZ_MARKED",0)!=(marking.equals("REQUIRED")?1:0)) {
            return Optional.of("description_request_binding_cardinality_invalid");
        }
        return Optional.empty();
    }

    private static String match(JsonNode actual,JsonNode shape,Expected expected,Map<String,Integer> counts,
                                int[] nodes,int depth,String field,String semantics) {
        if (depth>MAX_DEPTH || ++nodes[0]>MAX_NODES) return "description_request_schema_complexity_exceeded";
        if (actual==null || shape==null) return "description_request_shape_mismatch";
        if (shape.isObject() && shape.has("$bind")) {
            if (!keys(shape).equals(Set.of("$bind","$type")) || !shape.path("$bind").isString()
                    || !shape.path("$type").isString()) return "description_request_schema_unverified";
            String binding=shape.path("$bind").asString();
            if (!BINDINGS.contains(binding)) return "description_request_binding_unknown";
            counts.merge(binding,1,Integer::sum);
            String type=shape.path("$type").asString();
            if (binding.equals("DESCRIPTION_TEXT")) {
                if (semantics.equals("PARTIAL_FIELD") && !field.equals(expected.attributeKey())) return "description_attribute_node_mismatch";
                return type.equals("string") && string(actual,expected.descriptionText()) ? null : "description_text_node_mismatch";
            }
            if (binding.equals("KIZ_MARKED")) {
                return type.equals("boolean") && actual.isBoolean() && actual.asBoolean()==expected.kizMarkedDeclared()
                        ? null : "marking_node_mismatch";
            }
            String value=binding.equals("LISTING_KEY")?expected.nativeListingKey():expected.attributeKey();
            boolean matches=type.equals("string") ? string(actual,value)
                    : type.equals("integer") && value.matches("0|[1-9][0-9]*") && actual.isIntegralNumber()
                        && actual.bigIntegerValue().equals(new BigInteger(value));
            return matches ? null : binding.equals("LISTING_KEY")?"native_listing_node_mismatch":"description_attribute_node_mismatch";
        }
        if (shape.isObject()) {
            if (!actual.isObject() || !keys(actual).equals(keys(shape))) return "description_request_fields_mismatch";
            for (var entry:shape.properties()) {
                String gap=match(actual.get(entry.getKey()),entry.getValue(),expected,counts,nodes,depth+1,entry.getKey(),semantics);
                if (gap!=null) return gap;
            }
            return null;
        }
        if (shape.isArray()) {
            if (!actual.isArray() || actual.size()!=shape.size()) return "description_request_items_mismatch";
            for (int i=0;i<shape.size();i++) {
                String gap=match(actual.get(i),shape.get(i),expected,counts,nodes,depth+1,Integer.toString(i),semantics);
                if (gap!=null) return gap;
            }
            return null;
        }
        return actual.equals(shape) ? null : "description_protocol_literal_mismatch";
    }

    private static Set<String> keys(JsonNode node) {
        var result=new java.util.HashSet<String>();
        node.properties().forEach(entry -> result.add(entry.getKey()));
        return result;
    }

    private static boolean string(JsonNode node,String value) {
        return node!=null && node.isString() && node.asString().equals(value);
    }

    private static boolean validUnicode(String text) {
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (c==0 || Character.isLowSurrogate(c)) return false;
            if (Character.isHighSurrogate(c) && (++i>=text.length() || !Character.isLowSurrogate(text.charAt(i)))) return false;
        }
        return true;
    }
}
