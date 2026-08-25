package com.mimococo.marketops.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequestRebinderFixture;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ControllerViaExecutorFixture;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.SecondExecutorCallerFixture;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.SyntheticResultSetGrantCallerFixture;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Applies the acquisition-authority rules to production classes, and proves
 * each rule can fail for its intended reason.
 *
 * <p>The rules keep the outbound doorway singular: the owning module implements
 * the ports, the sole executor invokes acquisition and creates its request, the
 * trusted JDBC mapper constructs grants, and no web controller directly or transitively reaches
 * an authority surface.
 * Every production check is paired with a deliberately invalid fixture, because
 * a boundary rule that has never rejected anything proves only that it compiled.
 */
class IngestionAuthorityArchitectureTest {

    private static final String PRODUCTION_PACKAGE = "com.mimococo.marketops";
    private static final String FIXTURES = "com.mimococo.marketops.testfixture";
    private static final String VIOLATION = FIXTURES + ".violation";
    private static final String CONFORMING = FIXTURES + ".conforming.ingestionauthority";
    private static final String CONFORMING_QUERY =
            FIXTURES + ".conforming.ingestionauthorityquery";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PRODUCTION_PACKAGE);
    }

    @Test
    @DisplayName("TC-ARCH-020 acquisition ports are implemented only by marketplaceintegration")
    void portsAreImplementedOnlyByTheOwningModule() {
        IngestionAuthorityRules
                .acquisitionPortsAreImplementedOnlyByTheOwningModule(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-021 only AuthorizedAcquisitionExecutor calls AcquisitionPort.acquire")
    void acquisitionIsCalledOnlyByTheAuthorizedExecutor() {
        IngestionAuthorityRules
                .acquisitionPortAcquireIsCalledOnlyByAuthorizedExecutor(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-022 no web controller reaches an acquisition port")
    void noControllerReachesAcquisition() {
        IngestionAuthorityRules
                .webControllersDoNotReachAcquisition(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-023 only CallAuthorityGrantMapper constructs grants")
    void callAuthorityGrantConstructionIsMappedFromDatabaseResults() {
        IngestionAuthorityRules
                .callAuthorityGrantsAreConstructedOnlyByTrustedMapper(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-024 only AuthorizedAcquisitionExecutor creates requests")
    void requestsAreCreatedOnlyByTheAuthorizedExecutor() {
        IngestionAuthorityRules
                .acquisitionRequestsAreCreatedOnlyByTheAuthorizedExecutor(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-026 only JdbcAuthorizedAcquisitionGateway calls the mapper")
    void mapperIsCalledOnlyByTheExactGateway() {
        IngestionAuthorityRules.grantMapperIsCalledOnlyByGateway(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-027 only JdbcAuthorizedAcquisitionGateway calls the executor")
    void executorIsCalledOnlyByTheExactGateway() {
        IngestionAuthorityRules.authorizedExecutorIsCalledOnlyByGateway(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-028 no RestController reaches any authority-chain type")
    void controllersAreExcludedFromTheCompleteAuthorityChain() {
        IngestionAuthorityRules.webControllersDoNotReachAcquisition(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-029 grant, mapper and executor remain gateway-internal collaborators")
    void internalAuthorityTypesDoNotEscape() {
        IngestionAuthorityRules
                .internalAuthorityTypesDoNotEscapeTheirCollaborators(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-030 no RestController transitively reaches acquisition authority")
    void controllersAreTransitivelyExcludedFromTheAuthorityChain() {
        IngestionAuthorityRules
                .webControllersDoNotTransitivelyReachAcquisition(PRODUCTION_PACKAGE)
                .check(production);
    }

    /**
     * Each rule rejects arrangements built to violate it and accepts the
     * conforming arrangement, so a rule weakened by a later edit fails here
     * before it silently stops protecting production.
     */
    @Nested
    @DisplayName("rule sensitivity")
    class RuleSensitivity {

        @Test
        @DisplayName("F-ARCH-020 a second implementing module is rejected")
        void secondAuthorityIsRejected() {
            assertRejects(
                    IngestionAuthorityRules::acquisitionPortsAreImplementedOnlyByTheOwningModule,
                    VIOLATION + ".secondauthority",
                    "ReportingAcquisitionAdapter");
        }

        @Test
        @DisplayName("F-ARCH-021 an outside caller of the port is rejected")
        void outsideCallerIsRejected() {
            assertRejects(
                    IngestionAuthorityRules::acquisitionPortAcquireIsCalledOnlyByAuthorizedExecutor,
                    VIOLATION + ".acquisitioncaller",
                    "ReportRefresher");
        }

        @Test
        @DisplayName("F-ARCH-021I an owning-module bypass caller is rejected")
        void insideCallerIsRejected() {
            assertRejects(
                    IngestionAuthorityRules::acquisitionPortAcquireIsCalledOnlyByAuthorizedExecutor,
                    VIOLATION + ".insideacquisition",
                    "BypassAcquisitionCaller");
        }

        @Test
        @DisplayName("F-ARCH-022 a controller holding the port is rejected")
        void controllerHoldingThePortIsRejected() {
            assertRejects(
                    IngestionAuthorityRules::webControllersDoNotReachAcquisition,
                    VIOLATION + ".acquisitionweb",
                    "AcquisitionTriggerController");
        }

        @Test
        @DisplayName("F-ARCH-023 an outside grant constructor/rebinder is rejected")
        void outsideGrantRebinderIsRejected() {
            assertRejects(
                    IngestionAuthorityRules::callAuthorityGrantsAreConstructedOnlyByTrustedMapper,
                    VIOLATION + ".grantrebind",
                    "GrantRebinder");
        }

        @Test
        @DisplayName("F-ARCH-023I an owning-module grant rebinder is rejected")
        void insideGrantRebinderIsRejected() {
            assertRejects(
                    IngestionAuthorityRules::callAuthorityGrantsAreConstructedOnlyByTrustedMapper,
                    VIOLATION + ".insidegrant",
                    "UntrustedGrantRebinder");
        }

        @Test
        @DisplayName("F-ARCH-024 a second AcquisitionRequest.from caller is rejected")
        void requestRebinderIsRejected() {
            JavaClasses fixture = new ClassFileImporter()
                    .importClasses(AcquisitionRequestRebinderFixture.class);
            EvaluationResult result = IngestionAuthorityRules
                    .acquisitionRequestsAreCreatedOnlyByTheAuthorizedExecutor(PRODUCTION_PACKAGE)
                    .evaluate(fixture);

            assertThat(result.hasViolation()).isTrue();
            assertThat(result.getFailureReport().getDetails())
                    .anySatisfy(detail -> assertThat(detail)
                            .contains("AcquisitionRequestRebinderFixture"));
        }

        @Test
        @DisplayName("F-ARCH-026 an inside-owner synthetic ResultSet mapper caller is rejected")
        void syntheticResultSetMapperCallerIsRejected() {
            JavaClasses fixture = new ClassFileImporter()
                    .importClasses(SyntheticResultSetGrantCallerFixture.class);
            EvaluationResult mapperResult = IngestionAuthorityRules
                    .grantMapperIsCalledOnlyByGateway(PRODUCTION_PACKAGE)
                    .evaluate(fixture);
            EvaluationResult executorResult = IngestionAuthorityRules
                    .authorizedExecutorIsCalledOnlyByGateway(PRODUCTION_PACKAGE)
                    .evaluate(fixture);

            assertThat(mapperResult.hasViolation()).isTrue();
            assertThat(executorResult.hasViolation()).isTrue();
            assertThat(mapperResult.getFailureReport().getDetails())
                    .anySatisfy(detail -> assertThat(detail)
                            .contains("SyntheticResultSetGrantCallerFixture"));
            assertThat(executorResult.getFailureReport().getDetails())
                    .anySatisfy(detail -> assertThat(detail)
                            .contains("SyntheticResultSetGrantCallerFixture"));
        }

        @Test
        @DisplayName("F-ARCH-027 a controller depending on the executor is rejected")
        void controllerViaExecutorIsRejected() {
            EvaluationResult result = IngestionAuthorityRules
                    .webControllersDoNotReachAcquisition(PRODUCTION_PACKAGE)
                    .evaluate(new ClassFileImporter()
                            .importClasses(ControllerViaExecutorFixture.class));

            assertThat(result.hasViolation()).isTrue();
            assertThat(result.getFailureReport().getDetails())
                    .anySatisfy(detail -> assertThat(detail)
                            .contains("ControllerViaExecutorFixture"));
        }

        @Test
        @DisplayName("F-ARCH-028 a second internal executor caller is rejected")
        void secondInternalExecutorCallerIsRejected() {
            EvaluationResult result = IngestionAuthorityRules
                    .authorizedExecutorIsCalledOnlyByGateway(PRODUCTION_PACKAGE)
                    .evaluate(new ClassFileImporter()
                            .importClasses(SecondExecutorCallerFixture.class));

            assertThat(result.hasViolation()).isTrue();
            assertThat(result.getFailureReport().getDetails())
                    .anySatisfy(detail -> assertThat(detail)
                            .contains("SecondExecutorCallerFixture"));
        }

        @Test
        @DisplayName("F-ARCH-030 a controller-to-service-to-gateway path is rejected")
        void transitiveControllerPathIsRejectedWithTheCompletePath() {
            String fixturePackage = VIOLATION + ".transitiveacquisitionweb";
            EvaluationResult result = IngestionAuthorityRules
                    .webControllersDoNotTransitivelyReachAcquisition(fixturePackage)
                    .evaluate(importFixture(fixturePackage));

            assertThat(result.hasViolation()).isTrue();
            assertThat(result.getFailureReport().getDetails())
                    .anySatisfy(detail -> assertThat(detail)
                            .contains(
                                    "TransitiveAcquisitionController",
                                    "AcquisitionBridgeService",
                                    "JdbcAuthorizedAcquisitionGateway",
                                    " -> "));
        }

        @Test
        @DisplayName("F-ARCH-031 a controller-to-query-service path is permitted")
        void ordinaryQueryControllerPathIsPermitted() {
            JavaClasses queryPath = importFixture(CONFORMING_QUERY);

            assertThatCode(() -> IngestionAuthorityRules
                    .webControllersDoNotTransitivelyReachAcquisition(CONFORMING_QUERY)
                    .check(queryPath))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("F-ARCH-025 the conforming arrangement passes all authority rules")
        void conformingArrangementPasses() {
            JavaClasses conforming = importFixture(CONFORMING);
            assertThatCode(() -> {
                IngestionAuthorityRules
                        .acquisitionPortsAreImplementedOnlyByTheOwningModule(CONFORMING)
                        .check(conforming);
                IngestionAuthorityRules
                        .acquisitionPortAcquireIsCalledOnlyByAuthorizedExecutor(CONFORMING)
                        .check(conforming);
                IngestionAuthorityRules
                        .webControllersDoNotReachAcquisition(CONFORMING)
                        .check(conforming);
                IngestionAuthorityRules
                        .webControllersDoNotTransitivelyReachAcquisition(CONFORMING)
                        .check(conforming);
                IngestionAuthorityRules
                        .callAuthorityGrantsAreConstructedOnlyByTrustedMapper(CONFORMING)
                        .check(conforming);
                IngestionAuthorityRules
                        .acquisitionRequestsAreCreatedOnlyByTheAuthorizedExecutor(CONFORMING)
                        .check(conforming);
                IngestionAuthorityRules.grantMapperIsCalledOnlyByGateway(CONFORMING)
                        .check(conforming);
                IngestionAuthorityRules.authorizedExecutorIsCalledOnlyByGateway(CONFORMING)
                        .check(conforming);
                IngestionAuthorityRules
                        .internalAuthorityTypesDoNotEscapeTheirCollaborators(CONFORMING)
                        .check(conforming);
            }).doesNotThrowAnyException();
        }

        private void assertRejects(
                Function<String, ArchRule> ruleFactory,
                String fixturePackage,
                String... offendingClasses) {
            EvaluationResult result = ruleFactory.apply(fixturePackage)
                    .evaluate(importFixture(fixturePackage));

            assertThat(result.hasViolation())
                    .as("the rule rejects %s", fixturePackage)
                    .isTrue();
            for (String offending : offendingClasses) {
                assertThat(result.getFailureReport().getDetails())
                        .as("the violation names %s", offending)
                        .anySatisfy(detail -> assertThat(detail).contains(offending));
            }
        }

        private JavaClasses importFixture(String fixturePackage) {
            return new ClassFileImporter().importPackages(fixturePackage);
        }
    }
}
