package com.mimococo.marketops.operatingfacts.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.marketplaceintegration.IngestionJobView;
import com.mimococo.marketops.marketplaceintegration.RawObservationView;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.FactWriteRepository;
import com.mimococo.marketops.productlisting.ListingObservationSink;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FactRecorderTest {
    private final UUID organization=UUID.randomUUID(),store=UUID.randomUUID(),variant=UUID.randomUUID(),provenance=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-08-28T00:00:00Z");
    private final FactWriteRepository facts=mock(FactWriteRepository.class);
    private final ListingObservationSink listings=mock(ListingObservationSink.class);
    private final FactRecorder recorder=new FactRecorder(facts,listings,UUID::randomUUID,Clock.fixed(now,ZoneOffset.UTC));

    @BeforeEach
    void knownListing() {
        when(listings.record(anyList(),any())).thenReturn(Map.of("native-listing",Map.of("native-variant",variant)));
        when(facts.recordProvenance(any(),any(),anyString(),any(),any(),any(),any(),any(),any())).thenReturn(provenance);
    }

    @ParameterizedTest
    @ValueSource(strings={"LISTING","LISTING_HEALTH","PRICE","STOCK","TRAFFIC","SALES","RETURNS","FINANCE","ADVERTISING"})
    void everyDatasetRetainsSourceIdentityAndReplayUsesTheSameLogicalKey(String dataset) {
        var job=job(dataset); var record=new CanonicalRecord(fields());
        for (int n=0;n<2;n++) assertThat(recorder.record(job,observation(n==0?now:null),record)).isEqualTo(1);
        var calls=mockingDetails(facts).getInvocations().stream().filter(call -> call.getMethod().getName().startsWith("insert")).toList();
        if (dataset.equals("LISTING")) { assertThat(calls).isEmpty(); return; }
        String method=Map.of("LISTING_HEALTH","insertListingHealth","PRICE","insertPrice","STOCK","insertStock","TRAFFIC","insertTraffic",
                "SALES","insertSale","RETURNS","insertReturn","FINANCE","insertFee","ADVERTISING","insertAdvertising").get(dataset);
        assertThat(calls).hasSize(2).allSatisfy(call -> {
            assertThat(call.getMethod().getName()).isEqualTo(method);
            assertThat(Arrays.asList(call.getArguments())).contains(organization,provenance,variant,now);
        });
        var keys=calls.stream().map(call -> Arrays.stream(call.getArguments()).filter(v -> v instanceof String text && text.matches("[0-9a-f]{64}"))
                .map(Object::toString).findFirst().orElseThrow()).toList();
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
        if (Set.of("LISTING_HEALTH","SALES").contains(dataset)) assertThat(Arrays.asList(calls.getFirst().getArguments())).contains("brand-new-source-state");
        if (dataset.equals("RETURNS")) assertThat(Arrays.asList(calls.getFirst().getArguments())).contains("UNKNOWN","source-reason");
        if (dataset.equals("PRICE")) assertThat(Arrays.asList(calls.getFirst().getArguments())).contains("UNKNOWN",new BigDecimal("99999999999999.9999"));
        if (dataset.equals("FINANCE")) assertThat(Arrays.asList(calls.getFirst().getArguments())).contains("UNKNOWN");
    }

    @Test
    void absentNativeIdentityOrUnresolvedListingCannotCreateProvenanceOrFacts() {
        assertThat(recorder.record(job("PRICE"),observation(now),new CanonicalRecord(Map.of()))).isZero();
        assertThat(recorder.record(job("PRICE"),observation(now),new CanonicalRecord(Map.of("nativeListingKey","native-listing")))).isZero();
        verifyNoInteractions(facts,listings);
        when(listings.record(anyList(),any())).thenReturn(Map.of());
        assertThat(recorder.record(job("PRICE"),observation(now),new CanonicalRecord(fields()))).isZero();
        verifyNoInteractions(facts);
    }

    @ParameterizedTest
    @ValueSource(strings={"0.00001","100000000000000","-100000000000000","1E+999999999","1E-999999999"})
    void sourceMoneyCannotBeRoundedOrOverflowTheStoredFact(String amount) {
        var values=fields(); values.put("sellingPrice",new BigDecimal(amount));
        assertThatThrownBy(() -> recorder.record(job("PRICE"),observation(now),new CanonicalRecord(values)))
                .isInstanceOf(ArithmeticException.class);
        verifyNoInteractions(facts,listings);
    }

    @Test
    void representableTrailingZeroesAndZeroAreNotRejected() {
        var values=fields(); values.put("sellingPrice",new BigDecimal("0.000000")); values.put("listPrice",new BigDecimal("1.230000"));
        assertThat(recorder.record(job("PRICE"),observation(now),new CanonicalRecord(values))).isEqualTo(1);
    }

    @Test
    void quantityCannotWrapWhenAStorageColumnIsAnInteger() {
        for (String dataset:List.of("STOCK","SALES","RETURNS")) {
            var values=fields(); values.put("quantity",1L+Integer.MAX_VALUE); values.put("availableQuantity",1L+Integer.MAX_VALUE);
            assertThatThrownBy(() -> recorder.record(job(dataset),observation(now),new CanonicalRecord(values)))
                    .isInstanceOf(ArithmeticException.class);
        }
        assertThat(mockingDetails(facts).getInvocations()).noneSatisfy(call -> assertThat(call.getMethod().getName()).startsWith("insert"));
    }

    private Map<String,Object> fields() {
        var values=new HashMap<String,Object>();
        values.put("nativeListingKey","native-listing"); values.put("nativeVariantKey","native-variant");
        values.put("nativeStatus","brand-new-source-state"); values.put("currencyCode","RUB");
        for (String field:List.of("observedAt","occurredAt","periodStart","periodEnd")) values.put(field,now);
        values.put("periodEnd",now.plusSeconds(3600));
        values.put("fulfillmentModeCode","FBO"); values.put("quantity",2L); values.put("nativeOrderKey","order-key");
        values.put("nativeReturnKey","return-key"); values.put("returnKind","POST_DELIVERY_RETURN"); values.put("reasonNative","source-reason");
        values.put("feeCategory","COMMISSION"); values.put("nativeCampaignKey","campaign-key");
        for (String field:List.of("grossAmount","netAmount","amount","spendAmount")) values.put(field,new BigDecimal("12.3456"));
        values.put("sellingPrice",new BigDecimal("99999999999999.9999"));
        return values;
    }
    private IngestionJobView job(String kind) { return new IngestionJobView(UUID.randomUUID(),organization,"OZON",UUID.randomUUID(),store,kind,"logical-job","ACTIVE"); }
    private RawObservationView observation(Instant sourceTime) {
        return new RawObservationView(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"PAGE","page",sourceTime,"200","SUCCESS_BYTES",now,"1".repeat(64),32);
    }
}
