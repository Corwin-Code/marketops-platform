package com.mimococo.marketops;

import com.mimococo.marketops.identityaccess.*;
import com.mimococo.marketops.identityaccess.internal.application.UserAdministrationService;
import com.mimococo.marketops.identityaccess.internal.web.BrowserSigningFixture;
import com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** Isolated browser entry point. Actual servlet/JWT/IAM/SQL; no marketplace adapters or workers. */
public final class AdvertisingBrowserFixtureApplication {
    public static final UUID STORE=UUID.fromString("00000000-0000-0000-0000-0000000000d3");
    private AdvertisingBrowserFixtureApplication() { }
    public static void main(String[] args) throws Exception {
        String config=System.getenv("MARKETOPS_AD_BROWSER_CONFIG");
        if (!"ISOLATED_SYNTHETIC_DATABASE".equals(System.getenv("MARKETOPS_BROWSER_FIXTURE"))
                || config==null || !Path.of(config).toAbsolutePath().normalize().startsWith(Path.of("/tmp"))
                && !Path.of(config).toAbsolutePath().normalize().startsWith(Path.of("/private/tmp"))
                || !Files.isRegularFile(Path.of(config))
                || !("file:"+config).equals(System.getenv("SPRING_CONFIG_IMPORT"))) {
            throw new IllegalStateException("Explicit /tmp isolated advertising browser configuration required");
        }
        var app=new SpringApplication(MarketOpsServerApplication.class,BrowserSigningFixture.class);
        app.addInitializers(initial->{
            var env=initial.getEnvironment(); String url=env.getRequiredProperty("spring.datasource.url");
            if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/marketops") || url.contains(":5432/"))
                throw new IllegalStateException("Advertising browser fixture requires a dedicated loopback database port");
            var jdbc=JdbcClient.create(new DriverManagerDataSource(url,env.getRequiredProperty("spring.flyway.user"),
                    env.getRequiredProperty("spring.flyway.password")));
            if(jdbc.sql("SELECT to_regclass('core.organization') IS NOT NULL").query(Boolean.class).single()
                    && jdbc.sql("SELECT count(*) FROM core.organization").query(Long.class).single()!=0)
                throw new IllegalStateException("Advertising browser fixture refuses a nonempty database");
        });
        var context=app.run("--spring.profiles.active=local","--server.address=127.0.0.1",
                "--marketops.identity.oidc.issuer-uri="+BrowserSigningFixture.ISSUER,
                "--marketops.identity.oidc.jwk-set-uri=https://127.0.0.1/unused-secondary-decoder",
                "--marketops.identity.oidc.audience="+BrowserSigningFixture.AUDIENCE,
                "--marketops.advertising.worker-enabled=false","--marketops.ad-bid-write.worker-enabled=false",
                "--marketops.price-write.worker-enabled=false","--marketops.acquisition.scheduler-enabled=false",
                "--marketops.diagnostic-export.worker-enabled=false",
                "--marketops.object-storage.root-directory="+Files.createTempDirectory(Path.of(config).getParent(),"custody-"));
        try {
            var env=context.getEnvironment();
            var migration=new DriverManagerDataSource(env.getRequiredProperty("spring.datasource.url"),
                    env.getRequiredProperty("spring.flyway.user"),env.getRequiredProperty("spring.flyway.password"));
            var jdbc=JdbcClient.create(migration);
            if(jdbc.sql("SELECT count(*) FROM core.organization").query(Long.class).single()!=0)
                throw new IllegalStateException("Fixture data must be empty after migration");
            var graph=AdvertisingR1Fixture.seedBrowser(migration,STORE);
            jdbc.sql("UPDATE core.store SET timezone='Europe/Moscow' WHERE id=:id").param("id",graph.id("store")).update();
            jdbc.sql("UPDATE iam.identity_provider SET issuer=:issuer WHERE id=:id")
                    .param("issuer",BrowserSigningFixture.ISSUER).param("id",graph.id("provider")).update();
            jdbc.sql("UPDATE iam.user_account SET credentials_valid_from=now()-interval '1 day' WHERE organization_id=:org")
                    .param("org",graph.id("organization")).update();
            var users=context.getBean(UserAdministrationService.class);
            var roles=Map.of("MAKER",BusinessRoleCode.MARKETPLACE_OPERATOR,"OPS_LEAD",BusinessRoleCode.OPS_LEAD,"OWNER",BusinessRoleCode.OWNER);
            var ids=Map.of("MAKER",graph.id("executorUser"),"OPS_LEAD",graph.id("verifierUser"),"OWNER",graph.id("ownerUser"));
            for(var entry:roles.entrySet()) {
                UUID user=ids.get(entry.getKey());
                if(entry.getValue()==BusinessRoleCode.MARKETPLACE_OPERATOR) users.assignRole("synthetic-ad-browser",user,entry.getValue(),null);
                for(var action:List.of(ActionScopeCode.ADVERTISING_VIEW,ActionScopeCode.ADVERTISING_TASK_ACT,
                        ActionScopeCode.ADVERTISING_EXCEPTION_REQUEST))
                    users.grantScope("synthetic-ad-browser",user,action,ResourceScopeType.ORGANIZATION,graph.id("organization"),null);
                if(entry.getValue()!=BusinessRoleCode.MARKETPLACE_OPERATOR)
                    users.grantScope("synthetic-ad-browser",user,ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW,
                            ResourceScopeType.ORGANIZATION,graph.id("organization"),null);
            }
            jdbc.sql("UPDATE core.ad_human_slo_profile SET staffed_coverage_enabled=true,staffed_coverage_timezone='Etc/UTC',staffed_coverage_start_minute=0,staffed_coverage_end_minute=1439 WHERE id=:id")
                    .param("id",graph.id("humanSlo")).update();
            jdbc.sql("""
                    INSERT INTO core.ad_reporting_calendar(id,organization_id,policy_version,scope_kind,reporting_timezone,
                      daily_cut_minute,operating_days,weekly_cut_weekday,weekly_cut_minute,late_revision_horizon_hours,
                      owner_user_id,reason,evidence_reference,effective_from,status,created_at)
                    VALUES(gen_random_uuid(),:org,1,'ORGANIZATION','Etc/UTC',0,ARRAY[1,2,3,4,5,6,7]::smallint[],1,0,24,
                      :owner,'Isolated fictional browser calendar','fixture://ad-browser/calendar',now()-interval '1 day','ACTIVE',now())
                    """).param("org",graph.id("organization")).param("owner",graph.id("ownerUser")).update();
            UUID task=context.getBean(AdvertisingResponsibilityIntake.class).ensureResponsibility(graph.id("caseId"),graph.id("calculationRun"),"MARKETPLACE_OPERATOR");
            var queueCases=AdvertisingBrowserQueueSeed.seed(context,jdbc,graph);
            var scenarios=new LinkedHashMap<String,Scenario>();
            scenarios.put("API",new Scenario(graph,task,null));
            for(String platform:List.of("OZON","WILDBERRIES")) {
                var original=AdvertisingR1Fixture.seedManualBrowser(migration,platform,UUID.randomUUID());
                var manualIds=new java.util.HashMap<>(original.ids());manualIds.put("provider",graph.id("provider"));
                var manual=new AdvertisingR1Fixture.Graph(Map.copyOf(manualIds),platform);
                configureManualUsers(jdbc,users,manual);
                configureManualCoverage(jdbc,manual);
                UUID manualTask=context.getBean(AdvertisingResponsibilityIntake.class)
                        .ensureResponsibility(manual.id("caseId"),manual.id("calculationRun"),"MARKETPLACE_OPERATOR");
                UUID manualPolicy=AdvertisingManualBrowserSeed.seed(context,jdbc,manual);
                scenarios.put("MANUAL_"+platform,new Scenario(manual,manualTask,manualPolicy));
            }
            var exceptionOriginal=AdvertisingR1Fixture.seedBrowser(migration,UUID.randomUUID());
            var exceptionIds=new java.util.HashMap<>(exceptionOriginal.ids());exceptionIds.put("provider",graph.id("provider"));
            var exceptionGraph=new AdvertisingR1Fixture.Graph(Map.copyOf(exceptionIds),exceptionOriginal.platform());
            configureManualUsers(jdbc,users,exceptionGraph);
            configureManualCoverage(jdbc,exceptionGraph);
            UUID exceptionTask=context.getBean(AdvertisingResponsibilityIntake.class).ensureResponsibility(
                    exceptionGraph.id("caseId"),exceptionGraph.id("calculationRun"),"MARKETPLACE_OPERATOR");
            scenarios.put("EXCEPTION",new Scenario(exceptionGraph,exceptionTask,null));
            for(String historyName:List.of("HISTORY_UNKNOWN","HISTORY_MISMATCH","HISTORY_REGRESSION","HISTORY_EXPIRED")) {
                var original=AdvertisingBrowserHistorySeed.seed(context,migration,historyName);
                var historyIds=new java.util.HashMap<>(original.ids());historyIds.put("provider",graph.id("provider"));
                var history=new AdvertisingR1Fixture.Graph(Map.copyOf(historyIds),original.platform());
                // Seal first; changing the display users/coverage never rewrites the frozen approval.
                configureManualUsers(jdbc,users,history);
                configureManualCoverage(jdbc,history);
                UUID historyTask=context.getBean(AdvertisingResponsibilityIntake.class).ensureResponsibility(
                        history.id("caseId"),history.id("calculationRun"),"MARKETPLACE_OPERATOR");
                scenarios.put(historyName,new Scenario(history,historyTask,null));
            }
            users.grantScope("synthetic-ad-browser",graph.id("verifierUser"),ActionScopeCode.ADVERTISING_POLICY_MANAGE,
                    ResourceScopeType.ORGANIZATION,graph.id("organization"),null);
            var mapper=context.getBean(ObjectMapper.class);
            var driver=HttpServer.create(new InetSocketAddress("127.0.0.1",8082),0);
            driver.createContext("/advertising-fixture",exchange->{
                try {
                    if(!"GET".equals(exchange.getRequestMethod())) {exchange.sendResponseHeaders(405,-1);return;}
                    Map<String,String> query=queryParameters(exchange.getRequestURI().getRawQuery());
                    String role=query.getOrDefault("role","MAKER");
                    Scenario scenario=scenarios.get(query.getOrDefault("scenario","API"));
                    if(!ids.containsKey(role) || scenario==null) {exchange.sendResponseHeaders(400,-1);return;}
                    var selected=scenario.graph();
                    UUID user=selected.id(role.equals("MAKER")?"executorUser":role.equals("OPS_LEAD")?"verifierUser":"ownerUser");
                    String subject=jdbc.sql("SELECT external_subject FROM iam.user_account WHERE id=:id")
                            .param("id",user).query(String.class).single();
                    var data=new LinkedHashMap<String,Object>();data.put("accessToken",BrowserSigningFixture.token(subject));
                    data.put("role",role);data.put("userId",user);data.put("storeId",selected.id("store"));
                    data.put("caseId",selected.id("caseId"));data.put("objectId",selected.id("object"));data.put("taskId",scenario.task());
                    data.put("candidateId",selected.id("candidate"));data.put("recommendationId",selected.id("recommendation"));
                    data.put("commandId",selected.ids().get("historyCommand"));
                    data.put("platform",selected.platform());data.put("productionWriteEnabled",false);
                    data.put("queueCases",scenario.graph().id("organization").equals(graph.id("organization"))?queueCases:Map.of());
                    data.put("manualPolicyId",scenario.manualPolicy());data.put("scenario",query.getOrDefault("scenario","API"));
                    data.put("semanticVerificationState",jdbc.sql("SELECT verification_state FROM platform.ad_semantic_profile WHERE id=:id")
                            .param("id",selected.id("profile")).query(String.class).single());
                    data.put("apiCommandCount",jdbc.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org")
                            .param("org",selected.id("organization")).query(Integer.class).single());
                    byte[] bytes=mapper.writeValueAsBytes(data);exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch(Exception failure) {exchange.sendResponseHeaders(500,-1);} finally {exchange.close();}
            });
            driver.start();Runtime.getRuntime().addShutdownHook(new Thread(()->driver.stop(0)));
        } catch(Exception failure) {context.close();throw failure;}
    }
    private record Scenario(AdvertisingR1Fixture.Graph graph,UUID task,UUID manualPolicy) { }

    private static Map<String,String> queryParameters(String raw) {
        Map<String,String> values=new LinkedHashMap<>();
        if(raw!=null) for(String pair:raw.split("&")) {
            String[] part=pair.split("=",2);
            if(part.length==2) values.put(java.net.URLDecoder.decode(part[0],java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(part[1],java.nio.charset.StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void configureManualUsers(JdbcClient jdbc,UserAdministrationService users,AdvertisingR1Fixture.Graph graph) {
        for(String name:List.of("executorUser","verifierUser","ownerUser")) {
            UUID user=graph.id(name);
            jdbc.sql("UPDATE iam.user_account SET identity_provider_id=:provider,external_subject=:subject,credentials_valid_from=now()-interval '1 day' WHERE id=:id")
                    .param("provider",graph.id("provider")).param("subject","synthetic-"+graph.id("organization")+"-"+name).param("id",user).update();
            if(name.equals("executorUser")) users.assignRole("synthetic-manual-browser",user,BusinessRoleCode.MARKETPLACE_OPERATOR,null);
            for(var action:List.of(ActionScopeCode.ADVERTISING_VIEW,ActionScopeCode.ADVERTISING_TASK_ACT,ActionScopeCode.ADVERTISING_EXCEPTION_REQUEST))
                users.grantScope("synthetic-manual-browser",user,action,ResourceScopeType.ORGANIZATION,graph.id("organization"),null);
            List<ActionScopeCode> manualActions=name.equals("executorUser")?List.of(ActionScopeCode.ADVERTISING_MANUAL_EXECUTE)
                    :name.equals("verifierUser")?List.of(ActionScopeCode.ADVERTISING_MANUAL_ENDORSE,ActionScopeCode.ADVERTISING_MANUAL_VERIFY,ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW)
                    :List.of(ActionScopeCode.ADVERTISING_MANUAL_APPROVE,ActionScopeCode.ADVERTISING_POLICY_MANAGE,ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW);
            for(var action:manualActions) users.grantScope("synthetic-manual-browser",user,action,ResourceScopeType.ORGANIZATION,graph.id("organization"),null);
        }
    }

    private static void configureManualCoverage(JdbcClient jdbc,AdvertisingR1Fixture.Graph graph) {
        jdbc.sql("UPDATE core.store SET timezone='Europe/Moscow' WHERE id=:id").param("id",graph.id("store")).update();
        jdbc.sql("UPDATE core.ad_human_slo_profile SET staffed_coverage_enabled=true,staffed_coverage_timezone='Etc/UTC',staffed_coverage_start_minute=0,staffed_coverage_end_minute=1439 WHERE id=:id")
                .param("id",graph.id("humanSlo")).update();
        jdbc.sql("""
                INSERT INTO core.ad_reporting_calendar(id,organization_id,policy_version,scope_kind,reporting_timezone,
                  daily_cut_minute,operating_days,weekly_cut_weekday,weekly_cut_minute,late_revision_horizon_hours,
                  owner_user_id,reason,evidence_reference,effective_from,status,created_at)
                VALUES(gen_random_uuid(),:org,1,'ORGANIZATION','Etc/UTC',0,ARRAY[1,2,3,4,5,6,7]::smallint[],1,0,24,
                  :owner,'Explicit synthetic manual coverage','fixture://manual-browser/calendar',now()-interval '1 day','ACTIVE',now())
                """).param("org",graph.id("organization")).param("owner",graph.id("ownerUser")).update();
    }

}
