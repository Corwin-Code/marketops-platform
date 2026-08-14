package com.mimococo.marketops.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that each rule fails when it should.
 *
 * <p>A rule that passes tells you either that the code conforms or that the rule
 * never looked at anything. Two of the rules below permit an empty subject,
 * which is correct when a tree legitimately contains no web resource, and which
 * would otherwise let the rule report success for the wrong reason. Each rule is
 * therefore also evaluated against a package written to break exactly it, and
 * the failure message is checked for the offending type.
 *
 * <p>The last case runs all seven rules against a package that conforms, so a
 * rule that has become unable to pass is caught as well.
 */
class RuleSensitivityArchitectureTest {

    private static final String FIXTURES = "com.mimococo.marketops.testfixture";
    private static final String VIOLATION = FIXTURES + ".violation";
    private static final String CONFORMING = FIXTURES + ".conforming.inward";

    @Test
    @DisplayName("F-1 reaching into another module's internals is rejected")
    void moduleInternalsRuleFails() {
        assertRejects(
                ArchitectureRules::moduleInternalsAreNotAccessedFromOtherModules,
                VIOLATION + ".moduleinternals",
                "BetaReadsAlphaInternals");
    }

    @Test
    @DisplayName("F-2 a cycle between two modules is rejected")
    void cycleRuleFails() {
        assertRejects(
                ArchitectureRules::modulesAreFreeOfCycles,
                VIOLATION + ".cycle",
                "Cycle");
    }

    @Test
    @DisplayName("F-3 a resource that opens a connection is rejected")
    void webLayerRuleFails() {
        assertRejects(
                ArchitectureRules::theWebLayerDoesNotReachTheDatabase,
                VIOLATION + ".weblayer",
                "ResourceReadingTheDatabase");
    }

    @Test
    @DisplayName("F-4 a field-injected dependency is rejected")
    void fieldInjectionRuleFails() {
        assertRejects(
                ArchitectureRules::dependenciesAreNotInjectedIntoFields,
                VIOLATION + ".fieldinjection",
                "ServiceWithInjectedField");
    }

    @Test
    @DisplayName("F-5 reading the ambient clock is rejected")
    void ambientTimeRuleFails() {
        assertRejects(
                ArchitectureRules::timeIsReadFromAnInjectedClock,
                VIOLATION + ".ambienttime",
                "ServiceReadingTheAmbientClock");
    }

    @Test
    @DisplayName("F-6 writing to the console is rejected")
    void consoleOutputRuleFails() {
        assertRejects(
                ArchitectureRules::diagnosticsAreWrittenThroughTheLogger,
                VIOLATION + ".consoleoutput",
                "ServiceWritingToTheConsole");
    }

    @Test
    @DisplayName("F-7 a dependency out of the shared module is rejected")
    void sharedModuleRuleFails() {
        assertRejects(
                ArchitectureRules::theSharedModuleDependsOnNoOtherModule,
                VIOLATION + ".sharedoutward",
                "SharedTypeReachingOutward");
    }

    @Test
    @DisplayName("F-8 a conforming arrangement satisfies all seven rules")
    void conformingFixturePassesEveryRule() {
        JavaClasses classes = new ClassFileImporter().importPackages(CONFORMING);
        assertThat(classes).as("the conforming fixture must contain classes").isNotEmpty();

        for (ArchRule rule : new ArchRule[] {
                ArchitectureRules.moduleInternalsAreNotAccessedFromOtherModules(CONFORMING),
                ArchitectureRules.modulesAreFreeOfCycles(CONFORMING),
                ArchitectureRules.theWebLayerDoesNotReachTheDatabase(CONFORMING),
                ArchitectureRules.dependenciesAreNotInjectedIntoFields(CONFORMING),
                ArchitectureRules.timeIsReadFromAnInjectedClock(CONFORMING),
                ArchitectureRules.diagnosticsAreWrittenThroughTheLogger(CONFORMING),
                ArchitectureRules.theSharedModuleDependsOnNoOtherModule(CONFORMING)}) {
            assertThatCode(() -> rule.check(classes))
                    .as("rule [%s] must accept a conforming arrangement", rule.getDescription())
                    .doesNotThrowAnyException();
        }
    }

    private static void assertRejects(Function<String, ArchRule> ruleFactory,
                                      String fixturePackage,
                                      String expectedInMessage) {
        JavaClasses classes = new ClassFileImporter().importPackages(fixturePackage);
        assertThat(classes)
                .as("fixture package %s must contain classes", fixturePackage)
                .isNotEmpty();

        ArchRule rule = ruleFactory.apply(fixturePackage);

        assertThatThrownBy(() -> rule.check(classes))
                .as("rule [%s] must reject %s", rule.getDescription(), fixturePackage)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(expectedInMessage);
    }
}
