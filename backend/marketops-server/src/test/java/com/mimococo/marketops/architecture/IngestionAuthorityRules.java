package com.mimococo.marketops.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * The authority-boundary rules for the ingestion acquisition ports.
 *
 * <p>Acquisition is the one doorway through which traffic could ever leave the
 * system, so its ports are owned by exactly one module and reachable from no
 * public route. Each factory accepts a base package so the identical rule
 * instance can check production code, a deliberately invalid fixture and a
 * conforming fixture, in the same way the approved boundary rules are checked.
 */
final class IngestionAuthorityRules {

    private static final String ACQUISITION_PORT =
            "com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort";
    private static final String OBJECT_STORAGE_PORT =
            "com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort";
    private static final String CALL_AUTHORITY_GRANT =
            "com.mimococo.marketops.marketplaceintegration.port.CallAuthorityGrant";
    private static final String OWNING_MODULE_SEGMENT = ".marketplaceintegration.";

    private IngestionAuthorityRules() {
    }

    /** Only the owning module implements an acquisition or object-storage port. */
    static ArchRule acquisitionPortsAreImplementedOnlyByTheOwningModule(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>(
                        "implement acquisition ports only inside marketplaceintegration") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        boolean implementsPort = item.getRawInterfaces().stream()
                                .anyMatch(IngestionAuthorityRules::isAcquisitionPort);
                        if (implementsPort && !isInOwningModule(item.getPackageName())) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getDescription()
                                            + " implements an acquisition port outside"
                                            + " marketplaceintegration"));
                        }
                    }
                })
                .as("acquisition ports are implemented only inside marketplaceintegration")
                .because("a second implementing module would be a second acquisition authority");
    }

    /** Only the owning module calls an acquisition or object-storage port. */
    static ArchRule acquisitionPortsAreCalledOnlyFromTheOwningModule(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>(
                        "reach acquisition ports only from marketplaceintegration") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        if (isInOwningModule(item.getPackageName())) {
                            return;
                        }
                        item.getDirectDependenciesFromSelf().stream()
                                .filter(dependency ->
                                        isAcquisitionPort(dependency.getTargetClass()))
                                .forEach(dependency -> events.add(SimpleConditionEvent.violated(
                                        item, dependency.getDescription())));
                    }
                })
                .as("acquisition ports are called only from marketplaceintegration")
                .because("another module reaching the outbound doorway would bypass the"
                        + " single scheduler, permit and evidence authority");
    }

    /** No web controller reaches the acquisition doorway. */
    static ArchRule webControllersDoNotReachAcquisition(String basePackage) {
        return noClasses()
                .that().resideInAPackage(basePackage + "..")
                .and().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should(new ArchCondition<>("depend on an acquisition port") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        item.getDirectDependenciesFromSelf().stream()
                                .filter(dependency ->
                                        isAcquisitionPort(dependency.getTargetClass()))
                                .forEach(dependency -> events.add(
                                        SimpleConditionEvent.satisfied(
                                                item, dependency.getDescription())));
                    }
                })
                .allowEmptyShould(true)
                .as("no web controller reaches an acquisition port")
                .because("acquisition runs only under a worker's leased, fenced, granted"
                        + " authority, never under a request thread's");
    }

    /** No module outside the owner may manufacture a database-derived grant. */
    static ArchRule callAuthorityGrantsAreConstructedOnlyByTheOwningModule(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>(
                        "construct call-authority grants only inside marketplaceintegration") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        if (isInOwningModule(item.getPackageName())) {
                            return;
                        }
                        item.getDirectDependenciesFromSelf().stream()
                                .filter(dependency -> dependency.getTargetClass().getName()
                                        .equals(CALL_AUTHORITY_GRANT))
                                .forEach(dependency -> events.add(SimpleConditionEvent.violated(
                                        item, dependency.getDescription())));
                    }
                })
                .as("call-authority grants are constructed only inside marketplaceintegration")
                .because("a caller-made grant could rebind an expiry to another call identity");
    }

    private static boolean isAcquisitionPort(JavaClass type) {
        return type.getName().equals(ACQUISITION_PORT)
                || type.getName().equals(OBJECT_STORAGE_PORT);
    }

    private static boolean isInOwningModule(String packageName) {
        return (packageName + ".").contains(OWNING_MODULE_SEGMENT);
    }
}
