package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingManualPacketRepository;
import com.mimococo.marketops.operationsworkflow.AdvertisingResponsibilityIntake;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.sql.Timestamp;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Actual PostgreSQL human lifecycle with a separate fictional identity issuer and no Provider route. */
@SpringBootTest
@ActiveProfiles("ci")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@org.springframework.context.annotation.Import(AdvertisingManualWorkflowIT.Storage.class)
class AdvertisingManualWorkflowIT {
    private static final org.testcontainers.postgresql.PostgreSQLContainer DATABASE=TestDatabase.isolatedContainer();
    private static final String ISSUER_PASSWORD=UUID.randomUUID().toString();
    @Autowired DataSource application;
    @Autowired AdvertisingManualPacketRepository packets;
    @Autowired com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository outcomes;
    @Autowired AdvertisingResponsibilityIntake responsibilities;
    @Autowired com.mimococo.marketops.operationsworkflow.AdvertisingOutcomePlanning outcomePlanning;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired com.mimococo.marketops.advertisingefficiency.internal.application.AdvertisingManualWorkflowService manual;
    @Autowired com.mimococo.marketops.marketplaceintegration.RawCustody custody;
    private DataSource admin;
    private JdbcClient seed;
    private AdvertisingR1Fixture.Graph graph;
    private UUID currentConfiguration;
    private UUID rawProvenance;
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
    @AfterEach void clearAuthentication() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @BeforeEach void fixture() throws Exception {
        var migration=new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
        admin=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        seed=JdbcClient.create(migration);
        graph=AdvertisingR1Fixture.seedManual(migration);
        try(var connection=admin.getConnection()) {
            TestDatabase.enableSyntheticIdentityIssuer(connection,ISSUER_PASSWORD);
        }
        seedOutcomeAuthority();
        role("executorUser","MARKETPLACE_OPERATOR");
        scope("executorUser","ADVERTISING_VIEW"); scope("verifierUser","ADVERTISING_VIEW");
        scope("executorUser","ADVERTISING_TASK_ACT"); scope("executorUser","ADVERTISING_MANUAL_EXECUTE");
        scope("verifierUser","ADVERTISING_MANUAL_ENDORSE"); scope("verifierUser","ADVERTISING_MANUAL_VERIFY");
        scope("verifierUser","ADVERTISING_DECISION_EVIDENCE_VIEW");
        scope("ownerUser","ADVERTISING_MANUAL_APPROVE"); scope("ownerUser","ADVERTISING_POLICY_MANAGE");
        scope("ownerUser","ADVERTISING_DECISION_EVIDENCE_VIEW");
        responsibilities.ensureResponsibility(graph.id("caseId"),graph.id("calculationRun"),"MARKETPLACE_OPERATOR");
        rawProvenance=rawConfigurationProvenance();
        currentConfiguration=configuration("30",rawProvenance);
    }

