package com.mimococo.marketops.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves that every production rule can fail for its intended reason and pass.
 *
 * <p>The approved suite covers seven rule factories with eleven invalid observations:
 * application and port exercise the two halves of one composite rule, while the
 * internal-access scenario proves both an ordinary cross-module access and the
 * {@code alpha}/{@code alphabeta} prefix-collision case. Vendor-signature
 * scenarios check domain, direct module API and named-interface leakage. A shared
 * inward-dependency arrangement must pass all seven rules.
 *
 * <p>The separately named quality suite supplies the same mutation protection
 * for the four optional safeguards without counting them as approved boundaries.
 */
class RuleSensitivityArchitectureTest {

    private static final String FIXTURES = "com.mimococo.marketops.testfixture";
    private static final String VIOLATION = FIXTURES + ".violation";
    private static final String CONFORMING = FIXTURES + ".conforming.architecture";

    @Test
    @DisplayName("F-ARCH-001 ordinary and prefix-collision internal access are rejected")
    void moduleInternalAccessRuleFailsForBothCollisionShapes() {
        assertRejects(
                ArchitectureRules::moduleInternalsAreNotAccessedFromOtherModules,
                VIOLATION + ".moduleinternals",
                "BetaReadsAlphaInternals",
                "AlphaBetaReadsAlphaInternals");
    }

