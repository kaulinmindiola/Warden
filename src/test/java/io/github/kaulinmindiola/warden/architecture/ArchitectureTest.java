package io.github.kaulinmindiola.warden.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.kaulinmindiola.warden.WardenApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Permanent architecture fitness functions.
 *
 * <p>FF-01: Spring Modulith verifies module boundaries and cycles. FF-02: ArchUnit rules that
 * Modulith does not express, starting with the SDD V04 rule that {@code shared} depends on no
 * domain module.
 */
class ArchitectureTest {

    private static final String BASE_PACKAGE = "io.github.kaulinmindiola.warden";

    private static final ApplicationModules MODULES = ApplicationModules.of(WardenApplication.class);

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    @Test
    void modulesRespectTheirBoundaries() {
        System.out.println("POC-001 Spring Modulith detected modules:");
        System.out.println(MODULES);
        MODULES.verify();
    }

    @Test
    void sharedKernelDependsOnNoDomainModule() {
        noClasses()
                .that()
                .resideInAPackage(BASE_PACKAGE + ".shared..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        BASE_PACKAGE + ".iam..",
                        BASE_PACKAGE + ".workspace..",
                        BASE_PACKAGE + ".notes..",
                        BASE_PACKAGE + ".notification..")
                .because("SDD V04: the shared kernel depends on no domain module")
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }
}
