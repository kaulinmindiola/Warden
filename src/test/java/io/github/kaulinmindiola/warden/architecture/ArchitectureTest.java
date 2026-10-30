package io.github.kaulinmindiola.warden.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.kaulinmindiola.warden.WardenApplication;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.github.kaulinmindiola.warden.support.Verifies;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Permanent architecture fitness functions (SDD V04, appendix B).
 *
 * <ul>
 *   <li>FF-01: Spring Modulith verifies module boundaries and cycles (R1, R2, R6).
 *   <li>FF-02: {@code notification} depends on no module of the application but {@code shared}
 *       (R3, REQ-MAINT-002).
 *   <li>FF-03: only {@code notification.application.worker} opens a system-context (cross-tenant)
 *       transaction through {@link TenantTransactions#system} (R5).
 * </ul>
 *
 * <p>The ArchUnit rules are built for a base package so that they can also be evaluated against
 * the classes under {@code architecture.fixture}, which break them on purpose: a rule that passes
 * on the application is only meaningful if it is shown to fail on a violation.
 */
class ArchitectureTest {

    private static final String BASE_PACKAGE = "io.github.kaulinmindiola.warden";

    private static final String FIXTURE_PACKAGE = BASE_PACKAGE + ".architecture.fixture";

    private static final ApplicationModules MODULES = ApplicationModules.of(WardenApplication.class);

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    private static final JavaClasses FIXTURE_CLASSES = new ClassFileImporter().importPackages(FIXTURE_PACKAGE);

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

    @Test
    @Verifies("REQ-MAINT-002")
    void notificationDependsOnlyOnTheSharedKernel() {
        notificationDependsOnlyOnShared(BASE_PACKAGE).check(PRODUCTION_CLASSES);
    }

    @Test
    void onlyTheNotificationWorkerOpensSystemTransactions() {
        onlyTheWorkerOpensSystemTransactions(BASE_PACKAGE).check(PRODUCTION_CLASSES);
    }

    @Test
    @Verifies("REQ-MAINT-002")
    void theNotificationRuleRejectsADependencyOnAnotherModule() {
        EvaluationResult result =
                notificationDependsOnlyOnShared(FIXTURE_PACKAGE).evaluate(FIXTURE_CLASSES);

        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains(FIXTURE_PACKAGE + ".notification.LeakyHandler")
                .contains(FIXTURE_PACKAGE + ".iam.Account")
                .doesNotContain(FIXTURE_PACKAGE + ".notification.CompliantHandler");
    }

    @Test
    void theSystemContextRuleRejectsACallerOutsideTheWorker() {
        EvaluationResult result =
                onlyTheWorkerOpensSystemTransactions(FIXTURE_PACKAGE).evaluate(FIXTURE_CLASSES);

        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains(FIXTURE_PACKAGE + ".notification.application.RogueService")
                .doesNotContain(FIXTURE_PACKAGE + ".notification.application.worker.SystemWorker");
    }

    /**
     * FF-02: no class of {@code notification} depends on a class of the application outside
     * {@code notification} and {@code shared}. Expressed as an allow-list, so a module added later
     * is forbidden too. Libraries and the JDK are outside the base package and unaffected.
     */
    private static ArchRule notificationDependsOnlyOnShared(String basePackage) {
        DescribedPredicate<JavaClass> otherApplicationModule = resideInAPackage(basePackage + "..")
                .and(not(resideInAnyPackage(basePackage + ".notification..", basePackage + ".shared..")))
                .as("classes of the application outside notification and shared");

        return noClasses()
                .that()
                .resideInAPackage(basePackage + ".notification..")
                .should()
                .dependOnClassesThat(otherApplicationModule)
                .because("SDD V04 R3 (REQ-MAINT-002): notification learns about users and workspaces"
                        + " only through outbox events")
                .allowEmptyShould(true);
    }

    /**
     * FF-03: a call to, or a reference to, {@link TenantTransactions#system} outside {@code
     * notification.application.worker}. A system-context transaction bypasses row-level security
     * across tenants (SDD V07), so the set of callers is kept as small as possible.
     */
    private static ArchRule onlyTheWorkerOpensSystemTransactions(String basePackage) {
        DescribedPredicate<JavaAccess<?>> opensASystemContext = DescribedPredicate.describe(
                "TenantTransactions.system(..)",
                access -> access.getTargetOwner().isEquivalentTo(TenantTransactions.class)
                        && access.getName().equals("system"));

        return noClasses()
                .that()
                .resideOutsideOfPackage(basePackage + ".notification.application.worker..")
                .should()
                .accessTargetWhere(opensASystemContext)
                .because("SDD V04 R5: only the notification worker may open a cross-tenant transaction")
                .allowEmptyShould(true);
    }
}
