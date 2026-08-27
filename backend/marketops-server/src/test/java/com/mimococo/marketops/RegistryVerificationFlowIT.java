package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.marketplaceintegration.internal.application.RegistryVerificationService;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PriceCommandRepository.CommandRow;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteRequest.Operation;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult;
import com.mimococo.marketops.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** All accounts, people and evidence are synthetic, in an isolated PostgreSQL server.
 * Even the REAL_ACCOUNT cases below exercise an attestation workflow only; they
 * are never evidence of a real account or permission to contact a provider. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class RegistryVerificationFlowIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE = TestDatabase.isolatedContainer();
    @Autowired RegistryVerificationService service;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @Autowired com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository operations;
    @Autowired com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository callSpecs;
    @Autowired com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PriceCommandRepository priceCommands;
    @Autowired com.mimococo.marketops.marketplaceintegration.internal.application.CredentialDirectory credentialDirectory;
    @Autowired com.mimococo.marketops.marketplaceintegration.RawCustody custody;
    private JdbcClient arranger;
    private UUID organization,account,capability,endpoint,header;
    private AuthenticatedActor author,reviewer;
    private String platform;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        var database=DATABASE;
        registry.add("spring.datasource.url",database::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
    }

    @BeforeEach
    void isolatedReadProtocol() {
        arranger=JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword()));
        organization=UUID.randomUUID(); account=UUID.randomUUID(); capability=UUID.randomUUID(); endpoint=UUID.randomUUID();
        UUID legal=UUID.randomUUID(),provider=UUID.randomUUID();
        platform="OZON";
        run("INSERT INTO core.organization(id,code,display_name,status,created_at,updated_at) VALUES (:id,:code,'Synthetic organization','ACTIVE',now(),now())",Map.of("id",organization,"code","organization-"+organization));
        run("INSERT INTO core.legal_entity(id,organization_id,code,display_name,status,created_at,updated_at) VALUES (:id,:organization,:code,'Synthetic legal entity','ACTIVE',now(),now())",Map.of("id",legal,"organization",organization,"code","legal-"+legal));
        run("INSERT INTO core.marketplace_account(id,organization_id,legal_entity_id,platform_code,code,display_name,status,created_at,updated_at) VALUES (:id,:organization,:legal,:platform,:code,'Synthetic account','ACTIVE',now(),now())",Map.of("id",account,"organization",organization,"legal",legal,"platform",platform,"code","account-"+account));
        run("""
                INSERT INTO iam.identity_provider(id,code,display_name,issuer,mfa_claim_name,mfa_claim_value,max_auth_age_seconds,
                    verification_state,last_verified_at,evidence_ref,verified_source_title,owner_label,status,created_at,updated_at)
                VALUES (:id,:code,'Synthetic identity',:issuer,'amr','mfa',900,'VERIFIED',now(),
                    'evidence://fixture/identity','Synthetic identity fixture','fixture','ACTIVE',now(),now())
                """,Map.of("id",provider,"code","idp-"+provider,"issuer","https://identity.fixture.invalid/"+provider));
        author=person(provider); reviewer=person(provider);
        run("""
                INSERT INTO platform.platform_capability(id,platform_code,capability_code,display_name,applies_to,read_write_class,
                    subscription_required,verification_state,owner_label,contract_test_status,status,created_at,updated_at)
                VALUES (:id,:platform,:code,'Synthetic read','MARKETPLACE_ACCOUNT','READ','NO','UNVERIFIED',
                    'fixture','NOT_IMPLEMENTED','ACTIVE',now(),now())
                """,Map.of("id",capability,"platform",platform,"code","catalog-read-"+capability));
        run("""
                INSERT INTO platform.platform_endpoint(id,platform_code,endpoint_code,api_version,capability_id,read_write_class,
                    pagination_model,idempotency_support,verification_state,owner_label,contract_test_status,status,created_at,updated_at)
                VALUES (:id,:platform,:code,'v1',:capability,'READ','UNKNOWN','UNKNOWN','UNVERIFIED','fixture','NOT_IMPLEMENTED','ACTIVE',now(),now())
                """,Map.of("id",endpoint,"platform",platform,"capability",capability,"code","catalog-"+endpoint));
        var profileVersion=jdbc.sql("SELECT version FROM platform.platform_api_profile WHERE platform_code=:code")
                .param("code",platform).query(Long.class).optional();
        if (profileVersion.isPresent()) {
            service.beginRevision(author,account,capability,service.configuration(author,account,capability).digest());
        }
        service.configure(author,account,capability,"PROFILE",null,profileVersion.map(v -> v+1).orElse(-1L),Map.of("base_url","https://provider.fixture.invalid",
                "request_timeout_ms",1000,"max_response_bytes",4096,"owner_label","fixture"));
        header=service.configure(author,account,capability,"HEADER",null,-1,Map.of("header_name","Authorization",
                "value_source","RESOLVED_SECRET","value_template","Bearer {value}","credential_purpose","READ","ordinal",1,"owner_label","fixture"));
        service.configure(author,account,capability,"ENDPOINT",endpoint,profileVersion.isPresent()?1:0,Map.of("http_method","GET","path_template","/catalog",
                "operation_function","READ_DATA","response_content_type","application/json","pagination_model","NONE","rate_limit_per_minute",60));
    }

    @Test
    void independentReviewPromotesTheExactSnapshotAndRevocationStopsIt() {
        UUID id=submit("REAL_ACCOUNT");
        assertThat(service.find(author,id).currentEvidence()).isFalse();
        assertThatThrownBy(() -> service.review(author,id,0,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        service.review(reviewer,id,0,true);
        assertThat(service.find(author,id).currentEvidence()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ops.metadata_audit_event WHERE entity_id=:id").param("id",id).query(Integer.class).single()).isEqualTo(2);
        assertThatThrownBy(() -> service.configure(author,account,capability,"PROFILE",null,1,Map.of("base_url","https://other.fixture.invalid",
                "request_timeout_ms",1000,"max_response_bytes",4096,"owner_label","fixture"))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        service.revoke(author,id,1);
        assertThat(service.find(author,id).state()).isEqualTo("REVOKED");
        assertThat(service.find(author,id).currentEvidence()).isFalse();
    }

    @Test
    void protocolFixturesCanBeReviewedButNeverPromotedAsRealAccountEvidence() {
        UUID id=submit("PROTOCOL_FIXTURE");
        assertThatThrownBy(() -> service.review(reviewer,id,0,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        service.review(reviewer,id,0,false);
        assertThat(service.find(author,id).state()).isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT verification_state FROM platform.platform_capability WHERE id=:id").param("id",capability).query(String.class).single()).isEqualTo("UNVERIFIED");
    }

    @Test
    void changedConfigurationAndExpiredEvidenceCannotRemainCurrent() {
        UUID id=submit("REAL_ACCOUNT"); service.review(reviewer,id,0,true);
        run("UPDATE platform.registry_verification_case SET valid_until=now()-interval '1 second' WHERE id=:id",Map.of("id",id));
        assertThat(service.find(author,id).currentEvidence()).isFalse();
        service.beginRevision(author,account,capability,service.configuration(author,account,capability).digest());
        assertThat(jdbc.sql("SELECT verification_state FROM platform.platform_api_profile WHERE platform_code=:code").param("code",platform).query(String.class).single()).isEqualTo("UNVERIFIED");
    }

    @Test
    void changingAnyCapturedVersionBetweenSubmissionAndReviewIsRefused() {
        UUID id=submit("REAL_ACCOUNT");
        service.configure(author,account,capability,"HEADER",header,0,Map.of("header_name","Authorization","value_source","RESOLVED_SECRET",
                "value_template","{value}","credential_purpose","READ","ordinal",1,"owner_label","fixture"));
        assertThatThrownBy(() -> service.review(reviewer,id,0,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.find(author,id).state()).isEqualTo("SUBMITTED");
    }

    @ParameterizedTest
    @ValueSource(strings={"platform_api_profile","platform_auth_header","platform_endpoint","platform_capability"})
    void ordinaryApplicationDmlCannotAlterVerifiedFacts(String table) {
        UUID id=submit("REAL_ACCOUNT"); service.review(reviewer,id,0,true);
        assertThatThrownBy(() -> jdbc.sql("UPDATE platform."+table+" SET owner_label='changed' WHERE platform_code=:platform")
                .param("platform",platform).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={"http_method='DELETE'","operation_function='PRICE_APPLY'","rate_limit_per_minute=NULL","response_content_type=NULL",
        "query_template='cursor={forbidden}'","path_template='/catalog/{unknown}'","http_method='POST',body_template='[1]'",
        "http_method='POST',body_template='{\"value\":1,\"value\":2}'"})
    void incompleteReadSemanticsCannotBeApproved(String mutation) {
        run("UPDATE platform.platform_endpoint SET "+mutation+" WHERE id=:id",Map.of("id",endpoint));
        UUID id=submit("REAL_ACCOUNT");
        assertThatThrownBy(() -> service.review(reviewer,id,0,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void httpReviewRequiresBearerIdentityLoopbackAndIndependentReviewer() throws Exception {
        UUID id=submit("REAL_ACCOUNT");
        String path="/api/v1/console/registry-verification/cases/"+id+"/review";
        String body="{\"expectedVersion\":0,\"approve\":true}";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(auth(author)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(auth(reviewer)).with(request -> { request.setRemoteAddr("192.0.2.1"); return request; })
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(auth(reviewer)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/console/registry-verification/cases/"+id).with(auth(author)))
                .andExpect(status().isOk()).andExpect(jsonPath("state").value("APPROVED")).andExpect(jsonPath("currentEvidence").value(true));
    }

    @Test
    void httpRejectsDuplicateKeysAndNeverAcceptsAttributionAsAuthentication() throws Exception {
        String path="/api/v1/console/registry-verification/accounts/"+account+"/capabilities/"+capability+"/revision";
        mvc.perform(post(path).header("X-Operator-Id","fixture").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(auth(author)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedDigest\":\"a\",\"expectedDigest\":\"b\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void inactiveReviewerAndAnotherAccountCannotUseTheEvidence() {
        UUID id=submit("REAL_ACCOUNT");
        run("UPDATE iam.user_role_assignment SET status='REVOKED',reason='fixture revocation' WHERE user_id=:id",Map.of("id",reviewer.userId()));
        assertThatThrownBy(() -> service.review(reviewer,id,0,true)).isInstanceOf(com.mimococo.marketops.shared.OperationRejectedException.class);
        assertThat(jdbc.sql("SELECT platform.capability_evidence_current(:account,:capability,:endpoint)")
                .param("account",UUID.randomUUID()).param("capability",capability).param("endpoint",endpoint).query(Boolean.class).single()).isFalse();
    }

    @Test
    void anotherAccountCanAttestTheSameConfigurationWithoutInvalidatingTheFirst() {
        UUID first=submit("REAL_ACCOUNT"); service.review(reviewer,first,0,true);
        String digest=service.configuration(author,account,capability).digest();
        UUID firstAccount=account;
        account=UUID.randomUUID();
        run("""
                INSERT INTO core.marketplace_account(id,organization_id,legal_entity_id,platform_code,code,display_name,status,created_at,updated_at)
                SELECT :id,organization_id,legal_entity_id,platform_code,:code,'Second synthetic account','ACTIVE',now(),now()
                FROM core.marketplace_account WHERE id=:first
                """,Map.of("id",account,"code","account-"+account,"first",firstAccount));
        UUID second=submit("REAL_ACCOUNT"); service.review(reviewer,second,0,true);
        assertThat(service.configuration(author,account,capability).digest()).isEqualTo(digest);
        assertThat(service.find(author,first).currentEvidence()).isTrue();
        assertThat(service.find(author,second).currentEvidence()).isTrue();
        service.revoke(author,second,1);
        assertThat(service.find(author,first).currentEvidence()).isTrue();
        assertThat(service.find(author,second).currentEvidence()).isFalse();
    }

    @Test
    void submittedEvidenceCannotBeReboundOrChangedByTheApplicationRole() {
        UUID id=submit("REAL_ACCOUNT");
        for (String assignment:List.of("configuration_snapshot='{}'::jsonb","state='APPROVED'","valid_until=now()+interval '1 day'")) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE platform.registry_verification_case SET "+assignment+" WHERE id=:id")
                    .param("id",id).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        run("UPDATE platform.registry_verification_case SET valid_until=now()-interval '1 second' WHERE id=:id",Map.of("id",id));
        assertThatThrownBy(() -> service.review(reviewer,id,0,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.find(author,id).state()).isEqualTo("SUBMITTED");
    }

    @Test
    void currentAccountEvidenceControlsTheActualAcquisitionRepositoryQueries() {
        UUID credential=UUID.randomUUID();
        run("""
                INSERT INTO platform.credential_metadata(id,organization_id,marketplace_account_id,purpose_code,code,display_name,
                    secret_reference,scope_mode,status,effective_from,expires_at,verification_state,custodian_label,created_at,updated_at)
                VALUES (:id,:organization,:account,'READ',:code,'Synthetic metadata',:reference,'ACCOUNT','ACTIVE',now()-interval '1 hour',
                    now()+interval '1 day','UNVERIFIED','fixture',now(),now())
                """,Map.of("id",credential,"organization",organization,"account",account,"code","fixture-"+credential,
                    "reference","secret-ref://fixture/"+credential));
        assertThat(callSpecs.acquisitionEvidenceDigest(endpoint,credential)).isEmpty();
        UUID id=submit("REAL_ACCOUNT"); service.review(reviewer,id,0,true);
        assertThat(callSpecs.acquisitionEvidenceDigest(endpoint,credential))
                .contains(service.configuration(author,account,capability).digest());
        assertThat(callSpecs.findVerifiedSpec(endpoint).orElseThrow().httpMethod()).isEqualTo("GET");
        assertThat(callSpecs.verifiedAuthHeaders(platform,"READ")).hasSize(1);
        assertThat(callSpecs.activeSecretReference(credential,"READ")).contains("secret-ref://fixture/"+credential);
        assertThat(callSpecs.activeSecretReference(credential,"PRICE_WRITE")).isEmpty();
        run("UPDATE core.marketplace_account SET status='SUSPENDED' WHERE id=:id",Map.of("id",account));
        assertThat(callSpecs.acquisitionEvidenceDigest(endpoint,credential)).isEmpty();
        assertThat(jdbc.sql("SELECT platform.capability_evidence_current(:account,:capability,NULL)")
                .param("account",account).param("capability",capability).query(Boolean.class).single()).isFalse();
    }

    @Test
    void staleReviewAndRevocationCannotReplayTheirAuditOrTransition() {
        UUID id=submit("REAL_ACCOUNT");
        assertThatThrownBy(() -> service.review(reviewer,id,2,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        service.review(reviewer,id,0,true);
        assertThatThrownBy(() -> service.review(reviewer,id,0,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> service.revoke(author,id,0)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM ops.metadata_audit_event WHERE entity_id=:id").param("id",id).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void concurrentReviewersCannotBothConsumeTheSubmittedVersion() throws Exception {
        UUID id=submit("REAL_ACCOUNT");
        var otherReviewer=person(author.identityProviderId());
        var start=new java.util.concurrent.CountDownLatch(1);
        try (var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=List.of(reviewer,otherReviewer).stream().map(actor -> pool.submit(() -> {
                start.await();
                try { service.review(actor,id,0,true); return true; }
                catch (org.springframework.dao.DataAccessException stale) { return false; }
            })).toList();
            start.countDown();
            assertThat(List.of(futures.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS),
                    futures.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ops.metadata_audit_event WHERE entity_id=:id").param("id",id).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void fullDraftAndEvidenceWorkflowIsAvailableThroughAuthenticatedHttp() throws Exception {
        String path="/api/v1/console/registry-verification/accounts/"+account+"/capabilities/"+capability;
        mvc.perform(get(path).with(auth(author))).andExpect(status().isOk())
                .andExpect(jsonPath("snapshot.capability.id").value(capability.toString()));
        var definition=Map.of("header_name","Authorization","value_source","RESOLVED_SECRET","value_template","{value}",
                "credential_purpose","READ","ordinal",1,"owner_label","fixture");
        mvc.perform(post(path+"/draft").with(auth(author)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(
                Map.of("kind","HEADER","id",header,"expectedVersion",0,"definition",definition))))
                .andExpect(status().isCreated()).andExpect(jsonPath("id").value(header.toString()));
        var response=mvc.perform(post(path+"/cases").with(auth(author)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(
                Map.of("endpointIds",List.of(endpoint),"authHeaderIds",List.of(header),"evidence",evidence("REAL_ACCOUNT"),
                        "expectedDigest",service.configuration(author,account,capability).digest()))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        UUID id=UUID.fromString(mapper.readTree(response.getContentAsByteArray()).path("id").asString());
        mvc.perform(post("/api/v1/console/registry-verification/cases/"+id+"/review").with(auth(reviewer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"approve\":true}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/console/registry-verification/cases/"+id+"/revoke").with(auth(author))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isNoContent());
        mvc.perform(post(path+"/revision").with(auth(author)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(
                Map.of("expectedDigest",service.configuration(author,account,capability).digest()))))
                .andExpect(status().isNoContent());
        assertThat(service.find(author,id).currentEvidence()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","[]","null","{\"expectedVersion\":0.5,\"approve\":true}",
        "{\"expectedVersion\":0,\"approve\":\"true\"}","{\"expectedVersion\":0,\"approve\":true,\"actor\":\"forged\"}",
        "{\"expectedVersion\":9223372036854775808,\"approve\":true}","{\"expectedVersion\":0,\"approve\":true} {}"})
    void malformedHttpReviewHasNoMutation(String body) throws Exception {
        UUID id=submit("REAL_ACCOUNT");
        mvc.perform(post("/api/v1/console/registry-verification/cases/"+id+"/review").with(auth(reviewer))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("title").value("VALIDATION_FAILED"));
        assertThat(service.find(author,id).state()).isEqualTo("SUBMITTED");
    }

    @Test
    void staleSnapshotAndForeignMembersAreRejectedWithoutCreatingACase() {
        String digest=service.configuration(author,account,capability).digest();
        assertThatThrownBy(() -> service.submit(author,account,capability,List.of(endpoint),List.of(header),evidence("REAL_ACCOUNT"),"0".repeat(64)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> service.submit(author,account,capability,List.of(UUID.randomUUID()),List.of(header),evidence("REAL_ACCOUNT"),digest))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> service.submit(author,account,capability,List.of(endpoint,endpoint),List.of(header),evidence("REAL_ACCOUNT"),digest))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> service.submit(author,account,capability,List.of(endpoint),List.of(UUID.randomUUID()),evidence("REAL_ACCOUNT"),digest))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.registry_verification_case WHERE marketplace_account_id=:id")
                .param("id",account).query(Integer.class).single()).isZero();
    }

    @Test
    void completeAsynchronousWriteProtocolRequiresEverySemanticAndDoesNotEnableWrites() {
        run("UPDATE platform.platform_capability SET capability_code='price-change',read_write_class='WRITE' WHERE id=:id",Map.of("id",capability));
        service.configure(author,account,capability,"CAPABILITY",capability,version("platform_capability",capability),Map.of("write_result_model","ASYNCHRONOUS_TASK"));
        header=service.configure(author,account,capability,"HEADER",null,-1,Map.of("header_name","Authorization","value_source","RESOLVED_SECRET",
                "value_template","Bearer {value}","credential_purpose","PRICE_WRITE","ordinal",1,"owner_label","fixture"));
        run("UPDATE platform.platform_endpoint SET read_write_class='WRITE' WHERE id=:id",Map.of("id",endpoint));
        configureEndpoint(endpoint,"APPLY");
        UUID apply=service.configure(author,account,capability,"OPERATION",null,-1,Map.of("operation","APPLY","endpoint_id",endpoint.toString(),
                "request_template","{\"price\":\"{targetPrice}\",\"currency\":\"{currencyCode}\",\"sku\":\"{nativeVariantKey}\"}",
                "accepted_pointer","/accepted","accepted_value",true,"task_key_pointer","/task","owner_label","fixture"));
        UUID readback=newEndpoint("READBACK");
        service.configure(author,account,capability,"OPERATION",null,-1,Map.of("operation","READBACK","endpoint_id",readback.toString(),
                "request_template","","observed_price_pointer","/price","observed_currency_pointer","/currency",
                "version_token_header","etag","owner_label","fixture"));
        UUID incomplete=submit(List.of(endpoint,readback));
        assertThatThrownBy(() -> service.review(reviewer,incomplete,0,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID status=newEndpoint("STATUS_ENQUIRY");
        service.configure(author,account,capability,"OPERATION",null,-1,Map.of("operation","STATUS_ENQUIRY","endpoint_id",status.toString(),
                "request_template","","task_status_pointer","/status","task_success_value","done","task_failure_value","failed",
                "task_pending_values",List.of("pending"),"owner_label","fixture"));
        UUID restore=newEndpoint("RESTORE");
        service.configure(author,account,capability,"OPERATION",null,-1,Map.of("operation","RESTORE","endpoint_id",restore.toString(),
                "request_template","{\"price\":\"{targetPrice}\",\"currency\":\"{currencyCode}\",\"sku\":\"{nativeVariantKey}\"}",
                "accepted_pointer","/accepted","accepted_value",true,"task_key_pointer","/task",
                "conditional_write_header","If-Match","owner_label","fixture"));
        UUID complete=submit(List.of(endpoint,readback,status,restore));
        service.review(reviewer,complete,0,true);
        assertThat(service.find(author,complete).currentEvidence()).isTrue();
        assertThat(operations.verifiedOperation(capability,"APPLY").orElseThrow().acceptedValue().booleanValue()).isTrue();
        assertThat(operations.verifiedOperation(capability,"STATUS_ENQUIRY").orElseThrow().taskPendingValues()).containsExactly("pending");
        assertThat(callSpecs.verifiedAuthHeaders(platform,"PRICE_WRITE")).hasSize(1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE platform.capability_operation SET request_template='{}' WHERE id=:id")
                .param("id",apply).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM platform.feature_flag WHERE flag_code='price-change-write' AND state='ENABLED'")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ops.pilot_allowlist_entry").query(Integer.class).single()).isZero();
        assertPriceWireIntentUsesTheApprovedCommand(List.of(endpoint,readback,status,restore));
    }

    /** The following graph and write flags are test fixtures, not effects of verification. No adapter is called. */
    private void assertPriceWireIntentUsesTheApprovedCommand(List<UUID> endpoints) {
        var assertions = new org.assertj.core.api.SoftAssertions();
        for (Operation operation : Operation.values()) {
            var mutations = new java.util.ArrayList<>(List.of("NONE", "PRICE", "CURRENCY", "LISTING", "VARIANT",
                    "IDEMPOTENCY", "TASK", "PRECONDITION"));
            if (operation == Operation.STATUS_ENQUIRY) mutations.add("MISSING_TASK");
            if (operation == Operation.RESTORE) mutations.addAll(List.of("STALE_READBACK", "APPLY_KEY"));
            for (String mutation : mutations) {
                UUID commandId = PriceCommandFixture.seed(arranger, "registry-wire-" + UUID.randomUUID());
                var command = priceCommands.row(commandId).orElseThrow();
                UUID commandAccount = jdbc.sql("SELECT marketplace_account_id FROM core.store WHERE id=:id")
                        .param("id", command.storeId()).query(UUID.class).single();
                var commandAuthor = person(author.identityProviderId(), command.organizationId());
                var commandReviewer = person(author.identityProviderId(), command.organizationId());
                UUID verification = service.submit(commandAuthor, commandAccount, capability, endpoints, List.of(header),
                        evidence("REAL_ACCOUNT"), service.configuration(commandAuthor,commandAccount,capability).digest());
                service.review(commandReviewer, verification, 0, true);
                UUID credential = UUID.randomUUID();
                run("""
                        INSERT INTO platform.credential_metadata(id,organization_id,marketplace_account_id,purpose_code,code,display_name,
                            secret_reference,scope_mode,status,effective_from,expires_at,verification_state,custodian_label,created_at,updated_at)
                        VALUES (:id,:organization,:account,'PRICE_WRITE',:code,'Synthetic metadata',:reference,'ACCOUNT','ACTIVE',
                            now()-interval '1 hour',now()+interval '1 day','UNVERIFIED','fixture',now(),now())
                        """,Map.of("id",credential,"organization",command.organizationId(),"account",commandAccount,
                            "code","wire-"+credential,"reference","secret-ref://fixture/"+credential));
                if (mutation.equals("NONE") && operation == Operation.APPLY) {
                    run("""
                            INSERT INTO platform.credential_metadata(id,organization_id,marketplace_account_id,purpose_code,code,display_name,
                                secret_reference,scope_mode,status,effective_from,expires_at,verification_state,custodian_label,created_at,updated_at)
                            SELECT gen_random_uuid(),organization_id,marketplace_account_id,'READ','older-read-'||id::text,display_name,
                                'secret-ref://fixture/older-read-'||id::text,scope_mode,status,effective_from,expires_at,verification_state,custodian_label,
                                created_at-interval '1 day',updated_at FROM platform.credential_metadata WHERE id=:id
                            """,Map.of("id",credential));
                    assertions.assertThat(credentialDirectory.writeCredential(command.storeId(),capability))
                            .as("write credential selection ignores an older read credential").contains(credential);
                }
                var identity = jdbc.sql("""
                        SELECT listing.native_listing_key,variant.native_variant_key
                        FROM core.platform_listing_variant variant JOIN core.platform_listing listing ON listing.id=variant.platform_listing_id
                        WHERE variant.id=:id
                        """).param("id",command.platformListingVariantId()).query().singleRow();
                var context = new WireContext(command,credential,(String)identity.get("native_listing_key"),
                        (String)identity.get("native_variant_key"));
                long fence = prepareWireOperation(context,operation);
                UUID attempt = UUID.randomUUID();
                var approvedPrice = operation == Operation.RESTORE ? command.priorPrice() : command.targetPrice();
                String task = operation == Operation.STATUS_ENQUIRY ? priceCommands.latestTaskKey(commandId).orElseThrow() : null;
                String precondition = operation == Operation.RESTORE ? priceCommands.restoreVersion(commandId,fence).orElseThrow() : null;
                var request = new PriceWriteRequest(operation,
                        capability,credential,
                        mutation.equals("LISTING") ? "other-listing" : context.listing(),
                        mutation.equals("VARIANT") ? "other-variant" : context.variant(),
                        Money.of(mutation.equals("PRICE") ? new java.math.BigDecimal("999.0000") : approvedPrice,
                                mutation.equals("CURRENCY") ? "USD" : command.currencyCode()),
                        mutation.equals("IDEMPOTENCY") ? "other-idempotency-key" : mutation.equals("APPLY_KEY")
                                ? command.idempotencyKey() : PriceWriteRequest.operationIdempotencyKey(operation,command.idempotencyKey()),
                        mutation.equals("TASK") ? "unrelated-task" : mutation.equals("MISSING_TASK") ? null : task,
                        mutation.equals("PRECONDITION") ? "unapproved-etag" : precondition,attempt);
                // The application can currently supply this digest to the controlled function.
                // Even if it records a forged intent, the dispatch query must not authorize it.
                priceCommands.openAttempt(attempt,commandId,operation.name(),fence,"registry-wire-fixture",request.digest(),"registry-wire-fixture");
                if (mutation.equals("STALE_READBACK")) {
                    assertThat(callSpecs.priceAttemptCurrent(request)).as("fresh restore can dispatch").isTrue();
                    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(35), () -> {
                        while (jdbc.sql("""
                                SELECT max(observed_at)>clock_timestamp()-interval '30 seconds'
                                FROM ops.price_command_readback WHERE command_id=:id
                                """).param("id",commandId).query(Boolean.class).single()) {
                            Thread.sleep(250);
                        }
                    });
                    assertThat(jdbc.sql("SELECT lease_expires_at>clock_timestamp() FROM ops.price_command WHERE id=:id")
                            .param("id",commandId).query(Boolean.class).single()).isTrue();
                }
                assertions.assertThat(callSpecs.priceAttemptCurrent(request)).as("wire mutation %s/%s",operation,mutation)
                        .isEqualTo(mutation.equals("NONE"));
                if (mutation.equals("NONE")) {
                    service.revoke(commandReviewer,verification,1);
                    assertions.assertThat(callSpecs.priceAttemptCurrent(request)).as("revoked account evidence").isFalse();
                }
            }
        }
        assertions.assertAll();
    }

    private record WireContext(CommandRow command,UUID credential,String listing,String variant) {}

    /** Prepare real controlled state transitions with explicitly synthetic response custody. */
    private long prepareWireOperation(WireContext context,Operation operation) {
        UUID command = context.command().id();
        long fence = priceCommands.lease(command,"registry-wire-fixture",600);
        priceCommands.transition(command,fence,"registry-wire-fixture","EXECUTING",null,null,null);
        if (operation == Operation.APPLY) return fence;
        if (operation == Operation.STATUS_ENQUIRY) {
            completeWireFixture(context,Operation.APPLY,fence,"{\"accepted\":true,\"task\":\"fixture-task\"}");
            priceCommands.transition(command,fence,"registry-wire-fixture","PLATFORM_PENDING",null,null,null);
            return fence;
        }
        if (operation == Operation.RESTORE) {
            completeWireFixture(context,Operation.APPLY,fence,"{\"accepted\":true,\"task\":\"fixture-task\"}");
        }
        priceCommands.transition(command,fence,"registry-wire-fixture","READBACK_PENDING",null,null,null);
        if (operation == Operation.READBACK) return fence;
        String body = "{\"price\":\""+context.command().targetPrice()+"\",\"currency\":\""+context.command().currencyCode()+"\"}";
        completeWireFixture(context,Operation.READBACK,fence,body);
        priceCommands.transition(command,fence,"registry-wire-fixture","READBACK_MISMATCH",null,null,null);
        priceCommands.transition(command,fence,"registry-wire-fixture","COMPENSATION_PENDING",null,null,null);
        fence = jdbc.sql("SELECT ops.lease_price_compensation(:id,'registry-wire-fixture',600)")
                .param("id",command).query(Long.class).single();
        completeWireFixture(context,Operation.READBACK,fence,body);
        return fence;
    }

    private void completeWireFixture(WireContext context,Operation operation,long fence,String body) {
        UUID attempt = UUID.randomUUID();
        var command = context.command();
        var request = new PriceWriteRequest(operation,command.capabilityId(),context.credential(),context.listing(),context.variant(),
                Money.of(command.targetPrice(),command.currencyCode()),command.idempotencyKey(),null,null,attempt);
        priceCommands.openAttempt(attempt,command.id(),operation.name(),fence,"registry-wire-fixture",request.digest(),"registry-wire-fixture");
        assertThat(callSpecs.priceAttemptCurrent(request)).as("canonical fixture %s",operation).isTrue();
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var response = new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED,null,null,null,null,bytes,Instant.now(),null,
                new PriceWriteResult.Response(200,Map.of("etag","fixture-etag"),request.digest(),"PROTOCOL_FIXTURE"));
        var recorded = priceCommands.completeAttempt(attempt,fence,"registry-wire-fixture",response,
                custody.store("registry-wire-fixture",bytes).contentId(),request.digest());
        assertThat(recorded.outcome()).isEqualTo(PriceWriteResult.Outcome.ACCEPTED);
        assertThat(callSpecs.priceAttemptCurrent(request)).as("completed attempt cannot dispatch again").isFalse();
        if (operation == Operation.READBACK) {
            assertThat(priceCommands.insertReadback(UUID.randomUUID(),command.id(),attempt,fence,"registry-wire-fixture","registry-wire-fixture"))
                    .isEqualTo("MATCHES_TARGET");
        }
    }

    private long version(String table,UUID id) {
        return jdbc.sql("SELECT version FROM platform."+table+" WHERE id=:id").param("id",id).query(Long.class).single();
    }

    private UUID newEndpoint(String operation) {
        UUID id=UUID.randomUUID();
        run("""
                INSERT INTO platform.platform_endpoint(id,platform_code,endpoint_code,api_version,capability_id,read_write_class,
                    pagination_model,idempotency_support,verification_state,owner_label,contract_test_status,status,created_at,updated_at)
                VALUES (:id,:platform,:code,'v1',:capability,:kind,'UNKNOWN','UNKNOWN','UNVERIFIED','fixture','NOT_IMPLEMENTED','ACTIVE',now(),now())
                """,Map.of("id",id,"platform",platform,"code","operation-"+id,"capability",capability,
                    "kind",operation.equals("RESTORE")?"WRITE":"READ"));
        configureEndpoint(id,operation);
        return id;
    }

    private void configureEndpoint(UUID id,String operation) {
        var definition=new java.util.HashMap<String,Object>();
        boolean writing=Set.of("APPLY","RESTORE").contains(operation);
        definition.put("http_method",writing?"POST":"GET"); definition.put("path_template","/"+operation.toLowerCase(java.util.Locale.ROOT));
        definition.put("operation_function",operation.equals("STATUS_ENQUIRY")?"PRICE_STATUS":"PRICE_"+operation);
        definition.put("response_content_type","application/json"); definition.put("pagination_model","NONE"); definition.put("rate_limit_per_minute",60);
        if (!writing) definition.put("query_template",operation.equals("STATUS_ENQUIRY")?"task={nativeTaskKey}":"sku={nativeVariantKey}");
        service.configure(author,account,capability,"ENDPOINT",id,version("platform_endpoint",id),definition);
    }

    private UUID submit(List<UUID> endpoints) {
        return service.submit(author,account,capability,endpoints,List.of(header),evidence("REAL_ACCOUNT"),service.configuration(author,account,capability).digest());
    }

    private UUID submit(String evidenceClass) {
        return service.submit(author,account,capability,List.of(endpoint),List.of(header),evidence(evidenceClass),service.configuration(author,account,capability).digest());
    }

    private Map<String,Object> evidence(String evidenceClass) {
        return Map.of(
                "officialSourceUrl","https://docs.fixture.invalid/protocol","officialSourceSha256","1".repeat(64),
                "accountEvidenceRef","evidence://fixture/account-protocol","accountEvidenceSha256","2".repeat(64),
                "evidenceClass",evidenceClass,"testedAt",Instant.now().minusSeconds(3600).toString(),
                "validUntil",Instant.now().plusSeconds(3600).toString());
    }

    private AuthenticatedActor person(UUID provider) {
        return person(provider,organization);
    }

    private AuthenticatedActor person(UUID provider,UUID organization) {
        UUID id=UUID.randomUUID();
        run("""
                INSERT INTO iam.user_account(id,organization_id,identity_provider_id,external_subject,display_name,status,
                    credentials_valid_from,created_at,updated_at) VALUES (:id,:organization,:provider,:subject,'Synthetic reviewer','ACTIVE',now()-interval '1 day',now(),now())
                """,Map.of("id",id,"organization",organization,"provider",provider,"subject",id.toString()));
        run("""
                INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,effective_from,status,created_at,updated_at)
                VALUES (gen_random_uuid(),:organization,:id,'OWNER',now()-interval '1 day','ACTIVE',now(),now())
                """,Map.of("organization",organization,"id",id));
        run("""
                INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,effective_from,status,created_at,updated_at)
                VALUES (gen_random_uuid(),:organization,:id,'KILL_SWITCH_OPERATE',:organization,now()-interval '1 day','ACTIVE',now(),now())
                """,Map.of("organization",organization,"id",id));
        return new AuthenticatedActor(id,organization,provider,"https://identity.fixture.invalid/"+provider,"Synthetic reviewer","3".repeat(64),
                "4".repeat(64),Instant.now(),Instant.now().plusSeconds(900),true,Set.of(BusinessRoleCode.OWNER));
    }

    private void run(String sql,Map<String,?> params) { arranger.sql(sql).params(params).update(); }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(AuthenticatedActor actor) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,null,List.of()));
    }
}
