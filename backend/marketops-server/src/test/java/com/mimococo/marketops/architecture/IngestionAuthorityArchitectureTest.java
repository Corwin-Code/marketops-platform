package com.mimococo.marketops.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * <p>The three rules keep the outbound doorway singular: only the owning module
 * implements the acquisition ports, only the owning module calls them, and no
 * web controller reaches them. Every production check is paired with a
 * deliberately invalid fixture, because a boundary rule that has never rejected
 * anything proves only that it compiled.
 */
class IngestionAuthorityArchitectureTest {

    private static final String PRODUCTION_PACKAGE = "com.mimococo.marketops";
    private static final String FIXTURES = "com.mimococo.marketops.testfixture";
    private static final String VIOLATION = FIXTURES + ".violation";
    private static final String CONFORMING = FIXTURES + ".conforming.ingestionauthority";

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
    @DisplayName("TC-ARCH-021 acquisition ports are called only from marketplaceintegration")
    void portsAreCalledOnlyFromTheOwningModule() {
        IngestionAuthorityRules
                .acquisitionPortsAreCalledOnlyFromTheOwningModule(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-022 no web controller reaches an acquisition port")
    void noControllerReachesAcquisition() {
        IngestionAuthorityRules
                .webControllersDoNotReachAcquisition(PRODUCTION_PACKAGE)
                .check(production);
    }

    /**
     * Each rule rejects the arrangement built to violate it and accepts the
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
                    IngestionAuthorityRules::acquisitionPortsAreCalledOnlyFromTheOwningModule,
                    VIOLATION + ".acquisitioncaller",
                    "ReportRefresher");
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
        @DisplayName("F-ARCH-023 the conforming arrangement passes all three rules")
        void conformingArrangementPasses() {
            JavaClasses conforming = importFixture(CONFORMING);
            assertThatCode(() -> {
                IngestionAuthorityRules
                        .acquisitionPortsAreImplementedOnlyByTheOwningModule(CONFORMING)
                        .check(conforming);
                IngestionAuthorityRules
                        .acquisitionPortsAreCalledOnlyFromTheOwningModule(CONFORMING)
                        .check(conforming);
                IngestionAuthorityRules
                        .webControllersDoNotReachAcquisition(CONFORMING)
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
