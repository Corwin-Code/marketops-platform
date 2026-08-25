package com.mimococo.marketops.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact production allowlists for the complete acquisition-authority chain. */
final class IngestionAuthorityRules {

    private static final String ACQUISITION_PORT =
            "com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort";
    private static final String OBJECT_STORAGE_PORT =
            "com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort";
    private static final String ACQUISITION_REQUEST =
            "com.mimococo.marketops.marketplaceintegration.port.AcquisitionRequest";
    private static final String JDBC_PACKAGE =
            "com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.";
    private static final String AUTHORIZED_GATEWAY =
            JDBC_PACKAGE + "JdbcAuthorizedAcquisitionGateway";
    private static final String AUTHORIZED_EXECUTOR =
            JDBC_PACKAGE + "AuthorizedAcquisitionExecutor";
    private static final String GRANT_MAPPER = JDBC_PACKAGE + "CallAuthorityGrantMapper";
    private static final String CALL_AUTHORITY_GRANT = JDBC_PACKAGE + "CallAuthorityGrant";
    private static final String PRODUCTION_ROOT = "com.mimococo.marketops";
    private static final String REST_CONTROLLER =
            "org.springframework.web.bind.annotation.RestController";
    private static final String OWNING_MODULE_SEGMENT = ".marketplaceintegration.";
    private static final Set<String> CONTROLLER_FORBIDDEN_SURFACES = Set.of(
            ACQUISITION_PORT,
            OBJECT_STORAGE_PORT,
            ACQUISITION_REQUEST,
            AUTHORIZED_GATEWAY,
            AUTHORIZED_EXECUTOR,
            GRANT_MAPPER,
            CALL_AUTHORITY_GRANT);
    private static final Set<String> INTERNAL_AUTHORITY_COLLABORATORS = Set.of(
            AUTHORIZED_GATEWAY, AUTHORIZED_EXECUTOR, GRANT_MAPPER, CALL_AUTHORITY_GRANT);

    private IngestionAuthorityRules() {
    }

