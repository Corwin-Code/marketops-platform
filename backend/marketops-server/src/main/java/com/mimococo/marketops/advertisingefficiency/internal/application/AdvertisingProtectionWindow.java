package com.mimococo.marketops.advertisingefficiency.internal.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Point-in-time source history for the exact action window; no repair authority. */
@Component
class AdvertisingProtectionWindow {
    record Proof(boolean complete, boolean safe, Instant source, Instant accepted, List<UUID> evidenceIds) { }
    record Row(UUID id,Instant effective,Instant source,Instant accepted,String mode,String state,Integer quantity) { }
    private final JdbcClient jdbc;
    AdvertisingProtectionWindow(JdbcClient jdbc) { this.jdbc=jdbc; }

    List<UUID> affectedListings(UUID organization,UUID object,UUID affectedSet,List<AdvertisingOutcomeEvidenceService.Unit> frozenUnits) {
        record Scope(List<UUID> listings,List<UUID> products,UUID store) { }
        var scope=jdbc.sql("""
                SELECT a.platform_listing_variant_ids,a.product_variant_ids,o.store_id FROM core.ad_affected_set a
                JOIN core.ad_native_object o ON o.id=a.ad_native_object_id AND o.organization_id=a.organization_id
                WHERE a.id=:id AND a.organization_id=:org AND o.id=:object AND a.resolution_state='COMPLETE'
                """).param("id",affectedSet).param("org",organization).param("object",object)
                .query((rs,index)->new Scope(List.of((UUID[])rs.getArray("platform_listing_variant_ids").getArray()),
                        List.of((UUID[])rs.getArray("product_variant_ids").getArray()),rs.getObject("store_id",UUID.class))).optional();
        if(scope.isEmpty()) return List.of();
        if(!scope.get().listings().isEmpty()) return scope.get().listings();
        // Product-scoped objects may not name native listing IDs. The Planner
        // already froze their canonical company listing mapping before action;
        // require every affected product at the advertising store, never guess
        // a new mapping from today's more favorable state.
        var units=frozenUnits.stream().filter(unit->unit.storeId().equals(scope.get().store())
                && scope.get().products().contains(unit.productVariantId())).toList();
        if(!units.stream().map(AdvertisingOutcomeEvidenceService.Unit::productVariantId).distinct().toList().containsAll(scope.get().products())) return List.of();
        return units.stream().map(AdvertisingOutcomeEvidenceService.Unit::listingVariantId).distinct().toList();
    }

    Proof read(UUID organization,List<UUID> listings,String kind,Instant from,Instant to,Instant at) {
        boolean complete=!listings.isEmpty(),safe=true;
        List<Row> all=new ArrayList<>();
        for (UUID listing:listings) {
            // Table and field expressions are a fixed evidence-kind mapping, never caller SQL.
            boolean stock="AVAILABILITY".equals(kind);
            boolean price="PRICE_AND_PROMOTION".equals(kind);
            String table=stock?"core.listing_stock_observation":price?"core.listing_price_observation":"core.listing_health_observation";
            String fields=stock?"o.fulfillment_mode_code mode,NULL::text state,o.available_quantity quantity":price?
                    "''::text mode,CASE WHEN coalesce(o.discount_price,o.selling_price,o.list_price) IS NOT NULL AND o.promotion_active IN('YES','NO') THEN concat(coalesce(o.discount_price,o.selling_price,o.list_price),':',o.currency_code,':',o.promotion_active) END state,NULL::integer quantity"
                    :"''::text mode,o.sellable state,NULL::integer quantity";
            var rows=jdbc.sql("SELECT o.id,o.observed_at,p.source_time,p.ingestion_time,"+fields+" FROM "+table+" o "
                    +"JOIN core.fact_provenance p ON p.id=o.provenance_id WHERE o.organization_id=:org "
                    +"AND o.platform_listing_variant_id=:listing AND o.observed_at<=:to AND p.ingestion_time<=:at "
                    +"ORDER BY o.observed_at,p.ingestion_time,o.id")
                    .param("org",organization).param("listing",listing).param("to",Timestamp.from(to)).param("at",Timestamp.from(at))
                    .query((rs,index)->new Row(rs.getObject("id",UUID.class),rs.getTimestamp("observed_at").toInstant(),
                            rs.getTimestamp("source_time")==null?null:rs.getTimestamp("source_time").toInstant(),rs.getTimestamp("ingestion_time").toInstant(),
                            rs.getString("mode"),rs.getString("state"),rs.getObject("quantity",Integer.class))).list();
            Map<String,Row> current=new HashMap<>();
            rows.stream().filter(row->!row.effective().isAfter(from)).forEach(row->current.put(row.mode(),row));
            if(current.isEmpty()) { complete=false;safe=false; }
            for(Row selected:current.values()) {
                var peers=rows.stream().filter(row->row.mode().equals(selected.mode()) && row.effective().equals(selected.effective())).toList();
                if(peers.stream().anyMatch(row->!sameValue(row,selected))) complete=false;
            }
            complete &= known(current,stock,price);
            all.addAll(current.values());
            String initial=current.isEmpty()?null:current.values().iterator().next().state();
            safe &= price ? initial!=null : safe(current,stock);
            for (Instant transition:rows.stream().map(Row::effective).filter(time->time.isAfter(from)).distinct().toList()) {
                List<Row> updates=rows.stream().filter(row->row.effective().equals(transition)).toList();
                // An identical retrospective source refresh may replace an old
                // report. Conflicting values still cannot be resolved by UUID.
                Map<String,Row> refreshed=new HashMap<>();
                for(Row row:updates) {
                    Row previous=refreshed.put(row.mode(),row);
                    if(previous!=null && !sameValue(previous,row)) complete=false;
                }
                current.putAll(refreshed);all.addAll(refreshed.values());
                complete &= known(current,stock,price);
                safe &= price ? initial!=null && current.values().stream().allMatch(row->initial.equals(row.state())) : safe(current,stock);
            }
        }
        complete &= all.stream().allMatch(row->row.source()!=null && !row.source().isAfter(at)
                && row.accepted()!=null && !row.accepted().isAfter(at));
        return new Proof(complete,safe,
                all.stream().map(Row::source).filter(Objects::nonNull).min(Instant::compareTo).orElse(null),
                all.stream().map(Row::accepted).filter(Objects::nonNull).min(Instant::compareTo).orElse(null),
                all.stream().map(Row::id).distinct().sorted().toList());
    }

