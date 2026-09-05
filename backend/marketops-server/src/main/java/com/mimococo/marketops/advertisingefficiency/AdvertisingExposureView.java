package com.mimococo.marketops.advertisingefficiency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The same six independent measurements used by admission, for every applicable envelope. */
public record AdvertisingExposureView(Instant measuredAt,List<Envelope> envelopes,
        List<UUID> unresolvedStoreIds,boolean resolved,String status) {
    public AdvertisingExposureView {
        envelopes=List.copyOf(envelopes);unresolvedStoreIds=List.copyOf(unresolvedStoreIds);
    }
    public AdvertisingExposureView(Instant measuredAt,List<Envelope> envelopes,List<UUID> unresolvedStoreIds) {
        this(measuredAt,envelopes,unresolvedStoreIds,!envelopes.isEmpty()&&unresolvedStoreIds.isEmpty(),
                !envelopes.isEmpty()&&unresolvedStoreIds.isEmpty()?"MEASURED":"UNRESOLVED");
    }
    /** Scope and exact policy version remain attached to the measurements. */
    public record Envelope(UUID envelopeId,int policyVersion,String scopeKind,String platformCode,
            UUID storeId,String currencyCode,Integer measurementWindowHours,Integer retainedWindowDays,
            Map<String,Axis> axes,List<String> reasons) {
        public Envelope {axes=Map.copyOf(axes);reasons=List.copyOf(reasons);}
    }
    /** Null measurement and UNKNOWN remain distinct from an observed zero. */
    public record Axis(BigDecimal usage,BigDecimal limit,BigDecimal available,BigDecimal reserved,
            BigDecimal companySales,BigDecimal affectedSales,String state,String unit,Integer windowHours,
            String aggregationBasis,Integer conservativeBoundaryReportCount) { }
}
