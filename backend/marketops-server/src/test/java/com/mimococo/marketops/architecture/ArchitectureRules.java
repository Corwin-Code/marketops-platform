package com.mimococo.marketops.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.Set;

/**
 * The seven approved dependency-boundary rules for the modular monolith.
 *
 * <p>These are the architecture contract from the canonical foundation design,
 * not a collection of general Java style preferences. Every factory accepts a
 * base package so the identical rule instance can check production code, a
 * deliberately invalid fixture and the shared conforming fixture.
 *
 * <p>The domain rule and each half of the application/port composite permit an
 * empty layer because the foundation intentionally has no business module yet.
 * All other rules select the non-empty application tree and retain ArchUnit's
 * normal empty-subject protection.
 */
final class ArchitectureRules {

    /** Locally declared stand-ins for Marketplace SDK types used by sensitivity tests. */
    static final String VENDOR_STANDIN_PACKAGE =
            "com.mimococo.marketops.testfixture.vendorsdk";

    private static final String INTERNAL_SEGMENT = ".internal";
    private static final String SHARED_MODULE = "shared";

    /**
     * Vendor namespaces known to this foundation.
     *
     * <p>A work package that introduces a Marketplace SDK must register its
     * namespace here in the same change. The stand-in namespace makes the rule
     * executable before any real SDK or credential is introduced.
     */
    private static final Set<String> VENDOR_SDK_PREFIXES = Set.of(
            VENDOR_STANDIN_PACKAGE + ".",
            "com.ozon.",
            "ru.ozon.",
            "com.wildberries.",
            "ru.wildberries.");

    private ArchitectureRules() {
    }

