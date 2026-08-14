package com.mimococo.marketops.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * The architecture rules this project is willing to enforce.
 *
 * <p>There are seven, each stated once and applied twice: to the production
 * classes, and to a fixture built to break it. The second application is what
 * makes the first meaningful. A rule whose subject set turns out to be empty
 * reports success, and a rule that has never been observed failing is
 * indistinguishable from one that cannot fail.
 *
 * <p>Every rule takes the package it applies to, so the same definition can be
 * evaluated against the production tree and against a fixture tree without a
 * second copy of the rule existing anywhere.
 */
final class ArchitectureRules {

    private ArchitectureRules() {
    }

    /** Package segment that marks a module's private implementation. */
    private static final String INTERNAL = ".internal";

    /** Name of the module that may be depended on by every other module. */
    private static final String SHARED = "shared";

    /**
     * A module's private implementation is reachable only from that module.
     *
     * <p>The condition derives the owning module from the position of the
     * internal segment, so it holds for any depth of package nesting and needs no
     * list of module names to be kept current.
     */
    static ArchRule moduleInternalsAreNotAccessedFromOtherModules(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>("reach into the internals of another module") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            String target = dependency.getTargetClass().getPackageName();
                            if (!target.startsWith(basePackage) || !target.contains(INTERNAL)) {
                                continue;
                            }
                            String owner = target.substring(0, target.indexOf(INTERNAL));
                            if (!item.getPackageName().startsWith(owner)) {
                                events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
                            }
                        }
                    }
                })
                .as("a module's internals are reachable only from that module")
                .because("a package another module may call is a published contract, "
                        + "and publishing one by accident is how a boundary stops holding");
    }

    /**
     * Modules do not depend on each other in a circle.
     *
     * <p>A cycle removes the possibility of understanding, testing, or extracting
     * either participant on its own, and it forms one dependency at a time.
     */
    static ArchRule modulesAreFreeOfCycles(String basePackage) {
        return SlicesRuleDefinition.slices()
                .matching(basePackage + ".(*)..")
                .should().beFreeOfCycles()
                .as("modules are free of cycles")
                .because("a cycle makes two modules one, and it is formed one dependency at a time");
    }

    /**
     * A resource does not open a connection.
     *
     * <p>The web layer translates between the outside world and the application.
     * A controller that reaches the database directly has no place left to put a
     * transaction boundary, and its behaviour can only be exercised through HTTP.
     */
    static ArchRule theWebLayerDoesNotReachTheDatabase(String basePackage) {
        return noClasses()
                .that().resideInAPackage(basePackage + "..")
                .and().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..",
                        "org.flywaydb..", "com.zaxxer.hikari..")
                // A tree with no resource in it has nothing to say about this rule.
                // The fixture below is what proves the rule still fails when a
                // resource does reach the database.
                .allowEmptyShould(true)
                .as("a web resource does not reach the database directly")
                .because("a resource that opens its own connection leaves nowhere to put "
                        + "a transaction boundary and can only be exercised over HTTP");
    }

    /**
     * Dependencies arrive through the constructor.
     *
     * <p>A field-injected dependency cannot be supplied by a caller, so the class
     * cannot be constructed in a test without a container, and its requirements
     * are invisible in its signature.
     */
    static ArchRule dependenciesAreNotInjectedIntoFields(String basePackage) {
        return noFields()
                .that().areDeclaredInClassesThat().resideInAPackage(basePackage + "..")
                .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .orShould().beAnnotatedWith("org.springframework.beans.factory.annotation.Value")
                .orShould().beAnnotatedWith("jakarta.annotation.Resource")
                .orShould().beAnnotatedWith("jakarta.inject.Inject")
                .allowEmptyShould(true)
                .as("dependencies are supplied through the constructor")
                .because("a field-injected dependency is invisible in the signature and "
                        + "cannot be supplied without a container");
    }

    /**
     * Time is read from the injected clock.
     *
     * <p>A class that reads the ambient clock cannot be tested at a chosen
     * instant, so behaviour that depends on time is exercised by whatever moment
     * the test happens to run at.
     */
    static ArchRule timeIsReadFromAnInjectedClock(String basePackage) {
        return noClasses()
                .that().resideInAPackage(basePackage + "..")
                .should().callMethod(Instant.class, "now")
                .orShould().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalTime.class, "now")
                .orShould().callMethod(LocalDateTime.class, "now")
                .orShould().callMethod(ZonedDateTime.class, "now")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .orShould().callConstructor(java.util.Date.class)
                .allowEmptyShould(true)
                .as("time is read from the injected clock")
                .because("a class that reads the ambient clock cannot be exercised "
                        + "at a chosen instant");
    }

    /**
     * Diagnostics go to the logger.
     *
     * <p>Output written to the console carries no level, no timestamp and no
     * correlation identifier, so it cannot be filtered, correlated, or turned off
     * where it is deployed.
     */
    static ArchRule diagnosticsAreWrittenThroughTheLogger(String basePackage) {
        return noClasses()
                .that().resideInAPackage(basePackage + "..")
                .should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .orShould().callMethod(Throwable.class, "printStackTrace")
                .allowEmptyShould(true)
                .as("diagnostics are written through the logger")
                .because("console output carries no level, no time and no correlation "
                        + "identifier, so it can be neither filtered nor correlated");
    }

    /**
     * The shared module depends on no other module.
     *
     * <p>Every module may depend on {@code shared}. If {@code shared} depended
     * back on one of them, that module would be reachable from all of the others,
     * and the encapsulation each of them has would be worth nothing.
     */
    static ArchRule theSharedModuleDependsOnNoOtherModule(String basePackage) {
        String sharedPackage = basePackage + "." + SHARED;
        return classes()
                .that().resideInAPackage(sharedPackage + "..")
                .should(new ArchCondition<>("depend on another module") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            String target = dependency.getTargetClass().getPackageName();
                            if (!target.startsWith(basePackage + ".")) {
                                continue;
                            }
                            if (target.equals(sharedPackage) || target.startsWith(sharedPackage + ".")) {
                                continue;
                            }
                            events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
                        }
                    }
                })
                .allowEmptyShould(true)
                .as("the shared module depends on no other module")
                .because("everything may depend on shared, so a dependency out of it "
                        + "makes its target reachable from everywhere");
    }
}
