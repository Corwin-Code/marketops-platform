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
    private static final String ACQUISITION_REQUEST =
            "com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest";
    private static final String AUTHORIZED_EXECUTOR =
            "com.mimococo.marketops.marketplaceintegration.port.AuthorizedAcquisitionExecutor";
    private static final String GRANT_MAPPER = "com.mimococo.marketops.marketplaceintegration"
            + ".internal.infrastructure.jdbc.CallAuthorityGrantMapper";
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

    /** Only the designated executor invokes the outbound acquisition method. */
    static ArchRule acquisitionPortAcquireIsCalledOnlyByAuthorizedExecutor(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>(
                        "invoke AcquisitionPort.acquire only from AuthorizedAcquisitionExecutor") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        item.getMethodCallsFromSelf().stream()
                                .filter(call -> call.getName().equals("acquire"))
                                .filter(call -> call.getTargetOwner()
                                        .isAssignableTo(ACQUISITION_PORT))
                                .filter(call -> !item.getName().equals(AUTHORIZED_EXECUTOR))
                                .forEach(call -> events.add(SimpleConditionEvent.violated(
                                        item, call.getDescription())));
                    }
                })
                .as("only AuthorizedAcquisitionExecutor calls AcquisitionPort.acquire")
                .because("every outbound start must pass the expiry check and identity-bound"
                        + " request factory in the sole executor");
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

    /** Only the trusted database-result mapper may manufacture a grant. */
    static ArchRule callAuthorityGrantsAreConstructedOnlyByTrustedMapper(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>(
                        "construct CallAuthorityGrant only in CallAuthorityGrantMapper") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        item.getConstructorCallsFromSelf().stream()
                                .filter(call -> call.getTargetOwner().getName()
                                        .equals(CALL_AUTHORITY_GRANT))
                                .filter(call -> !item.getName().equals(GRANT_MAPPER))
                                .forEach(call -> events.add(SimpleConditionEvent.violated(
                                        item, call.getDescription())));
                    }
                })
                .as("only CallAuthorityGrantMapper constructs CallAuthorityGrant")
                .because("the grant must contain only columns returned by the database"
                        + " authority primitive, with no caller-selected replacement identity");
    }

    /** Only the sole executor may derive a request from the complete grant. */
    static ArchRule acquisitionRequestsAreCreatedOnlyByTheAuthorizedExecutor(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>(
                        "invoke AcquisitionRequest.from only in AuthorizedAcquisitionExecutor") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        item.getMethodCallsFromSelf().stream()
                                .filter(call -> call.getTargetOwner().getName()
                                        .equals(ACQUISITION_REQUEST))
                                .filter(call -> call.getName().equals("from"))
                                .filter(call -> !item.getName().equals(AUTHORIZED_EXECUTOR))
                                .forEach(call -> events.add(SimpleConditionEvent.violated(
                                        item, call.getDescription())));
                    }
                })
                .as("only AuthorizedAcquisitionExecutor calls AcquisitionRequest.from")
                .because("the request must be derived once from the complete structured grant");
    }

    private static boolean isAcquisitionPort(JavaClass type) {
        return type.getName().equals(ACQUISITION_PORT)
                || type.getName().equals(OBJECT_STORAGE_PORT);
    }

    private static boolean isInOwningModule(String packageName) {
        return (packageName + ".").contains(OWNING_MODULE_SEGMENT);
    }
}
