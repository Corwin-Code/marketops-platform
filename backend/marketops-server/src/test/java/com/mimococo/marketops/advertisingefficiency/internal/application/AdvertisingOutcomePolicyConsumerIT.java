package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual calculator/Planner and HTTP controllers; synthetic topology/Owner facts, app-role DB. */
@SpringBootTest @ActiveProfiles("ci") @AutoConfigureMockMvc @Import(AdvertisingVerticalPathIT.Runtime.class)
class AdvertisingOutcomePolicyConsumerIT {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    AdvertisingEconomicCauseBoundIT economic;
    AdvertisingVerticalPathIT f;
    UUID candidate;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) { AdvertisingVerticalPathIT.properties(p); }
    @BeforeEach void actualFactsFixture() throws Exception {
        economic=new AdvertisingEconomicCauseBoundIT();context.getAutowireCapableBeanFactory().autowireBean(economic);
        economic.seedOnlySyntheticTopologyAndAuthority();f=economic.f;
    }
    @AfterEach void disabledProductionAndNoProvider() {
        SecurityContextHolder.clearContext();
        if(f!=null) { assertThat(f.productionWrites.getEnabled()).isFalse();assertThat(f.provider.calls).isEmpty(); }
    }

    @ParameterizedTest @ValueSource(strings={"OUTCOME_POLICY_UNRESOLVED","OUTCOME_POLICY_CONFLICTED"})
    void missingOrConflictedPolicyKeepsActualProvenHarmQueueAndSameProtectionTaskObservable(String reason) throws Exception {
        f.acceptPreActionFacts(true);defect(reason);
        var result=economic.refresh();
        var protection=result.calculation().cases().stream().filter(c->c.identity().cause().name().equals("PROVEN_ADVERTISING_LOSS")).findFirst().orElseThrow();
        assertThat(protection.decision().lane().name()).isEqualTo("PROTECTION");
        assertThat(protection.contributionProfit().value()).isEqualByComparingTo("-1000");
        assertThat(protection.decision().blockerCodes()).contains(reason);
        UUID caseId=f.sql("SELECT id FROM mart.ad_case WHERE organization_id=:org AND cause_code='PROVEN_ADVERTISING_LOSS' AND superseded_at IS NULL")
                .query(UUID.class).single();
        UUID task=taskFor(caseId);
        assertThat(task).isNotNull();
        JsonNode detail=read("/api/v1/console/advertising/cases/"+caseId,f.owner);
        assertThat(detail.path("lane").asString()).isEqualTo("PROTECTION");assertThat(strings(detail.path("blockerCodes"))).contains(reason);
        JsonNode queue=read("/api/v1/console/advertising/queue?lane=PROTECTION",f.owner);
        assertThat(queue.isArray()).isTrue();
        assertThat(java.util.stream.StreamSupport.stream(queue.spliterator(),false).map(n->n.path("id").asString()).toList()).contains(caseId.toString());
        f.refresh.refresh(f.graph.id("organization"),f.graph.id("object"),f.start,"RECONCILIATION",null,"outcome-policy-visible-repair").orElseThrow();
        assertThat(taskFor(caseId)).isEqualTo(task);
        assertThat(f.sql("SELECT count(*) FROM ops.ad_bid_candidate WHERE organization_id=:org AND direction='OPTIMIZATION_INCREASE'").query(Integer.class).single()).isZero();
        assertNoExecution();
    }

    @Test void completeBoundPolicyStillPermitsActualCandidateSelectionAndOneFrozenPlanThroughHttp() throws Exception {
        candidate();
        var response=mvc.perform(post(selectionUrl()).with(auth(f.maker)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"reason\":\"Select exact complete synthetic Outcome plan\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsByteArray()).path("state").asString()).isEqualTo("VALIDATED");
        assertThat(f.count("ops.ad_outcome_baseline")).isEqualTo(1);
        assertThat(f.sql("SELECT count(*) FROM mart.ad_case_evidence WHERE organization_id=:org AND evidence_role='OUTCOME_POLICY'").query(Integer.class).single()).isPositive();
        assertThat(f.sql("SELECT outcome_policy_id FROM ops.ad_outcome_baseline WHERE organization_id=:org").query(UUID.class).single()).isEqualTo(f.graph.id("outcome"));
        assertNoExecution();
    }

    @ParameterizedTest @ValueSource(strings={"OUTCOME_POLICY_UNRESOLVED","OUTCOME_POLICY_CONFLICTED","BOUND_MISMATCH"})
    void actualCandidateCannotSelectThroughAnExpiredConflictedOrReboundPolicy(String fault) throws Exception {
        candidate();String bundle=bundleBytes();
        if(fault.equals("BOUND_MISMATCH"))additionalPolicy("STORE");else defect(fault);
        String expected=fault.equals("OUTCOME_POLICY_CONFLICTED")?fault:"OUTCOME_POLICY_UNRESOLVED";
        var projected=context.getBean(com.mimococo.marketops.operationsworkflow.AdvertisingDecisionAuthority.class)
                .bidProjection(f.recommendation).orElseThrow();
        assertThat(projected.actionBlockerCodes()).contains(expected);
        var response=mvc.perform(post(selectionUrl()).with(auth(f.maker)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"reason\":\"The old exact candidate cannot replace Owner policy\"}"))
                .andExpect(status().isConflict()).andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsByteArray()).path("title").asString()).isEqualTo(expected);
        assertThat(f.count("ops.ad_outcome_baseline")).isZero();assertThat(f.count("ops.ad_candidate_selection")).isZero();
        assertThat(f.recommendations.require(f.recommendation).state().name()).isEqualTo("DRAFT");
        assertThat(bundleBytes()).isEqualTo(bundle);assertNoExecution();
    }

    @ParameterizedTest @ValueSource(strings={"OUTCOME_POLICY_UNRESOLVED","OUTCOME_POLICY_CONFLICTED","BOUND_MISMATCH"})
    void manualOptionsAndActualSelectionExposeTheSamePolicyRefusalWithoutReplacingItsBinding(String fault) throws Exception {
        candidate();rawConfiguration();f.scope("ownerUser","ADVERTISING_POLICY_MANAGE");
        var policy=json.createObjectNode().put("storeId",f.graph.id("store").toString()).put("semanticProfileId",f.graph.id("profile").toString())
                .put("policyVersion",1).put("causeCode","PROVEN_ADVERTISING_LOSS").put("actionKind","AD_BID_CHANGE")
                .put("candidateBasis","CAUSE_BOUND_PROTECTION_STEP").put("currencyCode","RUB").put("verificationMode","INDEPENDENT_OR_OFFICIAL")
                .put("configurationMaxAgeSeconds",3600).put("packetLeaseSeconds",1800).put("outcomePolicyId",f.graph.id("outcome").toString())
                .put("effectiveFrom",f.start.minusSeconds(60).toString()).put("effectiveTo",f.start.plusSeconds(3600).toString())
                .put("evidenceReference","fixture://outcome-policy-consumer/manual-owner");
        var published=mvc.perform(post("/api/v1/console/advertising/manual-policies").with(auth(f.owner)).contentType(MediaType.APPLICATION_JSON).content(policy.toString()))
                .andExpect(status().isOk()).andReturn().getResponse();
        UUID manual=UUID.fromString(json.readTree(published.getContentAsByteArray()).path("policyId").asString());
        String frozenManual=f.sql("SELECT to_jsonb(p)::text FROM core.ad_manual_policy p WHERE id=:id").param("id",manual).query(String.class).single();
        String optionsUrl="/api/v1/console/advertising/cases/"+f.caseId+"/manual-options";
        assertThat(strings(read(optionsUrl,f.maker).path("allowedActions"))).contains("SELECT_MANUAL_PROPOSAL");
        if(fault.equals("BOUND_MISMATCH"))additionalPolicy("STORE");else defect(fault);
        String expected=fault.equals("OUTCOME_POLICY_CONFLICTED")?fault:"OUTCOME_POLICY_UNRESOLVED";
        JsonNode blocked=read(optionsUrl,f.maker);
        assertThat(strings(blocked.path("allowedActions"))).doesNotContain("SELECT_MANUAL_PROPOSAL");
        List<JsonNode> matching=java.util.stream.StreamSupport.stream(blocked.path("options").spliterator(),false)
                .filter(n->n.path("policyId").asString().equals(manual.toString())).toList();
        assertThat(matching).hasSize(1);assertThat(strings(matching.getFirst().path("blockerCodes"))).contains(expected);
        var request=json.createObjectNode().put("policyId",manual.toString()).put("candidateId",candidate.toString()).put("reason","Preserve exact manual Outcome authority");
        var selected=mvc.perform(post("/api/v1/console/advertising/cases/"+f.caseId+"/manual-selections").with(auth(f.maker))
                .contentType(MediaType.APPLICATION_JSON).content(request.toString())).andExpect(status().isConflict()).andReturn().getResponse();
        assertThat(json.readTree(selected.getContentAsByteArray()).path("title").asString()).isEqualTo(expected);
        assertThat(f.count("ops.ad_manual_proposal")).isZero();assertThat(f.count("ops.ad_manual_execution_packet")).isZero();
        assertThat(f.count("ops.ad_outcome_baseline")).isZero();
        assertThat(f.sql("SELECT to_jsonb(p)::text FROM core.ad_manual_policy p WHERE id=:id").param("id",manual).query(String.class).single()).isEqualTo(frozenManual);
        assertNoExecution();
    }

    private void rawConfiguration() {
        UUID provenance=rawConfigurationProvenance();
        f.sql("""
                INSERT INTO core.ad_object_configuration_observation(id,organization_id,ad_native_object_id,provenance_id,
                  semantic_profile_id,lineage_generation,observed_bid_amount,bid_currency_code,bid_unit_code,observed_status,
                  native_status_raw,observed_bidding_mode,evidence_grade,observed_at,source_time,created_at)
                VALUES(gen_random_uuid(),:org,:object,:provenance,:profile,1,30,'RUB','CURRENCY_MAJOR','RUNNING','native-running','MANUAL_BID',
                  'OFFICIAL_API_READBACK',clock_timestamp(),clock_timestamp(),clock_timestamp())
                """).param("provenance",provenance).param("profile",f.graph.id("profile")).update();
    }
    private UUID rawConfigurationProvenance() {
        UUID service=UUID.randomUUID(),endpoint=UUID.randomUUID(),job=UUID.randomUUID(),run=UUID.randomUUID();
        UUID unit=UUID.randomUUID(),observation=UUID.randomUUID(),provenance=UUID.randomUUID();
        byte[] bytes=("{\"fixtureNativeObject\":\""+f.graph.id("object")+"\",\"bid\":30}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID content=context.getBean(com.mimococo.marketops.marketplaceintegration.RawCustody.class).store("manual-fixture",bytes).contentId();
        assertThat(context.getBean(com.mimococo.marketops.marketplaceintegration.RawCustody.class).readById(content).orElseThrow()).containsExactly(bytes);
        f.seed.sql("INSERT INTO iam.service_account(id,organization_id,code,display_name,purpose,owner_label,status,expires_at,created_at,updated_at) VALUES(:id,:org,:code,'Stored synthetic configuration','INGESTION','fixture','ACTIVE',now()+interval '1 day',now(),now())")
                .param("id",service).param("org",f.graph.id("organization")).param("code","manual-"+service).update();
        f.seed.sql("INSERT INTO platform.platform_endpoint(id,platform_code,endpoint_code,api_version,read_write_class,pagination_model,idempotency_support,verification_state,owner_label,contract_test_status,status,created_at,updated_at) VALUES(:id,:platform,:endpointCode,'v1','READ','NONE','UNKNOWN','UNVERIFIED','fixture','NOT_IMPLEMENTED','ACTIVE',now(),now())")
                .param("id",endpoint).param("platform",f.graph.platform()).param("endpointCode","manual.config."+endpoint).update();
        f.seed.sql("INSERT INTO platform.ingestion_job(id,organization_id,marketplace_account_id,platform_code,service_account_id,endpoint_id,job_code,display_name,status,created_at,updated_at) VALUES(:id,:org,:account,:platform,:service,:endpoint,:code,'Synthetic stored configuration','PAUSED',now(),now())")
                .param("id",job).param("org",f.graph.id("organization")).param("account",f.graph.id("account"))
                .param("platform",f.graph.platform()).param("service",service).param("endpoint",endpoint).param("code","manual-"+job).update();
        f.seed.sql("INSERT INTO ops.ingestion_run(id,job_id,state,fence_token,attempt_no,last_call_seq,created_at,updated_at) VALUES(:id,:job,'SUCCEEDED',1,1,1,now(),now())").param("id",run).param("job",job).update();
        f.seed.sql("INSERT INTO raw.raw_logical_unit(id,job_id,marketplace_account_id,unit_kind,source_unit_key,source_time) VALUES(:id,:job,:account,'AD_CONFIGURATION',:key,now())")
                .param("id",unit).param("job",job).param("account",f.graph.id("account")).param("key",unit.toString()).update();
        f.seed.sql("INSERT INTO raw.raw_acquisition_observation(id,run_id,logical_unit_id,content_id,call_seq,native_status,outcome_class,pagination_outcome) VALUES(:id,:run,:unit,:content,1,'fixture-success','SUCCESS_BYTES','END')")
                .param("id",observation).param("run",run).param("unit",unit).param("content",content).update();
        f.seed.sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,raw_observation_id,source_time,ingestion_time,evidence_note) VALUES(:id,:org,'MARKETPLACE_RAW',:observation,now(),now(),'Isolated synthetic raw configuration oracle')")
                .param("id",provenance).param("org",f.graph.id("organization")).param("observation",observation).update();
        return provenance;
    }
    private void candidate() {
        economic.calculateCandidate();candidate=economic.candidate;f.fictionalDispatchControls(candidate);
        assertThat(f.count("ops.ad_outcome_baseline")).isZero();
    }
    private void defect(String reason) {
        if(reason.equals("OUTCOME_POLICY_UNRESOLVED"))f.sql("UPDATE core.ad_outcome_policy SET effective_to=:at WHERE id=:outcome").update();
        else additionalPolicy("ORGANIZATION");
    }
    private UUID additionalPolicy(String scope) {
        UUID id=UUID.randomUUID();
        f.sql("""
                INSERT INTO core.ad_outcome_policy SELECT (jsonb_populate_record(NULL::core.ad_outcome_policy,to_jsonb(p)||jsonb_build_object(
                  'id',CAST(:id AS uuid),'policy_version',2,'scope_kind',CAST(:scope AS text),
                  'platform_code',CASE WHEN :scope='STORE' THEN CAST(:platform AS text) ELSE NULL END,
                  'store_ref_id',CASE WHEN :scope='STORE' THEN CAST(:store AS uuid) ELSE NULL END,
                  'effective_from',CAST(:at AS timestamptz)-interval '1 hour','created_at',CAST(:at AS timestamptz),
                  'reason','Synthetic separately scoped Outcome policy input'))).* FROM core.ad_outcome_policy p WHERE p.id=:outcome
                """).param("id",id).param("scope",scope).param("platform",f.graph.platform()).update();return id;
    }
    private UUID taskFor(UUID caseId) { return f.sql("SELECT task_id FROM ops.ad_case_responsibility WHERE case_id=:case").param("case",caseId).query(UUID.class).single(); }
    private String bundleBytes() { return f.sql("SELECT to_jsonb(b)::text FROM ops.ad_decision_policy_bundle b WHERE id=:id").param("id",f.graph.id("bundle")).query(String.class).single(); }
    private String selectionUrl() { return "/api/v1/console/advertising/cases/"+f.caseId+"/candidates/"+candidate+"/selection"; }
    private JsonNode read(String url,AuthenticatedActor actor) throws Exception {
        return json.readTree(mvc.perform(get(url).with(auth(actor))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private static RequestPostProcessor auth(AuthenticatedActor actor) { return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(actor,null,List.of())); }
    private static List<String> strings(JsonNode node) { assertThat(node.isArray()).isTrue();return java.util.stream.StreamSupport.stream(node.spliterator(),false).map(JsonNode::asString).toList(); }
    private void assertNoExecution() { assertThat(f.count("ops.ad_bid_command")).isZero();assertThat(f.count("ops.ad_action_reservation")).isZero();assertThat(f.provider.calls).isEmpty(); }
}
