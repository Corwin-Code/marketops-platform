package com.mimococo.marketops.marketplaceintegration.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The only body this product may send for a description change carries the
 * one verified attribute, the exact text and an honest marking declaration.
 */
class DescriptionChangeGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "4191";
    private static final String TEXT = "Описание товара для покупателя";

    private static JsonNode body(String json) {
        return JSON.readTree(json);
    }

    private static Optional<String> refusal(JsonNode body) {
        return DescriptionChangeGuard.refusal(body, KEY, TEXT, false, 10, 5000);
    }

    private static String oneAttribute(String value, String marking) {
        return "{\"offer_id\":\"o1\",\"attributes\":[{\"id\":\"" + KEY + "\",\"values\":[{\"value\":\"" + value
                + "\"}]}]" + (marking == null ? "" : ",\"kizMarked\":" + marking) + "}";
    }

    @Test
    @DisplayName("TC-LC-G01 a one-attribute body with the exact text and a true declaration passes")
    void exactOneAttributeBodyPasses() {
        assertThat(refusal(body(oneAttribute(TEXT, "false")))).isEmpty();
    }

    @Test
    @DisplayName("TC-LC-G02 any field outside the verified attribute shape is a non-target field")
    void foreignFieldIsRefused() {
        JsonNode withPrice = body("{\"offer_id\":\"o1\",\"price\":\"10\",\"attributes\":[{\"id\":\"" + KEY
                + "\",\"values\":[{\"value\":\"" + TEXT + "\"}]}],\"kizMarked\":false}");

        assertThat(refusal(withPrice)).contains("non_target_field_present");
    }

    @Test
    @DisplayName("TC-LC-G03 a second attribute, numeric or textual, is a non-target attribute")
    void secondAttributeIsRefused() {
        String two = "{\"offer_id\":\"o1\",\"attributes\":[{\"id\":\"" + KEY + "\",\"values\":[{\"value\":\"" + TEXT
                + "\"}]},{\"id\":%s,\"values\":[{\"value\":\"x\"}]}],\"kizMarked\":false}";

        assertThat(refusal(body(two.formatted("\"4180\"")))).contains("non_target_attribute_present");
        assertThat(refusal(body(two.formatted("4180")))).contains("non_target_attribute_present");
    }

    @Test
    @DisplayName("TC-LC-G04 a body wide enough to be a whole card is refused as an overwrite risk")
    void wholeCardShapeIsRefused() {
        StringBuilder values = new StringBuilder("{\"value\":\"" + TEXT + "\"}");
        for (int index = 0; index < 12; index++) {
            values.append(",{\"value\":\"v").append(index).append("\"}");
        }
        JsonNode wide = body("{\"offer_id\":\"o1\",\"attributes\":[{\"id\":\"" + KEY + "\",\"values\":[" + values
                + "]}],\"kizMarked\":false}");

        assertThat(refusal(wide)).contains("full_card_overwrite_risk");
    }

    @Test
    @DisplayName("TC-LC-G05 the rendered body must carry the exact text")
    void textMustBeRendered() {
        assertThat(refusal(body(oneAttribute("другой текст", "false")))).contains("description_text_not_rendered");
    }

    @Test
    @DisplayName("TC-LC-G06 the marking declaration is required and must equal the declared value")
    void markingMustBeHonest() {
        assertThat(refusal(body(oneAttribute(TEXT, null)))).contains("kiz_marked_declaration_missing_or_forged");
        assertThat(refusal(body(oneAttribute(TEXT, "true")))).contains("kiz_marked_declaration_missing_or_forged");
        assertThat(refusal(body(oneAttribute(TEXT, "\"false\"")))).contains("kiz_marked_declaration_missing_or_forged");
    }

    @Test
    @DisplayName("TC-LC-G07 text, length, attribute key and body shape are checked before anything is sent")
    void preconditionsAreChecked() {
        assertThat(DescriptionChangeGuard.refusal(body(oneAttribute(TEXT, "false")), KEY, " ", false, 10, 5000))
                .contains("description_text_absent");
        assertThat(DescriptionChangeGuard.refusal(body(oneAttribute(TEXT, "false")), KEY, TEXT, false, 100, 5000))
                .contains("description_length_out_of_bounds");
        assertThat(DescriptionChangeGuard.refusal(body(oneAttribute(TEXT, "false")), KEY, TEXT, false, 1, 20))
                .contains("description_length_out_of_bounds");
        assertThat(DescriptionChangeGuard.refusal(body(oneAttribute(TEXT, "false")), " ", TEXT, false, 10, 5000))
                .contains("description_attribute_unverified");
        assertThat(DescriptionChangeGuard.refusal(null, KEY, TEXT, false, 10, 5000))
                .contains("request_could_not_be_built");
        assertThat(DescriptionChangeGuard.refusal(body("[1]"), KEY, TEXT, false, 10, 5000))
                .contains("request_could_not_be_built");
    }
}
