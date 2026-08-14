package com.mimococo.marketops.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Applies every architecture rule to the production classes.
 *
 * <p>Only classes compiled from {@code src/main} are imported. The fixtures that
 * prove each rule can fail are deliberate violations, and importing them here
 * would make this test fail for the wrong reason.
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
    @DisplayName("the production tree is not empty, so every rule below has a subject")
    void productionClassesWereImported() {
        org.assertj.core.api.Assertions.assertThat(production)
                .as("an empty import would make every rule in this class pass without checking anything")
                .isNotEmpty();
    }

    @Test
    @DisplayName("TC-ARCH-001 a module's internals are reachable only from that module")
    void moduleInternalsAreEncapsulated() {
        ArchitectureRules.moduleInternalsAreNotAccessedFromOtherModules(PRODUCTION_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("TC-ARCH-002 modules are free of cycles")
    void modulesAreFreeOfCycles() {
        ArchitectureRules.modulesAreFreeOfCycles(PRODUCTION_PACKAGE).check(production);
    }

    @Test
    @DisplayName("TC-ARCH-003 a web resource does not reach the database")
    void webLayerDoesNotReachTheDatabase() {
        ArchitectureRules.theWebLayerDoesNotReachTheDatabase(PRODUCTION_PACKAGE).check(production);
    }

    @Test
    @DisplayName("TC-ARCH-004 dependencies are supplied through the constructor")
    void dependenciesArriveThroughTheConstructor() {
        ArchitectureRules.dependenciesAreNotInjectedIntoFields(PRODUCTION_PACKAGE).check(production);
    }

    @Test
    @DisplayName("TC-ARCH-005 time is read from the injected clock")
    void timeComesFromTheInjectedClock() {
        ArchitectureRules.timeIsReadFromAnInjectedClock(PRODUCTION_PACKAGE).check(production);
    }

    @Test
    @DisplayName("TC-ARCH-006 diagnostics are written through the logger")
    void diagnosticsGoToTheLogger() {
        ArchitectureRules.diagnosticsAreWrittenThroughTheLogger(PRODUCTION_PACKAGE).check(production);
    }

    @Test
    @DisplayName("TC-ARCH-007 the shared module depends on no other module")
    void sharedModuleDependsOnNoOtherModule() {
        ArchitectureRules.theSharedModuleDependsOnNoOtherModule(PRODUCTION_PACKAGE).check(production);
    }
}
