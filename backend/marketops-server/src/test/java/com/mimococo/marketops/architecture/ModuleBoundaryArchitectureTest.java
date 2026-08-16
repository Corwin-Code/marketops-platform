package com.mimococo.marketops.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Applies the seven approved boundary rules to production classes.
 *
 * <p>Only main classes are imported. Sensitivity fixtures live under test
 * sources and are evaluated separately by {@link RuleSensitivityArchitectureTest}.
 * General code-quality rules are retained in the explicitly named nested suite
 * and do not count toward the approved boundary total.
 */
class ModuleBoundaryArchitectureTest {

    static final String PRODUCTION_PACKAGE = "com.mimococo.marketops";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PRODUCTION_PACKAGE);
    }

    @Test
    @DisplayName("the production import is non-empty")
    void productionClassesWereImported() {
        assertThat(production).isNotEmpty();
    }

    @Test
    @DisplayName("TC-ARCH-001 exact module internals are closed")
    void moduleInternalsAreClosed() {
        ArchitectureRules.moduleInternalsAreNotAccessedFromOtherModules(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-002 module dependencies are acyclic")
    void modulesAreFreeOfCycles() {
        ArchitectureRules.modulesAreFreeOfCycles(PRODUCTION_PACKAGE).check(production);
    }

    @Test
    @DisplayName("TC-ARCH-003 shared depends on no business module")
    void sharedIsADependencyLeaf() {
        ArchitectureRules.theSharedModuleDependsOnNoBusinessModule(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-004 domain does not depend outward")
    void domainDoesNotDependOutward() {
        ArchitectureRules.domainDoesNotDependOutward(PRODUCTION_PACKAGE).check(production);
    }

    @Test
    @DisplayName("TC-ARCH-005 application and port do not depend on implementations")
    void applicationAndPortDoNotDependOutward() {
        ArchitectureRules.applicationAndPortDoNotDependOutward(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-006 vendor SDK types stay in platform adapters")
    void vendorSdkTypesStayInPlatformAdapters() {
        ArchitectureRules.vendorSdkTypesStayInsidePlatformAdapters(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-007 vendor SDK types do not leak through protected signatures")
    void vendorSdkTypesDoNotLeakThroughSignatures() {
        ArchitectureRules.vendorSdkTypesDoNotAppearInDomainOrModuleApiSignatures(
                PRODUCTION_PACKAGE).check(production);
    }

    /** Extra safeguards, deliberately separate from the approved boundary contract. */
    @Nested
    @DisplayName("Additional code-quality architecture safeguards")
    class CodeQualitySafeguards {

        @Test
        @DisplayName("TC-QUALITY-ARCH-001 REST resources do not open database connections")
        void webLayerDoesNotReachTheDatabase() {
            CodeQualityArchitectureRules.webLayerDoesNotReachTheDatabase(PRODUCTION_PACKAGE)
                    .check(production);
        }

        @Test
        @DisplayName("TC-QUALITY-ARCH-002 dependencies are not injected into fields")
        void dependenciesArriveThroughConstructors() {
            CodeQualityArchitectureRules.dependenciesAreNotInjectedIntoFields(PRODUCTION_PACKAGE)
                    .check(production);
        }

        @Test
        @DisplayName("TC-QUALITY-ARCH-003 time comes from an injected clock")
        void timeComesFromAnInjectedClock() {
            CodeQualityArchitectureRules.timeIsReadFromAnInjectedClock(PRODUCTION_PACKAGE)
                    .check(production);
        }

        @Test
        @DisplayName("TC-QUALITY-ARCH-004 diagnostics use the logger")
        void diagnosticsUseTheLogger() {
            CodeQualityArchitectureRules.diagnosticsAreWrittenThroughTheLogger(PRODUCTION_PACKAGE)
                    .check(production);
        }
    }
}
