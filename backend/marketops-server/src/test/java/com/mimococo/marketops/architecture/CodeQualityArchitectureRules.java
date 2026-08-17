package com.mimococo.marketops.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Additional code-quality safeguards that are not counted as approved boundaries.
 *
 * <p>They remain executable because they make failures deterministic, but they
 * cannot substitute for any rule in {@link ArchitectureRules}.
 */
final class CodeQualityArchitectureRules {

    private CodeQualityArchitectureRules() {
    }

    /** REST resources delegate instead of opening database connections. */
    static ArchRule webLayerDoesNotReachTheDatabase(String basePackage) {
        return noClasses()
                .that().resideInAPackage(basePackage + "..")
                .and().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..",
                        "org.flywaydb..", "com.zaxxer.hikari..")
                .allowEmptyShould(true)
                .as("a REST resource does not reach the database directly");
    }

    /** Dependencies are visible in constructors rather than injected into fields. */
    static ArchRule dependenciesAreNotInjectedIntoFields(String basePackage) {
        return noFields()
                .that().areDeclaredInClassesThat().resideInAPackage(basePackage + "..")
                .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .orShould().beAnnotatedWith("org.springframework.beans.factory.annotation.Value")
                .orShould().beAnnotatedWith("jakarta.annotation.Resource")
                .orShould().beAnnotatedWith("jakarta.inject.Inject")
                .allowEmptyShould(true)
                .as("dependencies are supplied through constructors");
    }

    /** Time-dependent code reads an injected clock. */
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
                .as("time is read from an injected clock");
    }

    /** Diagnostics use a logger rather than process-global console streams. */
    static ArchRule diagnosticsAreWrittenThroughTheLogger(String basePackage) {
        return noClasses()
                .that().resideInAPackage(basePackage + "..")
                .should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .orShould().callMethod(Throwable.class, "printStackTrace")
                .as("diagnostics are written through the logger");
    }
}