    @Test
    @DisplayName("F-ARCH-001P an internalization package remains public")
    void internalizationPackageIsNotTreatedAsInternal() {
        String fixture = FIXTURES + ".conforming.internalization";
        JavaClasses classes = importFixture(fixture);

        assertThatCode(() -> ArchitectureRules
                .moduleInternalsAreNotAccessedFromOtherModules(fixture)
                .check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("F-ARCH-002 a cycle between modules is rejected")
    void cycleRuleFails() {
        assertRejects(ArchitectureRules::modulesAreFreeOfCycles, VIOLATION + ".cycle", "Cycle");
    }

    @Test
    @DisplayName("F-ARCH-003 a shared dependency on a business module is rejected")
    void sharedRuleFails() {
        assertRejects(
                ArchitectureRules::theSharedModuleDependsOnNoBusinessModule,
                VIOLATION + ".sharedoutward",
                "SharedTypeReachingOutward");
    }

    @Test
    @DisplayName("F-ARCH-004 domain dependencies on adapter, infrastructure and SDK are rejected")
    void domainRuleFails() {
        assertRejects(
                ArchitectureRules::domainDoesNotDependOutward,
                VIOLATION + ".domainoutward",
                "DomainOrder");
    }

    @Test
    @DisplayName("F-ARCH-005a application dependencies on Marketplace adapters and SDK are rejected")
    void applicationHalfOfCompositeRuleFails() {
        assertRejects(
                ArchitectureRules::applicationAndPortDoNotDependOutward,
                VIOLATION + ".applicationoutward",
                "OrderUseCase");
    }

    @Test
    @DisplayName("F-ARCH-005b port dependencies on adapter, infrastructure and SDK are rejected")
    void portHalfOfCompositeRuleFails() {
        assertRejects(
                ArchitectureRules::applicationAndPortDoNotDependOutward,
                VIOLATION + ".portoutward",
                "OrderPort");
    }

    @Test
    @DisplayName("F-ARCH-006 an SDK dependency outside a platform adapter is rejected")
    void vendorLocationRuleFails() {
        assertRejects(
                ArchitectureRules::vendorSdkTypesStayInsidePlatformAdapters,
                VIOLATION + ".vendorlocation",
                "SdkUseOutsideAdapter");
    }

    @Test
    @DisplayName("F-ARCH-007 SDK types in domain, direct and named module APIs are rejected")
    void vendorSignatureRuleFails() {
        assertRejects(
                ArchitectureRules::vendorSdkTypesDoNotAppearInDomainOrModuleApiSignatures,
                VIOLATION + ".vendorapi",
                "DomainOffer",
                "OrderFacade",
                "NamedInterfaceVendorApi");
    }

    @Test
    @DisplayName("F-ARCH-007N every named-interface signature shape is inspected")
    void namedInterfaceSignatureShapesAreRejected() {
        String fixture = VIOLATION + ".vendorapi.namedinterface";
        JavaClasses classes = importFixture(fixture);
        EvaluationResult result = ArchitectureRules
                .vendorSdkTypesDoNotAppearInDomainOrModuleApiSignatures(fixture)
                .evaluate(classes);

        assertThat(result.hasViolation()).isTrue();
        String report = String.join("\n", result.getFailureReport().getDetails());
        assertThat(report).contains(
                "vendorField",
                "protectedVendorField",
                "NamedInterfaceVendorApi",
                "returnOrder",
                "acceptOrder",
                "genericOrders",
                "extends vendor SDK type",
                "implements vendor SDK type");
    }

    @Test
    @DisplayName("F-ARCH-007P named interfaces may expose platform-owned contracts")
    void platformOwnedNamedInterfacePasses() {
        JavaClasses classes = importFixture(CONFORMING);

        assertThatCode(() -> ArchitectureRules
                .vendorSdkTypesDoNotAppearInDomainOrModuleApiSignatures(CONFORMING)
                .check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("F-ARCH-PASS adapter and infrastructure dependencies point inward")
    void conformingInwardArrangementPassesEveryApprovedRule() {
        JavaClasses classes = importFixture(CONFORMING);
        assertThat(classes).isNotEmpty();

        for (ArchRule rule : approvedRules(CONFORMING)) {
            assertThatCode(() -> rule.check(classes))
                    .as("approved rule [%s] must accept the conforming fixture",
                            rule.getDescription())
                    .doesNotThrowAnyException();
        }
    }

    /** Sensitivity checks for safeguards that are not approved dependency rules. */
    @Nested
    @DisplayName("Additional code-quality rule sensitivity")
    class CodeQualitySensitivity {

        @Test
        @DisplayName("F-QUALITY-001 a resource opening a connection is rejected")
        void webLayerRuleFails() {
            assertRejects(
                    CodeQualityArchitectureRules::webLayerDoesNotReachTheDatabase,
                    VIOLATION + ".weblayer",
                    "ResourceReadingTheDatabase");
        }

        @Test
        @DisplayName("F-QUALITY-002 field injection is rejected")
        void fieldInjectionRuleFails() {
            assertRejects(
                    CodeQualityArchitectureRules::dependenciesAreNotInjectedIntoFields,
                    VIOLATION + ".fieldinjection",
                    "ServiceWithInjectedField");
        }

        @Test
        @DisplayName("F-QUALITY-003 ambient time is rejected")
        void ambientTimeRuleFails() {
            assertRejects(
                    CodeQualityArchitectureRules::timeIsReadFromAnInjectedClock,
                    VIOLATION + ".ambienttime",
                    "ServiceReadingTheAmbientClock");
        }

        @Test
        @DisplayName("F-QUALITY-004 console output is rejected")
        void consoleOutputRuleFails() {
            assertRejects(
                    CodeQualityArchitectureRules::diagnosticsAreWrittenThroughTheLogger,
                    VIOLATION + ".consoleoutput",
                    "ServiceWritingToTheConsole");
        }

        @Test
        @DisplayName("F-QUALITY-PASS the conforming arrangement satisfies all safeguards")
        void conformingArrangementPassesQualityRules() {
            JavaClasses classes = importFixture(CONFORMING);
            for (ArchRule rule : new ArchRule[] {
                    CodeQualityArchitectureRules.webLayerDoesNotReachTheDatabase(CONFORMING),
                    CodeQualityArchitectureRules.dependenciesAreNotInjectedIntoFields(CONFORMING),
                    CodeQualityArchitectureRules.timeIsReadFromAnInjectedClock(CONFORMING),
                    CodeQualityArchitectureRules.diagnosticsAreWrittenThroughTheLogger(CONFORMING)}) {
                assertThatCode(() -> rule.check(classes)).doesNotThrowAnyException();
            }
        }
    }

    private static ArchRule[] approvedRules(String basePackage) {
        return new ArchRule[] {
                ArchitectureRules.moduleInternalsAreNotAccessedFromOtherModules(basePackage),
                ArchitectureRules.modulesAreFreeOfCycles(basePackage),
                ArchitectureRules.theSharedModuleDependsOnNoBusinessModule(basePackage),
                ArchitectureRules.domainDoesNotDependOutward(basePackage),
                ArchitectureRules.applicationAndPortDoNotDependOutward(basePackage),
                ArchitectureRules.vendorSdkTypesStayInsidePlatformAdapters(basePackage),
                ArchitectureRules.vendorSdkTypesDoNotAppearInDomainOrModuleApiSignatures(basePackage)
        };
    }

    private static void assertRejects(Function<String, ArchRule> ruleFactory,
                                      String fixturePackage,
                                      String... expectedInMessage) {
        JavaClasses classes = importFixture(fixturePackage);
        assertThat(classes).as("fixture package %s must contain classes", fixturePackage).isNotEmpty();

        assertThatThrownBy(() -> ruleFactory.apply(fixturePackage).check(classes))
                .isInstanceOf(AssertionError.class)
                .satisfies(failure -> {
                    for (String expected : expectedInMessage) {
                        assertThat(failure).hasMessageContaining(expected);
                    }
                });
    }

    private static JavaClasses importFixture(String fixturePackage) {
        return new ClassFileImporter()
                .importPackages(fixturePackage, ArchitectureRules.VENDOR_STANDIN_PACKAGE);
    }
}