    private static boolean sameValue(Row left,Row right) {
        return Objects.equals(left.state(),right.state()) && Objects.equals(left.quantity(),right.quantity());
    }

    private static boolean known(Map<String,Row> current,boolean stock,boolean price) {
        return !current.isEmpty() && (stock?current.values().stream().allMatch(row->row.quantity()!=null && row.quantity()>=0)
                :price?current.values().stream().allMatch(row->row.state()!=null)
                :current.values().stream().allMatch(row->"YES".equals(row.state()) || "NO".equals(row.state())));
    }

    private static boolean safe(Map<String,Row> current,boolean stock) {
        if(current.isEmpty()) return false;
        return stock ? current.values().stream().allMatch(row->row.quantity()!=null && row.quantity()>=0)
                && current.values().stream().mapToLong(row->row.quantity()==null?0:row.quantity()).sum()>0
                : current.values().stream().allMatch(row->"YES".equals(row.state()));
    }

    boolean mappingStable(UUID organization,List<AdvertisingOutcomeEvidenceService.Unit> units,Instant from,Instant to,Instant at) {
        if(units.isEmpty()) return false;
        for(var unit:units) {
            boolean valid=jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM core.listing_mapping m WHERE m.organization_id=:org
                      AND m.product_variant_id=:product AND m.platform_listing_variant_id=:listing
                      AND m.status IN('ACTIVE','ENDED') AND m.effective_from<=:from
                      AND (m.effective_to IS NULL OR m.effective_to>=:to) AND m.created_at<=:at)
                    AND NOT EXISTS(SELECT 1 FROM core.mapping_conflict c WHERE c.organization_id=:org
                      AND c.platform_listing_variant_id=:listing AND c.detected_at<=least(CAST(:to AS timestamptz),CAST(:at AS timestamptz))
                      AND (c.resolved_at IS NULL OR c.resolved_at>:from))
                    """).param("org",organization).param("product",unit.productVariantId()).param("listing",unit.listingVariantId())
                    .param("from",Timestamp.from(from)).param("to",Timestamp.from(to)).param("at",Timestamp.from(at)).query(Boolean.class).single();
            if(!valid) return false;
        }
        return !jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM core.listing_mapping m WHERE m.organization_id=:org
                  AND m.product_variant_id=ANY(:products) AND NOT(m.platform_listing_variant_id=ANY(:listings))
                  AND m.status IN('ACTIVE','ENDED') AND m.effective_from<:to
                  AND (m.effective_to IS NULL OR m.effective_to>:from) AND m.created_at<=:at)
                """).param("org",organization).param("products",units.stream().map(AdvertisingOutcomeEvidenceService.Unit::productVariantId).distinct().toArray(UUID[]::new))
                .param("listings",units.stream().map(AdvertisingOutcomeEvidenceService.Unit::listingVariantId).distinct().toArray(UUID[]::new))
                .param("from",Timestamp.from(from)).param("to",Timestamp.from(to)).param("at",Timestamp.from(at)).query(Boolean.class).single();
    }

    boolean exactScope(UUID organization,UUID object,UUID affectedSet,Instant from,Instant to,Instant at) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM core.ad_affected_set frozen
                  WHERE frozen.id=:affected AND frozen.organization_id=:org AND frozen.ad_native_object_id=:object
                    AND frozen.resolution_state='COMPLETE' AND frozen.resolved_at<=:from AND frozen.created_at<=:at
                    AND NOT EXISTS(SELECT 1 FROM core.ad_affected_set later WHERE later.organization_id=:org
                      AND later.ad_native_object_id=:object AND later.id<>frozen.id
                      AND later.created_at<=:at
                      AND later.resolved_at<=least(CAST(:to AS timestamptz),CAST(:at AS timestamptz)) AND later.resolved_at>=frozen.resolved_at
                      AND (later.resolution_state<>'COMPLETE' OR later.affected_set_digest<>frozen.affected_set_digest
                        OR later.product_variant_ids<>frozen.product_variant_ids OR later.platform_listing_variant_ids<>frozen.platform_listing_variant_ids)))
                """).param("affected",affectedSet).param("org",organization).param("object",object)
                .param("from",Timestamp.from(from)).param("to",Timestamp.from(to)).param("at",Timestamp.from(at)).query(Boolean.class).single();
    }

    Proof configurationProof(UUID organization,UUID object,Instant from,Instant to,Instant at) {
        var rows=jdbc.sql("""
                SELECT c.id,c.source_time,p.ingestion_time FROM core.ad_object_configuration_observation c
                JOIN core.fact_provenance p ON p.id=c.provenance_id
                WHERE c.organization_id=:org AND c.ad_native_object_id=:object AND p.ingestion_time<=:at
                  AND c.observed_at<=:to AND (c.observed_at>=:from OR c.observed_at=(
                    SELECT max(prior.observed_at) FROM core.ad_object_configuration_observation prior
                    JOIN core.fact_provenance source ON source.id=prior.provenance_id
                    WHERE prior.organization_id=:org AND prior.ad_native_object_id=:object
                      AND prior.observed_at<=:from AND source.ingestion_time<=:at))
                """).param("org",organization).param("object",object).param("from",Timestamp.from(from))
                .param("to",Timestamp.from(to)).param("at",Timestamp.from(at)).query((rs,index)->new Row(rs.getObject("id",UUID.class),null,
                        rs.getTimestamp("source_time")==null?null:rs.getTimestamp("source_time").toInstant(),
                        rs.getTimestamp("ingestion_time").toInstant(),null,null,null)).list();
        boolean complete=!rows.isEmpty() && rows.stream().allMatch(row->row.source()!=null && !row.source().isAfter(at));
        return new Proof(complete,complete,rows.stream().map(Row::source).filter(Objects::nonNull).min(Instant::compareTo).orElse(null),
                rows.stream().map(Row::accepted).min(Instant::compareTo).orElse(null),rows.stream().map(Row::id).sorted().toList());
    }

    boolean configurationWindow(UUID organization,UUID object,Instant from,Instant to,Instant at) {
        return jdbc.sql("""
                WITH relevant AS (
                  (SELECT c.* FROM core.ad_object_configuration_observation c WHERE c.organization_id=:org
                    AND c.ad_native_object_id=:object AND c.observed_at<=:from AND c.source_time<=:at
                    AND EXISTS(SELECT 1 FROM core.fact_provenance p WHERE p.id=c.provenance_id AND p.ingestion_time<=:at)
                    ORDER BY c.observed_at DESC LIMIT 1)
                  UNION ALL
                  SELECT c.* FROM core.ad_object_configuration_observation c WHERE c.organization_id=:org
                    AND c.ad_native_object_id=:object AND c.observed_at>:from AND c.observed_at<=least(CAST(:to AS timestamptz),CAST(:at AS timestamptz))
                    AND EXISTS(SELECT 1 FROM core.fact_provenance p WHERE p.id=c.provenance_id AND p.ingestion_time<=:at)
                )
                SELECT count(*)>0 AND bool_or(observed_at<=:from) AND bool_and(observed_bid_amount IS NOT NULL
                  AND evidence_grade IN('OFFICIAL_API_READBACK','OFFICIAL_CONFIGURATION_EXPORT') AND source_time<=:at)
                  AND count(DISTINCT lineage_generation)=1 AND count(DISTINCT semantic_profile_id)=1 FROM relevant
                """).param("org",organization).param("object",object).param("from",Timestamp.from(from))
                .param("to",Timestamp.from(to)).param("at",Timestamp.from(at)).query(Boolean.class).single();
    }

    boolean configurationIdentity(UUID organization,UUID object,Instant from,Instant to,Instant at,UUID semantic,int generation) {
        return jdbc.sql("""
                WITH known AS (
                  SELECT c.* FROM core.ad_object_configuration_observation c
                  JOIN core.fact_provenance p ON p.id=c.provenance_id
                  WHERE c.organization_id=:org AND c.ad_native_object_id=:object
                    AND c.observed_at<=:to AND p.ingestion_time<=:at
                ), relevant AS (
                  SELECT c.* FROM known c WHERE c.observed_at>:from
                    OR c.observed_at=(SELECT max(prior.observed_at) FROM known prior WHERE prior.observed_at<=:from)
                )
                SELECT count(*)>0 AND bool_or(observed_at<=:from)
                  AND bool_and(semantic_profile_id=:semantic AND lineage_generation=:generation)
                FROM relevant
                """).param("org",organization).param("object",object).param("from",Timestamp.from(from))
                .param("to",Timestamp.from(to)).param("at",Timestamp.from(at)).param("semantic",semantic)
                .param("generation",generation).query(Boolean.class).single();
    }
}
