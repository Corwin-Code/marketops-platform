package com.mimococo.marketops.marketplaceintegration.adapter.http;

import static org.assertj.core.api.Assertions.*;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.JsonValues;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RequestTemplateTest {
    @Test
    void opaqueCursorCannotIntroduceAPathSegmentQueryArgumentOrFragment() {
        String value="AZaz09-._~ /?&=#%+Ж😀";
        assertThat(RequestTemplate.render("/page/{cursor}?again={cursor}",Map.of("cursor",value),RequestTemplate.Escaping.URL))
                .isEqualTo("/page/AZaz09-._~%20%2F%3F%26%3D%23%25%2B%D0%96%F0%9F%98%80?again=AZaz09-._~%20%2F%3F%26%3D%23%25%2B%D0%96%F0%9F%98%80");
    }

    @Test
    void hostileJsonStringRoundTripsWithoutCreatingPropertiesOrResubstituting() {
        String value="\"},\"injected\":true,\"x\":\"\\$1\b\f\n\r\t\u0001{targetPrice}Ж😀";
        String rendered=RequestTemplate.render("{\"cursor\":\"{cursor}\",\"limit\":{limit}}",
                Map.of("cursor",value,"limit","100"),RequestTemplate.Escaping.JSON);
        var json=JsonValues.read(JsonMapper.builder().build(),rendered);
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.get("cursor").asString()).isEqualTo(value);
        assertThat(json.get("limit").intValue()).isEqualTo(100);
    }

    @Test
    void onlyClosedPlaceholdersWithSuppliedValuesCanBeRendered() {
        for(var values:java.util.List.of(Map.<String,String>of(),Map.of("unapproved","value"))) {
            String token=values.isEmpty()?"cursor":"unapproved";
            assertThatThrownBy(() -> RequestTemplate.render("{"+token+"}",values,RequestTemplate.Escaping.URL))
                    .isInstanceOf(OperationRejectedException.class)
                    .extracting(e -> ((OperationRejectedException)e).errorCode()).isEqualTo(ErrorCode.CAPABILITY_NOT_USABLE);
        }
    }

    @Test
    void optionalTemplatesAndApplicationProducedValuesHaveExplicitSemantics() {
        assertThat(RequestTemplate.render(null,Map.of(),RequestTemplate.Escaping.URL)).isNull();
        assertThat(RequestTemplate.render("",Map.of(),RequestTemplate.Escaping.URL)).isEmpty();
        assertThat(RequestTemplate.render("/fixed",Map.of(),RequestTemplate.Escaping.URL)).isEqualTo("/fixed");
        assertThat(RequestTemplate.render("{targetPrice}",Map.of("targetPrice","99999999999999.9999"),RequestTemplate.Escaping.NONE))
                .isEqualTo("99999999999999.9999");
    }
}
