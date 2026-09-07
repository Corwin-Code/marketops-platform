package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation.PurposeEvidence;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.FreshnessProfile;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Applies the existing scoped Profile authority to each frozen Outcome input. */
@Component
class AdvertisingOutcomeFreshness {
    static String purpose(String stage) {
        return switch (stage) {
            case "OPERATIONAL" -> "EARLY_COMPLETED_SALES_OUTCOME";
            case "RETAINED" -> "FINAL_RETAINED_SALES_OUTCOME";
            default -> "SETTLED_FINANCIAL_OUTCOME";
        };
    }
    static String companyKind(String stage) {
        return switch (stage) {
            case "OPERATIONAL" -> "COMPANY_COMPLETED_SALE";
            case "RETAINED" -> "COMPANY_RETAINED_SALE";
            default -> "SETTLEMENT";
        };
    }
    static List<String> kinds(String stage) {
        return List.of(companyKind(stage), "OFFICIAL_AD_SPEND", "OFFICIAL_AD_TRAFFIC",
                "AD_LINKED_SALE_EVENT", "COST_AND_FEE", "AD_OBJECT_CONFIGURATION", "AFFECTED_SET",
                "SELLABILITY", "AVAILABILITY", "PRICE_AND_PROMOTION");
    }
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    AdvertisingOutcomeFreshness(JdbcClient jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    Map<String, FreshnessProfile> resolve(AdvertisingPolicyRepository policies, UUID organization, String stage,
            String platform, UUID store, UUID semantic, Instant at) {
        Map<String, FreshnessProfile> result=new LinkedHashMap<>();
        for (String kind:kinds(stage)) policies.resolveFreshness(organization,kind,purpose(stage),platform,store,semantic,at)
                .ifPresent(profile->result.put(kind,profile));
        return Map.copyOf(result);
    }

    PurposeEvidence qualify(UUID organization, UUID object, String stage, String kind, FreshnessProfile profile,
            Instant source, Instant accepted, boolean complete, boolean closed, BigDecimal coverage,
            Instant windowEnd, Instant at, boolean incident) {
        List<String> failures=new ArrayList<>();
        String purpose=purpose(stage);
        if (profile==null || !kind.equals(profile.evidenceKind()) || !purpose.equals(profile.decisionPurpose())) {
            failures.add("FRESHNESS_PROFILE_UNRESOLVED:"+purpose+":"+kind);
        } else {
            if (!validFrozen(organization,object,profile,at)) failures.add("FROZEN_FRESHNESS_VERSION_INVALID:"+purpose+":"+kind);
            if (profile.providerIncidentBlocks() && incident) failures.add("PROVIDER_INCIDENT_BLOCKS:"+purpose+":"+kind);
            failures.addAll(AdvertisingPurposeFreshness.bounds(profile,source,accepted,complete,closed,coverage,at));
            if (windowEnd!=null && (at.isBefore(windowEnd.plusSeconds(profile.expectedPublicationLagMinutes()*60L))
                    || profile.requiresCorrectionWindowClosed() && at.isBefore(windowEnd.plusSeconds(profile.correctionWindowMinutes()*60L)))) {
                failures.add("OUTCOME_PUBLICATION_OR_CORRECTION_NOT_MATURE:"+purpose+":"+kind);
            }
        }
        Instant expiry=profile==null?null:AdvertisingPurposeFreshness.expires(profile,source,accepted);
        return new PurposeEvidence(purpose,kind,profile==null?null:profile.id(),source,accepted,expiry,failures.isEmpty(),List.copyOf(failures));
    }

    private boolean validFrozen(UUID organization,UUID object,FreshnessProfile profile,Instant at) {
        // Never replace a frozen version with a newer, more favorable profile.
        // Revocation, scope mismatch and changed bounds invalidate its use.
        return jdbc.sql("SELECT ops.ad_outcome_frozen_profile_is_valid(CAST(:snapshot AS jsonb),:org,:object,:kind,:purpose,:at)")
                .param("snapshot",json.writeValueAsString(profile)).param("org",organization).param("object",object)
                .param("kind",profile.evidenceKind()).param("purpose",profile.decisionPurpose()).param("at",Timestamp.from(at))
                .query(Boolean.class).single();
    }

    /** Same canonical report rows, with only this stage's source fields consumed. */
    record CompanyCoverage(UUID snapshotId,Instant source,Instant acceptedAt,boolean complete) { }
    CompanyCoverage companyCoverage(UUID organization,UUID listing,String stage,Instant from,Instant to,Instant at) {
        return jdbc.sql("""
                SELECT r.id,r.accepted_at,
                  CASE :stage WHEN 'OPERATIONAL' THEN r.completed_source_updated_at
                    WHEN 'RETAINED' THEN least(r.retained_source_updated_at,r.return_source_updated_at)
                    ELSE least(r.retained_source_updated_at,r.return_source_updated_at,r.qc_source_updated_at) END source,
                  CASE :stage WHEN 'OPERATIONAL' THEN r.completed_coverage IN('COMPLETE','COMPLETE_ZERO')
                      AND r.completed_source_updated_at IS NOT NULL AND r.completed_source_updated_at<=:at
                    WHEN 'RETAINED' THEN r.retained_coverage IN('COMPLETE','COMPLETE_ZERO') AND r.return_coverage IN('COMPLETE_OBSERVED','COMPLETE_ZERO')
                      AND r.retained_source_updated_at IS NOT NULL AND r.retained_source_updated_at<=:at
                      AND r.return_source_updated_at IS NOT NULL AND r.return_source_updated_at<=:at
                    ELSE r.retained_coverage IN('COMPLETE','COMPLETE_ZERO') AND r.return_coverage IN('COMPLETE_OBSERVED','COMPLETE_ZERO')
                      AND r.qc_coverage IN('COMPLETE','COMPLETE_ZERO')
                      AND r.retained_source_updated_at IS NOT NULL AND r.retained_source_updated_at<=:at
                      AND r.return_source_updated_at IS NOT NULL AND r.return_source_updated_at<=:at
                      AND r.qc_source_updated_at IS NOT NULL AND r.qc_source_updated_at<=:at END complete
                FROM ledger.return_quality_evidence_snapshot r
                WHERE r.organization_id=:org AND r.platform_listing_variant_id=:listing AND r.report_window_start<=:from
                  AND r.report_window_end>=:to AND r.accepted_at<=:at AND NOT EXISTS(
                    SELECT 1 FROM ledger.return_quality_evidence_snapshot n WHERE n.supersedes_snapshot_id=r.id AND n.accepted_at<=:at)
                ORDER BY r.accepted_at DESC,r.id DESC LIMIT 1
                """).param("stage",stage).param("org",organization).param("listing",listing).param("from",Timestamp.from(from))
                .param("to",Timestamp.from(to)).param("at",Timestamp.from(at)).query((rs,index)->new CompanyCoverage(rs.getObject("id",UUID.class),
                        rs.getTimestamp("source")==null?null:rs.getTimestamp("source").toInstant(),rs.getTimestamp("accepted_at").toInstant(),rs.getBoolean("complete")))
                .optional().orElse(new CompanyCoverage(null,null,null,false));
    }

}
