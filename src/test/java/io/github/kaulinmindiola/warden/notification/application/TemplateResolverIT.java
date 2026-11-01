package io.github.kaulinmindiola.warden.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.notification.domain.Template;
import io.github.kaulinmindiola.warden.notification.domain.TemplateKey;
import io.github.kaulinmindiola.warden.notification.domain.TemplateRenderer;
import io.github.kaulinmindiola.warden.notification.infrastructure.NotificationTemplateRepository;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The seeded global templates and their resolution (REQ-FUNC-052 as reduced for release 1.0,
 * REQ-SEC-013), read as the application role. Rows added by this test use keys starting with
 * {@code test.}, which no release key has, and are seeded as the superuser because the
 * application role can only read templates.
 */
class TemplateResolverIT extends IntegrationTest {

    /** PostgreSQL SQLSTATE for insufficient_privilege ("permission denied"). */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    private TemplateResolver resolver;

    @Autowired
    private NotificationTemplateRepository repository;

    @Autowired
    private TemplateRenderer renderer;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void everyReleaseKeyIsSeededInSpanishAndEnglishAtVersionOne() {
        Map<String, List<String>> localesByKey = releaseTemplates().stream()
                .collect(Collectors.groupingBy(
                        Template::key, Collectors.mapping(Template::locale, Collectors.toList())));

        assertThat(localesByKey.keySet())
                .containsExactlyInAnyOrderElementsOf(List.of(TemplateKey.values()).stream()
                        .map(TemplateKey::key)
                        .toList());
        assertThat(localesByKey.values())
                .allSatisfy(locales -> assertThat(locales).containsExactlyInAnyOrder("es", "en"));
        assertThat(releaseTemplates())
                .allSatisfy(template -> assertThat(template.version()).isEqualTo(1));
    }

    @Test
    @Verifies("REQ-SEC-013")
    void everySeededTemplatePassesValidationAndUsesEveryDeclaredVariable() {
        assertThat(releaseTemplates()).allSatisfy(template -> {
            assertThatCode(() -> renderer.validate(template.key(), template.subject(), template.body()))
                    .as("%s/%s", template.key(), template.locale())
                    .doesNotThrowAnyException();
            TemplateKey key = TemplateKey.fromKey(template.key()).orElseThrow();
            assertThat(key.variables())
                    .as("%s/%s", template.key(), template.locale())
                    .allSatisfy(variable ->
                            assertThat(template.subject() + template.body()).contains("{{" + variable + "}}"));
        });
    }

    @Test
    @Verifies("REQ-FUNC-052")
    void eachLocaleResolvesToItsOwnGlobalTemplate() {
        Template spanish = resolver.resolve(TemplateKey.WORKSPACE_INVITATION, "es");
        Template english = resolver.resolve(TemplateKey.WORKSPACE_INVITATION, "en");

        assertThat(spanish.locale()).isEqualTo("es");
        assertThat(spanish.subject()).startsWith("Te invitaron");
        assertThat(english.locale()).isEqualTo("en");
        assertThat(english.subject()).startsWith("You are invited");
    }

    @Test
    @Verifies("REQ-FUNC-052")
    void aKeyWithoutAnEnglishTemplateFallsBackToSpanish() {
        String key = testKey();
        seedGlobal(key, "es", 1, "Asunto", "Cuerpo");

        Template resolved = repository.findGlobal(key, "en").orElseThrow();

        assertThat(resolved.locale()).isEqualTo("es");
        assertThat(resolved.subject()).isEqualTo("Asunto");
    }

    @Test
    void theLatestVersionOfTheLocaleIsChosen() {
        String key = testKey();
        seedGlobal(key, "en", 1, "First", "Body");
        seedGlobal(key, "en", 2, "Second", "Body");
        seedGlobal(key, "es", 3, "Tercera", "Cuerpo");

        Template resolved = repository.findGlobal(key, "en").orElseThrow();

        assertThat(resolved.version()).isEqualTo(2);
        assertThat(resolved.subject()).isEqualTo("Second");
    }

    @Test
    void anUnsupportedLocaleIsRejected() {
        assertThatThrownBy(() -> resolver.resolve(TemplateKey.WORKSPACE_INVITATION, "fr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported locale: fr");
    }

    @Test
    void theApplicationRoleCanOnlyReadTemplates() {
        JdbcClient app = JdbcClient.create(dataSource);

        assertInsufficientPrivilege(() -> app.sql("""
                        INSERT INTO notification_template (id, scope, template_key, locale, version, subject, body)
                        VALUES (gen_random_uuid(), 'GLOBAL', 'test.forbidden', 'es', 1, 's', 'b')
                        """).update());
        assertInsufficientPrivilege(() ->
                app.sql("UPDATE notification_template SET subject = 'changed'").update());
        assertInsufficientPrivilege(
                () -> app.sql("DELETE FROM notification_template").update());
    }

    @Test
    void workspaceTemplatesAreVisibleOnlyInTheirWorkspaceOrToTheWorkers() {
        String key = testKey();
        UUID workspace = UUID.randomUUID();
        superuser().sql("""
                        INSERT INTO notification_template
                            (id, scope, workspace_id, template_key, locale, version, subject, body)
                        VALUES (gen_random_uuid(), 'WORKSPACE', ?, ?, 'es', 1, 'Asunto', 'Cuerpo')
                        """).param(workspace).param(key).update();

        assertThat(visibleRows(key, null, null)).as("no context").isZero();
        assertThat(visibleRows(key, "app.workspace_id", UUID.randomUUID().toString()))
                .as("another workspace")
                .isZero();
        assertThat(visibleRows(key, "app.workspace_id", workspace.toString()))
                .as("its workspace")
                .isEqualTo(1);
        assertThat(visibleRows(key, "app.system_context", "on"))
                .as("system context")
                .isEqualTo(1);
    }

    /**
     * The statement fails with PostgreSQL's {@code insufficient_privilege}. Spring's exception
     * message does not carry the driver's, so the SQLSTATE of the root cause is checked.
     */
    private static void assertInsufficientPrivilege(ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .asInstanceOf(InstanceOfAssertFactories.type(SQLException.class))
                .extracting(SQLException::getSQLState)
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    /** Rows of the key the application role sees, with one transaction-local setting applied. */
    private long visibleRows(String key, String setting, String value) {
        JdbcClient app = JdbcClient.create(dataSource);
        return new TransactionTemplate(transactionManager).execute(status -> {
            if (setting != null) {
                app.sql("SELECT set_config(?, ?, true)")
                        .param(setting)
                        .param(value)
                        .query(String.class)
                        .single();
            }
            return app.sql("SELECT count(*) FROM notification_template WHERE template_key = ?")
                    .param(key)
                    .query(Long.class)
                    .single();
        });
    }

    private List<Template> releaseTemplates() {
        return repository.findAllGlobal().stream()
                .filter(template -> !template.key().startsWith("test."))
                .toList();
    }

    private static void seedGlobal(String key, String locale, int version, String subject, String body) {
        superuser()
                .sql("""
                        INSERT INTO notification_template (id, scope, template_key, locale, version, subject, body)
                        VALUES (gen_random_uuid(), 'GLOBAL', ?, ?, ?, ?, ?)
                        """)
                .param(key)
                .param(locale)
                .param(version)
                .param(subject)
                .param(body)
                .update();
    }

    private static String testKey() {
        return "test." + UUID.randomUUID();
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
