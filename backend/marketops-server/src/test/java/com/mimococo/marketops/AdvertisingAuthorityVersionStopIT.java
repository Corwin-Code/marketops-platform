package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static com.mimococo.marketops.AdvertisingRetryProofIT.assertSqlState;

import com.mimococo.marketops.identityaccess.internal.web.BrowserSigningFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real signed HTTP identity, separate invocation issuer and app SQL; Owner policy graphs remain fictional inputs. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1","marketops.identity.oidc.issuer-uri=https://id.example.test/browser",
        "marketops.identity.oidc.jwk-set-uri=https://127.0.0.1/unused-secondary-decoder",
        "marketops.identity.oidc.audience=marketops"})
@Import(BrowserSigningFixture.class)
@ActiveProfiles("ci")
class AdvertisingAuthorityVersionStopIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static final String ISSUER_PASSWORD=UUID.randomUUID().toString();
    @LocalServerPort int port;
    @Autowired DataSource application;
    @Autowired tools.jackson.databind.ObjectMapper json;
    private DataSource migration,admin;
    private JdbcClient seed,app;
    private AdvertisingR1Fixture.Graph graph;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
        registry.add("marketops.identity.invocation.jdbc-url",DATABASE::getJdbcUrl);
        registry.add("marketops.identity.invocation.username",()->"marketops_identity_issuer");
        registry.add("marketops.identity.invocation.password",()->ISSUER_PASSWORD);
    }
    @BeforeEach void fixture() throws Exception {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);app=JdbcClient.create(application);graph=AdvertisingR1Fixture.seed(migration);
        for(String person:List.of("ownerUser","verifierUser")) seed.sql("""
            INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,store_ref_id,effective_from,status,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:actor,'ADVERTISING_POLICY_MANAGE',:store,now()-interval '1 day','ACTIVE',
              'Synthetic Store scope cannot imply organization authority',now(),now())
            """).param("org",graph.id("organization")).param("actor",graph.id(person)).param("store",graph.id("store")).update();
        try(var connection=admin.getConnection();var statement=connection.createStatement()) {
            statement.execute("ALTER ROLE marketops_identity_issuer LOGIN PASSWORD '"+ISSUER_PASSWORD+"'");
        }
    }
    @ParameterizedTest @ValueSource(strings={"ownerUser","verifierUser"})
    void organizationScopedSignedHttpStopQuarantinesEveryConsumerWithoutCrossingOrganizations(String person) throws Exception {
        configureBrowserIdentity();orgGrant(graph.id(person));orgGrant(graph.id("verifierUser"));
        UUID command=sealedCommand();Consumer second=secondPublishedConsumer();Consumer unrelated=sameStorePublishedNonconsumer();
        var foreign=AdvertisingR1Fixture.seed(migration);
        assertThat(active(graph.id("object"),graph.id("store"))).isEmpty();
        assertThat(active(second.object(),second.store())).isEmpty();
        assertThat(active(unrelated.object(),unrelated.store())).isEmpty();
        var response=post(person,graph.id("targetPolicy"),graph.id("verifierUser"));
        assertThat(response.statusCode()).isEqualTo(200);
        UUID containment=UUID.fromString(json.readTree(response.body()).path("containmentId").asText());
        assertThat(active(graph.id("object"),graph.id("store"))).containsExactly("AUTHORITY_VERSION_QUARANTINE");
        assertThat(active(second.object(),second.store())).containsExactly("AUTHORITY_VERSION_QUARANTINE");
        assertThat(active(unrelated.object(),unrelated.store())).isEmpty();
        assertThat(app.sql("SELECT ops.ad_active_containment(:org,:object,:store,:platform,'ad-bid-change',NULL)")
                .param("org",foreign.id("organization")).param("object",foreign.id("object")).param("store",foreign.id("store"))
                .param("platform",foreign.platform()).query((row,n)->(String[])row.getArray(1).getArray()).single()).isEmpty();
        assertThat(app.sql("SELECT scope_kind,authority_version_reference,activated_by_user_id,review_owner_user_id,cause_class,reason,evidence_reference,activated_at FROM ops.ad_containment WHERE id=:id")
                .param("id",containment).query().singleRow()).containsEntry("scope_kind","AUTHORITY_VERSION")
                .containsEntry("authority_version_reference",graph.id("targetPolicy").toString())
                .containsEntry("activated_by_user_id",graph.id(person)).containsEntry("review_owner_user_id",graph.id("verifierUser"))
                .containsEntry("cause_class","AUTHORITY_VERSION_INVALID").containsEntry("reason","Reviewed synthetic target policy defect")
                .containsEntry("evidence_reference","fixture://reviewed-authority-version");
        assertThat(app.sql("SELECT count(*) FROM ops.ad_authority_invalidation WHERE cause_reference=:id")
                .param("id",containment).query(Integer.class).single()).isEqualTo(1);
        assertThat(app.sql("SELECT unnest(ops.evaluate_ad_bid_write_gate(:id))").param("id",command).query(String.class).list())
                .contains("QUARANTINE_ACTIVE","AUTHORITY_PERMANENTLY_INVALIDATED");
        assertSqlState(()->app.sql("SELECT ops.create_ad_bid_command(:rec,(SELECT version FROM ops.recommendation WHERE id=:rec),:reservation,'invalid-version-replay')")
                .param("rec",graph.id("recommendation")).param("reservation",graph.id("reservation")).query(UUID.class).single(),"MO092");
        assertThat(app.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",graph.id("gate")).query(Boolean.class).single()).isFalse();
    }
    @Test void signedHttpStoreOnlyActorCannotPublishOrganizationWideAuthorityStop() throws Exception {
        configureBrowserIdentity();orgGrant(graph.id("verifierUser"));
        var response=post("ownerUser",graph.id("targetPolicy"),graph.id("verifierUser"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(json.readTree(response.body()).path("title").asText()).isEqualTo("RESOURCE_SCOPE_DENIED");
        assertNoPublication();
    }
    @ParameterizedTest @ValueSource(strings={"STORE_ACTOR","STORE_REVIEWER","FOREIGN_VERSION","UNCONSUMED_VERSION","OPERATOR_ROLE"})
    void actualApplicationSinkRequiresOrganizationControlReviewScopeAndExactConsumedVersion(String fault) throws Exception {
        UUID actor=graph.id("ownerUser"),reference=graph.id("targetPolicy");
        if(!fault.equals("STORE_ACTOR")) orgGrant(actor);
        if(!fault.equals("STORE_REVIEWER")) orgGrant(graph.id("verifierUser"));
        if(fault.equals("FOREIGN_VERSION")) reference=AdvertisingR1Fixture.seed(migration).id("targetPolicy");
        if(fault.equals("UNCONSUMED_VERSION")) reference=UUID.randomUUID();
        if(fault.equals("OPERATOR_ROLE")) {actor=graph.id("executorUser");orgGrant(actor);}
        UUID exactActor=actor,exactReference=reference;
        assertSqlState(()->stopSql(exactActor,exactReference,"AUTHORITY_VERSION_STOP"),"MO064");assertNoPublication();
    }
    @Test void finalApprovalOrObjectStopProofCannotBeReplayedAsVersionQuarantine() throws Exception {
        orgGrant(graph.id("ownerUser"));orgGrant(graph.id("verifierUser"));
        assertSqlState(()->stopSql(graph.id("ownerUser"),graph.id("targetPolicy"),"CONTAINMENT_STOP"),"MO092");assertNoPublication();
    }
    @Test void currentAndFrozenPurposeFreshnessAreExplicitConsumedVersionsRatherThanArbitraryJsonIds() throws Exception {
        orgGrant(graph.id("ownerUser"));orgGrant(graph.id("verifierUser"));sealedCommand();
        UUID profile=seed.sql("SELECT (authority_snapshot#>>'{freshness,0,id}')::uuid FROM ops.ad_action_authorization WHERE recommendation_id=:id")
                .param("id",graph.id("recommendation")).query(UUID.class).single();
        assertThat(app.sql("SELECT ops.ad_bundle_consumes_authority_version(:bundle,:id)").param("bundle",graph.id("bundle"))
                .param("id",profile).query(Boolean.class).single()).isTrue();
        assertThat(app.sql("SELECT ops.ad_bundle_consumes_authority_version(:bundle,:id)").param("bundle",graph.id("bundle"))
                .param("id",graph.id("ownerUser")).query(Boolean.class).single()).isFalse();
        UUID containment=stopSql(graph.id("ownerUser"),profile,"AUTHORITY_VERSION_STOP");assertThat(containment).isNotNull();
        assertThat(active(graph.id("object"),graph.id("store"))).contains("AUTHORITY_VERSION_QUARANTINE");
    }
    @Test void organizationRecoveryRequiresEveryStoreToReplaceTheVersionBeforeIndependentFinalApproval() throws Exception {
        promotePublisher();orgGrant(graph.id("executorUser"));orgGrant(graph.id("verifierUser"));
        UUID command=sealedCommand();Consumer second=secondPublishedConsumer();
        UUID stop=stopSql(graph.id("executorUser"),graph.id("targetPolicy"),"AUTHORITY_VERSION_STOP");
        UUID replacement=publishedReplacementTargetPolicy();
        Consumer first=new Consumer(graph.id("store"),graph.id("object"),graph.id("bundle"),graph.id("gate"));
        Consumer firstReplaced=replaceBundleThroughThreeActualActors(first,replacement);
        // A Store-only Ops grant cannot attest to organization-wide recovery.
        seed.sql("UPDATE iam.user_scope_grant SET status='REVOKED' WHERE user_id=:actor AND organization_ref_id=:org AND action_code='ADVERTISING_POLICY_MANAGE'")
                .param("actor",graph.id("verifierUser")).param("org",graph.id("organization")).update();
        assertSqlState(()->attest(stop,"ROOT_CAUSE_CLASSIFIED"),"MO097");
        orgGrant(graph.id("verifierUser"));
        for(String condition:List.of("ROOT_CAUSE_CLASSIFIED","UNKNOWNS_RESOLVED","AUTHORITIES_REPLACED",
                "RESULTS_RECONCILED","CAPABILITY_EVIDENCE_CURRENT","OPERATIONS_ENDORSEMENT")) attest(stop,condition);
        // The final Owner is also still Store-scoped at this first refusal.
        assertSqlState(()->reenable(stop,firstReplaced.bundle()),"MO097");
        orgGrant(graph.id("ownerUser"));
        // Full human quorum and one replacement cannot reopen the second Store.
        assertSqlState(()->reenable(stop,firstReplaced.bundle()),"MO097");
        assertThat(app.sql("SELECT state FROM ops.ad_containment WHERE id=:id").param("id",stop).query(String.class).single())
                .isEqualTo("REENABLEMENT_REVIEW");
        assertThat(active(second.object(),second.store())).contains("AUTHORITY_VERSION_QUARANTINE");
        Consumer secondReplaced=replaceBundleThroughThreeActualActors(second,replacement);
        assertThat(reenable(stop,firstReplaced.bundle())).isTrue();
        assertThat(active(first.object(),first.store())).isEmpty();assertThat(active(second.object(),second.store())).isEmpty();
        assertThat(app.sql("SELECT activated_by_user_id,endorsed_by_user_id,approved_by_user_id,reenabled_scope->>'invalidVersionRemainsForbidden' AS forbidden FROM ops.ad_containment WHERE id=:id")
                .param("id",stop).query().singleRow()).containsEntry("activated_by_user_id",graph.id("executorUser"))
                .containsEntry("endorsed_by_user_id",graph.id("verifierUser")).containsEntry("approved_by_user_id",graph.id("ownerUser"))
                .containsEntry("forbidden","true");
        assertThat(app.sql("SELECT unnest(ops.evaluate_ad_bid_write_gate(:id))").param("id",command).query(String.class).list())
                .contains("AUTHORITY_PERMANENTLY_INVALIDATED");
        assertThat(app.sql("SELECT unnest(ops.ad_bundle_validation_failures(:id))").param("id",first.bundle()).query(String.class).list())
                .contains("AUTHORITY_VERSION_QUARANTINED");
        for(UUID gate:List.of(firstReplaced.gate(),secondReplaced.gate())) assertThat(app.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
                .param("id",gate).query(Boolean.class).single()).isFalse();
    }
    @Test void anExistingIndependentHoldCannotBeAttributedToANewUnconsumedVersionOrBlockItsRecovery() throws Exception {
        promotePublisher();for(String person:List.of("executorUser","verifierUser","ownerUser")) orgGrant(graph.id(person));
        UUID command=sealedCommand();Consumer versionConsumer=sameStorePublishedNonconsumer();
        UUID version=seed.sql("SELECT target_policy_id FROM ops.ad_decision_policy_bundle WHERE id=:id")
                .param("id",versionConsumer.bundle()).query(UUID.class).single();
        // Unresolved existing execution is an explicit synthetic worker-state input,
        // not an assertion that this engineering run sent a Provider request.
        seed.sql("UPDATE ops.ad_bid_command SET state='EXECUTING',fence_token=1,lease_owner='synthetic-existing-execution',lease_expires_at=clock_timestamp()+interval '5 minutes' WHERE id=:id")
                .param("id",command).update();
        UUID oldHold=holdOriginalObject();
        assertThat(active(graph.id("object"),graph.id("store"))).containsExactly("EMERGENCY_ENTITY_HOLD");
        assertThat(active(versionConsumer.object(),versionConsumer.store())).isEmpty();
        UUID versionStop=stopSql(graph.id("executorUser"),version,"AUTHORITY_VERSION_STOP");
        assertThat(app.sql("SELECT count(*) FROM ops.ad_authority_invalidation WHERE cause_reference=:id")
                .param("id",oldHold).query(Integer.class).single()).isPositive();
        assertThat(app.sql("SELECT count(*) FROM ops.ad_authority_invalidation WHERE cause_reference=:id")
                .param("id",versionStop).query(Integer.class).single()).isZero();
        assertThat(active(graph.id("object"),graph.id("store"))).containsExactly("EMERGENCY_ENTITY_HOLD");
        assertThat(active(versionConsumer.object(),versionConsumer.store())).containsExactly("AUTHORITY_VERSION_QUARANTINE");
        Consumer replacement=replaceBundleThroughThreeActualActors(versionConsumer,publishedReplacementTargetPolicy(version));
        for(String condition:List.of("ROOT_CAUSE_CLASSIFIED","UNKNOWNS_RESOLVED","AUTHORITIES_REPLACED",
                "RESULTS_RECONCILED","CAPABILITY_EVIDENCE_CURRENT","OPERATIONS_ENDORSEMENT")) attest(versionStop,condition);
        assertThat(reenable(versionStop,replacement.bundle())).isTrue();
        assertThat(active(versionConsumer.object(),versionConsumer.store())).isEmpty();
        assertThat(active(graph.id("object"),graph.id("store"))).containsExactly("EMERGENCY_ENTITY_HOLD");
        assertThat(app.sql("SELECT state FROM ops.ad_containment WHERE id=:id").param("id",oldHold).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(app.sql("SELECT state FROM ops.ad_bid_command WHERE id=:id").param("id",command).query(String.class).single()).isEqualTo("EXECUTING");
    }
    private UUID holdOriginalObject() throws Exception {
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);UUID id=UUID.randomUUID();
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("executorUser"),"CONTAINMENT_STOP",graph.id("object"),id);
            try(var call=connection.prepareStatement("SELECT ops.activate_ad_human_containment(?,?,'ENTITY','EMERGENCY_ENTITY_HOLD','BUSINESS_HARM',?,?,?,?)")) {
                call.setObject(1,id);call.setObject(2,graph.id("object"));call.setObject(3,graph.id("verifierUser"));
                call.setString(4,"Independent synthetic local harm");call.setString(5,"fixture://independent-local-hold");call.setString(6,proof);
                call.execute();connection.commit();return id;
            }
        }
    }
    private void promotePublisher() {
        seed.sql("""
            INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,effective_from,status,reason,created_at,updated_at)
            VALUES(gen_random_uuid(),:org,:actor,'OPS_LEAD',now()-interval '1 day','ACTIVE','Synthetic distinct policy maker',now(),now())
            """).param("org",graph.id("organization")).param("actor",graph.id("executorUser")).update();
    }
    private void attest(UUID stop,String condition) throws Exception {
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String purpose=condition.equals("OPERATIONS_ENDORSEMENT")?"CONTAINMENT_ENDORSE":"CONTAINMENT_ATTEST";
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("verifierUser"),purpose,stop,stop);
            try(var call=connection.prepareStatement("SELECT ops.attest_ad_containment(?,?,?,?)")) {
                call.setObject(1,stop);call.setString(2,condition);call.setString(3,"fixture://org-version-review/"+condition);
                call.setString(4,proof);call.execute();connection.commit();
            }
        }
    }
    private boolean reenable(UUID stop,UUID bundle) throws Exception {
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("ownerUser"),"CONTAINMENT_REENABLE",stop,bundle);
            try(var call=connection.prepareStatement("SELECT ops.reenable_ad_containment(?,?,?)")) {
                call.setObject(1,stop);call.setObject(2,bundle);call.setString(3,proof);
                try(var rows=call.executeQuery()){rows.next();boolean result=rows.getBoolean(1);connection.commit();return result;}
            }
        }
    }
    private UUID publishedReplacementTargetPolicy() { return publishedReplacementTargetPolicy(graph.id("targetPolicy")); }
    private UUID publishedReplacementTargetPolicy(UUID priorPolicy) {
        UUID policy=UUID.randomUUID();
        // Synthetic Owner policy publication is input; no attestation or human Bundle decision is seeded.
        seed.sql("UPDATE core.ad_bid_target_policy SET status='RETIRED' WHERE id=:id").param("id",priorPolicy).update();
        seed.sql("""
            INSERT INTO core.ad_bid_target_policy SELECT (jsonb_populate_record(NULL::core.ad_bid_target_policy,
              to_jsonb(prior)||jsonb_build_object('id',:id::text,'policy_version',prior.policy_version+1,'status','ACTIVE'))).*
            FROM core.ad_bid_target_policy prior WHERE id=:prior
            """).param("id",policy).param("prior",priorPolicy).update();return policy;
    }
    private Consumer replaceBundleThroughThreeActualActors(Consumer prior,UUID target) throws Exception {
        Consumer next=new Consumer(prior.store(),prior.object(),UUID.randomUUID(),UUID.randomUUID());
        String content=seed.sql("""
            SELECT (to_jsonb(bundle)||jsonb_build_object('id',:id::text,'bundle_version',bundle.bundle_version+1,
              'target_policy_id',:target::text,'gate_scope_reference',:gate::text,
              'effective_from',clock_timestamp(),'effective_to',clock_timestamp()+interval '1 hour'))::text
            FROM ops.ad_decision_policy_bundle bundle WHERE id=:prior
            """).param("id",next.bundle()).param("target",target).param("gate",next.gate()).param("prior",prior.bundle()).query(String.class).single();
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("executorUser"),"BUNDLE_DRAFT",next.bundle(),next.gate());
            try(var call=connection.prepareStatement("SELECT ops.create_ad_bundle_draft(?::jsonb,?)")) {
                call.setString(1,content);call.setString(2,proof);call.execute();connection.commit();
            }
        }
        seed.sql("INSERT INTO ops.ad_gate_authority SELECT (jsonb_populate_record(NULL::ops.ad_gate_authority,to_jsonb(gate)||jsonb_build_object('id',:id::text,'bundle_id',:bundle::text))).* FROM ops.ad_gate_authority gate WHERE id=:prior")
                .param("id",next.gate()).param("bundle",next.bundle()).param("prior",prior.gate()).update();
        bundleControl(next,"verifierUser","BUNDLE_ENDORSE","endorse_ad_bundle");
        bundleControl(next,"ownerUser","BUNDLE_APPROVE","activate_ad_bundle");return next;
    }
    private void bundleControl(Consumer next,String person,String purpose,String function) throws Exception {
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id(person),purpose,next.bundle(),next.gate());
            try(var call=connection.prepareStatement("SELECT ops."+function+"(?,?,?)")) {
                call.setObject(1,next.bundle());call.setObject(2,next.gate());call.setString(3,proof);call.execute();connection.commit();
            }
        }
    }
    private UUID stopSql(UUID actor,UUID reference,String purpose) throws Exception {
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);UUID id=UUID.randomUUID();
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,actor,purpose,reference,id);
            try(var query=connection.prepareStatement("SELECT ops.activate_ad_authority_version_containment(?,?,?,?,?,?)")) {
                query.setObject(1,id);query.setObject(2,reference);query.setObject(3,graph.id("verifierUser"));
                query.setString(4,"Reviewed synthetic target policy defect");query.setString(5,"fixture://reviewed-authority-version");query.setString(6,proof);
                query.execute();connection.commit();return id;
            }
        }
    }
    private UUID sealedCommand() throws Exception {
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("ownerUser"),null,graph.id("recommendation"),graph.id("approval"));
            AdvertisingR1Fixture.seal(connection,graph,proof);UUID id=AdvertisingR1Fixture.createCommand(connection,graph);connection.commit();return id;
        }
    }
    private void orgGrant(UUID actor) {
        seed.sql("""
            INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,effective_from,status,reason,created_at,updated_at)
            SELECT gen_random_uuid(),:org,:actor,'ADVERTISING_POLICY_MANAGE',:org,now()-interval '1 day','ACTIVE',
              'Explicit synthetic organization authority',now(),now()
            WHERE NOT EXISTS(SELECT 1 FROM iam.user_scope_grant WHERE user_id=:actor AND action_code='ADVERTISING_POLICY_MANAGE' AND organization_ref_id=:org AND status='ACTIVE')
            """).param("org",graph.id("organization")).param("actor",actor).update();
    }
    private void configureBrowserIdentity() {
        UUID provider=seed.sql("SELECT id FROM iam.identity_provider WHERE issuer=:issuer").param("issuer",BrowserSigningFixture.ISSUER)
                .query(UUID.class).optional().orElseGet(()->{seed.sql("UPDATE iam.identity_provider SET issuer=:issuer WHERE id=:id")
                    .param("issuer",BrowserSigningFixture.ISSUER).param("id",graph.id("provider")).update();return graph.id("provider");});
        for(String person:List.of("executorUser","verifierUser","ownerUser")) seed.sql("UPDATE iam.user_account SET identity_provider_id=:provider,external_subject=:subject,credentials_valid_from=now()-interval '1 day' WHERE id=:id")
                .param("provider",provider).param("subject","authority-stop-"+graph.id(person)).param("id",graph.id(person)).update();
        var ids=new HashMap<>(graph.ids());ids.put("provider",provider);graph=new AdvertisingR1Fixture.Graph(ids,graph.platform());
    }
    private HttpResponse<String> post(String person,UUID reference,UUID reviewer) throws Exception {
        String token=BrowserSigningFixture.token("authority-stop-"+graph.id(person));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/console/advertising/containments/authority-versions/"+reference+"/stop"))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reviewOwnerUserId\":\""+reviewer+"\",\"reason\":\"Reviewed synthetic target policy defect\",\"evidenceReference\":\"fixture://reviewed-authority-version\"}"))
                .build(),HttpResponse.BodyHandlers.ofString());
    }
    private String[] active(UUID object,UUID store) {return app.sql("SELECT ops.ad_active_containment(:org,:object,:store,:platform,'ad-bid-change',NULL)")
            .param("org",graph.id("organization")).param("object",object).param("store",store).param("platform",graph.platform())
            .query((row,n)->(String[])row.getArray(1).getArray()).single();}
    private void assertNoPublication(){assertThat(app.sql("SELECT count(*) FROM ops.ad_containment WHERE organization_id=:org")
            .param("org",graph.id("organization")).query(Integer.class).single()).isZero();}
    private record Consumer(UUID store,UUID object,UUID bundle,UUID gate) { }
    private Consumer secondPublishedConsumer() {
        UUID store=UUID.randomUUID(),object=UUID.randomUUID(),bundle=UUID.randomUUID(),gate=UUID.randomUUID();
        seed.sql("INSERT INTO core.store SELECT (jsonb_populate_record(NULL::core.store,to_jsonb(prior)||jsonb_build_object('id',:id::text,'code',:code))).* FROM core.store prior WHERE id=:prior")
                .param("id",store).param("code","consumer-"+store).param("prior",graph.id("store")).update();
        seed.sql("INSERT INTO core.ad_native_object SELECT (jsonb_populate_record(NULL::core.ad_native_object,to_jsonb(prior)||jsonb_build_object('id',:id::text,'store_id',:store::text,'native_object_key',:key))).* FROM core.ad_native_object prior WHERE id=:prior")
                .param("id",object).param("store",store).param("key","consumer-"+object).param("prior",graph.id("object")).update();
        // Independently published synthetic Owner policy graph is input; only the quarantine action is asserted as a real human request.
        seed.sql("""
            INSERT INTO ops.ad_decision_policy_bundle SELECT (jsonb_populate_record(NULL::ops.ad_decision_policy_bundle,
              to_jsonb(prior)||jsonb_build_object('id',:id::text,'bundle_version',prior.bundle_version+1,'store_id',:store::text,
                'status','DRAFT','validation_state','PENDING','validation_failure_codes',jsonb_build_array('NOT_VALIDATED'),'gate_authority_id',NULL,'gate_scope_reference',:gate::text))).*
            FROM ops.ad_decision_policy_bundle prior WHERE id=:prior
            """).param("id",bundle).param("store",store).param("gate",gate).param("prior",graph.id("bundle")).update();
        seed.sql("""
            INSERT INTO ops.ad_gate_authority SELECT (jsonb_populate_record(NULL::ops.ad_gate_authority,to_jsonb(prior)||
              jsonb_build_object('id',:id::text,'bundle_id',:bundle::text,'store_id',:store::text,
                'native_object_ids',jsonb_build_array(:object::text),'demonstrated_object_ids',jsonb_build_array(:object::text),
                'exact_object_values',jsonb_build_object(:object::text,jsonb_build_object('currentBid',30,'targetBid',20,'currencyCode','RUB','bidUnitCode','CURRENCY_MAJOR'))))).*
            FROM ops.ad_gate_authority prior WHERE id=:prior
            """).param("id",gate).param("bundle",bundle).param("store",store).param("object",object).param("prior",graph.id("gate")).update();
        seed.sql("UPDATE ops.ad_decision_policy_bundle SET gate_authority_id=:gate,status='ACTIVE',validation_state='VALIDATED',validation_failure_codes='{}' WHERE id=:id")
                .param("gate",gate).param("id",bundle).update();return new Consumer(store,object,bundle,gate);
    }
    private Consumer sameStorePublishedNonconsumer() {
        UUID object=UUID.randomUUID(),profile=UUID.randomUUID(),target=UUID.randomUUID(),bundle=UUID.randomUUID(),gate=UUID.randomUUID();
        seed.sql("""
            INSERT INTO platform.ad_semantic_profile SELECT (jsonb_populate_record(NULL::platform.ad_semantic_profile,
              to_jsonb(prior)||jsonb_build_object('id',:id::text,'native_object_kind','PLACEMENT','control_level','PLACEMENT'))).*
            FROM platform.ad_semantic_profile prior WHERE id=:prior
            """).param("id",profile).param("prior",graph.id("profile")).update();
        seed.sql("""
            INSERT INTO core.ad_bid_target_policy SELECT (jsonb_populate_record(NULL::core.ad_bid_target_policy,
              to_jsonb(prior)||jsonb_build_object('id',:id::text,'native_object_kind','PLACEMENT'))).*
            FROM core.ad_bid_target_policy prior WHERE id=:prior
            """).param("id",target).param("prior",graph.id("targetPolicy")).update();
        seed.sql("""
            INSERT INTO core.ad_native_object SELECT (jsonb_populate_record(NULL::core.ad_native_object,to_jsonb(prior)||
              jsonb_build_object('id',:id::text,'semantic_profile_id',:profile::text,'native_object_kind','PLACEMENT','native_object_key',:key))).*
            FROM core.ad_native_object prior WHERE id=:prior
            """).param("id",object).param("profile",profile).param("key","nonconsumer-"+object).param("prior",graph.id("object")).update();
        seed.sql("""
            INSERT INTO ops.ad_decision_policy_bundle SELECT (jsonb_populate_record(NULL::ops.ad_decision_policy_bundle,to_jsonb(prior)||
              jsonb_build_object('id',:id::text,'native_object_kind','PLACEMENT','semantic_profile_id',:profile::text,'target_policy_id',:target::text,
                'status','DRAFT','validation_state','PENDING','validation_failure_codes',jsonb_build_array('NOT_VALIDATED'),'gate_authority_id',NULL,'gate_scope_reference',:gate::text))).*
            FROM ops.ad_decision_policy_bundle prior WHERE id=:prior
            """).param("id",bundle).param("profile",profile).param("target",target).param("gate",gate).param("prior",graph.id("bundle")).update();
        seed.sql("""
            INSERT INTO ops.ad_gate_authority SELECT (jsonb_populate_record(NULL::ops.ad_gate_authority,to_jsonb(prior)||
              jsonb_build_object('id',:id::text,'bundle_id',:bundle::text,
                'native_object_ids',jsonb_build_array(:object::text),'demonstrated_object_ids',jsonb_build_array(:object::text),
                'exact_object_values',jsonb_build_object(:object::text,jsonb_build_object('currentBid',30,'targetBid',20,'currencyCode','RUB','bidUnitCode','CURRENCY_MAJOR'))))).*
            FROM ops.ad_gate_authority prior WHERE id=:prior
            """).param("id",gate).param("bundle",bundle).param("object",object).param("prior",graph.id("gate")).update();
        seed.sql("UPDATE ops.ad_decision_policy_bundle SET gate_authority_id=:gate,status='ACTIVE',validation_state='VALIDATED',validation_failure_codes='{}' WHERE id=:id")
                .param("gate",gate).param("id",bundle).update();return new Consumer(graph.id("store"),object,bundle,gate);
    }

}
