package com.mimococo.marketops.operatingfacts.internal.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class PayloadReaderTest {
    private final PayloadReader reader=new PayloadReader(JsonMapper.builder().build());

    @Test
    void declaredTypesPreserveExactMoneyTimeAndUnknownStates() {
        var pointers=Map.of("amount","/money","quantity","/qty","seen","/at","enabled","/flag","native","/state");
        var kinds=Map.of("amount","DECIMAL","quantity","INTEGER","seen","INSTANT","enabled","BOOLEAN");
        var result=reader.read(bytes("""
                {"rows":[{"money":99999999999999.9999,"qty":9223372036854775807,
                 "at":"2026-08-28T08:00:00+08:00","flag":true,"state":"NEW_PROVIDER_STATE"}]}
                """),"/rows",pointers,kinds);
        var record=result.records().getFirst();
        assertThat(record.requiredDecimal("amount")).isEqualByComparingTo(new BigDecimal("99999999999999.9999"));
        assertThat(record.requiredInteger("quantity")).isEqualTo(Long.MAX_VALUE);
        assertThat(record.requiredInstant("seen")).isEqualTo(Instant.parse("2026-08-28T00:00:00Z"));
        assertThat(record.requiredText("native")).isEqualTo("NEW_PROVIDER_STATE");
        assertThat(record.triState("enabled")).isEqualTo("YES");
        assertThat(record.triState("absent")).isEqualTo("UNKNOWN");
        assertThat(result.unmappedPointers()).isEmpty();
        assertThatThrownBy(() -> record.values().put("amount",BigDecimal.ZERO)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={"9223372036854775808","-9223372036854775809","1.25","true","false","{}","[]","null","\"1.2\"","\"NaN\"","\"99999999999999999999\""})
    void integerCannotOverflowTruncateOrBecomeAnInventedZero(String value) {
        var record=one(value,"INTEGER");
        assertThat(record.integer("value")).isEmpty();
        assertThatThrownBy(() -> record.requiredInteger("value")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void supportedTextualNumbersAreExactAndNoOtherTypeIsGuessed() {
        assertThat(one("\" -42 \"","INTEGER").requiredInteger("value")).isEqualTo(-42);
        assertThat(one("\" 1.234567890123456789 \"","DECIMAL").requiredDecimal("value"))
                .isEqualByComparingTo("1.234567890123456789");
        assertThat(one("false","BOOLEAN").triState("value")).isEqualTo("NO");
        assertThat(one("\"false\"","BOOLEAN").triState("value")).isEqualTo("UNKNOWN");
        assertThat(one("1","UNDECLARED_KIND").values()).isEmpty();
        assertThat(one("{\"nested\":1}","TEXT").text("value")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"true","{}","[]","\"Infinity\"","\"NaN\"","\"1,23\"","\"\""})
    void invalidDecimalIsAbsent(String value) {
        assertThat(one(value,"DECIMAL").decimal("value")).isEmpty();
        assertThatThrownBy(() -> one(value,"DECIMAL").requiredDecimal("value")).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={"0","{}","[]","\"2026-08-28\"","\"2026-08-28T00:00:00\"","\"tomorrow\""})
    void instantsRequireAnExplicitOffsetAndValidRepresentation(String value) {
        assertThat(one(value,"INSTANT").instant("value")).isEmpty();
        assertThatThrownBy(() -> one(value,"INSTANT").requiredInstant("value")).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={"", "not-json","{} {}","{\"key\":1,\"key\":2}","null","1","true","[{},null]","[{},1]","[[]]"})
    void malformedOrMixedRecordPayloadIsRejectedAsAWhole(String payload) {
        assertThatThrownBy(() -> reader.read(bytes(payload),"",Map.of(),Map.of()))
                .isInstanceOf(PayloadReader.PayloadUnreadableException.class);
    }

    @Test
    void missingRecordPointerAndInvalidUtf8CannotNormalize() {
        for (String pointer:new String[]{"/missing","invalid-pointer","/value"}) {
            assertThatThrownBy(() -> reader.read(bytes("{\"value\":1}"),pointer,Map.of(),Map.of()))
                    .isInstanceOf(PayloadReader.PayloadUnreadableException.class);
        }
        assertThatThrownBy(() -> reader.read(new byte[]{(byte)0xc3,(byte)0x28},null,Map.of(),Map.of()))
                .isInstanceOf(PayloadReader.PayloadUnreadableException.class);
    }

    @Test
    void driftUsesEscapedPointersAndIsDeduplicatedAcrossRecords() {
        var result=reader.read(bytes("""
                [{"known":{"value":1,"new":2},"a/b~c":3},
                 {"known":{"value":2,"new":4},"a/b~c":5}]
                """),null,Map.of("number","/known/value"),Map.of("number","INTEGER"));
        assertThat(result.records()).hasSize(2);
        assertThat(result.unmappedPointers()).containsExactly("/known/new","/a~1b~0c");
        assertThat(reader.read(bytes("[]"),null,Map.of(),Map.of()).records()).isEmpty();
        assertThat(reader.read(bytes("{}"),null,Map.of("value","/absent"),Map.of()).records().getFirst().values()).isEmpty();
    }

    @Test
    void boundedRecordsAndDriftRefuseOversizedWorkWithoutPartialResults() {
        String payload="["+"{},".repeat(PayloadReader.MAXIMUM_RECORDS)+"{}]";
        assertThatThrownBy(() -> reader.read(bytes(payload),null,Map.of(),Map.of()))
                .isInstanceOf(PayloadReader.PayloadUnreadableException.class);
        var record=new java.util.LinkedHashMap<String,Integer>();
        for (int n=0;n<=PayloadReader.MAXIMUM_DRIFT_POINTERS;n++) record.put("newField"+n,n);
        assertThatThrownBy(() -> reader.read(JsonMapper.builder().build().writeValueAsBytes(record),null,Map.of(),Map.of()))
                .isInstanceOf(PayloadReader.PayloadUnreadableException.class);
    }

    private CanonicalRecord one(String value,String kind) {
        return reader.read(bytes("{\"value\":"+value+"}"),null,Map.of("value","/value"),Map.of("value",kind)).records().getFirst();
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
