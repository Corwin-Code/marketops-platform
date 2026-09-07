package com.mimococo.marketops;

import com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Fictional projection oracles for browser navigation and disclosure only.
 * These rows do not demonstrate canonical classification, qualification or
 * capacity; the canonical facts and orchestration integration tests do that.
 * No candidate, approval, command, baseline or reservation is manufactured here.
 */
final class AdvertisingBrowserQueueSeed {
    private AdvertisingBrowserQueueSeed() { }

    static Map<String,UUID> seed(ApplicationContext context,JdbcClient migration,AdvertisingR1Fixture.Graph graph) {
        Map<String,UUID> cases=new LinkedHashMap<>();cases.put("PROTECTION",graph.id("caseId"));
        for(String lane:java.util.List.of("DATA_REPAIR","OPTIMIZATION","WATCH")) {
            UUID object=UUID.randomUUID(),affected=UUID.randomUUID(),kase=UUID.randomUUID();
            String name="Fictional "+lane+" browser projection";
            migration.sql("""
                    INSERT INTO core.ad_native_object SELECT (jsonb_populate_record(NULL::core.ad_native_object,
                      to_jsonb(o)||jsonb_build_object('id',CAST(:id AS uuid),'native_object_key',:key,
                        'native_object_name',:name,'lineage_key',:key))).*
                    FROM core.ad_native_object o WHERE o.id=:source
                    """).param("id",object).param("key","browser-projection-"+lane).param("name",name)
                    .param("source",graph.id("object")).update();
            migration.sql("""
                    INSERT INTO core.ad_affected_set SELECT (jsonb_populate_record(NULL::core.ad_affected_set,
                      to_jsonb(a)||jsonb_build_object('id',CAST(:id AS uuid),'ad_native_object_id',CAST(:object AS uuid)))).*
                    FROM core.ad_affected_set a WHERE a.id=:source
                    """).param("id",affected).param("object",object).param("source",graph.id("affectedSet")).update();
            String cause=switch(lane) {
                case "DATA_REPAIR" -> "PROFIT_ECONOMICS_BLOCKED";
                case "OPTIMIZATION" -> "RECOVERABLE_ADVERTISING_PROFIT";
                default -> "IMMATURE_SIGNAL";
            };
            migration.sql("""
                    INSERT INTO mart.ad_case SELECT (jsonb_populate_record(NULL::mart.ad_case,to_jsonb(c)
                      ||jsonb_build_object('id',CAST(:id AS uuid),'ad_native_object_id',CAST(:object AS uuid),
                        'affected_set_id',CAST(:affected AS uuid),'case_key',:key,'lane',:lane,
                        'protection_tier',NULL,'cause_code',:cause,'bundle_id',NULL,
                        'calculation_id',gen_random_uuid(),'sustained_lane',NULL,'sustained_since',NULL,'sustained_cycles',0,
                        'rank_score',CASE :lane WHEN 'DATA_REPAIR' THEN 300000 WHEN 'OPTIMIZATION' THEN 200000 ELSE 100000 END,
                        'contribution_profit_state','NOT_AVAILABLE','contribution_profit_amount',NULL,
                        'profit_per_ad_rub_state','NOT_AVAILABLE','profit_per_ad_rub_value',NULL,
                        'max_cpc_state','NOT_AVAILABLE','max_cpc_amount',NULL,'recoverable_profit_amount',NULL,
                        'evidence_state','INCOMPLETE','blocker_codes',ARRAY['BROWSER_PROJECTION_ORACLE_NO_ACTION_PROOF']))).*
                    FROM mart.ad_case c WHERE c.id=:source
                    """).param("id",kase).param("object",object).param("affected",affected)
                    .param("key","browser-projection-"+lane).param("lane",lane).param("cause",cause)
                    .param("source",graph.id("caseId")).update();
            if(!lane.equals("WATCH")) context.getBean(AdvertisingResponsibilityIntake.class)
                    .ensureResponsibility(kase,graph.id("calculationRun"),lane.equals("DATA_REPAIR")?"FINANCE_ANALYST":"MARKETPLACE_OPERATOR");
            cases.put(lane,kase);
        }
        return Map.copyOf(cases);
    }
}