    /** Only the owning module declares or inherits an acquisition or object-storage port. */
    static ArchRule acquisitionPortsAreImplementedOnlyByTheOwningModule(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>(
                        "be assignable to acquisition ports only inside marketplaceintegration") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        if (isAuthorityPort(item)
                                && !isInOwningModule(item.getPackageName())) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getDescription()
                                            + " is assignable to an acquisition port outside"
                                            + " marketplaceintegration"));
                        }
                    }
                })
                .as("all acquisition-port assignable types remain inside marketplaceintegration")
                .because("a second implementing module would be a second acquisition authority");
    }

    /** Only the internal executor invokes the outbound acquisition method. */
    static ArchRule acquisitionPortAcquireIsCalledOnlyByAuthorizedExecutor(String basePackage) {
        return methodCallAllowlist(
                basePackage,
                ACQUISITION_PORT,
                "acquire",
                AUTHORIZED_EXECUTOR,
                "only AuthorizedAcquisitionExecutor calls AcquisitionPort.acquire");
    }

    /** Only the gateway can turn a JDBC row into an internal grant. */
    static ArchRule grantMapperIsCalledOnlyByGateway(String basePackage) {
        return methodCallAllowlist(
                basePackage,
                GRANT_MAPPER,
                "map",
                AUTHORIZED_GATEWAY,
                "only JdbcAuthorizedAcquisitionGateway calls CallAuthorityGrantMapper.map");
    }

    /** Only the gateway can pass an internal grant to the one-shot executor. */
    static ArchRule authorizedExecutorIsCalledOnlyByGateway(String basePackage) {
        return methodCallAllowlist(
                basePackage,
                AUTHORIZED_EXECUTOR,
                "execute",
                AUTHORIZED_GATEWAY,
                "only JdbcAuthorizedAcquisitionGateway calls AuthorizedAcquisitionExecutor.execute");
    }

    /** No RestController may depend on any link in the authority chain. */
    static ArchRule webControllersDoNotReachAcquisition(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .and(restControllerRoots())
                .should(new ArchCondition<>("not depend on the acquisition-authority chain") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        item.getDirectDependenciesFromSelf().stream()
                                .filter(dependency -> isControllerForbidden(
                                        dependency.getTargetClass()))
                                .forEach(dependency -> events.add(
                                        SimpleConditionEvent.violated(
                                                item, dependency.getDescription())));
                    }
                })
                .allowEmptyShould(true)
                .as("no web controller reaches the gateway, executor, mapper, grant,"
                        + " request or acquisition port")
                .because("acquisition starts only from a leased worker through the DB gateway");
    }

    /**
     * No direct or meta-annotated RestController may reach the authority chain through either
     * compile-time dependencies or runtime dispatch to a concrete implementation.
     */
    static ArchRule webControllersDoNotTransitivelyReachAcquisition(
            JavaClasses universe, String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .and(restControllerRoots())
                .should(new ArchCondition<>("not transitively reach the acquisition-authority chain") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        ArrayDeque<DependencyPath> pending = new ArrayDeque<>();
                        Set<String> visited = new HashSet<>();
                        visited.add(item.getName());
                        pending.add(new DependencyPath(item, item.getName()));

                        while (!pending.isEmpty()) {
                            DependencyPath current = pending.removeFirst();
                            current.type().getDirectDependenciesFromSelf().stream()
                                    .map(dependency -> dependency.getTargetClass())
                                    .filter(IngestionAuthorityRules::isMarketOpsClass)
                                    .distinct()
                                    .sorted(Comparator.comparing(JavaClass::getName))
                                    .forEach(target -> inspectControllerPath(
                                            item, current, target, " -> ",
                                            visited, pending, events));
                            runtimeDispatchTargets(current.type(), universe).forEach(target ->
                                    inspectControllerPath(
                                            item, current, target, " => ",
                                            visited, pending, events));
                        }
                    }
                })
                .allowEmptyShould(true)
                .as("no web controller transitively reaches the gateway, executor, mapper, grant,"
                        + " request, acquisition port or object-storage port")
                .because("request threads cannot enter a leased worker's authority graph");
    }

    /** Only the mapper constructs the internal grant value. */
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
                .because("only the exact stored-function row may manufacture a grant");
    }

    /** Only the executor derives the adapter request from the consumed grant. */
    static ArchRule acquisitionRequestsAreCreatedOnlyByTheAuthorizedExecutor(
            String basePackage) {
        return methodCallAllowlist(
                basePackage,
                ACQUISITION_REQUEST,
                "fromDatabaseAuthority",
                AUTHORIZED_EXECUTOR,
                "only AuthorizedAcquisitionExecutor creates AcquisitionRequest");
    }

    /** Mapper, executor and grant are private collaborators of the one JDBC gateway. */
    static ArchRule internalAuthorityTypesDoNotEscapeTheirCollaborators(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>("not depend on internal authority collaborators") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        item.getDirectDependenciesFromSelf().stream()
                                .filter(dependency -> Set.of(
                                                AUTHORIZED_EXECUTOR,
                                                GRANT_MAPPER,
                                                CALL_AUTHORITY_GRANT)
                                        .contains(dependency.getTargetClass().getName()))
                                .filter(dependency -> !INTERNAL_AUTHORITY_COLLABORATORS
                                        .contains(item.getName()))
                                .forEach(dependency -> events.add(SimpleConditionEvent.violated(
                                        item, dependency.getDescription())));
                    }
                })
                .as("grant, mapper and executor do not escape the exact JDBC collaborators");
    }

    private static ArchRule methodCallAllowlist(
            String basePackage,
            String targetOwner,
            String targetMethod,
            String allowedCaller,
            String description) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>(description) {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        item.getMethodCallsFromSelf().stream()
                                .filter(call -> call.getName().equals(targetMethod))
                                .filter(call -> call.getTargetOwner().getName()
                                        .equals(targetOwner)
                                        || (targetOwner.equals(ACQUISITION_PORT)
                                            && call.getTargetOwner()
                                                    .isAssignableTo(ACQUISITION_PORT)))
                                .filter(call -> !item.getName().equals(allowedCaller))
                                .forEach(call -> events.add(SimpleConditionEvent.violated(
                                        item, call.getDescription())));
                    }
                })
                .as(description);
    }

    private static boolean isControllerForbidden(JavaClass type) {
        return CONTROLLER_FORBIDDEN_SURFACES.contains(type.getName())
                || isAuthorityPort(type);
    }

    private static void inspectControllerPath(
            JavaClass controller,
            DependencyPath current,
            JavaClass target,
            String edgeMarker,
            Set<String> visited,
            ArrayDeque<DependencyPath> pending,
            ConditionEvents events) {
        String path = current.path() + edgeMarker + target.getName();
        if (isControllerForbidden(target)) {
            events.add(SimpleConditionEvent.violated(
                    controller, "transitive acquisition-authority path: " + path));
            return;
        }
        if (visited.add(target.getName())) {
            pending.addLast(new DependencyPath(target, path));
        }
    }

    private static List<JavaClass> runtimeDispatchTargets(
            JavaClass contract, JavaClasses universe) {
        boolean runtimeContract = contract.isInterface()
                || contract.getModifiers().contains(JavaModifier.ABSTRACT);
        if (!runtimeContract) {
            return List.of();
        }
        return universe.stream()
                .filter(IngestionAuthorityRules::isMarketOpsClass)
                .filter(IngestionAuthorityRules::isConcreteClass)
                .filter(candidate -> candidate.isAssignableTo(contract.getName()))
                .sorted(Comparator.comparing(JavaClass::getName))
                .toList();
    }

    private static boolean isConcreteClass(JavaClass type) {
        return !type.isInterface()
                && !type.getModifiers().contains(JavaModifier.ABSTRACT);
    }

    private static DescribedPredicate<JavaClass> restControllerRoots() {
        return new DescribedPredicate<>("are directly or meta-annotated with @RestController") {
            @Override
            public boolean test(JavaClass input) {
                return input.isAnnotatedWith(REST_CONTROLLER)
                        || input.isMetaAnnotatedWith(REST_CONTROLLER);
            }
        };
    }

    private static boolean isMarketOpsClass(JavaClass type) {
        return type.getPackageName().equals(PRODUCTION_ROOT)
                || type.getPackageName().startsWith(PRODUCTION_ROOT + ".");
    }

    private static boolean isAuthorityPort(JavaClass type) {
        return type.isAssignableTo(ACQUISITION_PORT)
                || type.isAssignableTo(OBJECT_STORAGE_PORT);
    }

    private static boolean isInOwningModule(String packageName) {
        return (packageName + ".").contains(OWNING_MODULE_SEGMENT);
    }

    private record DependencyPath(JavaClass type, String path) {
    }
}