    @Test void makerOpsOwnerExecutionReportAndIndependentProofUseCurrentAuthorityWithoutApiEligibility() throws Exception {
        assertThat(seed.sql("SELECT verification_state FROM platform.ad_semantic_profile WHERE id=:id").param("id",graph.id("profile")).query(String.class).single()).isEqualTo("UNVERIFIED");
        UUID packet=selected();
        assertThat(packets.packet(packet).orElseThrow().state()).isEqualTo("MANUAL_PACKET_DRAFT");
        assertThatThrownBy(()->decide(packet,"ownerUser",true)).isInstanceOf(SQLException.class);
        decide(packet,"verifierUser",false); decide(packet,"ownerUser",true);
        start(packet);
        assertThat(packets.packet(packet).orElseThrow().reservationId()).isNotNull();
        observation(packet,"executorUser","REPORT",null,null);
        assertThat(packets.packet(packet).orElseThrow().configurationProven()).isFalse();
        assertThatThrownBy(()->independentObservation(packet,"executorUser",completeDirectConsole(packet,"20",observedNow()))).isInstanceOf(SQLException.class);
        UUID proof=independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",observedNow()));
        var verified=packets.packet(packet).orElseThrow();
        assertThat(verified.configurationProven()).isTrue(); assertThat(verified.currentProofId()).isEqualTo(proof);
        assertThat(seed.sql("SELECT early_observation_complete FROM ops.ad_action_reservation WHERE id=:id").param("id",verified.reservationId()).query(Boolean.class).single()).isFalse();
        configuration("19",rawProvenance);
        assertThat(packets.packet(packet).orElseThrow().configurationProven()).isFalse();
        assertThat(packets.packet(packet).orElseThrow().state()).isEqualTo("MANUAL_EXECUTION_UNCERTAIN");
        assertThat(packets.packet(packet).orElseThrow().verifications()).hasSize(2);
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org").param("org",graph.id("organization")).query(Long.class).single()).isZero();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_candidate_selection WHERE organization_id=:org").param("org",graph.id("organization")).query(Long.class).single()).isZero();
    }

    @Test void manualStartCannotBorrowMissingUnresolvedWriteCapacity() throws Exception {
        seed.sql("UPDATE core.ad_exposure_envelope SET max_unresolved_transmitted_writes=0 WHERE id=:id")
                .param("id",graph.id("exposure")).update();
        UUID packet=selected();decide(packet,"verifierUser",false);decide(packet,"ownerUser",true);
        assertThatThrownBy(()->start(packet)).isInstanceOf(SQLException.class)
                .hasMessageContaining("UNRESOLVED_TRANSMITTED_WRITES");
        assertThat(packets.packet(packet).orElseThrow().executionStartedAt()).isNull();
        assertThat(packets.packet(packet).orElseThrow().reservationId()).isNull();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_action_reservation WHERE organization_id=:id AND state='ACTIVE'")
                .param("id",graph.id("organization")).query(Integer.class).single()).isZero();
    }

    @Test void manualConfigurationUnknownConsumesTheSameUnresolvedAxisUntilIndependentProof() throws Exception {
        UUID packet=selected();decide(packet,"verifierUser",false);decide(packet,"ownerUser",true);start(packet);
        assertThat(manualUnresolvedExposure()).isEqualTo(1);
        observation(packet,"executorUser","REPORT",null,null);
        assertThat(manualUnresolvedExposure()).isEqualTo(1);
        independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",observedNow()));
        assertThat(manualUnresolvedExposure()).isZero();
        UUID reservation=packets.packet(packet).orElseThrow().reservationId();
        assertThat(seed.sql("SELECT state FROM ops.ad_action_reservation WHERE id=:id").param("id",reservation)
                .query(String.class).single()).isEqualTo("ACTIVE");
        configuration("19",rawProvenance);
        assertThat(manualUnresolvedExposure()).isEqualTo(1);
    }

    @Test void scopedStopRevokesIssuedManualPacketBeforeAnyExternalStart() throws Exception {
        UUID packet=selected();decide(packet,"verifierUser",false);decide(packet,"ownerUser",true);
        assertThat(packets.packet(packet).orElseThrow().state()).isEqualTo("MANUAL_PACKET_ISSUED");
        UUID stop=stopManualStore();
        var stopped=packets.packet(packet).orElseThrow();
        assertThat(stopped.state()).isEqualTo("MANUAL_PACKET_REVOKED");
        assertThat(stopped.executionStartedAt()).isNull();assertThat(stopped.reservationId()).isNull();
        assertThat(seed.sql("SELECT revoked_reason FROM ops.ad_manual_execution_packet WHERE id=:id")
                .param("id",packet).query(String.class).single()).isEqualTo("CONTAINMENT_ACTIVATED");
        assertThatThrownBy(()->start(packet)).isInstanceOf(SQLException.class);
        assertThatThrownBy(()->observation(packet,"executorUser","REPORT",null,null)).isInstanceOf(SQLException.class);
        assertThat(seed.sql("SELECT state FROM ops.ad_containment WHERE id=:id").param("id",stop)
                .query(String.class).single()).isEqualTo("ACTIVE");
        assertNoApiCommand();
    }

    @Test void scopedStopMakesStartedManualWorkUncertainAndOnlyFactualVerificationCanContinue() throws Exception {
        UUID packet=selected();decide(packet,"verifierUser",false);decide(packet,"ownerUser",true);start(packet);
        observation(packet,"executorUser","REPORT",null,null);
        UUID oldProof=independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",observedNow()));
        var before=packets.packet(packet).orElseThrow();
        assertThat(before.configurationProven()).isTrue();UUID held=before.reservationId();
        UUID stop=stopManualStore();
        var stopped=packets.packet(packet).orElseThrow();
        assertThat(stopped.state()).isEqualTo("MANUAL_EXECUTION_UNCERTAIN");
        assertThat(stopped.currentProofId()).isNull();assertThat(stopped.configurationProven()).isFalse();
        assertThat(stopped.executionStartedAt()).isEqualTo(before.executionStartedAt());
        assertThat(stopped.reservationId()).isEqualTo(held);assertThat(stopped.verifications()).hasSize(2);
        assertThat(seed.sql("SELECT state,configuration_resolved,unknown_or_mismatch_open,early_observation_complete FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",held).query().singleRow()).containsEntry("state","ACTIVE")
                .containsEntry("configuration_resolved",false).containsEntry("unknown_or_mismatch_open",true)
                .containsEntry("early_observation_complete",false);
        assertThatThrownBy(()->start(packet)).isInstanceOf(SQLException.class);
        observation(packet,"executorUser","REPORT",null,null);
        UUID newProof=independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",observedNow()));
        var verified=packets.packet(packet).orElseThrow();
        assertThat(newProof).isNotEqualTo(oldProof);assertThat(verified.currentProofId()).isEqualTo(newProof);
        assertThat(verified.configurationProven()).isTrue();assertThat(verified.verifications()).hasSize(4);
        assertThat(verified.reservationId()).isEqualTo(held);
        assertThat(seed.sql("SELECT state,configuration_resolved,unknown_or_mismatch_open,early_observation_complete FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",held).query().singleRow()).containsEntry("state","ACTIVE")
                .containsEntry("configuration_resolved",true).containsEntry("unknown_or_mismatch_open",false)
                .containsEntry("early_observation_complete",false);
        assertThat(seed.sql("SELECT state FROM ops.ad_containment WHERE id=:id").param("id",stop)
                .query(String.class).single()).isEqualTo("ACTIVE");
        assertThatThrownBy(()->start(packet)).isInstanceOf(SQLException.class);
        assertNoApiCommand();
    }

    @Test void knownSharedPriceCommandBlocksManualEndorsementAfterSelection() throws Exception {
        UUID packet=selected();
        AdvertisingCrossDomainPriceSeed.seed(seed,graph,null);
        assertThatThrownBy(()->decide(packet,"verifierUser",false)).isInstanceOf(SQLException.class)
                .hasMessageContaining("manual approval scope, independence or authority denied");
        assertThat(packets.packet(packet).orElseThrow().state()).isEqualTo("MANUAL_PACKET_DRAFT");
        assertThat(packets.packet(packet).orElseThrow().reservationId()).isNull();
        assertNoApiCommand();
    }

    @Test void knownSharedPriceCommandCommittedAfterManualApprovalBlocksActualStart() throws Exception {
        UUID packet=selected();decide(packet,"verifierUser",false);decide(packet,"ownerUser",true);
        AdvertisingCrossDomainPriceSeed.seed(seed,graph,null);
        assertThatThrownBy(()->start(packet)).isInstanceOf(SQLException.class)
                .hasMessageContaining("manual execution authority or live scope denied");
        assertThat(packets.packet(packet).orElseThrow().executionStartedAt()).isNull();
        assertThat(packets.packet(packet).orElseThrow().reservationId()).isNull();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_action_reservation WHERE organization_id=:org AND state='ACTIVE'")
                .param("org",graph.id("organization")).query(Integer.class).single()).isZero();
        assertNoApiCommand();
    }

    @ParameterizedTest
    @CsvSource({"AD_BID_CHANGE,targetBid,20", "AD_BUDGET_CHANGE,targetBudget,50", "AD_STATUS_CHANGE,targetStatus,native-paused"})
    void theSameManualValueInAPartialScreenshotCannotReplaceACompleteIndependentConsoleObservation(
            String action,String field,String value) throws Exception {
        // These are explicit fictional Owner policy/configuration inputs, not extra API capabilities.
        if(action.equals("AD_BUDGET_CHANGE")) {
            currentConfiguration=configuration("30",rawProvenance,new BigDecimal("100"));
        } else if(action.equals("AD_STATUS_CHANGE")) {
            seed.sql("UPDATE platform.ad_semantic_profile SET status_semantics='{" +
                    "\"native-running\":\"RUNNING\",\"native-paused\":\"PAUSED\"}'::jsonb WHERE id=:id")
                    .param("id",graph.id("profile")).update();
        }
        UUID packet=startedPacket(action),held=packets.packet(packet).orElseThrow().reservationId();
        Instant partialAt=observedNow();
        ObjectNode partial=completeDirectConsole(packet,value,partialAt)
                .put("evidenceSource","SCREENSHOT").put("completeness","INCOMPLETE").put("directObservationAttested",false);
        UUID partialId=independentObservation(packet,"verifierUser",partial);
        assertUnverifiedHeldWithoutOutcome(packet,held);
        assertThat(verification(partialId)).containsEntry("evidence_grade","UNVERIFIED_MANUAL_EVIDENCE")
                .containsEntry("observed_field_path",field).containsEntry("observed_value",value)
                .containsEntry("proves_configuration",false).containsEntry("observed_at",Timestamp.from(partialAt));
        String preserved=verificationBytes(partialId);
        Instant completeAt=observedNow();
        UUID completeId=independentObservation(packet,"verifierUser",completeDirectConsole(packet,value,completeAt));
        assertThat(packets.packet(packet).orElseThrow().configurationProven()).isTrue();
        assertThat(packets.packet(packet).orElseThrow().currentProofId()).isEqualTo(completeId);
        assertThat(verification(completeId)).containsEntry("evidence_grade","INDEPENDENT_MANUAL_VERIFICATION")
                .containsEntry("observed_field_path",field).containsEntry("observed_value",value)
                .containsEntry("proves_configuration",true).containsEntry("observed_at",Timestamp.from(completeAt));
        assertThat(seed.sql("SELECT independent_observation->>'evidenceSource' FROM ops.ad_manual_configuration_verification WHERE id=:id")
                .param("id",completeId).query(String.class).single()).isEqualTo("DIRECT_OFFICIAL_CONSOLE");
        var metadata=json.readTree(seed.sql("SELECT independent_observation::text FROM ops.ad_manual_configuration_verification WHERE id=:id")
                .param("id",completeId).query(String.class).single());
        assertThat(metadata.path("exactNativeObjectId").asString()).isEqualTo(graph.id("object").toString());
        assertThat(metadata.path("semanticProfileId").asString()).isEqualTo(graph.id("profile").toString());
        assertThat(metadata.path("evidenceReference").asString()).isEqualTo("fixture://complete-direct-console/"+packet);
        assertThat(Instant.parse(metadata.path("observedAt").asString())).isEqualTo(completeAt);
        assertThat(seed.sql("SELECT recorded_at FROM ops.ad_manual_configuration_verification WHERE id=:id")
                .param("id",completeId).query(Timestamp.class).single().toInstant()).isAfterOrEqualTo(completeAt);
        assertThat(verificationBytes(partialId)).isEqualTo(preserved);
        assertThat(seed.sql("SELECT state,early_observation_complete FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",held).query().singleRow()).containsEntry("state","ACTIVE").containsEntry("early_observation_complete",false);
        assertNoManualOutcomeOrApiCommand();
    }

    @ParameterizedTest @CsvSource({"SCREENSHOT,COMPLETE,true", "DIRECT_OFFICIAL_CONSOLE,INCOMPLETE,true", "DIRECT_OFFICIAL_CONSOLE,COMPLETE,false"})
    void sourceCompletenessAndDirectAttestationEachRemainNecessaryEvenWhenTheValueMatches(
            String source,String completeness,boolean attested) throws Exception {
        UUID packet=startedPacket("AD_BID_CHANGE"),held=packets.packet(packet).orElseThrow().reservationId();
        UUID id=independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",observedNow())
                .put("evidenceSource",source).put("completeness",completeness).put("directObservationAttested",attested));
        assertThat(verification(id)).containsEntry("evidence_grade","UNVERIFIED_MANUAL_EVIDENCE").containsEntry("proves_configuration",false);
        assertUnverifiedHeldWithoutOutcome(packet,held);
    }

    @ParameterizedTest @ValueSource(strings={"observedValue","observedAt","evidenceSource","completeness","exactNativeObjectId",
            "exactFieldPath","semanticProfileId","evidenceReference","directObservationAttested"})
    void theAppRoleCannotOmitAnyIndependentObservationMetadata(String missing) throws Exception {
        UUID packet=startedPacket("AD_BID_CHANGE"),held=packets.packet(packet).orElseThrow().reservationId();
        ObjectNode envelope=completeDirectConsole(packet,"20",observedNow());envelope.remove(missing);
        String before=packetAndHistoryBytes(packet);
        assertThatThrownBy(()->independentObservation(packet,"verifierUser",envelope))
                .isInstanceOfSatisfying(SQLException.class,failure->assertThat(failure.getSQLState()).isEqualTo("23514"));
        assertThat(packetAndHistoryBytes(packet)).isEqualTo(before);assertUnverifiedHeldWithoutOutcome(packet,held);
    }

    @ParameterizedTest @ValueSource(strings={"OTHER_OBJECT","OTHER_PROFILE","OTHER_FIELD","FUTURE","BEFORE_START","RELATIVE_TIME"})
    void independentObservationMustDescribeThisPacketFieldProfileAndActualExecutionTime(String fault) throws Exception {
        UUID packet=startedPacket("AD_BID_CHANGE"),held=packets.packet(packet).orElseThrow().reservationId();
        ObjectNode envelope=completeDirectConsole(packet,"20",observedNow());
        switch(fault) {
            case "OTHER_OBJECT" -> envelope.put("exactNativeObjectId",UUID.randomUUID().toString());
            case "OTHER_PROFILE" -> envelope.put("semanticProfileId",UUID.randomUUID().toString());
            case "OTHER_FIELD" -> envelope.put("exactFieldPath","targetBudget");
            case "FUTURE" -> envelope.put("observedAt",observedNow().plusSeconds(60).toString());
            case "BEFORE_START" -> envelope.put("observedAt",packets.packet(packet).orElseThrow().executionStartedAt().minusSeconds(1).toString());
            case "RELATIVE_TIME" -> envelope.put("observedAt","now");
            default -> throw new AssertionError(fault);
        }
        String before=packetAndHistoryBytes(packet);
        assertThatThrownBy(()->independentObservation(packet,"verifierUser",envelope))
                .isInstanceOfSatisfying(SQLException.class,failure->assertThat(failure.getSQLState()).isEqualTo("23514"));
        assertThat(packetAndHistoryBytes(packet)).isEqualTo(before);assertUnverifiedHeldWithoutOutcome(packet,held);
    }

    @ParameterizedTest @ValueSource(strings={"SELF_VERIFIER","REVOKED_SCOPE"})
    void completeMetadataCannotReplaceDistinctCurrentVerifierAuthority(String fault) throws Exception {
        UUID packet=startedPacket("AD_BID_CHANGE"),held=packets.packet(packet).orElseThrow().reservationId();
        if(fault.equals("SELF_VERIFIER"))authorizeExecutorForVerification();
        if(fault.equals("REVOKED_SCOPE"))seed.sql("UPDATE iam.user_scope_grant SET status='REVOKED' WHERE user_id=:id AND action_code='ADVERTISING_MANUAL_VERIFY'")
                .param("id",graph.id("verifierUser")).update();
        String before=packetAndHistoryBytes(packet);
        assertThatThrownBy(()->independentObservation(packet,fault.equals("SELF_VERIFIER")?"executorUser":"verifierUser",
                completeDirectConsole(packet,"20",observedNow())))
                .isInstanceOfSatisfying(SQLException.class,failure->assertThat(failure.getSQLState()).isEqualTo("MO064"));
        assertThat(packetAndHistoryBytes(packet)).isEqualTo(before);assertUnverifiedHeldWithoutOutcome(packet,held);
    }

    @ParameterizedTest @ValueSource(strings={"MISMATCH","UNKNOWN"})
    void anOlderCompleteConsoleValueCannotClearAnAlreadyKnownLaterConfiguration(String state) throws Exception {
        UUID packet=startedPacket("AD_BID_CHANGE"),held=packets.packet(packet).orElseThrow().reservationId();
        Instant observed=observedNow();
        UUID later=configuration(state.equals("MISMATCH")?"19":null,rawProvenance);
        assertThat(seed.sql("SELECT observed_at FROM core.ad_object_configuration_observation WHERE id=:id")
                .param("id",later).query(Timestamp.class).single().toInstant()).isAfterOrEqualTo(observed);
        String originalConfiguration=seed.sql("SELECT to_jsonb(c)::text FROM core.ad_object_configuration_observation c WHERE id=:id")
                .param("id",later).query(String.class).single();
        UUID evidence=independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",observed));
        assertThat(verification(evidence)).containsEntry("proves_configuration",false);
        assertThat(packets.packet(packet).orElseThrow().state()).isEqualTo("MANUAL_EXECUTION_UNCERTAIN");
        assertUnverifiedHeldWithoutOutcome(packet,held);
        assertThat(seed.sql("SELECT to_jsonb(c)::text FROM core.ad_object_configuration_observation c WHERE id=:id")
                .param("id",later).query(String.class).single()).isEqualTo(originalConfiguration);
    }

    @Test void anOlderCompleteValueCannotEraseAKnownLaterUnverifiedManualObservation() throws Exception {
        UUID packet=startedPacket("AD_BID_CHANGE"),held=packets.packet(packet).orElseThrow().reservationId();
        Instant earlier=observedNow();
        UUID later=independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",observedNow())
                .put("evidenceSource","SCREENSHOT").put("completeness","INCOMPLETE").put("directObservationAttested",false));
        String preserved=verificationBytes(later);
        UUID older=independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",earlier));
        assertThat(verification(older)).containsEntry("proves_configuration",false);
        assertThat(verificationBytes(later)).isEqualTo(preserved);assertUnverifiedHeldWithoutOutcome(packet,held);
    }

    @Test void anOlderOfficialConfigurationCannotClearAKnownLaterPartialManualObservation() throws Exception {
        UUID packet=startedPacket("AD_BID_CHANGE"),held=packets.packet(packet).orElseThrow().reservationId();
        UUID configurationId=configuration("20",rawProvenance);
        Instant officialAt=seed.sql("SELECT observed_at FROM core.ad_object_configuration_observation WHERE id=:id")
                .param("id",configurationId).query(Timestamp.class).single().toInstant();
        Instant partialAt=observedNow();assertThat(partialAt).isAfter(officialAt);
        UUID partial=independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",partialAt)
                .put("evidenceSource","SCREENSHOT").put("completeness","INCOMPLETE").put("directObservationAttested",false));
        String partialBefore=verificationBytes(partial);
        UUID official=observation(packet,"verifierUser","OFFICIAL",null,configurationId);
        assertThat(verification(official)).containsEntry("evidence_grade","OFFICIAL_API_READBACK")
                .containsEntry("observed_at",Timestamp.from(officialAt)).containsEntry("proves_configuration",false);
        assertThat(verificationBytes(partial)).isEqualTo(partialBefore);
        assertThat(packets.packet(packet).orElseThrow().state()).isEqualTo("MANUAL_EXECUTION_UNCERTAIN");
        assertUnverifiedHeldWithoutOutcome(packet,held);
    }

    @Test void anOlderPartialScreenshotIsAppendedWithoutDowngradingTheNewerCompleteProof() throws Exception {
        UUID packet=startedPacket("AD_BID_CHANGE"),held=packets.packet(packet).orElseThrow().reservationId();
        Instant olderAt=observedNow(),newerAt=observedNow();
        assertThat(newerAt).isAfter(olderAt);
        UUID current=independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",newerAt));
        assertThat(packets.packet(packet).orElseThrow().configurationProven()).isTrue();
        String currentBytes=verificationBytes(current),reservationBefore=reservationBytes(held);
        int journalBefore=seed.sql("SELECT count(*) FROM ops.work_task_event WHERE organization_id=:org AND action_kind='MANUAL_EXECUTION_VERIFIED'")
                .param("org",graph.id("organization")).query(Integer.class).single();
        submitHttp(packet,"verifierUser",completeDirectConsole(packet,"20",olderAt)
                .put("evidenceSource","SCREENSHOT").put("completeness","INCOMPLETE").put("directObservationAttested",false))
                .andExpect(status().isOk());
        UUID historical=packets.packet(packet).orElseThrow().verifications().stream()
                .filter(item->!item.id().equals(current)).findFirst().orElseThrow().id();
        assertThat(verification(historical)).containsEntry("evidence_grade","UNVERIFIED_MANUAL_EVIDENCE")
                .containsEntry("observed_at",Timestamp.from(olderAt)).containsEntry("proves_configuration",false);
        var view=packets.packet(packet).orElseThrow();
        assertThat(view.state()).isEqualTo("MANUAL_CONFIGURATION_VERIFIED");assertThat(view.configurationProven()).isTrue();
        assertThat(view.currentProofId()).isEqualTo(current);assertThat(view.verifications()).hasSize(2);
        assertThat(verificationBytes(current)).isEqualTo(currentBytes);assertThat(reservationBytes(held)).isEqualTo(reservationBefore);
        assertThat(seed.sql("SELECT count(*) FROM ops.work_task_event WHERE organization_id=:org AND action_kind='MANUAL_EXECUTION_VERIFIED'")
                .param("org",graph.id("organization")).query(Integer.class).single()).isEqualTo(journalBefore);
        assertNoManualOutcomeOrApiCommand();
    }

    @Test void staleNewSubmissionAfterHistoricalStartIsRefusedWhileFreshSameValueAndScopeCanProveConfiguration() throws Exception {
        // Historical start is explicit synthetic INPUT. This test proves new submission admission,
        // not that the application reconstructed an execution performed two hours earlier.
        seed.sql("UPDATE platform.ad_semantic_profile SET created_at=clock_timestamp()-interval '1 day' WHERE id=:id")
                .param("id",graph.id("profile")).update();
        UUID policy=syntheticHistoricalManualPolicy();
        UUID packet=selectedWithPolicy("AD_BID_CHANGE",policy);decide(packet,"verifierUser",false);decide(packet,"ownerUser",true);start(packet);
        UUID held=packets.packet(packet).orElseThrow().reservationId();
        Instant now=observedNow(),started=now.minusSeconds(7200),stale=now.minusSeconds(3601);
        seed.sql("UPDATE ops.ad_manual_execution_packet SET execution_started_at=:started,issued_at=:issued,created_at=:issued WHERE id=:id")
                .param("started",Timestamp.from(started)).param("issued",Timestamp.from(started.minusSeconds(30))).param("id",packet).update();
        assertThat(stale).isAfter(started);
        String before=packetAndHistoryBytes(packet);
        assertThatThrownBy(()->independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",stale)))
                .isInstanceOfSatisfying(SQLException.class,failure->assertThat(failure.getSQLState()).isEqualTo("23514"));
        assertThat(packetAndHistoryBytes(packet)).isEqualTo(before);assertUnverifiedHeldWithoutOutcome(packet,held);
        UUID proof=independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",observedNow()));
        assertThat(packets.packet(packet).orElseThrow().configurationProven()).isTrue();
        assertThat(packets.packet(packet).orElseThrow().currentProofId()).isEqualTo(proof);assertNoManualOutcomeOrApiCommand();
    }

    @Test void oldSqlAndServiceValueOnlyRoutesCannotCreateAnIndependentProof() throws Exception {
        UUID packet=startedPacket("AD_BID_CHANGE"),held=packets.packet(packet).orElseThrow().reservationId();
        String before=packetAndHistoryBytes(packet);
        assertThatThrownBy(()->observation(packet,"verifierUser","INDEPENDENT","20",null))
                .isInstanceOfSatisfying(SQLException.class,failure->assertThat(failure.getSQLState()).isEqualTo("MO097"));
        assertThatThrownBy(()->manual.independent(actor("verifierUser"),packet,packets.packet(packet).orElseThrow().version(),"20"))
                .isInstanceOfSatisfying(com.mimococo.marketops.shared.OperationRejectedException.class,
                        failure->assertThat(failure.errorCode()).isEqualTo(com.mimococo.marketops.shared.ErrorCode.ACTION_NOT_PERMITTED));
        assertThat(packetAndHistoryBytes(packet)).isEqualTo(before);assertUnverifiedHeldWithoutOutcome(packet,held);
    }

    @Test void actualHttpPartialScreenshotAndCompleteConsoleHaveDifferentProofResultsForTheSameValue() throws Exception {
        UUID packet=startedPacket("AD_BID_CHANGE"),held=packets.packet(packet).orElseThrow().reservationId();
        ObjectNode partial=completeDirectConsole(packet,"20",observedNow()).put("evidenceSource","SCREENSHOT")
                .put("completeness","INCOMPLETE").put("directObservationAttested",false);
        var partialResponse=submitHttp(packet,"verifierUser",partial).andExpect(status().isOk()).andReturn().getResponse();
        var partialBody=json.readTree(partialResponse.getContentAsByteArray());
        assertThat(partialBody.path("configurationProven").asBoolean()).isFalse();
        assertThat(partialBody.path("state").asString()).isEqualTo("ACTION_REPORTED_CONFIGURATION_UNVERIFIED");
        assertUnverifiedHeldWithoutOutcome(packet,held);
        String preserved=packetAndHistoryBytes(packet);
        ObjectNode bare=json.createObjectNode().put("observedValue","20");
        submitHttp(packet,"verifierUser",bare).andExpect(status().isBadRequest());
        assertThat(packetAndHistoryBytes(packet)).isEqualTo(preserved);
        Instant actualObserved=observedNow();
        var completeResponse=submitHttp(packet,"verifierUser",completeDirectConsole(packet,"20",actualObserved))
                .andExpect(status().isOk()).andReturn().getResponse();
        var completeBody=json.readTree(completeResponse.getContentAsByteArray());
        assertThat(completeBody.path("configurationProven").asBoolean()).isTrue();
        assertThat(completeBody.path("state").asString()).isEqualTo("MANUAL_CONFIGURATION_VERIFIED");
        var current=packets.packet(packet).orElseThrow();assertThat(current.configurationProven()).isTrue();
        assertThat(verification(current.currentProofId())).containsEntry("observed_at",Timestamp.from(actualObserved));
        assertThat(current.reservationId()).isEqualTo(held);assertNoManualOutcomeOrApiCommand();
    }

    @ParameterizedTest @ValueSource(strings={"FUTURE","SELF_VERIFIER","MISSING_ATTESTATION"})
    void actualHttpRefusesInvalidObservationTimeSelfVerificationAndMissingAttestation(String fault) throws Exception {
        UUID packet=startedPacket("AD_BID_CHANGE"),held=packets.packet(packet).orElseThrow().reservationId();
        ObjectNode envelope=completeDirectConsole(packet,"20",observedNow());
        if(fault.equals("SELF_VERIFIER"))authorizeExecutorForVerification();
        if(fault.equals("FUTURE"))envelope.put("observedAt",observedNow().plusSeconds(60).toString());
        if(fault.equals("MISSING_ATTESTATION"))envelope.remove("directObservationAttested");
        String before=packetAndHistoryBytes(packet);
        submitHttp(packet,fault.equals("SELF_VERIFIER")?"executorUser":"verifierUser",envelope)
                .andExpect(fault.equals("SELF_VERIFIER")?status().isForbidden():status().isBadRequest());
        assertThat(packetAndHistoryBytes(packet)).isEqualTo(before);assertUnverifiedHeldWithoutOutcome(packet,held);
    }

    private UUID startedPacket(String action) throws Exception {
        UUID packet=selected(true,action);decide(packet,"verifierUser",false);decide(packet,"ownerUser",true);start(packet);return packet;
    }
    private UUID syntheticHistoricalManualPolicy() {
        UUID policy=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_manual_policy(id,organization_id,store_id,semantic_profile_id,policy_version,cause_code,
                  outcome_policy_id,action_kind,candidate_basis,currency_code,verification_mode,configuration_max_age_seconds,
                  packet_lease_seconds,effective_from,effective_to,approved_by_user_id,approved_at,evidence_reference)
                VALUES(:id,:org,:store,:profile,1,'PROVEN_ADVERTISING_LOSS',:outcome,'AD_BID_CHANGE','MAX_CPC_BOUNDED','RUB',
                  'INDEPENDENT_OR_OFFICIAL',3600,1800,clock_timestamp()-interval '3 hours',clock_timestamp()+interval '1 hour',
                  :owner,clock_timestamp()-interval '3 hours','fixture://historical-owner-manual-policy')
                """).param("id",policy).param("org",graph.id("organization")).param("store",graph.id("store"))
                .param("profile",graph.id("profile")).param("outcome",graph.id("outcome")).param("owner",graph.id("ownerUser")).update();
        return policy;
    }
    private Instant observedNow() { return seed.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant(); }
    private ObjectNode completeDirectConsole(UUID packet,String value,Instant observed) {
        String field=seed.sql("SELECT CASE action_kind WHEN 'AD_BID_CHANGE' THEN 'targetBid' WHEN 'AD_BUDGET_CHANGE' THEN 'targetBudget' ELSE 'targetStatus' END FROM ops.ad_manual_execution_packet WHERE id=:id")
                .param("id",packet).query(String.class).single();
        return json.createObjectNode().put("observedValue",value).put("observedAt",observed.toString())
                .put("evidenceSource","DIRECT_OFFICIAL_CONSOLE").put("completeness","COMPLETE")
                .put("exactNativeObjectId",graph.id("object").toString()).put("exactFieldPath",field)
                .put("semanticProfileId",graph.id("profile").toString())
                .put("evidenceReference","fixture://complete-direct-console/"+packet).put("directObservationAttested",true);
    }
    private UUID independentObservation(UUID packet,String actor,ObjectNode envelope) throws Exception {
        UUID id=UUID.randomUUID();
        try(Connection connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id(actor),"MANUAL_INDEPENDENT_VERIFY",packet,packet);
            query(connection,"SELECT ops.record_ad_manual_independent_observation(?,?,?,?::jsonb,?)",id,packet,
                    packets.packet(packet).orElseThrow().version(),envelope.toString(),proof);connection.commit();
        }
        return id;
    }
    private java.util.Map<String,Object> verification(UUID id) {
        return seed.sql("SELECT evidence_grade,observed_field_path,observed_value,observed_at,proves_configuration FROM ops.ad_manual_configuration_verification WHERE id=:id")
                .param("id",id).query().singleRow();
    }
    private String verificationBytes(UUID id) {
        return seed.sql("SELECT to_jsonb(v)::text FROM ops.ad_manual_configuration_verification v WHERE id=:id")
                .param("id",id).query(String.class).single();
    }
    private String reservationBytes(UUID id) {
        return seed.sql("SELECT to_jsonb(r)::text FROM ops.ad_action_reservation r WHERE id=:id")
                .param("id",id).query(String.class).single();
    }
    private String packetAndHistoryBytes(UUID id) {
        return seed.sql("SELECT jsonb_build_object('packet',to_jsonb(p),'history',coalesce((SELECT jsonb_agg(to_jsonb(v) ORDER BY v.recorded_at,v.id) FROM ops.ad_manual_configuration_verification v WHERE v.packet_id=p.id),'[]'::jsonb))::text FROM ops.ad_manual_execution_packet p WHERE p.id=:id")
                .param("id",id).query(String.class).single();
    }
    private void assertUnverifiedHeldWithoutOutcome(UUID packet,UUID held) {
        var view=packets.packet(packet).orElseThrow();assertThat(view.configurationProven()).isFalse();
        assertThat(view.currentProofId()).isNull();assertThat(view.reservationId()).isEqualTo(held);
        assertThat(seed.sql("SELECT state,configuration_resolved,unknown_or_mismatch_open,early_observation_complete FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",held).query().singleRow()).containsEntry("state","ACTIVE").containsEntry("configuration_resolved",false)
                .containsEntry("unknown_or_mismatch_open",!"MANUAL_EXECUTION_IN_PROGRESS".equals(view.state()))
                .containsEntry("early_observation_complete",false);
        // Before an observation, the started packet itself contributes unresolved exposure.
        // An observed incomplete/conflicted configuration additionally opens the reservation flag.
        assertThat(manualUnresolvedExposure()).isEqualTo(1);
        assertNoManualOutcomeOrApiCommand();
    }
    private void assertNoManualOutcomeOrApiCommand() {
        assertNoApiCommand();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_outcome_observation WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Integer.class).single()).isZero();
    }
    private void authorizeExecutorForVerification() {
        role("executorUser","OPS_LEAD");scope("executorUser","ADVERTISING_MANUAL_VERIFY");
        scope("executorUser","ADVERTISING_DECISION_EVIDENCE_VIEW");
    }
    private com.mimococo.marketops.identityaccess.AuthenticatedActor actor(String user) {
        String issuer=seed.sql("SELECT issuer FROM iam.identity_provider WHERE id=:id").param("id",graph.id("provider")).query(String.class).single();
        Instant at=observedNow();
        var roles=seed.sql("SELECT role_code FROM iam.user_role_assignment WHERE user_id=:id AND status='ACTIVE'")
                .param("id",graph.id(user)).query(String.class).list().stream()
                .map(com.mimococo.marketops.identityaccess.BusinessRoleCode::valueOf).collect(java.util.stream.Collectors.toSet());
        return new com.mimococo.marketops.identityaccess.AuthenticatedActor(graph.id(user),graph.id("organization"),graph.id("provider"),issuer,
                "Synthetic independent observation actor","a".repeat(64),"b".repeat(64),at,at.plusSeconds(1800),true,roles);
    }
    private org.springframework.test.web.servlet.ResultActions submitHttp(UUID packet,String user,ObjectNode envelope) throws Exception {
        envelope.put("expectedVersion",packets.packet(packet).orElseThrow().version());
        var authentication=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor(user),null,java.util.List.of());
        return mvc.perform(post("/api/v1/console/advertising/manual-packets/"+packet+"/independent-verification")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(authentication))
                .contentType(MediaType.APPLICATION_JSON).content(envelope.toString()));
    }

    private UUID stopManualStore() throws Exception {
        role("verifierUser","OPS_LEAD");scope("verifierUser","ADVERTISING_POLICY_MANAGE");
        UUID stop=UUID.randomUUID();
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("verifierUser"),
                    "CONTAINMENT_STOP",graph.id("object"),stop);
            query(connection,"SELECT ops.activate_ad_human_containment(?,?,'PLATFORM_STORE_CAPABILITY','KILL_SWITCH_ACTIVE','BUSINESS_HARM',?,'fictional safety stop','fixture://manual-stop',?)",
                    stop,graph.id("object"),graph.id("verifierUser"),proof);connection.commit();
        }
        return stop;
    }
    private void assertNoApiCommand() {
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:org")
                .param("org",graph.id("organization")).query(Integer.class).single()).isZero();
    }

    private int manualUnresolvedExposure() {
        return seed.sql("SELECT (ops.ad_exposure_snapshot(:org,:store,'PROTECTION_DECREASE')#>>'{envelopes,0,axes,unresolvedTransmittedWrites,usage}')::integer")
                .param("org",graph.id("organization")).param("store",graph.id("store")).query(Integer.class).single();
    }

    @Test void officialProofMustBeCurrentSuccessfulRawFromTheExactAccountAndField() throws Exception {
        UUID packet=selected(); decide(packet,"verifierUser",false); decide(packet,"ownerUser",true); start(packet);
        UUID forged=configuration("20",graph.id("provenance"));
        assertThatThrownBy(()->observation(packet,"verifierUser","OFFICIAL",null,forged)).isInstanceOf(SQLException.class);
        UUID actual=configuration("20",rawProvenance);
        observation(packet,"verifierUser","OFFICIAL",null,actual);
        assertThat(packets.packet(packet).orElseThrow().configurationProven()).isTrue();
        assertThatThrownBy(()->observation(packet,"verifierUser","OFFICIAL","20",actual)).isInstanceOf(SQLException.class);
    }

    @Test void callerSqlCannotForgeGradeAndNewScopeRevocationStopsIssuedWork() throws Exception {
        UUID packet=selected(); decide(packet,"verifierUser",false); decide(packet,"ownerUser",true);
        try(Connection connection=application.getConnection()) {
            assertThatThrownBy(()->query(connection,"UPDATE ops.ad_manual_execution_packet SET state='MANUAL_CONFIGURATION_VERIFIED' WHERE id=? RETURNING id",packet)).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->query(connection,"SELECT iam.issue_ad_control_invocation_grant('MANUAL_EXECUTION_START',repeat('a',64),?,?,?,repeat('a',64),repeat('b',64),now(),now()+interval '1 hour',?,?,pg_backend_pid(),txid_current())",graph.id("executorUser"),graph.id("organization"),graph.id("provider"),packet,packet)).isInstanceOf(SQLException.class);
        }
        seed.sql("UPDATE iam.user_scope_grant SET status='REVOKED' WHERE user_id=:actor AND action_code='ADVERTISING_MANUAL_EXECUTE'").param("actor",graph.id("executorUser")).update();
        assertThatThrownBy(()->start(packet)).isInstanceOf(SQLException.class);
        assertThat(packets.packet(packet).orElseThrow().executionStartedAt()).isNull();
    }

    @Test void newConfigurationInvalidatesAnUnexecutedPacketAndVersionOrProofCannotBeReplayed() throws Exception {
        UUID packet=selected();
        try(Connection connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("verifierUser"),"MANUAL_PACKET_ENDORSE",packet,packet);
            query(connection,"SELECT ops.decide_ad_manual_packet(?,0,false,?)",packet,proof);
            var savepoint=connection.setSavepoint();
            assertThatThrownBy(()->query(connection,"SELECT ops.decide_ad_manual_packet(?,0,false,?)",packet,proof)).isInstanceOf(SQLException.class);
            connection.rollback(savepoint); connection.commit();
        }
        configuration("31",rawProvenance);
        assertThatThrownBy(()->decide(packet,"ownerUser",true)).isInstanceOf(SQLException.class);
        assertThat(packets.packet(packet).orElseThrow().approverUserId()).isNull();
    }

    @Test void realManualPlannerAndActualSalesEvidenceReleaseOnlyAfterIndependentConfigurationAndEarlySafety() throws Exception {
        actualEarlySafetyRelease();
    }
    @Test void aLaterCanonicalMismatchAfterActualReleaseIsRetainedAndReopensTheSameUnknownReservation() throws Exception {
        ReleasedManual prior=actualEarlySafetyRelease();
        String oldProof=verificationBytes(prior.proof()),outcomesBefore=manualOutcomeHistoryBytes(prior.packet());
        UUID actualConfiguration=configuration("19",rawProvenance);
        assertThat(seed.sql("SELECT observed_bid_amount FROM core.ad_object_configuration_observation WHERE id=:id")
                .param("id",actualConfiguration).query(BigDecimal.class).single()).isEqualByComparingTo("19");
        var packet=packets.packet(prior.packet()).orElseThrow();
        assertThat(packet.state()).isEqualTo("MANUAL_EXECUTION_UNCERTAIN");assertThat(packet.currentProofId()).isNull();
        assertThat(packet.configurationProven()).isFalse();assertThat(packet.reservationId()).isEqualTo(prior.reservation());
        assertThat(seed.sql("SELECT state,configuration_resolved,unknown_or_mismatch_open,early_observation_complete,regression_open,released_at,release_reason FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",prior.reservation()).query().singleRow()).containsEntry("state","ACTIVE")
                .containsEntry("configuration_resolved",false).containsEntry("unknown_or_mismatch_open",true)
                .containsEntry("early_observation_complete",false).containsEntry("regression_open",false)
                .containsEntry("released_at",null).containsEntry("release_reason",null);
        UUID blockingHolder=JdbcClient.create(application).sql("SELECT reservation_id FROM ops.ad_overlapping_reservation(:org,ARRAY[:variant]::uuid[],:other)")
                .param("org",graph.id("organization")).param("variant",graph.id("productVariant")).param("other",UUID.randomUUID())
                .query(UUID.class).single();
        assertThat(blockingHolder).isEqualTo(prior.reservation());assertThat(manualUnresolvedExposure()).isEqualTo(1);
        assertThat(verificationBytes(prior.proof())).isEqualTo(oldProof);
        assertThat(manualOutcomeHistoryBytes(prior.packet())).isEqualTo(outcomesBefore);assertNoApiCommand();
    }

    @Test void aLaterCanonicalMismatchCannotStealANewerOverlappingManualReservationOrDiscardTheFact() throws Exception {
        ReleasedManual prior=actualEarlySafetyRelease();
        UUID policy=packets.packet(prior.packet()).orElseThrow().manualPolicyId();
        // A fresh proposal/Planner/Owner lifecycle acquires the next reservation after actual release.
        // Reuse the same immutable Owner policy; do not publish a conflicting replacement policy.
        UUID next=selectedWithPolicy("AD_BID_CHANGE",policy);decide(next,"verifierUser",false);decide(next,"ownerUser",true);start(next);
        UUID held=packets.packet(next).orElseThrow().reservationId();assertThat(held).isNotEqualTo(prior.reservation());
        assertThat(seed.sql("SELECT state FROM ops.ad_action_reservation WHERE id=:id").param("id",held).query(String.class).single()).isEqualTo("ACTIVE");
        String originalReservationBytes=reservationBytes(prior.reservation());
        var originalRelease=seed.sql("SELECT state,released_at,release_reason FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",prior.reservation()).query().singleRow();
        var newHolder=seed.sql("SELECT id,organization_id,ad_native_object_id,affected_set_id,intervention_kind,intervention_reference_id,reserved_at FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",held).query().singleRow();
        String outcomesBefore=manualOutcomeHistoryBytes(prior.packet()),oldProof=verificationBytes(prior.proof());
        UUID actualConfiguration=configuration("19",rawProvenance);
        assertThat(seed.sql("SELECT observed_bid_amount FROM core.ad_object_configuration_observation WHERE id=:id")
                .param("id",actualConfiguration).query(BigDecimal.class).single()).isEqualByComparingTo("19");
        var priorPacket=packets.packet(prior.packet()).orElseThrow();
        assertThat(priorPacket.state()).isEqualTo("MANUAL_EXECUTION_UNCERTAIN");assertThat(priorPacket.currentProofId()).isNull();
        assertThat(priorPacket.configurationProven()).isFalse();assertThat(priorPacket.reservationId()).isEqualTo(prior.reservation());
        assertThat(seed.sql("SELECT state,released_at,release_reason FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",prior.reservation()).query().singleRow()).isEqualTo(originalRelease);
        assertThat(reservationBytes(prior.reservation())).isEqualTo(originalReservationBytes);
        assertThat(seed.sql("SELECT id,organization_id,ad_native_object_id,affected_set_id,intervention_kind,intervention_reference_id,reserved_at FROM ops.ad_action_reservation WHERE id=:id")
                .param("id",held).query().singleRow()).isEqualTo(newHolder);
        assertThat(seed.sql("SELECT state,unknown_or_mismatch_open FROM ops.ad_action_reservation WHERE id=:id").param("id",held).query().singleRow())
                .containsEntry("state","ACTIVE").containsEntry("unknown_or_mismatch_open",true);
        String digest=seed.sql("SELECT affected_set_digest FROM core.ad_affected_set WHERE id=:id").param("id",graph.id("affectedSet")).query(String.class).single();
        assertThat(seed.sql("SELECT containment_kind,scope_kind,cause_class,review_owner_user_id FROM ops.ad_containment WHERE organization_id=:org AND state='ACTIVE'")
                .param("org",graph.id("organization")).query().listOfRows()).anySatisfy(hold->assertThat(hold)
                .containsEntry("containment_kind","EMERGENCY_ENTITY_HOLD").containsEntry("scope_kind","AFFECTED_SET")
                .containsEntry("cause_class","EXECUTION_INTEGRITY").containsEntry("review_owner_user_id",graph.id("verifierUser")));
        String[] blocked=JdbcClient.create(application).sql("SELECT ops.ad_active_containment(:org,:object,:store,:platform,'ad-bid-change',:digest)")
                .param("org",graph.id("organization")).param("object",graph.id("object")).param("store",graph.id("store"))
                .param("platform",graph.platform()).param("digest",digest)
                .query((row,index)->(String[])row.getArray(1).getArray()).single();
        assertThat(blocked).contains("EMERGENCY_ENTITY_HOLD");
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_action_reservation WHERE organization_id=:org AND state='ACTIVE'")
                .param("org",graph.id("organization")).query(Integer.class).single()).isEqualTo(1);
        assertThat(verificationBytes(prior.proof())).isEqualTo(oldProof);
        assertThat(manualOutcomeHistoryBytes(prior.packet())).isEqualTo(outcomesBefore);
        assertThat(manualOutcomeHistoryBytes(next)).isEqualTo("[]");assertNoApiCommand();
    }

    private String manualOutcomeHistoryBytes(UUID packet) {
        return seed.sql("SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.id),'[]'::jsonb)::text FROM ops.ad_outcome_observation o WHERE o.manual_packet_id=:id")
                .param("id",packet).query(String.class).single();
    }

    private record ReleasedManual(UUID packet,UUID reservation,UUID baseline,UUID proof,UUID outcome) { }
    private ReleasedManual actualEarlySafetyRelease() throws Exception {
        java.time.Instant now=java.time.Instant.now();
        companyWindow(now.minusSeconds(61*86400L),now.plusSeconds(300),now.minusSeconds(1),"0");
        UUID packet=selected(true);decide(packet,"verifierUser",false);decide(packet,"ownerUser",true);start(packet);
        observation(packet,"executorUser","REPORT",null,null);
        independentObservation(packet,"verifierUser",completeDirectConsole(packet,"20",observedNow()));
        UUID baseline=seed.sql("SELECT outcome_baseline_id FROM ops.ad_manual_execution_packet WHERE id=:id").param("id",packet).query(UUID.class).single();
        var row=packets.packet(packet).orElseThrow();
        java.time.Instant landed=seed.sql("SELECT observed_at FROM ops.ad_manual_configuration_verification WHERE id=:id")
                .param("id",row.currentProofId()).query(java.sql.Timestamp.class).single().toInstant();
        String preservedLanding=verificationBytes(row.currentProofId());
        java.time.Instant from=landed.plusSeconds(1800),to=from.plusSeconds(24*3600),at=to.plusSeconds(60);
        assertThat(at).isAfter(landed.plusSeconds(3600));
        assertThat(outcomePlanning.observeManual(graph.id("organization"),packet,landed.plusSeconds(60))).isNotNull();
        assertThat(seed.sql("SELECT state FROM ops.ad_action_reservation WHERE id=:id").param("id",row.reservationId()).query(String.class).single()).isNotEqualTo("RELEASED");
        companyWindow(from,to,at,"1000");
        seed.sql("""
                INSERT INTO ledger.ad_object_fact(id,organization_id,provenance_id,ad_native_object_id,store_id,source_fact_key,
                  period_start,period_end,currency_code,spend_amount,clicks,report_window_complete,correction_window_open,source_time,recorded_at)
                VALUES(gen_random_uuid(),:org,:source,:object,:store,:key,:from,:to,'RUB',100,100,true,false,:at,:at)
                """).param("org",graph.id("organization")).param("source",rawProvenance).param("object",graph.id("object")).param("store",graph.id("store"))
                .param("key",java.util.UUID.randomUUID().toString()).param("from",java.sql.Timestamp.from(from)).param("to",java.sql.Timestamp.from(to)).param("at",java.sql.Timestamp.from(at)).update();
        UUID observation=outcomePlanning.observeManual(graph.id("organization"),packet,at);
        assertThat(observation).isNotNull();
        assertThat(seed.sql("SELECT input_snapshot->>'inferenceScope' FROM ops.ad_outcome_axes WHERE observation_id=:id")
                .param("id",observation).query(String.class).single()).isEqualTo("OPERATIONAL_ASSOCIATION_NOT_CAUSAL_INCREMENTALITY");
        assertThat(outcomes.forManualPacket(graph.id("organization"),packet,java.util.List.of(graph.id("store"))))
                .isNotEmpty().allSatisfy(value->assertThat(value.inferenceScope()).isEqualTo("OPERATIONAL_ASSOCIATION_NOT_CAUSAL_INCREMENTALITY"));

        assertThat(seed.sql("SELECT verdict FROM ops.ad_outcome_observation WHERE id=:id").param("id",observation).query(String.class).single()).isEqualTo("UNCHANGED");
        assertThat(seed.sql("SELECT state FROM ops.ad_action_reservation WHERE id=:id").param("id",row.reservationId()).query(String.class).single()).isEqualTo("RELEASED");
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_outcome_critical_guard WHERE observation_id=:id AND guard_state='PASS'").param("id",observation).query(Integer.class).single()).isEqualTo(1);
        assertThat(seed.sql("SELECT outcome_baseline_id FROM ops.ad_manual_execution_packet WHERE id=:id").param("id",packet).query(UUID.class).single()).isEqualTo(baseline);
        assertThat(verificationBytes(row.currentProofId())).isEqualTo(preservedLanding);
        assertThat(seed.sql("SELECT ops.ad_manual_observation_is_qualified(:id)").param("id",row.currentProofId()).query(Boolean.class).single()).isTrue();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_bid_command WHERE organization_id=:id").param("id",graph.id("organization")).query(Integer.class).single()).isZero();
        return new ReleasedManual(packet,row.reservationId(),baseline,row.currentProofId(),observation);
    }
    @Test void insufficientCompanyHistoryFreezesAnExplicitIncompletePlanAndCannotBeSelected() throws Exception {
        seed.sql("""
                INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,report_window_start,report_window_end,
                  completed_coverage,retained_coverage,return_coverage,qc_coverage,completed_source_updated_at,retained_source_updated_at,
                  return_source_updated_at,qc_source_updated_at,evidence_reference,accepted_at,correlation_id)
                VALUES(gen_random_uuid(),:org,:listing,now()-interval '61 days',now()+interval '1 hour',
                  'INCOMPLETE','COMPLETE','COMPLETE_ZERO','COMPLETE',now(),now(),now(),now(),'fixture://incomplete-history',clock_timestamp(),'incomplete-history')
                """).param("org",graph.id("organization")).param("listing",graph.id("listingVariant")).update();
        UUID policy=publishedPolicy(),proposal=UUID.randomUUID(),packet=UUID.randomUUID();
        try(var connection=application.getConnection()) {
            query(connection,"SELECT ops.generate_ad_manual_proposal(?,?,?,?)",proposal,graph.id("caseId"),policy,graph.id("candidate"));
        }
        UUID baseline=outcomePlanning.prepareManual(graph.id("organization"),proposal,java.time.Instant.now());
        assertThat(baseline).isNotNull();
        assertThat(seed.sql("SELECT state FROM ops.ad_outcome_baseline WHERE id=:id").param("id",baseline).query(String.class).single()).isEqualTo("INCOMPLETE");
        assertThat(seed.sql("SELECT 'OUTCOME_BASELINE_INSUFFICIENT'=ANY(blocker_codes) FROM ops.ad_outcome_baseline WHERE id=:id").param("id",baseline).query(Boolean.class).single()).isTrue();
        assertThat(seed.sql("SELECT ops.ad_outcome_baseline_is_attested(:id)").param("id",baseline).query(Boolean.class).single()).isTrue();
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("executorUser"),"MANUAL_PACKET_SELECT",proposal,packet);
            assertThatThrownBy(()->query(connection,"SELECT ops.select_ad_manual_packet(?,?,?,'cannot invent history',?)",packet,proposal,baseline,proof)).isInstanceOf(SQLException.class);
            connection.rollback();
        }
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_manual_execution_packet WHERE organization_id=:org").param("org",graph.id("organization")).query(Integer.class).single()).isZero();
        assertThat(seed.sql("SELECT count(*) FROM ops.ad_case_responsibility WHERE case_id=:id").param("id",graph.id("caseId")).query(Integer.class).single()).isEqualTo(1);
    }
    @Test void applicationCannotInsertOrSelfAttestACanonicalLookingBaseline() throws Exception {
        UUID packet=selected();
        UUID baseline=baselineForPacket(packet);
        try(var connection=application.getConnection()) {
            assertThatThrownBy(()->query(connection,"INSERT INTO ops.ad_outcome_baseline SELECT * FROM ops.ad_outcome_baseline WHERE id=?",baseline))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(()->query(connection,"INSERT INTO ops.ad_outcome_baseline_attestation SELECT ?,?,repeat('a',64),now(),'CANONICAL_OUTCOME_PLANNER_V1'",UUID.randomUUID(),graph.id("organization")))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(()->query(connection,"SELECT ops.issue_ad_outcome_plan_grant(repeat('a',64),?,?,repeat('a',64),pg_backend_pid(),txid_current())",UUID.randomUUID(),graph.id("organization")))
                .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
        assertThat(seed.sql("SELECT ops.ad_outcome_baseline_is_canonical(:id,clock_timestamp())").param("id",baseline).query(Boolean.class).single()).isTrue();
    }
    @Test void aPlannerProofRejectsChangedValuesAnotherTransactionAndReplay() throws Exception {
        UUID packet=selected();
        UUID original=baselineForPacket(packet);
        var payload=copyPlan(original);
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String proof=attestPlan(connection,payload,0);
            var changed=(tools.jackson.databind.node.ArrayNode)json.readTree(payload[1]);
            ((tools.jackson.databind.node.ObjectNode)changed.get(0).path("snapshot").path("companySales")).put("value",999999);
            var checkpoint=connection.setSavepoint();
            assertThatThrownBy(()->query(connection,"SELECT ops.freeze_ad_outcome_baseline(?::jsonb,?::jsonb,?::jsonb,?)",payload[0],changed.toString(),payload[2],proof))
                .isInstanceOf(SQLException.class).hasMessageContaining("exact canonical planner proof required");
            connection.rollback(checkpoint);
            String wrongBackend=attestPlan(connection,payload,1);
            checkpoint=connection.setSavepoint();
            assertThatThrownBy(()->query(connection,"SELECT ops.freeze_ad_outcome_baseline(?::jsonb,?::jsonb,?::jsonb,?)",payload[0],payload[1],payload[2],wrongBackend))
                .isInstanceOf(SQLException.class).hasMessageContaining("exact canonical planner proof required");
            connection.rollback(checkpoint);
            String wrongTransaction=attestPlan(connection,payload,2);
            checkpoint=connection.setSavepoint();
            assertThatThrownBy(()->query(connection,"SELECT ops.freeze_ad_outcome_baseline(?::jsonb,?::jsonb,?::jsonb,?)",payload[0],payload[1],payload[2],wrongTransaction))
                .isInstanceOf(SQLException.class).hasMessageContaining("exact canonical planner proof required");
            connection.rollback(checkpoint);
            query(connection,"SELECT ops.freeze_ad_outcome_baseline(?::jsonb,?::jsonb,?::jsonb,?)",payload[0],payload[1],payload[2],proof);
            checkpoint=connection.setSavepoint();
            assertThatThrownBy(()->query(connection,"SELECT ops.freeze_ad_outcome_baseline(?::jsonb,?::jsonb,?::jsonb,?)",payload[0],payload[1],payload[2],proof))
                .isInstanceOf(SQLException.class).hasMessageContaining("exact canonical planner proof required");
            connection.rollback(checkpoint);connection.rollback();
        }
    }
    @Test void evenAnAttestedPayloadCannotOmitAStageOrChangeItsOwnerPolicy() throws Exception {
        UUID packet=selected();var payload=copyPlan(baselineForPacket(packet));
        var stages=(tools.jackson.databind.node.ArrayNode)json.readTree(payload[1]);stages.remove(2);payload[1]=stages.toString();
        try(var connection=application.getConnection()) {
            connection.setAutoCommit(false);String proof=attestPlan(connection,payload,0);
            assertThatThrownBy(()->query(connection,"SELECT ops.freeze_ad_outcome_baseline(?::jsonb,?::jsonb,?::jsonb,?)",payload[0],payload[1],payload[2],proof))
                .isInstanceOf(SQLException.class).hasMessageContaining("canonical baseline shape");
            connection.rollback();
        }
        UUID baseline=baselineForPacket(packet);
        seed.sql("UPDATE core.ad_outcome_policy SET material_profit_delta=material_profit_delta+1 WHERE id=:id").param("id",graph.id("outcome")).update();
        assertThat(seed.sql("SELECT ops.ad_outcome_baseline_is_canonical(:id,clock_timestamp())").param("id",baseline).query(Boolean.class).single()).isFalse();
    }
    private UUID baselineForPacket(UUID packet) {
        return seed.sql("SELECT outcome_baseline_id FROM ops.ad_manual_execution_packet WHERE id=:id").param("id",packet).query(UUID.class).single();
    }
    private String[] copyPlan(UUID original) {
        UUID copy=UUID.randomUUID();
        var baseline=(tools.jackson.databind.node.ObjectNode)json.readTree(seed.sql("SELECT to_jsonb(b)::text FROM ops.ad_outcome_baseline b WHERE id=:id").param("id",original).query(String.class).single());
        baseline.put("id",copy.toString());baseline.put("input_digest",com.mimococo.marketops.shared.Digest.ofText(copy.toString()));
        var stages=(tools.jackson.databind.node.ArrayNode)json.readTree(seed.sql("SELECT jsonb_agg(to_jsonb(s) ORDER BY stage)::text FROM ops.ad_outcome_stage_baseline s WHERE outcome_baseline_id=:id").param("id",original).query(String.class).single());
        var units=(tools.jackson.databind.node.ArrayNode)json.readTree(seed.sql("SELECT coalesce(jsonb_agg(to_jsonb(u)),'[]')::text FROM ops.ad_outcome_critical_unit u WHERE outcome_baseline_id=:id").param("id",original).query(String.class).single());
        stages.forEach(row->((tools.jackson.databind.node.ObjectNode)row).put("outcome_baseline_id",copy.toString()));
        units.forEach(row->((tools.jackson.databind.node.ObjectNode)row).put("outcome_baseline_id",copy.toString()));
        return new String[]{baseline.toString(),stages.toString(),units.toString()};
    }
    private String attestPlan(Connection applicationConnection,String[] payload,int backendOffset) throws Exception {
        String proof=UUID.randomUUID().toString();String digest;int backend;long transaction;
        try(var query=applicationConnection.prepareStatement("SELECT ops.ad_outcome_payload_digest(?::jsonb,?::jsonb,?::jsonb),pg_backend_pid(),txid_current()")) {
            for(int i=0;i<3;i++) query.setString(i+1,payload[i]);
            try(var result=query.executeQuery()) {result.next();digest=result.getString(1);backend=result.getInt(2);transaction=result.getLong(3);}
        }
        try(var issuer=admin.getConnection();var role=issuer.createStatement()) {
            role.execute("SET ROLE marketops_identity_issuer");
            query(issuer,"SELECT ops.issue_ad_outcome_plan_grant(?,?,?,?,?,?)",com.mimococo.marketops.shared.Digest.ofText(proof),
                UUID.fromString(json.readTree(payload[0]).path("id").asText()),graph.id("organization"),digest,backend+(backendOffset==1?1:0),transaction+(backendOffset==2?1:0));
        }
        return proof;
    }
    private void seedOutcomeAuthority() {
        seed.sql("""
                UPDATE core.ad_outcome_policy SET completed_sales_guard_hours=24,critical_unit_definition_complete=true,
                  material_profit_delta=10,material_profit_per_rub_delta=0.1,sales_preservation_tolerance_ratio=0.05,
                  non_worsening_profit_band=0,non_worsening_per_rub_band=0,minimum_ad_spend_denominator=1,
                  comparison_scale=4,comparison_rounding_mode='HALF_UP',material_boundary_inclusive=true,negative_profit_terminal='KEEP_PROTECTION_OPEN'
                WHERE id=:id
                """).param("id",graph.id("outcome")).update();
        seed.sql("""
                INSERT INTO core.ad_outcome_critical_unit_rule(id,organization_id,outcome_policy_id,product_variant_id,store_id,reason,evidence_reference)
                VALUES(gen_random_uuid(),:org,:policy,:product,:store,'synthetic Owner required unit','fixture://manual-critical-unit')
                """).param("org",graph.id("organization")).param("policy",graph.id("outcome")).param("product",graph.id("productVariant")).param("store",graph.id("store")).update();
        String[] kinds={"COMPANY_COMPLETED_SALE","COMPANY_RETAINED_SALE","SETTLEMENT"};
        String[] purposes={"EARLY_COMPLETED_SALES_OUTCOME","FINAL_RETAINED_SALES_OUTCOME","SETTLED_FINANCIAL_OUTCOME"};
        for(int i=0;i<3;i++) if(!seed.sql("SELECT EXISTS(SELECT 1 FROM core.ad_freshness_profile WHERE organization_id=:org AND decision_purpose=:purpose AND evidence_kind=:kind AND scope_kind='ORGANIZATION' AND status='ACTIVE')")
                .param("org",graph.id("organization")).param("purpose",purposes[i]).param("kind",kinds[i]).query(Boolean.class).single()) seed.sql("""
                INSERT INTO core.ad_freshness_profile(id,organization_id,profile_version,evidence_kind,decision_purpose,scope_kind,
                  source_max_age_minutes,accepted_fact_max_age_minutes,expected_publication_lag_minutes,correction_window_minutes,
                  requires_window_complete,requires_correction_window_closed,minimum_coverage_ratio,minimum_confidence_state,provider_incident_blocks,
                  owner_user_id,reason,evidence_reference,effective_from,status,created_at)
                VALUES(gen_random_uuid(),:org,1,:kind,:purpose,'ORGANIZATION',1440,1440,0,0,true,true,1,'CANONICAL_CONFIRMED',true,
                  :owner,'synthetic Owner outcome freshness','fixture://manual-freshness',now()-interval '1 day','ACTIVE',now())
                """).param("org",graph.id("organization")).param("kind",kinds[i]).param("purpose",purposes[i]).param("owner",graph.id("ownerUser")).update();
    }
    private void companyWindow(java.time.Instant from,java.time.Instant to,java.time.Instant accepted,String amount) {
        UUID source=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.fact_provenance(id,organization_id,source_kind,source_time,ingestion_time,recorded_by_user_id,evidence_note)
                VALUES(:id,:org,'MANUAL_ENTRY',:at,:at,:owner,'synthetic canonical company report')
                """).param("id",source).param("org",graph.id("organization")).param("at",java.sql.Timestamp.from(accepted)).param("owner",graph.id("ownerUser")).update();
        seed.sql("""
                INSERT INTO ledger.sales_fact(id,organization_id,provenance_id,platform_listing_variant_id,store_id,sale_stage,source_fact_key,
                    native_order_key,occurred_at,quantity,currency_code,gross_amount,net_amount)
                VALUES(gen_random_uuid(),:org,:source,:listing,:store,'COMPLETED',:key,:key,:occurred,10,'RUB',:amount,:amount)
                """).param("org",graph.id("organization")).param("source",source).param("listing",graph.id("listingVariant")).param("store",graph.id("store"))
                .param("key",UUID.randomUUID().toString()).param("occurred",java.sql.Timestamp.from(accepted.isAfter(to)?from:accepted.minusSeconds(60)))
                .param("amount",new BigDecimal(amount)).update();
        seed.sql("""
                INSERT INTO ledger.return_quality_evidence_snapshot(id,organization_id,platform_listing_variant_id,report_window_start,report_window_end,
                  completed_coverage,retained_coverage,return_coverage,qc_coverage,completed_source_updated_at,retained_source_updated_at,
                  return_source_updated_at,qc_source_updated_at,evidence_reference,accepted_at,correlation_id)
                VALUES(gen_random_uuid(),:org,:listing,:from,:to,'COMPLETE','COMPLETE','COMPLETE_ZERO','COMPLETE',:at,:at,:at,:at,
                  'fixture://actual-manual-company-window',:at,'manual-outcome')
                """).param("org",graph.id("organization")).param("listing",graph.id("listingVariant")).param("from",java.sql.Timestamp.from(from))
                .param("to",java.sql.Timestamp.from(to)).param("at",java.sql.Timestamp.from(accepted)).update();
    }

    private UUID selected() throws Exception { return selected(true); }
    private UUID publishedPolicy() throws Exception { return publishedPolicy("AD_BID_CHANGE"); }
    private UUID publishedPolicy(String action) throws Exception {
        UUID policy=UUID.randomUUID();
        var content=json.createObjectNode().put("id",policy.toString()).put("organization_id",graph.id("organization").toString())
                .put("store_id",graph.id("store").toString()).put("semantic_profile_id",graph.id("profile").toString())
                .put("outcome_policy_id",graph.id("outcome").toString()).put("policy_version",1).put("cause_code","PROVEN_ADVERTISING_LOSS").put("action_kind",action).put("currency_code","RUB").put("verification_mode","INDEPENDENT_OR_OFFICIAL")
                .put("configuration_max_age_seconds",3600).put("packet_lease_seconds",1800)
                .put("effective_from",java.time.Instant.now().minusSeconds(60).toString()).put("effective_to",java.time.Instant.now().plusSeconds(3600).toString())
                .put("evidence_reference","fixture://owner-human-plan");
        if(action.equals("AD_BID_CHANGE")) content.put("candidate_basis","MAX_CPC_BOUNDED");
        else if(action.equals("AD_BUDGET_CHANGE")) content.put("target_budget",50);
        else content.put("target_status","native-paused");
        try(Connection connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("ownerUser"),"MANUAL_POLICY_PUBLISH",policy,graph.id("store"));
            query(connection,"SELECT ops.publish_ad_manual_policy(?::jsonb,?)",content.toString(),proof); connection.commit();
        }
        return policy;
    }
    private UUID selected(boolean actualPlanner) throws Exception { return selected(actualPlanner,"AD_BID_CHANGE"); }
    private UUID selected(boolean actualPlanner,String action) throws Exception {
        return selectedWithPolicy(action,publishedPolicy(action));
    }
    private UUID selectedWithPolicy(String action,UUID policy) throws Exception {
        UUID proposal=UUID.randomUUID(),packet=UUID.randomUUID(),baseline=UUID.randomUUID();
        try(Connection connection=application.getConnection()) {
            connection.setAutoCommit(false);
            query(connection,"SELECT ops.generate_ad_manual_proposal(?,?,?,?)",proposal,graph.id("caseId"),policy,action.equals("AD_BID_CHANGE")?graph.id("candidate"):null);
            connection.commit();
            baseline=outcomePlanning.prepareManual(graph.id("organization"),proposal,java.time.Instant.now());
            assertThat(baseline).isNotNull();
            assertThat(seed.sql("SELECT state FROM ops.ad_outcome_baseline WHERE id=:id").param("id",baseline).query(String.class).single()).isEqualTo("COMPLETE");
            assertThat(outcomePlanning.prepareManual(graph.id("organization"),proposal,java.time.Instant.now())).isEqualTo(baseline);
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("executorUser"),"MANUAL_PACKET_SELECT",proposal,packet);
            query(connection,"SELECT ops.select_ad_manual_packet(?,?,?,'exact generated proposal',?)",packet,proposal,baseline,proof); connection.commit();
        }
        return packet;
    }
    private void decide(UUID packet,String actor,boolean approve) throws Exception {
        try(Connection connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id(actor),approve?"MANUAL_PACKET_APPROVE":"MANUAL_PACKET_ENDORSE",packet,packet);
            query(connection,"SELECT ops.decide_ad_manual_packet(?,?,?,?)",packet,packets.packet(packet).orElseThrow().version(),approve,proof); connection.commit();
        }
    }
    private void start(UUID packet) throws Exception {
        try(Connection connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id("executorUser"),"MANUAL_EXECUTION_START",packet,packet);
            query(connection,"SELECT ops.start_ad_manual_execution(?,?,?)",packet,packets.packet(packet).orElseThrow().version(),proof); connection.commit();
        }
    }
    private UUID observation(UUID packet,String actor,String kind,String value,UUID configuration) throws Exception {
        UUID id=UUID.randomUUID();
        try(Connection connection=application.getConnection()) {
            connection.setAutoCommit(false);
            String proof=AdvertisingR1Fixture.proof(admin,connection,graph,graph.id(actor),kind.equals("REPORT")?"MANUAL_EXECUTION_REPORT":"MANUAL_INDEPENDENT_VERIFY",packet,packet);
            query(connection,"SELECT ops.record_ad_manual_observation(?,?,?,?,?,?,?)",id,packet,packets.packet(packet).orElseThrow().version(),kind,value,configuration,proof); connection.commit();
        }
        return id;
    }
    private static void query(Connection connection,String sql,Object...args) throws SQLException {
        try(var query=connection.prepareStatement(sql)) {for(int index=0;index<args.length;index++) query.setObject(index+1,args[index]);query.execute();}
    }
    private void role(String user,String role) {
        seed.sql("INSERT INTO iam.user_role_assignment(id,organization_id,user_id,role_code,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),:org,:user,:role,'ACTIVE',now()-interval '1 hour','synthetic role',now(),now()) ON CONFLICT DO NOTHING")
                .param("org",graph.id("organization")).param("user",graph.id(user)).param("role",role).update();
    }
    private void scope(String user,String action) {
        seed.sql("INSERT INTO iam.user_scope_grant(id,organization_id,user_id,action_code,organization_ref_id,status,effective_from,reason,created_at,updated_at) VALUES(gen_random_uuid(),:org,:user,:action,:org,'ACTIVE',now()-interval '1 hour','synthetic scope',now(),now()) ON CONFLICT DO NOTHING")
                .param("org",graph.id("organization")).param("user",graph.id(user)).param("action",action).update();
    }
    private UUID configuration(String amount,UUID provenance) { return configuration(amount,provenance,null); }
    private UUID configuration(String amount,UUID provenance,BigDecimal budget) {
        UUID id=UUID.randomUUID();
        seed.sql("""
                INSERT INTO core.ad_object_configuration_observation(id,organization_id,ad_native_object_id,provenance_id,
                    semantic_profile_id,lineage_generation,observed_bid_amount,bid_currency_code,bid_unit_code,observed_status,
                    native_status_raw,observed_bidding_mode,evidence_grade,observed_at,source_time,created_at,observed_budget_amount)
                VALUES(:id,:org,:object,:provenance,:profile,1,:amount,:currency,'CURRENCY_MAJOR','RUNNING','native-running','MANUAL_BID',
                    'OFFICIAL_API_READBACK',clock_timestamp(),clock_timestamp(),clock_timestamp(),:budget)
                """).param("id",id).param("org",graph.id("organization")).param("object",graph.id("object"))
                .param("provenance",provenance).param("profile",graph.id("profile")).param("amount",amount==null?null:new BigDecimal(amount)).param("currency",amount==null?null:"RUB").param("budget",budget).update();
        return id;
    }
    private UUID rawConfigurationProvenance() {
        UUID service=UUID.randomUUID(),endpoint=UUID.randomUUID(),job=UUID.randomUUID(),run=UUID.randomUUID();
        UUID unit=UUID.randomUUID(),observation=UUID.randomUUID(),provenance=UUID.randomUUID();
        byte[] bytes=("{\"fixtureNativeObject\":\""+graph.id("object")+"\",\"bid\":30}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID content=custody.store("manual-fixture",bytes).contentId();
        assertThat(custody.readById(content).orElseThrow()).containsExactly(bytes);
        seed.sql("INSERT INTO iam.service_account(id,organization_id,code,display_name,purpose,owner_label,status,expires_at,created_at,updated_at) VALUES(:id,:org,:code,'Stored synthetic configuration','INGESTION','fixture','ACTIVE',now()+interval '1 day',now(),now())")
                .param("id",service).param("org",graph.id("organization")).param("code","manual-"+service).update();
        seed.sql("INSERT INTO platform.platform_endpoint(id,platform_code,endpoint_code,api_version,read_write_class,pagination_model,idempotency_support,verification_state,owner_label,contract_test_status,status,created_at,updated_at) VALUES(:id,:platform,'manual.config','v1','READ','NONE','UNKNOWN','UNVERIFIED','fixture','NOT_IMPLEMENTED','ACTIVE',now(),now())")
                .param("id",endpoint).param("platform",graph.platform()).update();
        seed.sql("INSERT INTO platform.ingestion_job(id,organization_id,marketplace_account_id,platform_code,service_account_id,endpoint_id,job_code,display_name,status,created_at,updated_at) VALUES(:id,:org,:account,:platform,:service,:endpoint,:code,'Synthetic stored configuration','PAUSED',now(),now())")
                .param("id",job).param("org",graph.id("organization")).param("account",graph.id("account"))
                .param("platform",graph.platform()).param("service",service).param("endpoint",endpoint).param("code","manual-"+job).update();
        seed.sql("INSERT INTO ops.ingestion_run(id,job_id,state,fence_token,attempt_no,last_call_seq,created_at,updated_at) VALUES(:id,:job,'SUCCEEDED',1,1,1,now(),now())").param("id",run).param("job",job).update();
        seed.sql("INSERT INTO raw.raw_logical_unit(id,job_id,marketplace_account_id,unit_kind,source_unit_key,source_time) VALUES(:id,:job,:account,'AD_CONFIGURATION',:key,now())")
                .param("id",unit).param("job",job).param("account",graph.id("account")).param("key",unit.toString()).update();
        seed.sql("INSERT INTO raw.raw_acquisition_observation(id,run_id,logical_unit_id,content_id,call_seq,native_status,outcome_class,pagination_outcome) VALUES(:id,:run,:unit,:content,1,'fixture-success','SUCCESS_BYTES','END')")
                .param("id",observation).param("run",run).param("unit",unit).param("content",content).update();
        seed.sql("INSERT INTO core.fact_provenance(id,organization_id,source_kind,raw_observation_id,source_time,ingestion_time,evidence_note) VALUES(:id,:org,'MARKETPLACE_RAW',:observation,now(),now(),'Isolated synthetic raw configuration oracle')")
                .param("id",provenance).param("org",graph.id("organization")).param("observation",observation).update();
        return provenance;
    }
    @org.springframework.boot.test.context.TestConfiguration
    static class Storage {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort objects() {
            return new com.mimococo.marketops.marketplaceintegration.port.InMemoryObjectStoragePort();
        }
    }

}
