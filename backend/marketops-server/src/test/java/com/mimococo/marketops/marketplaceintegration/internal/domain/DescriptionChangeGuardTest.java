package com.mimococo.marketops.marketplaceintegration.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Synthetic verified layouts exercise exact scope; no actual provider attribute is certified. */
class DescriptionChangeGuardTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String TEXT="  Точное описание товара 😀  ";
    private static final DescriptionChangeGuard.Expected EXPECTED=new DescriptionChangeGuard.Expected("offer-one","4191",TEXT,false,0,65536);

    private static Object bind(String role,String type) { return Map.of("$bind",role,"$type",type); }
    private static Map<String,Object> template(boolean marking) {
        var root=new LinkedHashMap<String,Object>();
        root.put("offer_id",bind("LISTING_KEY","string"));
        root.put("attributes",List.of(Map.of("id",bind("ATTRIBUTE_KEY","integer"),
                "values",List.of(Map.of("value",bind("DESCRIPTION_TEXT","string"))))));
        if (marking) root.put("kizMarked",bind("KIZ_MARKED","boolean"));
        return root;
    }
    private static Map<String,Object> body(String listing,String text,boolean marking) {
        var root=new LinkedHashMap<String,Object>(); root.put("offer_id",listing);
        root.put("attributes",List.of(Map.of("id",4191,"values",List.of(Map.of("value",text)))));
        if (marking) root.put("kizMarked",false);
        return root;
    }
    private static JsonNode schema(Object template,String semantics,String marking) {
        return JSON.valueToTree(Map.of("schema","DESCRIPTION_REQUEST_V1","evidenceRef","fixture://verified-shape",
                "mutationSemantics",semantics,"markingPolicy",marking,"body",template));
    }
    private static java.util.Optional<String> refusal(Object body,Object template,boolean marking) {
        return DescriptionChangeGuard.refusal(JSON.valueToTree(body),schema(template,"PARTIAL_ATTRIBUTE",marking?"REQUIRED":"NOT_APPLICABLE"),EXPECTED);
    }

    @Test void exactAttributeAndUnicodePassWithoutInventingAMarkingField() {
        assertThat(refusal(body("offer-one",TEXT,false),template(false),false)).isEmpty();
    }
    @Test void exactArrayEnvelopeAndMoreThanTwelveFixedProtocolLeavesPass() {
        var request=body("offer-one",TEXT,false); var shape=template(false);
        var metadata=new LinkedHashMap<String,Object>();
        for (int i=0;i<20;i++) metadata.put("fixed"+i,"value"+i);
        request.put("protocol",metadata); shape.put("protocol",metadata);
        assertThat(refusal(List.of(request),List.of(shape),false)).isEmpty();
    }
    @Test void foreignListingCannotBorrowTheCorrectText() {
        assertThat(refusal(body("foreign",TEXT,false),template(false),false)).contains("native_listing_node_mismatch");
    }
    @Test void aSubstringOrTrimmedBodyIsNotTheApprovedText() {
        assertThat(refusal(body("offer-one",TEXT+" extra",false),template(false),false)).contains("description_text_node_mismatch");
        assertThat(refusal(body("offer-one",TEXT.strip(),false),template(false),false)).contains("description_text_node_mismatch");
    }
    @Test void correctTextInAnUnrelatedFieldCannotReplaceItsBoundNode() {
        var request=body("offer-one","wrong",false); var shape=template(false);
        request.put("metadata",TEXT); shape.put("metadata",TEXT);
        assertThat(refusal(request,shape,false)).contains("description_text_node_mismatch");
    }
    @Test void extraItemAttributeOrFieldIsRefused() {
        var request=body("offer-one",TEXT,false);
        assertThat(refusal(List.of(request,request),List.of(template(false)),false)).contains("description_request_items_mismatch");
        request.put("attributes",List.of(Map.of("id",4191,"values",List.of(Map.of("value",TEXT))),
                Map.of("id",2,"values",List.of(Map.of("value","other")))));
        assertThat(refusal(request,template(false),false)).contains("description_request_items_mismatch");
        request=body("offer-one",TEXT,false); request.put("title","foreign title");
        assertThat(refusal(request,template(false),false)).contains("description_request_fields_mismatch");
    }
    @Test void wrongAttributeAndWrongScalarTypeAreRefused() {
        var request=body("offer-one",TEXT,false);
        request.put("attributes",List.of(Map.of("id",123,"values",List.of(Map.of("value",TEXT)))));
        assertThat(refusal(request,template(false),false)).contains("description_attribute_node_mismatch");
        request.put("attributes",List.of(Map.of("id","4191","values",List.of(Map.of("value",TEXT)))));
        assertThat(refusal(request,template(false),false)).contains("description_attribute_node_mismatch");
    }
    @Test void markingIsConditionalButExactWhenRequired() {
        assertThat(refusal(body("offer-one",TEXT,true),template(true),true)).isEmpty();
        assertThat(refusal(body("offer-one",TEXT,false),template(true),true)).contains("description_request_fields_mismatch");
        var request=body("offer-one",TEXT,true); request.put("kizMarked",true);
        assertThat(refusal(request,template(true),true)).contains("marking_node_mismatch");
        assertThat(refusal(body("offer-one",TEXT,true),template(false),false)).contains("description_request_fields_mismatch");
    }
    @Test void directFieldAndNumericIdentityUseTheExactAttributeNode() {
        Object shape=List.of(Map.of("nativeId",bind("LISTING_KEY","integer"),"description",bind("DESCRIPTION_TEXT","string")));
        var expected=new DescriptionChangeGuard.Expected("123","description",TEXT,false,0,5000);
        assertThat(DescriptionChangeGuard.refusal(JSON.valueToTree(List.of(Map.of("nativeId",123,"description",TEXT))),
                schema(shape,"PARTIAL_FIELD","NOT_APPLICABLE"),expected)).isEmpty();
        assertThat(DescriptionChangeGuard.refusal(JSON.valueToTree(List.of(Map.of("nativeId",123,"description",TEXT))),
                schema(shape,"PARTIAL_FIELD","NOT_APPLICABLE"),new DescriptionChangeGuard.Expected("123","title",TEXT,false,0,5000)))
                .contains("description_attribute_node_mismatch");
    }
    @Test void unprovedWholeCardSemanticsAndAbsentSchemaAreRefused() {
        assertThat(DescriptionChangeGuard.refusal(JSON.valueToTree(body("offer-one",TEXT,false)),
                schema(template(false),"WHOLE_CARD_REPLACEMENT","NOT_APPLICABLE"),EXPECTED))
                .contains("whole_card_preservation_or_update_semantics_unproved");
        assertThat(DescriptionChangeGuard.refusal(JSON.valueToTree(body("offer-one",TEXT,false)),null,EXPECTED))
                .contains("description_request_schema_unverified");
    }
    @Test void literalTextWithoutTheRequiredSemanticBindingCannotPass() {
        assertThat(refusal(body("offer-one",TEXT,false),body("offer-one",TEXT,false),false))
                .contains("description_request_binding_cardinality_invalid");
    }
    @Test void longExactTextUsesCodePointsAndRejectsInvalidUnicode() {
        String longText="😀".repeat(5000);
        var expected=new DescriptionChangeGuard.Expected("offer-one","4191",longText,false,0,5000);
        assertThat(DescriptionChangeGuard.refusal(JSON.valueToTree(body("offer-one",longText,false)),
                schema(template(false),"PARTIAL_ATTRIBUTE","NOT_APPLICABLE"),expected)).isEmpty();
        assertThat(DescriptionChangeGuard.refusal(JSON.valueToTree(body("offer-one",longText,false)),
                schema(template(false),"PARTIAL_ATTRIBUTE","NOT_APPLICABLE"),
                new DescriptionChangeGuard.Expected("offer-one","4191",longText,false,0,4999)))
                .contains("description_length_out_of_bounds");
        assertThat(DescriptionChangeGuard.refusal(JSON.valueToTree(body("offer-one",TEXT,false)),
                schema(template(false),"PARTIAL_ATTRIBUTE","NOT_APPLICABLE"),
                new DescriptionChangeGuard.Expected("offer-one","4191",String.valueOf((char)0xd800),false,0,5000)))
                .contains("description_text_invalid");
    }
}
