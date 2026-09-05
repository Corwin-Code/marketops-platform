package com.mimococo.marketops;

import com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Dedicated browser pagination/keyboard read oracles, not classification or
 * economic proof. Fifty-six permitted Cases cross the real fifty-row boundary;
 * five earlier-sorting Cases in an ungranted Store must never consume a page.
 * No action candidate, baseline, approval, command or reservation is added.
 */
final class AdvertisingBrowserPaginationSeed {
    private AdvertisingBrowserPaginationSeed() { }

    record Projection(List<UUID> visibleCaseIds,List<UUID> watchCaseIds,
            List<UUID> dataRepairCaseIds,UUID hiddenStoreId,List<UUID> hiddenCaseIds) { }

    static Projection seed(ApplicationContext context,JdbcClient migration,AdvertisingR1Fixture.Graph graph) {
        migration.sql("UPDATE core.ad_native_object SET native_object_name=:name WHERE id=:id")
                .param("name","Синтетическая реклама — страница и клавиатура").param("id",graph.id("object")).update();
        var watch=new ArrayList<UUID>();var repair=new ArrayList<UUID>();
        for(int index=0;index<53;index++) watch.add(copyCase(migration,graph,graph.id("store"),"WATCH",
                "browser-page-watch-"+String.format(java.util.Locale.ROOT,"%03d",index),false));
        for(int index=0;index<2;index++) {
            UUID kase=copyCase(migration,graph,graph.id("store"),"DATA_REPAIR","browser-page-repair-"+index,false);
            repair.add(kase);
            context.getBean(AdvertisingResponsibilityIntake.class).ensureResponsibility(
                    kase,graph.id("calculationRun"),"FINANCE_ANALYST");
        }
        UUID hiddenStore=UUID.randomUUID();
        migration.sql("""
                INSERT INTO core.store SELECT (jsonb_populate_record(NULL::core.store,to_jsonb(s)
                  ||jsonb_build_object('id',CAST(:id AS uuid),'code','browser-page-hidden-store',
                    'display_name','Synthetic ungranted pagination Store'))).*
                FROM core.store s WHERE s.id=:source
                """).param("id",hiddenStore).param("source",graph.id("store")).update();
        var hidden=new ArrayList<UUID>();
        for(int index=0;index<5;index++) hidden.add(copyCase(migration,graph,hiddenStore,"DATA_REPAIR",
                "000-hidden-page-"+index,true));
        var visible=new ArrayList<UUID>();visible.add(graph.id("caseId"));visible.addAll(repair);visible.addAll(watch);
        return new Projection(List.copyOf(visible),List.copyOf(watch),List.copyOf(repair),hiddenStore,List.copyOf(hidden));
    }

    private static UUID copyCase(JdbcClient migration,AdvertisingR1Fixture.Graph graph,UUID store,
            String lane,String key,boolean hidden) {
        UUID object=UUID.randomUUID(),affected=hidden?null:UUID.randomUUID(),kase=UUID.randomUUID();
        migration.sql("""
                INSERT INTO core.ad_native_object SELECT (jsonb_populate_record(NULL::core.ad_native_object,
                  to_jsonb(o)||jsonb_build_object('id',CAST(:id AS uuid),'store_id',CAST(:store AS uuid),
                    'native_object_key',:key,'native_object_name',:name,'lineage_key',:key))).*
                FROM core.ad_native_object o WHERE o.id=:source
                """).param("id",object).param("store",store).param("key",key)
                .param("name",hidden?"Synthetic ungranted Case":"Синтетическая страница "+key)
                .param("source",graph.id("object")).update();
        if(!hidden) migration.sql("""
                INSERT INTO core.ad_affected_set SELECT (jsonb_populate_record(NULL::core.ad_affected_set,
                  to_jsonb(a)||jsonb_build_object('id',CAST(:id AS uuid),'ad_native_object_id',CAST(:object AS uuid)))).*
                FROM core.ad_affected_set a WHERE a.id=:source
                """).param("id",affected).param("object",object).param("source",graph.id("affectedSet")).update();
        String cause=hidden?"AFFECTED_SET_UNRESOLVED":lane.equals("DATA_REPAIR")?"PROFIT_ECONOMICS_BLOCKED":"IMMATURE_SIGNAL";
        migration.sql("""
                INSERT INTO mart.ad_case SELECT (jsonb_populate_record(NULL::mart.ad_case,to_jsonb(c)
                  ||jsonb_build_object('id',CAST(:id AS uuid),'store_id',CAST(:store AS uuid),
                    'ad_native_object_id',CAST(:object AS uuid),'affected_set_id',CAST(:affected AS uuid),
                    'case_key',:key,'lane',:lane,'protection_tier',NULL,'cause_code',:cause,'bundle_id',NULL,
                    'calculation_id',gen_random_uuid(),'sustained_lane',NULL,'sustained_since',NULL,'sustained_cycles',0,
                    'rank_score',0,'contribution_profit_state','NOT_AVAILABLE','contribution_profit_amount',NULL,
                    'profit_per_ad_rub_state','NOT_AVAILABLE','profit_per_ad_rub_value',NULL,
                    'max_cpc_state','NOT_AVAILABLE','max_cpc_amount',NULL,'recoverable_profit_amount',NULL,
                    'evidence_state','INCOMPLETE','blocker_codes',jsonb_build_array(:blocker)))).*
                FROM mart.ad_case c WHERE c.id=:source
                """).param("id",kase).param("store",store).param("object",object).param("affected",affected)
                .param("key",key).param("lane",lane).param("cause",cause)
                .param("blocker",hidden?"AFFECTED_SET_NEVER_RESOLVED":"BROWSER_PROJECTION_ORACLE_NO_ACTION_PROOF")
                .param("source",graph.id("caseId")).update();
        return kase;
    }
}
