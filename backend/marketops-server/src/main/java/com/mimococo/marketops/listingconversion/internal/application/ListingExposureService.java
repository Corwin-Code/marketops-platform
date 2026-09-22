package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.analyticsdecision.CanonicalScopeMetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** One consumer of the Metric owner's actual-exposure projection for every listing decision stage. */
@Service
class ListingExposureService {
    private final CanonicalScopeMetricQuery scopeMetrics;
    ListingExposureService(CanonicalScopeMetricQuery scopeMetrics) { this.scopeMetrics=scopeMetrics; }
    record ExposureBasis(Boolean material,Map<String,Object> evidence) { }

    ExposureBasis assess(ListingFactRepository.ListingContext listing,String affectedSetDigest,List<UUID> members,
                                        CalibrationService.Outcome calibration,Instant at) {
        Map<String,Object> basis=new java.util.LinkedHashMap<>();
        basis.put("model","LC_RETAINED_SALES_EXPOSURE_1");
        basis.put("affectedSetDigest",affectedSetDigest);
        basis.put("assessedAt",at);
        if (!calibration.ok()) {
            basis.put("state","CALIBRATION_UNRESOLVED");
            return new ExposureBasis(null,basis);
        }
        var ordinary=calibration.resolved().values().get("ORDINARY_TRIGGER_EXPOSURE");
        var material=calibration.resolved().values().get("MATERIAL_TRIGGER_EXPOSURE");
        var freshness=calibration.resolved().values().get("FRESHNESS_RULE");
        var rule=freshness==null || freshness.json()==null?null:freshness.json().get("materialityExposure");
        if (ordinary==null || material==null || ordinary.windowDays()==null
                || !ordinary.windowDays().equals(material.windowDays())
                || !List.of(7,14,30).contains(ordinary.windowDays()) || rule==null
                || !rule.path("maximumVerificationAgeSeconds").isIntegralNumber()
                || !rule.path("maximumPeriodEndAgeSeconds").isIntegralNumber()
                || !rule.path("maximumVerificationAgeSeconds").canConvertToLong()
                || !rule.path("maximumPeriodEndAgeSeconds").canConvertToLong()
                || rule.path("maximumVerificationAgeSeconds").asLong()<=0
                || rule.path("maximumPeriodEndAgeSeconds").asLong()<=0) {
            basis.put("state","EXPOSURE_RULE_UNQUALIFIED");
            return new ExposureBasis(null,basis);
        }
        var result=scopeMetrics.exposure(new com.mimococo.marketops.analyticsdecision.CanonicalScopeMetricQuery.ExposureScope(
                listing.organizationId(),listing.storeId(),members,
                MetricWindow.valueOf("D"+ordinary.windowDays()),at,rule.path("maximumVerificationAgeSeconds").asLong(),
                rule.path("maximumPeriodEndAgeSeconds").asLong()));
        basis.put("state",result.available()?"QUALIFIED":"EXPOSURE_UNRESOLVED");
        basis.put("projection",result);
        Integer materialComparison=result.compareWith(material.numeric());
        Integer ordinaryComparison=result.compareWith(ordinary.numeric());
        Boolean axis=materialComparison==null || ordinaryComparison==null?null
                : materialComparison>=0?Boolean.TRUE:ordinaryComparison<=0?Boolean.FALSE:null;
        if (axis==null && result.available()) basis.put("state","EXPOSURE_BETWEEN_ACCEPTED_BOUNDS");
        return new ExposureBasis(axis,basis);
    }

}
