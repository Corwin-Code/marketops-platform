package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.AdvertisingCaseQuery;
import com.mimococo.marketops.advertisingefficiency.internal.application.AdvertisingDisclosureService;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingDisclosureRepository;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.internal.application.UserAdministrationService;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real PostgreSQL ownership, role matrix, live grants and all five delivery projections. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1","marketops.identity.oidc.issuer-uri=https://id.example.test/browser",
        "marketops.identity.oidc.jwk-set-uri=https://127.0.0.1/unused-secondary-decoder",
        "marketops.identity.oidc.audience=marketops"})
@org.springframework.context.annotation.Import(com.mimococo.marketops.identityaccess.internal.web.BrowserSigningFixture.class)
@ActiveProfiles("ci")
class AdvertisingDisclosureIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE =
            TestDatabase.isolatedContainer();
    @Autowired private AdvertisingDisclosureService disclosure;
    @Autowired private com.mimococo.marketops.marketplaceintegration.AdBidCommandGateway commands;
    @Autowired private AdvertisingDisclosureRepository scopes;
    @Autowired private AdvertisingCaseQuery cases;
    @Autowired private com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingWorkflowQueryService workflow;
    @org.springframework.boot.test.web.server.LocalServerPort private int port;
    @Autowired private UserAdministrationService users;
    @Autowired private com.mimococo.marketops.operationsworkflow.internal.application.RecommendationService recommendations;
    private JdbcClient seed;
    private AdvertisingGraphFixture.Graph graph;
    private AuthenticatedActor maker;
    private AuthenticatedActor checker;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @BeforeEach
    void fixture() {
        seed = JdbcClient.create(new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword()));
        graph = AdvertisingGraphFixture.seed(seed);
        users.assignRole("disclosure-fixture", graph.executorUserId(), BusinessRoleCode.MARKETPLACE_OPERATOR, null);
        users.assignRole("disclosure-fixture", graph.verifierUserId(), BusinessRoleCode.OWNER, null);
        for (UUID user : List.of(graph.executorUserId(), graph.verifierUserId())) {
            grant(user, ActionScopeCode.ADVERTISING_VIEW, ResourceScopeType.ORGANIZATION, graph.organizationId());
        }
        maker = actor(graph.executorUserId(), BusinessRoleCode.MARKETPLACE_OPERATOR);
        checker = actor(graph.verifierUserId(), BusinessRoleCode.OWNER);
    }

    @Test
    void makerCannotDiscloseFinancialEvidenceThroughAnyDeliveryChannelEvenWithAResourceGrant() {
        grant(maker.userId(), ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.ORGANIZATION, graph.organizationId());
        var view = cases.caseById(graph.organizationId(), graph.caseId(), List.of(graph.storeId()),
                List.of(graph.productVariantId())).orElseThrow();
        for (var channel : AdvertisingDisclosureService.Channel.values()) {
            var projected = disclosure.caseView(maker, view, channel);
            assertThat(projected.path("contributionProfitState").asText()).isEqualTo("MASKED");
            assertThat(projected.path("officialSpendAmount").isNull()).isTrue();
            assertThat(projected.path("rankScore").isNull()).isTrue();
            assertThat(projected.path("affectedProductVariantIds").get(0).asText())
                    .isEqualTo(graph.productVariantId().toString());
            for(String field:List.of("contributionProfitAmount","profitPerAdRubValue","officialSpendAmount",
                    "eligibleTrafficCount","adLinkedConversionValue","maxCpcAmount","attributionGapRatio",
                    "recoverableProfitAmount","rankScore","profitCurrencyCode")) {
                assertThat(projected.path(field).isNull()).as("%s must be masked in %s",field,channel).isTrue();
            }
            assertNoFinancialSentinelAmount(projected);
            assertThat(projected.path("productionWriteEnabled").asBoolean()).isFalse();
            assertThat(projected.path("semanticProfile").path("verificationState").asText())
                    .isEqualTo("UNVERIFIED");
        }
        assertThatThrownBy(() -> disclosure.requireDecisionEvidence(maker, graph.objectId()))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void checkerNeedsTheStoreAndEveryAffectedVariantAndRevocationTakesEffectImmediately() {
        grant(checker.userId(), ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.STORE, graph.storeId());
        assertThat(disclosure.mayReadDecisionEvidence(checker, graph.objectId())).isFalse();
        var variantGrant = users.grantScope("disclosure-fixture", checker.userId(),
                ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.PRODUCT_VARIANT, graph.productVariantId(), null);
        assertThat(disclosure.mayReadDecisionEvidence(checker, graph.objectId())).isTrue();
        var view = cases.caseById(graph.organizationId(), graph.caseId(), List.of(graph.storeId()),
                List.of(graph.productVariantId())).orElseThrow();
        assertThat(disclosure.caseView(checker, view, AdvertisingDisclosureService.Channel.API)
                .path("contributionProfitAmount").decimalValue()).isEqualByComparingTo("-1200");
        users.revokeScope("disclosure-fixture", variantGrant.id(), "fixture revoke", variantGrant.version());
        assertThat(disclosure.mayReadDecisionEvidence(checker, graph.objectId())).isFalse();
        assertThat(disclosure.caseView(checker, view, AdvertisingDisclosureService.Channel.EXPORT)
                .path("contributionProfitState").asText()).isEqualTo("MASKED");
    }

    @Test
    void anOtherOrganizationCannotResolveObjectsOrBriefReferences() {
        assertThat(scopes.objectScope(UUID.randomUUID(), graph.objectId())).isEmpty();
        assertThat(scopes.relationships(UUID.randomUUID(), graph.objectId(), graph.storeId())).isEmpty();
        assertThat(scopes.visibleBriefReferences(graph.organizationId(), UUID.randomUUID(),
                List.of(graph.storeId()), false)).isEmpty();
        assertThat(scopes.relevantContainmentIds(graph.organizationId(), List.of())).isEmpty();
    }

    @Test
    void independentWildberriesCampaignAndPlacementStructureRemainsSyntheticAndUnverified() {
        UUID account = UUID.randomUUID(), store = UUID.randomUUID(), campaign = UUID.randomUUID();
        UUID placement = UUID.randomUUID(), campaignProfile = UUID.randomUUID(), placementProfile = UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.marketplace_account(id, organization_id, legal_entity_id, platform_code,
                    code, display_name, status, created_at, updated_at)
                VALUES (:id, :org, :legal, 'WILDBERRIES', :code, 'Синтетический WB', 'ACTIVE', now(), now())
                """).param("id", account).param("org", graph.organizationId()).param("legal", graph.legalEntityId())
                .param("code", "wb-" + account).update();
        seed.sql("""
                INSERT INTO core.store(id, organization_id, marketplace_account_id, code, display_name,
                    timezone, status, created_at, updated_at)
                VALUES (:id, :org, :account, :code, 'Магазин WB', 'Europe/Moscow', 'ACTIVE', now(), now())
                """).param("id", store).param("org", graph.organizationId()).param("account", account)
                .param("code", "wb-" + store).update();
        wbProfile(campaignProfile, "CAMPAIGN");
        wbProfile(placementProfile, "PLACEMENT");
        wbObject(campaign, store, campaignProfile, "CAMPAIGN", campaign);
        wbObject(placement, store, placementProfile, "PLACEMENT", campaign);
        UUID provenance = seed.sql("SELECT provenance_id FROM core.ad_object_configuration_observation WHERE id = :id")
                .param("id", graph.configurationId()).query(UUID.class).single();
        seed.sql("""
                INSERT INTO core.ad_object_relationship(id, organization_id, parent_object_id, child_object_id,
                    relationship_kind, observed_at, provenance_id, status, created_at)
                VALUES (:id, :org, :parent, :child, 'CONTAINS_OBJECT', now(), :provenance, 'ACTIVE', now())
                """).param("id", UUID.randomUUID()).param("org", graph.organizationId())
                .param("parent", campaign).param("child", placement).param("provenance", provenance).update();
        var ozon = scopes.objectScope(graph.organizationId(), graph.objectId()).orElseThrow();
        var wb = scopes.objectScope(graph.organizationId(), campaign).orElseThrow();
        assertThat(ozon.biddingMode()).isEqualTo("MANUAL_BID");
        assertThat(wb.biddingMode()).isEqualTo("AUTO_BID");
        assertThat(wb.bidUnitCode()).isEqualTo("CURRENCY_MINOR");
        assertThat(wb.verificationState()).isEqualTo("UNVERIFIED");
        var wbRules=new tools.jackson.databind.ObjectMapper().readTree(scopes.nativeRules(graph.organizationId(),campaign).orElseThrow());
        assertThat(wbRules.path("bidPrecision").asInt()).isZero();
        assertThat(wbRules.path("bidStep").decimalValue()).isEqualByComparingTo("100");
        assertThat(wbRules.path("readbackSemantics").asText()).isEqualTo("DERIVED_FIELD");
        assertThat(com.mimococo.marketops.advertisingefficiency.internal.domain.AdBidUnitConversion.toMajor(new java.math.BigDecimal("100"),wb.bidUnitCode())).isEqualByComparingTo("1");
        assertThat(com.mimococo.marketops.advertisingefficiency.internal.domain.AdBidUnitConversion.toMajor(new java.math.BigDecimal("100"),ozon.bidUnitCode())).isEqualByComparingTo("100");
        assertThat(wb.sourceMaturity()).isEqualTo("SYNTHETIC_FIXTURE");
        assertThat(wb.storeTimezone()).isEqualTo("Europe/Moscow");
        assertThat(scopes.relationships(graph.organizationId(), campaign, store)).singleElement()
                .satisfies(row -> {
                    assertThat(row.get("parentKind")).isEqualTo("CAMPAIGN");
                    assertThat(row.get("childKind")).isEqualTo("PLACEMENT");
                    assertThat(row.get("childObjectId")).isEqualTo(placement);
                });
        assertThat(scopes.relationships(graph.organizationId(), campaign, graph.storeId())).isEmpty();
        assertThat(disclosure.mayReadDecisionEvidence(checker, campaign)).isFalse();
    }

    @Test
    void recommendationFinancialScopeComesFromCanonicalCandidateWithoutAddingAParameter() throws Exception {
        var migration = new DriverManagerDataSource(DATABASE.getJdbcUrl(),
                TestDatabase.migrationRole(), TestDatabase.migrationPassword());
        var candidateGraph = AdvertisingR1Fixture.seedUnapproved(migration);
        seed.sql("UPDATE ops.recommendation SET expected_effect=expected_effect||jsonb_build_object('conservativeCeiling',987654321) WHERE id=:id")
                .param("id",candidateGraph.id("recommendation")).update();
        var row = recommendations.require(candidateGraph.id("recommendation"));
        assertThat(row.proposedParameters().keySet()).containsExactlyInAnyOrder("candidateId", "direction", "targetBid");
        var canonicalScope = scopes.recommendationScope(candidateGraph.id("organization"), row.id()).orElseThrow();
        assertThat(canonicalScope.objectId()).isEqualTo(candidateGraph.id("object"));
        UUID reviewer = candidateGraph.id("ownerUser");
        users.grantScope("disclosure-fixture", reviewer, ActionScopeCode.ADVERTISING_VIEW,
                ResourceScopeType.ORGANIZATION, candidateGraph.id("organization"), null);
        UUID candidateMaker=candidateGraph.id("executorUser");
        users.assignRole("disclosure-fixture",candidateMaker,BusinessRoleCode.MARKETPLACE_OPERATOR,null);
        users.grantScope("disclosure-fixture",candidateMaker,ActionScopeCode.ADVERTISING_VIEW,
                ResourceScopeType.ORGANIZATION,candidateGraph.id("organization"),null);
        users.grantScope("disclosure-fixture", reviewer, ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.ORGANIZATION, candidateGraph.id("organization"), null);
        var reviewerActor = new AuthenticatedActor(reviewer,candidateGraph.id("organization"),
                candidateGraph.id("provider"),"https://fixture.invalid/issuer","Synthetic owner","0".repeat(64),null,
                Instant.now(),Instant.now().plusSeconds(3600),true,Set.of(BusinessRoleCode.OWNER));
        var full = disclosure.discloseRecommendation(reviewerActor,row);
        assertThat(full.path("expectedEffect").toString()).isEqualTo(new tools.jackson.databind.ObjectMapper().valueToTree(row.expectedEffect()).toString());
        assertThat(full.path("proposedParameters").has("affectedSetDigest")).isFalse();
        var makerActor = new AuthenticatedActor(candidateMaker,candidateGraph.id("organization"),
                candidateGraph.id("provider"),"https://fixture.invalid/issuer","Synthetic maker","0".repeat(64),null,
                Instant.now(),Instant.now().plusSeconds(3600),true,Set.of(BusinessRoleCode.MARKETPLACE_OPERATOR));
        var outsider = disclosure.discloseRecommendation(makerActor,row);
        assertThat(outsider.path("disclosureState").asText()).isEqualTo("MASKED");
        assertThat(outsider.path("expectedEffect").isEmpty()).isTrue();
    }

    @Test
    void nativeCommandReadUsesTheExactProductScopeAndNeverThePriceReader() throws Exception {
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        var actual=AdvertisingR1Fixture.seed(migration);
        var admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        UUID command;
        try(var connection=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword()).getConnection()) {
            connection.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,connection,actual,actual.id("ownerUser"),null,actual.id("recommendation"),actual.id("approval"));
            AdvertisingR1Fixture.seal(connection,actual,proof);command=AdvertisingR1Fixture.createCommand(connection,actual);connection.commit();
        }
        UUID user=actual.id("executorUser");users.assignRole("disclosure-fixture",user,BusinessRoleCode.MARKETPLACE_OPERATOR,null);
        users.grantScope("disclosure-fixture",user,ActionScopeCode.ADVERTISING_VIEW,ResourceScopeType.STORE,actual.id("store"),null);
        var actor=new AuthenticatedActor(user,actual.id("organization"),actual.id("provider"),"https://fixture.invalid/issuer",
                "Synthetic native reader","0".repeat(64),null,Instant.now(),Instant.now().plusSeconds(3600),true,Set.of(BusinessRoleCode.MARKETPLACE_OPERATOR));
        String jwt=browserToken(actual.id("provider"),user);
        users.grantScope("disclosure-fixture",user,ActionScopeCode.DIAGNOSTIC_VIEW,ResourceScopeType.STORE,actual.id("store"),null);
        UUID historicalPacket=UUID.randomUUID();
        // An expired, unapproved historical read oracle has no proposal, baseline or action authority.
        seed.sql("""
                INSERT INTO ops.ad_manual_execution_packet(id,organization_id,case_id,ad_native_object_id,store_id,
                  platform_code,affected_set_id,affected_set_digest,semantic_profile_id,action_kind,observed_configuration_id,
                  intended_state,reason,evidence_reference,maker_user_id,expected_impact,verification_plan,state,issued_at,
                  expires_at,correlation_id,created_at,updated_at)
                SELECT :id,c.organization_id,c.id,c.ad_native_object_id,c.store_id,c.platform_code,a.id,a.affected_set_digest,
                  c.semantic_profile_id,'AD_BID_CHANGE',:configuration,'{"targetBid":20}',
                  'financial reason 987654321','fixture://expired-read-oracle',:maker,'{"profit":987654321}',
                  '{"evidenceGrade":"UNVERIFIED"}','MANUAL_PACKET_EXPIRED',now()-interval '2 hours',now()-interval '1 hour',
                  'synthetic-disclosure-read-oracle',now(),now() FROM mart.ad_case c
                  JOIN core.ad_affected_set a ON a.id=c.affected_set_id WHERE c.id=:case
                """).param("id",historicalPacket).param("configuration",actual.id("configuration"))
                .param("maker",user).param("case",actual.id("caseId")).update();
        seed.sql("UPDATE ops.ad_action_reservation SET release_reason='financial reason 987654321' WHERE id=:id")
                .param("id",actual.id("reservation")).update();
        assertThatThrownBy(()->disclosure.requireCommandRead(actor,command)).isInstanceOf(OperationRejectedException.class);
        // A later partial Case no longer includes the old ProductVariant. Its diagnostic
        // visibility must not disclose the existence of the command frozen on that old set.
        UUID currentSet=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_affected_set SELECT revised.* FROM core.ad_affected_set original
                CROSS JOIN LATERAL jsonb_populate_record(NULL::core.ad_affected_set,to_jsonb(original)||jsonb_build_object(
                    'id',:current::text,'affected_set_digest',:digest,'product_variant_ids','[]'::jsonb,
                    'platform_listing_variant_ids','[]'::jsonb,'unresolved_reason_codes','["MAPPING_UNRESOLVED"]'::jsonb,'resolution_state','INCOMPLETE','resolved_at',clock_timestamp())) revised
                WHERE original.id=:old
                """).param("current",currentSet).param("digest",com.mimococo.marketops.shared.Digest.ofText("partial-current:"+currentSet))
                .param("old",actual.id("affectedSet")).update();
        seed.sql("UPDATE mart.ad_case SET affected_set_id=:current WHERE id=:id")
                .param("current",currentSet).param("id",actual.id("caseId")).update();
        assertThat(disclosure.mayReadNativeCommand(actor,command)).isFalse();
        assertThat(disclosure.mayReadNativeRecommendation(actor,actual.id("recommendation"))).isFalse();
        assertThat(httpWorkflow(jwt,"/recommendations/"+actual.id("recommendation")).statusCode()).isEqualTo(403);
        assertThat(httpWorkflow(jwt,"/stores/"+actual.id("store")+"/recommendations").body()).isEqualTo("[]");
        assertThat(httpWorkflow(jwt,"/stores/"+actual.id("store")+"/recommendation-counts").body()).isEqualTo("{}");
        assertThat(workflow.workflow(actor,actual.id("caseId")).candidates()).isEmpty();
        var hiddenWorkflow=http(jwt,"/cases/"+actual.id("caseId")+"/workflow");
        assertThat(hiddenWorkflow.statusCode()).isEqualTo(200);
        assertThat(new tools.jackson.databind.ObjectMapper().readTree(hiddenWorkflow.body()).path("candidates").isEmpty()).isTrue();
        assertThat(http(jwt,"/commands/"+command).statusCode()).isEqualTo(403);
        assertThat(http(jwt,"/commands/"+command+"/outcomes").statusCode()).isEqualTo(403);
        assertThat(http(jwt,"/manual-packets/"+historicalPacket+"/outcomes").statusCode()).isEqualTo(403);
        assertThat(http(jwt,"/reservations").body()).isEqualTo("[]");
        assertThat(http(jwt,"/objects/"+actual.id("object")+"/manual-packets").body()).isEqualTo("[]");
        var grant=users.grantScope("disclosure-fixture",user,ActionScopeCode.ADVERTISING_VIEW,ResourceScopeType.PRODUCT_VARIANT,actual.id("productVariant"),null);
        assertThat(disclosure.mayReadNativeCommand(actor,command)).isTrue();
        var recommendation=httpWorkflow(jwt,"/recommendations/"+actual.id("recommendation"));
        assertThat(recommendation.statusCode()).isEqualTo(200);
        assertThat(recommendation.body()).contains(actual.id("candidate").toString(),"MASKED");
        assertThat(httpWorkflow(jwt,"/stores/"+actual.id("store")+"/recommendations").body()).contains(actual.id("recommendation").toString());
        assertThat(httpWorkflow(jwt,"/stores/"+actual.id("store")+"/recommendation-counts").body()).isEqualTo("{}");
        assertThat(workflow.workflow(actor,actual.id("caseId")).candidates()).singleElement()
                .satisfies(candidate->{assertThat(candidate.commandId()).isEqualTo(command);assertThat(candidate.basis()).isEqualTo("MASKED");});
        var visibleWorkflow=new tools.jackson.databind.ObjectMapper().readTree(http(jwt,"/cases/"+actual.id("caseId")+"/workflow").body());
        assertThat(visibleWorkflow.path("candidates").get(0).path("basis").asText()).isEqualTo("MASKED");
        assertThat(visibleWorkflow.path("candidates").get(0).path("commandId").asText()).isEqualTo(command.toString());
        var nativeCommand=commands.command(command).orElseThrow();
        var projected=disclosure.command(actor,nativeCommand);
        assertThat(projected.path("id").asText()).isEqualTo(command.toString());
        assertThat(projected.path("targetBidAmount").decimalValue()).isEqualByComparingTo("20");
        assertThat(projected.path("disclosureState").asText()).isEqualTo("MASKED");
        assertThat(projected.has("materialityRoute")).isFalse();
        assertThat(projected.has("candidateBasis")).isFalse();
        assertThat(http(jwt,"/commands/"+command).statusCode()).isEqualTo(200);
        assertThat(http(jwt,"/commands/"+command+"/outcomes").statusCode()).isEqualTo(200);
        assertThat(http(jwt,"/manual-packets/"+historicalPacket+"/outcomes").statusCode()).isEqualTo(200);
        var reservations=http(jwt,"/reservations");assertThat(reservations.statusCode()).isEqualTo(200);
        assertThat(reservations.body()).contains(actual.id("reservation").toString(),actual.id("candidate").toString(),"MASKED")
                .doesNotContain("987654321");
        var packets=http(jwt,"/objects/"+actual.id("object")+"/manual-packets");assertThat(packets.statusCode()).isEqualTo(200);
        assertThat(packets.body()).contains(historicalPacket.toString(),"MASKED").doesNotContain("987654321");
        users.revokeScope("disclosure-fixture",grant.id(),"Exact product scope revoked",grant.version());
        assertThat(httpWorkflow(jwt,"/recommendations/"+actual.id("recommendation")).statusCode()).isEqualTo(403);
        assertThat(httpWorkflow(jwt,"/stores/"+actual.id("store")+"/recommendations").body()).isEqualTo("[]");
        assertThat(workflow.workflow(actor,actual.id("caseId")).candidates()).isEmpty();
        assertThat(new tools.jackson.databind.ObjectMapper().readTree(http(jwt,"/cases/"+actual.id("caseId")+"/workflow").body()).path("candidates").isEmpty()).isTrue();
        assertThatThrownBy(()->disclosure.command(actor,nativeCommand)).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(()->disclosure.requireCommandRead(maker,command)).isInstanceOf(OperationRejectedException.class);
        assertThat(http(jwt,"/commands/"+command+"/outcomes").statusCode()).isEqualTo(403);
        assertThat(http(jwt,"/reservations").body()).isEqualTo("[]");
    }

    @Test
    void manualNativeOptionsRequireEveryProductAndACompleteAffectedSetOverActualHttp() throws Exception {
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        var manual=AdvertisingR1Fixture.seedManual(migration);
        UUID user=manual.id("executorUser");
        users.assignRole("manual-disclosure-fixture",user,BusinessRoleCode.MARKETPLACE_OPERATOR,null);
        users.grantScope("manual-disclosure-fixture",user,ActionScopeCode.ADVERTISING_VIEW,
                ResourceScopeType.STORE,manual.id("store"),null);
        users.grantScope("manual-disclosure-fixture",user,ActionScopeCode.ADVERTISING_TASK_ACT,
                ResourceScopeType.ORGANIZATION,manual.id("organization"),null);
        String jwt=browserToken(manual.id("provider"),user);
        // Native option read oracles only: no proposal, packet, approval, command or action authority.
        for(String kind:List.of("AD_BID_CHANGE","AD_BUDGET_CHANGE","AD_STATUS_CHANGE")) {
            seed.sql("""
                    INSERT INTO core.ad_manual_policy(id,organization_id,store_id,semantic_profile_id,policy_version,cause_code,
                      outcome_policy_id,action_kind,candidate_basis,target_budget,target_status,currency_code,verification_mode,
                      configuration_max_age_seconds,packet_lease_seconds,effective_from,effective_to,
                      approved_by_user_id,approved_at,evidence_reference)
                    VALUES(gen_random_uuid(),:org,:store,:profile,1,'PROVEN_ADVERTISING_LOSS',:outcome,:kind,
                      CASE WHEN :kind='AD_BID_CHANGE' THEN 'MAX_CPC_BOUNDED' END,
                      CASE WHEN :kind='AD_BUDGET_CHANGE' THEN 700 END,
                      CASE WHEN :kind='AD_STATUS_CHANGE' THEN 'PAUSED' END,
                      'RUB','INDEPENDENT_OR_OFFICIAL',3600,1800,now()-interval '1 minute',now()+interval '1 hour',
                      :owner,now(),'fixture://native-manual-option-read-oracle')
                    """).param("org",manual.id("organization")).param("store",manual.id("store"))
                    .param("profile",manual.id("profile")).param("outcome",manual.id("outcome"))
                    .param("kind",kind).param("owner",manual.id("ownerUser")).update();
        }
        var mapper=new tools.jackson.databind.ObjectMapper();
        String path="/cases/"+manual.id("caseId")+"/manual-options";
        var partial=http(jwt,path);
        assertThat(partial.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(partial.body()).path("options").isEmpty()).isTrue();
        assertThat(mapper.readTree(partial.body()).path("allowedActions").isEmpty()).isTrue();
        var productGrant=users.grantScope("manual-disclosure-fixture",user,ActionScopeCode.ADVERTISING_VIEW,
                ResourceScopeType.PRODUCT_VARIANT,manual.id("productVariant"),null);
        var visible=http(jwt,path);
        assertThat(visible.statusCode()).isEqualTo(200);
        var options=mapper.readTree(visible.body());
        assertThat(options.path("options").size()).isEqualTo(3);
        assertThat(options.path("allowedActions").get(0).asText()).isEqualTo("SELECT_MANUAL_PROPOSAL");
        assertThat(options.path("productionWriteEnabled").asBoolean()).isFalse();
        for(var option:options.path("options")) assertThat(option.path("apiProfileState").asText()).isEqualTo("UNVERIFIED");
        users.revokeScope("manual-disclosure-fixture",productGrant.id(),"Native product visibility revoked",productGrant.version());
        assertThat(mapper.readTree(http(jwt,path).body()).path("options").isEmpty()).isTrue();
        users.grantScope("manual-disclosure-fixture",user,ActionScopeCode.ADVERTISING_VIEW,
                ResourceScopeType.PRODUCT_VARIANT,manual.id("productVariant"),null);
        UUID incomplete=UUID.randomUUID();
        // Keep the known Product membership so this tests completeness separately from grant checks.
        seed.sql("""
                INSERT INTO core.ad_affected_set SELECT revised.* FROM core.ad_affected_set original
                CROSS JOIN LATERAL jsonb_populate_record(NULL::core.ad_affected_set,to_jsonb(original)||jsonb_build_object(
                  'id',:id::text,'affected_set_digest',:digest,'resolution_state','INCOMPLETE',
                  'unresolved_reason_codes','["MAPPING_UNRESOLVED"]'::jsonb,'resolved_at',clock_timestamp())) revised
                WHERE original.id=:original
                """).param("id",incomplete).param("digest",com.mimococo.marketops.shared.Digest.ofText("manual-partial:"+incomplete))
                .param("original",manual.id("affectedSet")).update();
        seed.sql("UPDATE mart.ad_case SET affected_set_id=:affected WHERE id=:id")
                .param("affected",incomplete).param("id",manual.id("caseId")).update();
        var incompleteView=http(jwt,path);
        assertThat(incompleteView.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(incompleteView.body()).path("options").isEmpty()).isTrue();
        assertThat(mapper.readTree(incompleteView.body()).path("allowedActions").isEmpty()).isTrue();
    }

    @Test
    void anUnknownHistoricalCaseIsReadableWithoutBorrowingFutureMembershipOrGenericPermission() throws Exception {
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        var actual=AdvertisingR1Fixture.seedManual(migration);UUID user=actual.id("ownerUser"),unknown=UUID.randomUUID();
        var visibility=users.grantScope("unknown-disclosure-fixture",user,ActionScopeCode.ADVERTISING_VIEW,
                ResourceScopeType.ORGANIZATION,actual.id("organization"),null);
        users.grantScope("unknown-disclosure-fixture",user,ActionScopeCode.ADVERTISING_DECISION_EVIDENCE_VIEW,
                ResourceScopeType.ORGANIZATION,actual.id("organization"),null);
        String jwt=browserToken(actual.id("provider"),user);
        // Typed historical read oracle: the real classification/Task persistence
        // path is exercised independently by AdvertisingEfficiencyFlowIT.
        seed.sql("""
                INSERT INTO mart.ad_case SELECT unknown_case.* FROM mart.ad_case original
                JOIN core.ad_affected_set future ON future.id=original.affected_set_id
                CROSS JOIN LATERAL jsonb_populate_record(NULL::mart.ad_case,to_jsonb(original)||jsonb_build_object(
                  'id',:id::text,'case_key',:key,'lane','DATA_REPAIR','protection_tier',NULL,
                  'cause_code','AFFECTED_SET_UNRESOLVED','affected_set_id',NULL,
                  'blocker_codes',jsonb_build_array('AFFECTED_SET_NEVER_RESOLVED'),
                  'as_of',future.resolved_at-interval '1 second','calculated_at',clock_timestamp())) unknown_case
                WHERE original.id=:original
                """).param("id",unknown).param("key","synthetic-unknown-set-"+unknown)
                .param("original",actual.id("caseId")).update();
        assertThat(scopes.objectScope(actual.id("organization"),actual.id("object")).orElseThrow()
                .productVariantIds()).containsExactly(actual.id("productVariant"));
        assertThat(scopes.caseObjectScope(actual.id("organization"),actual.id("object"),null).orElseThrow()
                .productVariantIds()).isEmpty();
        var response=http(jwt,"/cases/"+unknown);assertThat(response.statusCode()).isEqualTo(200);
        var body=new tools.jackson.databind.ObjectMapper().readTree(response.body());
        assertThat(body.path("lane").asText()).isEqualTo("DATA_REPAIR");
        assertThat(body.path("affectedSetResolution").asText()).isEqualTo("UNRESOLVED");
        assertThat(body.path("affectedSetDigest").isNull()).isTrue();
        assertThat(body.path("affectedProductVariantIds").isEmpty()).isTrue();
        assertThat(body.path("affectedListingVariantIds").isEmpty()).isTrue();
        assertThat(body.path("disclosureState").asText()).isEqualTo("MASKED");
        assertThat(body.path("contributionProfitAmount").isNull()).isTrue();
        assertThat(body.path("productionWriteEnabled").asBoolean()).isFalse();
        assertThat(http(jwt,"/queue?lane=DATA_REPAIR").body()).contains(unknown.toString());
        var actor=new AuthenticatedActor(user,actual.id("organization"),actual.id("provider"),
                "https://fixture.invalid/issuer","Synthetic unknown-case reviewer","0".repeat(64),null,
                Instant.now(),Instant.now().plusSeconds(3600),true,Set.of(BusinessRoleCode.OWNER));
        assertThat(disclosure.mayReadDecisionEvidence(actor,actual.id("object"))).isTrue();
        assertThat(disclosure.mayReadDecisionEvidence(actor,actual.id("object"),null)).isFalse();
        users.grantScope("unknown-disclosure-fixture",user,ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScopeType.ORGANIZATION,actual.id("organization"),null);
        users.revokeScope("unknown-disclosure-fixture",visibility.id(),"Advertising visibility revoked",visibility.version());
        assertThat(http(jwt,"/cases/"+unknown).statusCode()).isEqualTo(403);
    }

    private void assertNoFinancialSentinelAmount(tools.jackson.databind.JsonNode node) {
        // Check value semantics, not substrings of timestamps or UUIDs. Both JSON
        // numbers and whole numeric strings count as monetary disclosure.
        if(node.isNumber() || node.isTextual() && node.asText().matches("-?[0-9]+(?:\\.[0-9]+)?")) {
            var value=node.isNumber()?node.decimalValue():new java.math.BigDecimal(node.asText());
            for(String forbidden:List.of("1200","4500","18.0000")) {
                assertThat(value.abs().compareTo(new java.math.BigDecimal(forbidden)))
                        .as("financial sentinel must not cross a masked projection").isNotZero();
            }
        } else {
            for(var child:node) assertNoFinancialSentinelAmount(child);
        }
    }

    private String browserToken(UUID fixtureProvider,UUID user) throws Exception {
        String issuer=com.mimococo.marketops.identityaccess.internal.web.BrowserSigningFixture.ISSUER;
        UUID provider=seed.sql("SELECT id FROM iam.identity_provider WHERE issuer=:issuer")
                .param("issuer",issuer).query(UUID.class).optional().orElseGet(()->{
                    seed.sql("UPDATE iam.identity_provider SET issuer=:issuer WHERE id=:id")
                            .param("issuer",issuer).param("id",fixtureProvider).update();
                    return fixtureProvider;
                });
        // The issuer is globally unique; each graph keeps its own organization, subject and grants.
        seed.sql("UPDATE iam.user_account SET identity_provider_id=:provider,external_subject=:subject,credentials_valid_from=now()-interval '1 day' WHERE id=:id")
                .param("provider",provider).param("subject","disclosure-"+user).param("id",user).update();
        String subject=seed.sql("SELECT external_subject FROM iam.user_account WHERE id=:id")
                .param("id",user).query(String.class).single();
        return com.mimococo.marketops.identityaccess.internal.web.BrowserSigningFixture.token(subject);
    }

    private java.net.http.HttpResponse<String> httpWorkflow(String jwt,String path) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/console/workflow"+path))
                .header("Authorization","Bearer "+jwt).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private java.net.http.HttpResponse<String> http(String jwt,String path) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/console/advertising"+path))
                .header("Authorization","Bearer "+jwt).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private void wbProfile(UUID id, String kind) {
        seed.sql("""
                INSERT INTO platform.ad_semantic_profile(id, platform_code, profile_version, native_object_kind,
                    control_level, bidding_mode, bid_field_present, bid_currency_code, bid_unit_code,
                    bid_precision, bid_step, bid_minimum, bid_maximum, idempotency_semantics,
                    propagation_semantics, readback_semantics, correction_behaviour, source_maturity,
                    verification_state, owner_label, status, created_at, updated_at)
                VALUES (:id, 'WILDBERRIES', 904, :kind, 'CAMPAIGN', 'AUTO_BID', true, 'RUB', 'CURRENCY_MINOR',
                    0, 100, 100, 50000, 'UNKNOWN', 'EVENTUAL_UNBOUNDED', 'DERIVED_FIELD', 'UNKNOWN',
                    'SYNTHETIC_FIXTURE', 'UNVERIFIED', 'independent-wb-fixture', 'ACTIVE', now(), now())
                """).param("id", id).param("kind", kind).update();
    }

    private void wbObject(UUID id, UUID store, UUID profile, String kind, UUID campaign) {
        seed.sql("""
                INSERT INTO core.ad_native_object(id, organization_id, store_id, platform_code, semantic_profile_id,
                    native_object_kind, native_object_key, native_campaign_key, native_object_name, bidding_mode,
                    control_granularity_state, lineage_key, lineage_generation, observation_state, status,
                    first_observed_at, last_observed_at, created_at, updated_at)
                VALUES (:id, :org, :store, 'WILDBERRIES', :profile, :kind, :key, :campaign, 'Зимние сапоги WB',
                    'AUTO_BID', 'UNKNOWN', :key, 1, 'OBSERVED', 'ACTIVE', now(), now(), now(), now())
                """).param("id", id).param("org", graph.organizationId()).param("store", store)
                .param("profile", profile).param("kind", kind).param("key", "wb-native-" + id)
                .param("campaign", "wb-native-" + campaign).update();
    }

    private void grant(UUID user, ActionScopeCode action, ResourceScopeType type, UUID resource) {
        users.grantScope("disclosure-fixture", user, action, type, resource, null);
    }

    private AuthenticatedActor actor(UUID user, BusinessRoleCode role) {
        UUID provider = seed.sql("SELECT identity_provider_id FROM iam.user_account WHERE id = :id")
                .param("id", user).query(UUID.class).single();
        return new AuthenticatedActor(user, graph.organizationId(), provider, "https://fixture.invalid/issuer",
                "Synthetic disclosure actor", "0".repeat(64), null, Instant.now(),
                Instant.now().plusSeconds(3600), true, Set.of(role));
    }
}
