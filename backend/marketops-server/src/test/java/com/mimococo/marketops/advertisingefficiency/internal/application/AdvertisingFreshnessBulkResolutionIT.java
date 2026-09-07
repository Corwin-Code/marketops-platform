package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.AdvertisingGraphFixture;
import com.mimococo.marketops.TestDatabase;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real app-role resolution of independent scoped authorities; no Provider or write admission. */
@SpringBootTest @ActiveProfiles("ci")
class AdvertisingFreshnessBulkResolutionIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static final Instant AT=Instant.parse("2026-08-01T12:00:00Z");
    private static final List<String> KINDS=List.of("OFFICIAL_AD_SPEND","OFFICIAL_AD_TRAFFIC","AD_LINKED_SALE_EVENT",
            "COST_AND_FEE","AD_OBJECT_CONFIGURATION","AFFECTED_SET","SELLABILITY","AVAILABILITY");
    private static final List<String> PURPOSES=List.of("QUEUE_OBSERVATION","TASK_ACTIVATION","PROTECTION_RECOMMENDATION",
            "OPTIMIZATION_RECOMMENDATION","PROTECTION_BID_WRITE","OPTIMIZATION_BID_WRITE");
    private static final String KIND="OFFICIAL_AD_SPEND",PURPOSE="PROTECTION_BID_WRITE",KEY=PURPOSE+":"+KIND;
    @Autowired AdvertisingPolicyRepository policies;
    @Autowired AdvertisingEvidenceGatherer gatherer;
    private JdbcClient seed;
    private AdvertisingGraphFixture.Graph graph;
    private int nextVersion;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username",TestDatabase::applicationRole);
        registry.add("spring.datasource.password",TestDatabase::applicationPassword);
        registry.add("spring.flyway.user",TestDatabase::migrationRole);
        registry.add("spring.flyway.password",TestDatabase::migrationPassword);
    }

    @BeforeEach void fixture() {
        seed=JdbcClient.create(new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword()));
        graph=AdvertisingGraphFixture.seed(seed);nextVersion=1;
    }

    @Test void allFortyEightIndependentAuthoritiesRetainEveryTypedFieldAndFrozenDigest() {
        var expected=new LinkedHashMap<String,UUID>();
        for(String purpose:PURPOSES) for(String kind:KINDS)
            expected.put(purpose+":"+kind,profile(graph,kind,purpose,"ORGANIZATION","ACTIVE",AT.minusSeconds(60),AT.plusSeconds(600)));
        var actual=bulk(AT);
        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((key,id)-> {
            var value=actual.get(key);
            assertThat(value.id()).isEqualTo(id);
            assertThat(value.decisionPurpose()+":"+value.evidenceKind()).isEqualTo(key);
            assertThat(value.version()).isBetween(1,48);
            assertThat(value.sourceMaxAgeMinutes()).isEqualTo(120+value.version());
            assertThat(value.acceptedFactMaxAgeMinutes()).isEqualTo(60+value.version());
            assertThat(value.expectedPublicationLagMinutes()).isEqualTo(2);
            assertThat(value.correctionWindowMinutes()).isEqualTo(3);
            assertThat(value.requiresWindowComplete()).isTrue();
            assertThat(value.requiresCorrectionWindowClosed()).isTrue();
            assertThat(value.minimumCoverageRatio()).isEqualByComparingTo("0.75");
            assertThat(value.minimumConfidenceState()).isEqualTo("CANONICAL_CONFIRMED");
            assertThat(value.providerIncidentBlocks()).isTrue();
            assertThat(value.effectiveTo()).isEqualTo(AT.plusSeconds(600));
            assertThat(value.authorityDigest()).matches("[0-9a-f]{64}").isEqualTo(seed.sql(
                    "SELECT ops.ad_outcome_freshness_snapshot(:id)->>'authorityDigest'")
                    .param("id",id).query(String.class).single());
        });
        // The real gatherer must carry all48 exact authorities into its existing authority map.
        assertThat(gatherer.gather(graph.organizationId(),graph.objectId(),AT).orElseThrow().authorities().freshness())
                .containsExactlyInAnyOrderEntriesOf(actual);
    }

    @Test void exactOrganizationPlatformStoreAndSemanticScopesResolveWithoutCrossPurposeOrKindLeakage() {
        UUID org=profile(graph,KIND,PURPOSE,"ORGANIZATION","ACTIVE",AT.minusSeconds(60),null);
        UUID platform=profile(graph,KIND,PURPOSE,"PLATFORM","ACTIVE",AT.minusSeconds(60),null);
        UUID store=profile(graph,KIND,PURPOSE,"STORE","ACTIVE",AT.minusSeconds(60),null);
        UUID semantic=profile(graph,KIND,PURPOSE,"SEMANTIC_PROFILE","ACTIVE",AT.minusSeconds(60),null);
        UUID traffic=profile(graph,"OFFICIAL_AD_TRAFFIC",PURPOSE,"PLATFORM","ACTIVE",AT.minusSeconds(60),null);
        var other=AdvertisingGraphFixture.seed(seed);
        profile(other,KIND,PURPOSE,"SEMANTIC_PROFILE","ACTIVE",AT.minusSeconds(60),null);
        assertThat(bulk(AT).get(KEY).id()).isEqualTo(semantic);
        assertThat(bulk(AT).get(PURPOSE+":OFFICIAL_AD_TRAFFIC").id()).isEqualTo(traffic);
        assertThat(bulk(AT)).doesNotContainKey("OPTIMIZATION_BID_WRITE:"+KIND).doesNotContainKey(PURPOSE+":COST_AND_FEE");
        assertThat(policies.resolveFreshnessProfiles(graph.organizationId(),KINDS,PURPOSES,"OZON",graph.storeId(),UUID.randomUUID(),AT).get(KEY).id()).isEqualTo(store);
        assertThat(policies.resolveFreshnessProfiles(graph.organizationId(),KINDS,PURPOSES,"OZON",UUID.randomUUID(),null,AT).get(KEY).id()).isEqualTo(platform);
        assertThat(policies.resolveFreshnessProfiles(graph.organizationId(),KINDS,PURPOSES,"WB",null,null,AT).get(KEY).id()).isEqualTo(org);
        assertThat(policies.resolveFreshnessProfiles(UUID.randomUUID(),KINDS,PURPOSES,"OZON",graph.storeId(),graph.semanticProfileId(),AT)).isEmpty();
        // Both pre-existing overloads retain independently asserted selected identities.
        assertThat(policies.resolveFreshness(graph.organizationId(),KIND,PURPOSE,"OZON",graph.storeId(),graph.semanticProfileId(),AT).orElseThrow().id()).isEqualTo(semantic);
        assertThat(policies.resolveFreshness(graph.organizationId(),KIND,PURPOSE,"OZON",graph.storeId(),AT).orElseThrow().id()).isEqualTo(store);
    }

    @ParameterizedTest @ValueSource(strings={"ORGANIZATION","PLATFORM","STORE","SEMANTIC_PROFILE"})
    void ambiguousEffectiveScopeFailsClosedWithoutExposingBroaderAuthority(String scope) {
        if(!scope.equals("ORGANIZATION")) profile(graph,KIND,PURPOSE,"ORGANIZATION","ACTIVE",AT.minusSeconds(60),null);
        profile(graph,KIND,PURPOSE,scope,"ACTIVE",AT.minusSeconds(60),null);
        // RETIRED remains a legitimate as-of candidate; its overlap cannot be hidden by ACTIVE-only filtering.
        profile(graph,KIND,PURPOSE,scope,"RETIRED",AT.minusSeconds(60),AT.plusSeconds(60));
        UUID independent=profile(graph,"OFFICIAL_AD_TRAFFIC",PURPOSE,"ORGANIZATION","ACTIVE",AT.minusSeconds(60),null);
        assertThat(bulk(AT)).doesNotContainKey(KEY);
        assertThat(bulk(AT).get(PURPOSE+":OFFICIAL_AD_TRAFFIC").id()).isEqualTo(independent);
        assertThat(policies.resolveFreshness(graph.organizationId(),KIND,PURPOSE,"OZON",graph.storeId(),graph.semanticProfileId(),AT)).isEmpty();
    }

    @Test void retiredHistoryEffectiveBoundariesExpiryAndCancelledNarrowScopeStayDistinct() {
        UUID past=profile(graph,KIND,PURPOSE,"ORGANIZATION","RETIRED",AT.minusSeconds(120),AT);
        UUID current=profile(graph,KIND,PURPOSE,"ORGANIZATION","ACTIVE",AT,AT.plusSeconds(120));
        UUID future=profile(graph,KIND,PURPOSE,"ORGANIZATION","ACTIVE",AT.plusSeconds(120),AT.plusSeconds(240));
        profile(graph,KIND,PURPOSE,"SEMANTIC_PROFILE","CANCELLED",AT.minusSeconds(120),AT.plusSeconds(240));
        assertThat(bulk(AT.minusSeconds(1)).get(KEY).id()).isEqualTo(past);
        assertThat(bulk(AT).get(KEY).id()).isEqualTo(current);
        assertThat(bulk(AT.plusSeconds(120)).get(KEY).id()).isEqualTo(future);
        assertThat(bulk(AT.minusSeconds(121))).doesNotContainKey(KEY);
        assertThat(bulk(AT.plusSeconds(240))).doesNotContainKey(KEY);
        assertThat(policies.resolveFreshness(graph.organizationId(),KIND,PURPOSE,"OZON",graph.storeId(),graph.semanticProfileId(),AT.minusSeconds(1)).orElseThrow().id()).isEqualTo(past);
        assertThat(policies.resolveFreshness(graph.organizationId(),KIND,PURPOSE,"OZON",graph.storeId(),graph.semanticProfileId(),AT.plusSeconds(240))).isEmpty();
    }

    @Test void absentOrUnrequestedPairsCannotCreateDefaultAuthorities() {
        profile(graph,KIND,PURPOSE,"ORGANIZATION","ACTIVE",AT.minusSeconds(60),null);
        assertThat(policies.resolveFreshnessProfiles(graph.organizationId(),List.of("COST_AND_FEE"),PURPOSES,"OZON",graph.storeId(),graph.semanticProfileId(),AT)).isEmpty();
        assertThat(policies.resolveFreshnessProfiles(graph.organizationId(),KINDS,List.of("TASK_ACTIVATION"),"OZON",graph.storeId(),graph.semanticProfileId(),AT)).isEmpty();
        assertThat(policies.resolveFreshnessProfiles(graph.organizationId(),List.of(),PURPOSES,"OZON",graph.storeId(),graph.semanticProfileId(),AT)).isEmpty();
        assertThat(policies.resolveFreshnessProfiles(graph.organizationId(),KINDS,List.of(),"OZON",graph.storeId(),graph.semanticProfileId(),AT)).isEmpty();
        assertThat(policies.resolveFreshness(graph.organizationId(),null,PURPOSE,"OZON",graph.storeId(),AT)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"STORE","SEMANTIC_PROFILE"})
    void wrongPlatformNarrowProfileCannotSelectOrSuppressValidOrganizationAuthority(String scope) {
        UUID broader=profile(graph,KIND,PURPOSE,"ORGANIZATION","ACTIVE",AT.minusSeconds(120),null);
        profile(graph,KIND,PURPOSE,scope,"ACTIVE",AT.minusSeconds(60),null,
                "WILDBERRIES",graph.storeId(),scope.equals("SEMANTIC_PROFILE")?graph.semanticProfileId():null);
        assertThat(bulk(AT).get(KEY).id()).isEqualTo(broader);
        assertThat(policies.resolveFreshness(graph.organizationId(),KIND,PURPOSE,"OZON",
                graph.storeId(),graph.semanticProfileId(),AT).orElseThrow().id()).isEqualTo(broader);
    }

    @Test void anotherStoresSemanticProfileCannotSelectOrSuppressValidOrganizationAuthority() {
        UUID broader=profile(graph,KIND,PURPOSE,"ORGANIZATION","ACTIVE",AT.minusSeconds(120),null);
        UUID otherStore=anotherStore();
        UUID otherProfile=profile(graph,KIND,PURPOSE,"SEMANTIC_PROFILE","ACTIVE",AT.minusSeconds(60),null,
                "OZON",otherStore,graph.semanticProfileId());
        assertThat(bulk(AT).get(KEY).id()).isEqualTo(broader);
        assertThat(policies.resolveFreshness(graph.organizationId(),KIND,PURPOSE,"OZON",
                graph.storeId(),graph.semanticProfileId(),AT).orElseThrow().id()).isEqualTo(broader);
        assertThat(policies.resolveFreshnessProfiles(graph.organizationId(),KINDS,PURPOSES,"OZON",
                otherStore,graph.semanticProfileId(),AT).get(KEY).id()).isEqualTo(otherProfile);
    }

    @Test void twoStoresWithTheirOwnSemanticProfilesResolveOnlyTheirExactApplicableAuthority() {
        UUID broader=profile(graph,KIND,PURPOSE,"ORGANIZATION","ACTIVE",AT.minusSeconds(120),null);
        UUID otherStore=anotherStore();
        UUID first=profile(graph,KIND,PURPOSE,"SEMANTIC_PROFILE","ACTIVE",AT.minusSeconds(60),null,
                "OZON",graph.storeId(),graph.semanticProfileId());
        UUID second=profile(graph,KIND,PURPOSE,"SEMANTIC_PROFILE","ACTIVE",AT.minusSeconds(30),null,
                "OZON",otherStore,graph.semanticProfileId());
        assertThat(bulk(AT).get(KEY).id()).isEqualTo(first);
        assertThat(policies.resolveFreshnessProfiles(graph.organizationId(),KINDS,PURPOSES,"OZON",
                otherStore,graph.semanticProfileId(),AT).get(KEY).id()).isEqualTo(second);
        assertThat(policies.resolveFreshness(graph.organizationId(),KIND,PURPOSE,"OZON",
                graph.storeId(),graph.semanticProfileId(),AT).orElseThrow().id()).isEqualTo(first);
        assertThat(policies.resolveFreshness(graph.organizationId(),KIND,PURPOSE,"OZON",
                otherStore,graph.semanticProfileId(),AT).orElseThrow().id()).isEqualTo(second);
        // No store identity cannot accidentally authorize either store-bound semantic rule.
        assertThat(policies.resolveFreshnessProfiles(graph.organizationId(),KINDS,PURPOSES,"OZON",
                null,graph.semanticProfileId(),AT).get(KEY).id()).isEqualTo(broader);
    }

    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5})
    void globalAndStoreBoundSemanticOverlapIsUnresolvedRegardlessOfDatesOrInsertionOrder(int variant) {
        profile(graph,KIND,PURPOSE,"ORGANIZATION","ACTIVE",AT.minusSeconds(180),null);
        Instant globalFrom=AT.minusSeconds(variant>=4?30:120);
        Instant boundFrom=AT.minusSeconds(variant>=2 && variant<4?30:120);
        UUID global;
        if(variant%2==0) {
            global=profile(graph,KIND,PURPOSE,"SEMANTIC_PROFILE","ACTIVE",globalFrom,null,
                    "OZON",null,graph.semanticProfileId());
            profile(graph,KIND,PURPOSE,"SEMANTIC_PROFILE","ACTIVE",boundFrom,null,
                    "OZON",graph.storeId(),graph.semanticProfileId());
        } else {
            profile(graph,KIND,PURPOSE,"SEMANTIC_PROFILE","ACTIVE",boundFrom,null,
                    "OZON",graph.storeId(),graph.semanticProfileId());
            global=profile(graph,KIND,PURPOSE,"SEMANTIC_PROFILE","ACTIVE",globalFrom,null,
                    "OZON",null,graph.semanticProfileId());
        }
        UUID independent=profile(graph,"OFFICIAL_AD_TRAFFIC",PURPOSE,"ORGANIZATION","ACTIVE",AT.minusSeconds(60),null);
        assertThat(bulk(AT)).doesNotContainKey(KEY);
        assertThat(bulk(AT).get(PURPOSE+":OFFICIAL_AD_TRAFFIC").id()).isEqualTo(independent);
        assertThat(policies.resolveFreshness(graph.organizationId(),KIND,PURPOSE,"OZON",
                graph.storeId(),graph.semanticProfileId(),AT)).isEmpty();
        // The exact other-store scope still has one global semantic authority.
        assertThat(policies.resolveFreshnessProfiles(graph.organizationId(),KINDS,PURPOSES,"OZON",
                anotherStore(),graph.semanticProfileId(),AT).get(KEY).id()).isEqualTo(global);
    }

    private UUID anotherStore() {
        UUID id=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.store(id,organization_id,marketplace_account_id,code,display_name,
                    status,created_at,updated_at)
                VALUES(:id,:org,:account,:code,'Synthetic second freshness store','ACTIVE',:at,:at)
                """).param("id",id).param("org",graph.organizationId()).param("account",graph.accountId())
                .param("code","freshness-"+id).param("at",Timestamp.from(AT.minusSeconds(300))).update();
        return id;
    }

    private Map<String,AdvertisingPolicyRepository.FreshnessProfile> bulk(Instant at) {
        return policies.resolveFreshnessProfiles(graph.organizationId(),KINDS,PURPOSES,"OZON",graph.storeId(),graph.semanticProfileId(),at);
    }

    private UUID profile(AdvertisingGraphFixture.Graph owner,String kind,String purpose,String scope,String status,Instant from,Instant to) {
        return profile(owner,kind,purpose,scope,status,from,to,scope.equals("ORGANIZATION")?null:"OZON",
                scope.equals("STORE")?owner.storeId():null,scope.equals("SEMANTIC_PROFILE")?owner.semanticProfileId():null);
    }

    private UUID profile(AdvertisingGraphFixture.Graph owner,String kind,String purpose,String scope,String status,Instant from,Instant to,
            String platform,UUID store,UUID semantic) {
        UUID id=UUID.randomUUID();int version=nextVersion++;
        seed.sql("""
                INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,
                    scope_kind,platform_code,store_ref_id,semantic_profile_id,source_max_age_minutes,accepted_fact_max_age_minutes,
                    expected_publication_lag_minutes,correction_window_minutes,requires_window_complete,requires_correction_window_closed,
                    minimum_coverage_ratio,minimum_confidence_state,provider_incident_blocks,owner_user_id,reason,evidence_reference,
                    effective_from,effective_to,status,created_at)
                VALUES(:id,:org,:version,:kind,:purpose,:scope,:platform,:store,:semantic,:sourceAge,:acceptedAge,
                    2,3,true,true,0.75,'CANONICAL_CONFIRMED',true,:actor,'Synthetic bulk freshness authority',
                    'fixture://freshness-bulk',:from,:to,:status,:from)
                """).param("id",id).param("org",owner.organizationId()).param("version",version).param("kind",kind).param("purpose",purpose)
                .param("scope",scope).param("platform",platform).param("store",store).param("semantic",semantic)
                .param("sourceAge",120+version).param("acceptedAge",60+version).param("actor",owner.executorUserId())
                .param("from",Timestamp.from(from)).param("to",to==null?null:Timestamp.from(to)).param("status",status).update();
        return id;
    }
}
