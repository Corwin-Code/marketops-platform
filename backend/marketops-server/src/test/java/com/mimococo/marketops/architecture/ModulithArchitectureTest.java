package com.mimococo.marketops.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mimococo.marketops.MarketOpsServerApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the module structure the framework itself derives.
 *
 * <p>The rules in this package are written by hand and say what this project has
 * decided. This test asks the module system the same question from its own
 * model, so a boundary that holds only because a hand-written pattern happens to
 * match is caught.
 */
class ModulithArchitectureTest {

    /**
     * Packages that exist only to support tests.
     *
     * <p>They are compiled into the same tree as the production classes, and the
     * module system would otherwise read each of them as a module of the system,
     * including the fixtures written to violate a boundary on purpose.
     */
    private static final List<String> TEST_ONLY_PACKAGES = List.of(
            "com.mimococo.marketops.testfixture",
            "com.mimococo.marketops.architecture",
            "com.mimococo.marketops.database",
            "com.mimococo.marketops.build");

    private static final DescribedPredicate<JavaClass> TEST_SUPPORT =
            DescribedPredicate.describe("test support types", type ->
                    TEST_ONLY_PACKAGES.stream().anyMatch(prefix ->
                            type.getPackageName().equals(prefix)
                                    || type.getPackageName().startsWith(prefix + ".")));

    private final ApplicationModules modules =
            ApplicationModules.of(MarketOpsServerApplication.class, TEST_SUPPORT);

    @Test
    @DisplayName("TC-ARCH-008 the module structure verifies")
    void moduleStructureVerifies() {
        assertThatCode(modules::verify).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the modules the system reports are the ones this project declares")
    void declaredModulesAreTheOnesDetected() {
        List<String> detected = modules.stream()
                .map(ApplicationModule::getIdentifier)
                .map(Object::toString)
                .sorted()
                .toList();

        assertThat(detected).containsExactly("adminobservability", "identityaccess",
                "marketplaceintegration", "organizationaccount", "productlisting", "shared");
    }
}
