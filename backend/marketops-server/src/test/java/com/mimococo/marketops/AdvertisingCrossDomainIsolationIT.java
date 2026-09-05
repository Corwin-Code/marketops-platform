package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionAuthority;
import com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingImpactEvidenceService;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared command/fact authority and real advertising DB sinks; no Provider transport or invented Task plans. */
@SpringBootTest
@ActiveProfiles("ci")
class AdvertisingCrossDomainIsolationIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    @Autowired DataSource application;
    @Autowired AdvertisingDecisionAuthority decisions;
    @Autowired AdvertisingImpactEvidenceService impact;
    @Autowired ObjectMapper json;
    private DataSource migration,admin;
    private JdbcClient seed,app;
    private AdvertisingR1Fixture.Graph graph;
    private Instant prepared;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DATABASE::getJdbcUrl);
        r.add("spring.datasource.username",TestDatabase::applicationRole);
        r.add("spring.datasource.password",TestDatabase::applicationPassword);
        r.add("spring.flyway.user",TestDatabase::migrationRole);
        r.add("spring.flyway.password",TestDatabase::migrationPassword);
    }
    @BeforeEach void fixture() throws Exception {
        migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);app=JdbcClient.create(application);
        graph=AdvertisingR1Fixture.seed(migration);
        prepared=seed.sql("SELECT prepared_at FROM ops.ad_outcome_baseline WHERE id=:id")
                .param("id",graph.id("baseline")).query(Timestamp.class).single().toInstant();
    }

    @Test void absentContextRemainsExplicitUnknownWithoutInventingAKnownMaterialChange() throws Exception {
        assertThat(snapshot(Instant.now()).path("state").asText()).isEqualTo("UNRESOLVED");
        assertThat(snapshot(Instant.now()).path("uncertainties").toString()).contains("CROSS_DOMAIN_CONTEXT_UNRESOLVED");
        assertThat(failures()).isEmpty();
        assertThat(impact.capture(graph.id("recommendation"),Instant.now(),graph.id("bundle"))
                .path("crossDomainIsolation").path("state").asText()).isEqualTo("UNRESOLVED");
        // The pre-existing canonical baseline/evidence guards remain mandatory. This
        // oracle meets those guards; uncertainty is never presented as ISOLATED.
        assertThat(createAdvertisingCommand()).isNotNull();
        assertProductionOff();
    }

    @Test void actualOverlappingPriceCommandBlocksPreviewAndFinalApproval() throws Exception {
        var price=AdvertisingCrossDomainPriceSeed.seed(seed,graph,null);
        assertThat(failures()).containsExactly("KNOWN_CROSS_DOMAIN_INTERVENTION");
        assertThat(snapshot(Instant.now()).path("knownPriceCommands").get(0).path("commandId").asText())
                .isEqualTo(price.commandId().toString());
        assertThat(decisions.bidProjection(graph.id("recommendation")).orElseThrow().actionBlockerCodes())
                .contains("KNOWN_CROSS_DOMAIN_INTERVENTION");
        assertThat(decisions.unresolvedReasons(graph.id("recommendation"))).contains("KNOWN_CROSS_DOMAIN_INTERVENTION");
        var preview=impact.capture(graph.id("recommendation"),Instant.now(),graph.id("bundle"));
        assertThat(preview.path("crossDomainIsolation").path("failures").toString()).contains("KNOWN_CROSS_DOMAIN_INTERVENTION");
        try(var connection=transaction()) {
            assertThatThrownBy(()->seal(connection)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("known cross-domain intervention prevents isolated final approval");
            connection.rollback();
        }
        assertNoAdvertisingCommand();
    }

    @Test void anUnexecutedPriceRecommendationDoesNotBecomeAnInventedIntervention() throws Exception {
        var price=AdvertisingCrossDomainPriceSeed.seedRecommendationOnly(seed,graph,null);
        assertThat(seed.sql("SELECT count(*) FROM ops.price_command WHERE recommendation_id=:id")
                .param("id",price.recommendationId()).query(Integer.class).single()).isZero();
        assertThat(failures()).isEmpty();
        assertThat(createAdvertisingCommand()).isNotNull();
    }

    @Test void sameStoreDifferentVariantIsIsolatedButAnotherListingForTheAffectedVariantOverlaps() throws Exception {
        UUID sibling=siblingListing(false);
        var price=AdvertisingCrossDomainPriceSeed.seed(seed,graph,sibling);
        assertThat(failures()).isEmpty();
        // Shared Internal Variant identity, not store equality, is the overlap boundary.
        mapSibling(sibling);
        assertThat(failures()).containsExactly("KNOWN_CROSS_DOMAIN_INTERVENTION");
        assertThat(snapshot(Instant.now()).path("knownPriceCommands").get(0).path("commandId").asText())
                .isEqualTo(price.commandId().toString());
    }

    @Test void anotherOrganizationsActivePriceCommandCannotContaminateThisExactScope() throws Exception {
        var other=AdvertisingR1Fixture.seed(migration);
        AdvertisingCrossDomainPriceSeed.seed(seed,other,null);
        assertThat(failures()).isEmpty();
        assertThat(createAdvertisingCommand()).isNotNull();
    }

    @ParameterizedTest @ValueSource(strings={"SUCCEEDED","FAILED_FINAL","COMPENSATED","COMPENSATION_FAILED"})
    void terminalSharedCommandsDoNotRemainPermanentIsolationLocks(String state) throws Exception {
        var price=AdvertisingCrossDomainPriceSeed.seed(seed,graph,null);
        assertThat(failures()).containsExactly("KNOWN_CROSS_DOMAIN_INTERVENTION");
        // Historical fixture transition; no PriceCommand product behavior or trigger is bypassed.
        if ("COMPENSATED".equals(state)) seedObservedSharedPriceRestoration(price.commandId());
        seed.sql("UPDATE ops.price_command SET state=:state,terminal_at=clock_timestamp(),failure_code=CASE WHEN :state IN('FAILED_FINAL','COMPENSATION_FAILED') THEN 'SYNTHETIC_FINAL' ELSE NULL END WHERE id=:id")
                .param("state",state).param("id",price.commandId()).update();
        assertThat(failures()).isEmpty();
        assertThat(createAdvertisingCommand()).isNotNull();
    }

    private void seedObservedSharedPriceRestoration(UUID commandId) {
        // Synthetic historical protocol evidence, not an assertion that this
        // fixture executed the Price worker. The real compensation trigger must
        // independently find an accepted restore and later exact prior readback.
        String prior=seed.sql("SELECT prior_price::text FROM ops.price_command WHERE id=:id")
                .param("id",commandId).query(String.class).single();
        Instant anchor=Instant.now();
        for (int index=0;index<2;index++) {
            boolean read=index==1;
            UUID attempt=UUID.randomUUID(),raw=UUID.randomUUID(),content=UUID.randomUUID();
            String body=read?"{\"price\":"+prior+",\"currency\":\"RUB\"}":"{\"accepted\":true}";
            String digest=com.mimococo.marketops.shared.Digest.ofText(body);
            Instant started=anchor.minusSeconds(read?2:4),completed=anchor.minusSeconds(read?1:3);
            seed.sql("""
                INSERT INTO raw.raw_content(id,hash_algorithm,hash_value,byte_length,object_ref)
                VALUES(:id,'SHA256',:digest,:length,:ref) ON CONFLICT(hash_algorithm,hash_value) DO NOTHING
                """).param("id",content).param("digest",digest)
                    .param("length",body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .param("ref","object-ref://isolation-price/"+content).update();
            content=seed.sql("SELECT id FROM raw.raw_content WHERE hash_algorithm='SHA256' AND hash_value=:digest")
                    .param("digest",digest).query(UUID.class).single();
            seed.sql("""
                INSERT INTO ops.price_command_attempt(id,command_id,attempt_no,purpose,fence_token,lease_owner,
                  started_at,outcome_class,correlation_id,request_digest,operation_snapshot)
                SELECT :id,id,:number,:purpose,fence_token,'synthetic-historical-price',:started,
                  'IN_FLIGHT','synthetic-historical-price',:digest,'{"fixture":"historical restore observation"}'::jsonb
                FROM ops.price_command WHERE id=:command
                """).param("id",attempt).param("number",index+1).param("purpose",read?"READBACK":"RESTORE")
                    .param("started",Timestamp.from(started)).param("digest",digest).param("command",commandId).update();
            seed.sql("""
                INSERT INTO raw.price_response_observation(id,command_id,attempt_id,raw_content_id,request_digest,
                  http_status,response_headers,evidence_class,response_complete,observed_price,observed_currency,
                  observed_at,correlation_id)
                SELECT :id,id,:attempt,:content,:digest,200,'{}'::jsonb,'PROTOCOL_FIXTURE',true,
                  CASE WHEN :read THEN prior_price END,CASE WHEN :read THEN currency_code END,
                  :completed,'synthetic-historical-price' FROM ops.price_command WHERE id=:command
                """).param("id",raw).param("attempt",attempt).param("content",content).param("digest",digest)
                    .param("read",read).param("completed",Timestamp.from(completed)).param("command",commandId).update();
            seed.sql("UPDATE ops.price_command_attempt SET completed_at=:completed,outcome_class='ACCEPTED',raw_observation_id=:raw WHERE id=:id")
                    .param("completed",Timestamp.from(completed)).param("raw",raw).param("id",attempt).update();
            if(read) seed.sql("""
                INSERT INTO ops.price_command_readback(id,command_id,attempt_id,observed_at,observed_price,currency_code,
                  match_state,raw_observation_id,correlation_id)
                SELECT gen_random_uuid(),id,:attempt,:completed,prior_price,currency_code,'MATCHES_PRIOR',:raw,
                  'synthetic-historical-price' FROM ops.price_command WHERE id=:command
                """).param("attempt",attempt).param("completed",Timestamp.from(completed)).param("raw",raw)
                    .param("command",commandId).update();
        }
    }

    @Test void commandCommittedAfterApprovalIsRecheckedAtActualReservationAdmission() throws Exception {
        try(var connection=transaction()) { seal(connection);connection.commit(); }
        AdvertisingCrossDomainPriceSeed.seed(seed,graph,null);
        try(var connection=transaction()) {
            assertThatThrownBy(()->AdvertisingR1Fixture.reserve(connection,graph)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("cross-domain isolation admission refused: KNOWN_CROSS_DOMAIN_INTERVENTION");
            connection.rollback();
        }
        assertNoAdvertisingCommand();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_action_reservation WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Integer.class).single()).isZero();
    }

    @Test void commandCommittedAfterReservationIsRecheckedByPublicCommandCreator() throws Exception {
        try(var connection=transaction()) { seal(connection);AdvertisingR1Fixture.reserve(connection,graph);connection.commit(); }
        AdvertisingCrossDomainPriceSeed.seed(seed,graph,null);
        assertThatThrownBy(()->app.sql("SELECT ops.create_ad_bid_command(:id,(SELECT version FROM ops.recommendation WHERE id=:id),:reservation,'isolation-test')")
                .param("id",graph.id("recommendation")).param("reservation",graph.id("reservation")).query(UUID.class).single())
                .hasRootCauseInstanceOf(SQLException.class)
                .hasStackTraceContaining("known cross-domain intervention prevents isolated command creation");
        assertNoAdvertisingCommand();
    }

    @Test void commandCommittedAfterCreationIsRecheckedBeforeApplyWithoutProviderTransport() throws Exception {
        var transport=new AdvertisingControlProofFixture(migration,application,admin,false);
        graph=transport.graph;
        assertThat(transport.reasons()).isEmpty();
        AdvertisingCrossDomainPriceSeed.seed(seed,graph,null);
        assertThat(transport.reasons()).contains("KNOWN_CROSS_DOMAIN_INTERVENTION");
        transport.state("EXECUTING");
        Throwable refusal=org.assertj.core.api.Assertions.catchThrowable(()->transport.open("APPLY"));
        while(refusal!=null && !(refusal instanceof SQLException)) refusal=refusal.getCause();
        assertThat(refusal).isInstanceOf(SQLException.class);
        assertThat(((SQLException)refusal).getSQLState()).isEqualTo("MO092");
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command_attempt WHERE command_id=:id")
                .param("id",transport.command).query(Integer.class).single()).isZero();
        assertProductionOff();
    }

    @ParameterizedTest @ValueSource(strings={"price","currency","promotion","sellable","availability"})
    void eachKnownChangedContextFactorIndependentlyPreventsComparableIsolation(String changed) {
        context(prepared.minusSeconds(120),prepared.minusSeconds(60),null,false);
        assertThat(snapshot(Instant.now()).path("state").asText()).isEqualTo("ISOLATED");
        context(Instant.now().minusMillis(1),Instant.now(),changed,false);
        assertThat(failures()).containsExactly("CROSS_DOMAIN_BASELINE_NOT_COMPARABLE");
    }

    @ParameterizedTest @ValueSource(strings={"nativeStatus","contentCompleteness"})
    void descriptiveListingFieldsDoNotInventNewMaterialityThresholds(String descriptive) {
        context(prepared.minusSeconds(120),prepared.minusSeconds(60),null,false);
        context(Instant.now().minusMillis(1),Instant.now(),descriptive,false);
        assertThat(failures()).isEmpty();
        assertThat(snapshot(Instant.now()).path("state").asText()).isEqualTo("ISOLATED");
    }

    @Test void oneUnknownFactorCannotHideADifferentKnownContextChange() {
        context(prepared.minusSeconds(120),prepared.minusSeconds(60),null,false);
        context(Instant.now().minusMillis(1),Instant.now(),"price",true);
        var result=snapshot(Instant.now());
        assertThat(result.path("failures").toString()).contains("CROSS_DOMAIN_BASELINE_NOT_COMPARABLE");
        assertThat(result.path("uncertainties").toString()).contains("CROSS_DOMAIN_CONTEXT_UNRESOLVED");
    }

    @Test void lateAcceptedFactsDoNotRewriteFrozenKnowledgeAndFutureFactsCannotLeakIntoCurrentContext() {
        context(prepared.minusSeconds(120),prepared.minusSeconds(60),null,false);
        Instant now=Instant.now();
        context(prepared.minusSeconds(30),now.plusSeconds(600),"price",false);
        assertThat(snapshot(now).path("state").asText()).isEqualTo("ISOLATED");
        assertThat(snapshot(now.plusSeconds(601)).path("failures").toString()).contains("CROSS_DOMAIN_BASELINE_NOT_COMPARABLE");
        assertThat(snapshot(now.plusSeconds(601)).path("contexts").get(0).path("before").path("price").decimalValue())
                .isEqualByComparingTo("100");
    }

    private void context(Instant observed,Instant accepted,String change,boolean unknownSellable) {
        UUID provenance=UUID.randomUUID();
        seed.sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note) VALUES(:id,:org,'MANUAL_ENTRY',:observed,:accepted,:owner,'synthetic canonical isolation context')")
                .param("id",provenance).param("org",graph.id("organization")).param("observed",Timestamp.from(observed))
                .param("accepted",Timestamp.from(accepted)).param("owner",graph.id("ownerUser")).update();
        seed.sql("INSERT INTO core.listing_price_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,currency_code,selling_price,promotion_active) VALUES(gen_random_uuid(),:org,:source,:listing,:key,:at,:currency,:price,:promotion)")
                .param("org",graph.id("organization")).param("source",provenance).param("listing",graph.id("listingVariant"))
                .param("key",UUID.randomUUID().toString()).param("at",Timestamp.from(observed))
                .param("currency","currency".equals(change)?"USD":"RUB").param("price",new java.math.BigDecimal("price".equals(change)?"105":"100"))
                .param("promotion","promotion".equals(change)?"YES":"NO").update();
        seed.sql("INSERT INTO core.listing_health_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,native_status,sellable,content_completeness) VALUES(gen_random_uuid(),:org,:source,:listing,:key,:at,:native,:sellable,:content)")
                .param("org",graph.id("organization")).param("source",provenance).param("listing",graph.id("listingVariant"))
                .param("key",UUID.randomUUID().toString()).param("at",Timestamp.from(observed))
                .param("native","nativeStatus".equals(change)?"HIDDEN":"PUBLISHED")
                .param("sellable",unknownSellable?"UNKNOWN":"sellable".equals(change)?"NO":"YES")
                .param("content",new java.math.BigDecimal("contentCompleteness".equals(change)?"0.5":"1")).update();
        seed.sql("INSERT INTO core.listing_stock_observation(id,organization_id,provenance_id,platform_listing_variant_id,source_fact_key,observed_at,fulfillment_mode_code,available_quantity) VALUES(gen_random_uuid(),:org,:source,:listing,:key,:at,'MARKETPLACE_FULFILLED',:quantity)")
                .param("org",graph.id("organization")).param("source",provenance).param("listing",graph.id("listingVariant"))
                .param("key",UUID.randomUUID().toString()).param("at",Timestamp.from(observed)).param("quantity","availability".equals(change)?0:10).update();
    }
    private UUID siblingListing(boolean map) {
        UUID sibling=UUID.randomUUID();
        seed.sql("INSERT INTO core.platform_listing_variant(id,organization_id,platform_listing_id,native_variant_key,native_sku_key,first_seen_at,last_seen_at,status,created_at,updated_at) SELECT :id,organization_id,platform_listing_id,:key,:key,clock_timestamp(),clock_timestamp(),'OBSERVED',clock_timestamp(),clock_timestamp() FROM core.platform_listing_variant WHERE id=:source")
                .param("id",sibling).param("key",sibling.toString()).param("source",graph.id("listingVariant")).update();
        if(map) mapSibling(sibling);
        else {
            UUID otherVariant=UUID.randomUUID();
            seed.sql("INSERT INTO core.product_variant(id,organization_id,product_id,sku_code,display_name,status,created_at,updated_at) SELECT :id,organization_id,product_id,:sku,'Synthetic nonoverlapping Variant','ACTIVE',clock_timestamp(),clock_timestamp() FROM core.product_variant WHERE id=:original")
                    .param("id",otherVariant).param("sku",otherVariant.toString()).param("original",graph.id("productVariant")).update();
            seed.sql("INSERT INTO core.listing_mapping(id,organization_id,platform_listing_variant_id,product_variant_id,effective_from,status,confirmed_by_user_id,reason,created_at,updated_at) VALUES(gen_random_uuid(),:org,:listing,:variant,clock_timestamp(),'ACTIVE',:owner,'synthetic known nonoverlap',clock_timestamp(),clock_timestamp())")
                    .param("org",graph.id("organization")).param("listing",sibling).param("variant",otherVariant)
                    .param("owner",graph.id("ownerUser")).update();
        }
        return sibling;
    }
    private void mapSibling(UUID sibling) {
        seed.sql("UPDATE core.listing_mapping SET status='ENDED',effective_to=clock_timestamp(),updated_at=clock_timestamp() WHERE platform_listing_variant_id=:listing AND status='ACTIVE'")
                .param("listing",sibling).update();
        seed.sql("INSERT INTO core.listing_mapping(id,organization_id,platform_listing_variant_id,product_variant_id,effective_from,status,confirmed_by_user_id,reason,created_at,updated_at) VALUES(gen_random_uuid(),:org,:listing,:variant,clock_timestamp(),'ACTIVE',:owner,'synthetic exact Variant overlap',clock_timestamp(),clock_timestamp())")
                .param("org",graph.id("organization")).param("listing",sibling).param("variant",graph.id("productVariant"))
                .param("owner",graph.id("ownerUser")).update();
    }
    private JsonNode snapshot(Instant at) {
        return json.readTree(app.sql("SELECT ops.ad_action_isolation_snapshot(:affected,:baseline,:at)::text")
                .param("affected",graph.id("affectedSet")).param("baseline",graph.id("baseline"))
                .param("at",Timestamp.from(at)).query(String.class).single());
    }
    private List<String> failures() { return app.sql("SELECT unnest(ops.ad_action_isolation_failures(:affected,:baseline,clock_timestamp()))")
            .param("affected",graph.id("affectedSet")).param("baseline",graph.id("baseline")).query(String.class).list(); }
    private List<String> gate(UUID command) { return app.sql("SELECT unnest(ops.evaluate_ad_bid_write_gate(:id))").param("id",command).query(String.class).list(); }
    private Connection transaction() throws SQLException { var c=application.getConnection();c.setAutoCommit(false);return c; }
    private UUID seal(Connection c) throws Exception { return AdvertisingR1Fixture.seal(c,graph,
            AdvertisingR1Fixture.proof(admin,c,graph,graph.id("ownerUser"),null,graph.id("recommendation"),graph.id("approval"))); }
    private UUID createAdvertisingCommand() throws Exception { try(var c=transaction()) { seal(c);UUID command=AdvertisingR1Fixture.createCommand(c,graph);c.commit();return command; } }
    private void assertNoAdvertisingCommand() { assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org")
            .param("org",graph.id("organization")).query(Integer.class).single()).isZero(); }
    private void assertProductionOff() { assertThat(seed.sql("SELECT production_write_enabled FROM ops.ad_gate_authority WHERE id=:id")
            .param("id",graph.id("gate")).query(Boolean.class).single()).isFalse(); }
}