    /** A module's {@code internal} packages are reachable only from that module. */
    static ArchRule moduleInternalsAreNotAccessedFromOtherModules(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>("reach only their own module internals") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            String targetPackage = dependency.getTargetClass().getPackageName();
                            int internal = targetPackage.indexOf(INTERNAL_SEGMENT);
                            if (!isWithin(targetPackage, basePackage) || internal < 0) {
                                continue;
                            }
                            String owningModulePackage = targetPackage.substring(0, internal);
                            if (!isWithin(item.getPackageName(), owningModulePackage)) {
                                events.add(SimpleConditionEvent.violated(
                                        item, dependency.getDescription()));
                            }
                        }
                    }
                })
                .as("a module's internals are reachable only from that exact module")
                .because("package prefixes such as alpha and alphabeta are different modules");
    }

    /** Direct child packages of the application root form an acyclic module graph. */
    static ArchRule modulesAreFreeOfCycles(String basePackage) {
        return SlicesRuleDefinition.slices()
                .matching(basePackage + ".(*)..")
                .should().beFreeOfCycles()
                .as("modules are free of dependency cycles")
                .because("a cycle erases the boundary between every module in the cycle");
    }

    /** The shared module is a dependency leaf and never reaches a business module. */
    static ArchRule theSharedModuleDependsOnNoBusinessModule(String basePackage) {
        String sharedPackage = basePackage + "." + SHARED_MODULE;
        return classes()
                .that().resideInAPackage(sharedPackage + "..")
                .should(new ArchCondition<>("depend only on shared or external libraries") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            String target = dependency.getTargetClass().getPackageName();
                            if (isWithin(target, basePackage) && !isWithin(target, sharedPackage)) {
                                events.add(SimpleConditionEvent.violated(
                                        item, dependency.getDescription()));
                            }
                        }
                    }
                })
                .as("the shared module depends on no business module")
                .because("everything may depend on shared, so shared must remain a leaf");
    }

    /** Domain code does not depend on adapters, infrastructure or vendor SDKs. */
    static ArchRule domainDoesNotDependOutward(String basePackage) {
        return noClasses()
                .that().resideInAPackage(basePackage + "..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(outwardAndVendorPackages(basePackage))
                .allowEmptyShould(true)
                .as("domain does not depend on adapter, infrastructure or vendor SDK types")
                .because("the domain must remain independent of delivery and vendor mechanisms");
    }

    /**
     * Application and port layers do not point to their concrete implementations.
     *
     * <p>The two layer-specific prohibitions form one approved dependency rule.
     * They remain separate inside the composite because application may use
     * ordinary infrastructure ports while a port must not use any implementation.
     */
    static ArchRule applicationAndPortDoNotDependOutward(String basePackage) {
        ArchRule application = noClasses()
                .that().resideInAPackage(basePackage + "..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(marketplaceAdaptersAndVendorPackages(basePackage))
                .allowEmptyShould(true)
                .as("application does not depend on concrete Marketplace adapters or vendor SDKs");
        ArchRule port = noClasses()
                .that().resideInAPackage(basePackage + "..port..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(outwardAndVendorPackages(basePackage))
                .allowEmptyShould(true)
                .as("port does not depend on adapter, infrastructure or vendor SDK types");
        return CompositeArchRule.of(application)
                .and(port)
                .as("application and port dependencies point inward, never to implementations");
    }

    /** Vendor SDK dependencies occur only in a platform-specific Marketplace adapter. */
    static ArchRule vendorSdkTypesStayInsidePlatformAdapters(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>(
                        "use vendor SDK types only below marketplaceintegration.adapter.<platform>") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            if (isVendorSdk(dependency.getTargetClass())
                                    && !isPlatformAdapter(item.getPackageName(), basePackage)) {
                                events.add(SimpleConditionEvent.violated(
                                        item, dependency.getDescription()));
                            }
                        }
                    }
                })
                .as("vendor SDK types occur only under marketplaceintegration.adapter.<platform>")
                .because("vendor models and clients must terminate at the anti-corruption adapter");
    }

    /** Vendor SDK types never form a domain signature or a module's public contract. */
    static ArchRule vendorSdkTypesDoNotAppearInDomainOrModuleApiSignatures(String basePackage) {
        return classes()
                .that().resideInAPackage(basePackage + "..")
                .should(new ArchCondition<>("expose no vendor SDK type from domain or module APIs") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        boolean domain = hasPackageSegment(item.getPackageName(), "domain");
                        boolean moduleApi = isDirectModulePackage(item.getPackageName(), basePackage)
                                && item.getModifiers().contains(JavaModifier.PUBLIC);
                        if (!domain && !moduleApi) {
                            return;
                        }

                        for (JavaMember member : item.getMembers()) {
                            if (!domain && !isPublicOrProtected(member)) {
                                continue;
                            }
                            for (JavaClass involved : member.getAllInvolvedRawTypes()) {
                                if (isVendorSdk(involved)) {
                                    events.add(SimpleConditionEvent.violated(item,
                                            member.getDescription() + " exposes vendor SDK type <"
                                                    + involved.getName() + ">"));
                                }
                            }
                        }
                        if (domain || moduleApi) {
                            item.getRawSuperclass().filter(ArchitectureRules::isVendorSdk)
                                    .ifPresent(type -> events.add(SimpleConditionEvent.violated(
                                            item, item.getDescription() + " extends vendor SDK type <"
                                                    + type.getName() + ">")));
                            item.getRawInterfaces().stream()
                                    .filter(ArchitectureRules::isVendorSdk)
                                    .forEach(type -> events.add(SimpleConditionEvent.violated(
                                            item, item.getDescription() + " implements vendor SDK type <"
                                                    + type.getName() + ">")));
                        }
                    }
                })
                .as("vendor SDK types never appear in domain or module API signatures")
                .because("module callers must depend on platform-owned contracts");
    }

    private static String[] outwardAndVendorPackages(String basePackage) {
        return new String[] {
                basePackage + "..adapter..",
                basePackage + "..infrastructure..",
                VENDOR_STANDIN_PACKAGE + "..",
                "com.ozon..",
                "ru.ozon..",
                "com.wildberries..",
                "ru.wildberries.."
        };
    }

    private static String[] marketplaceAdaptersAndVendorPackages(String basePackage) {
        return new String[] {
                basePackage + "..marketplaceintegration.adapter..",
                VENDOR_STANDIN_PACKAGE + "..",
                "com.ozon..",
                "ru.ozon..",
                "com.wildberries..",
                "ru.wildberries.."
        };
    }

    private static boolean isVendorSdk(JavaClass type) {
        String packageName = type.getPackageName() + ".";
        return VENDOR_SDK_PREFIXES.stream().anyMatch(packageName::startsWith);
    }

    private static boolean isPlatformAdapter(String packageName, String basePackage) {
        if (!isWithin(packageName, basePackage)) {
            return false;
        }
        String[] segments = packageName.substring(basePackage.length() + 1).split("\\.");
        for (int index = 0; index + 2 < segments.length; index++) {
            if (segments[index].equals("marketplaceintegration")
                    && segments[index + 1].equals("adapter")
                    && !segments[index + 2].isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDirectModulePackage(String packageName, String basePackage) {
        if (!isWithin(packageName, basePackage) || packageName.equals(basePackage)) {
            return false;
        }
        return !packageName.substring(basePackage.length() + 1).contains(".");
    }

    private static boolean hasPackageSegment(String packageName, String segment) {
        for (String candidate : packageName.split("\\.")) {
            if (candidate.equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPublicOrProtected(JavaMember member) {
        return member.getModifiers().contains(JavaModifier.PUBLIC)
                || member.getModifiers().contains(JavaModifier.PROTECTED);
    }

    private static boolean isWithin(String packageName, String parentPackage) {
        return packageName.equals(parentPackage) || packageName.startsWith(parentPackage + ".");
    }
}
