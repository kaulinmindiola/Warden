package io.github.kaulinmindiola.warden.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.notification.domain.RenderedMessage;
import io.github.kaulinmindiola.warden.notification.domain.Template;
import io.github.kaulinmindiola.warden.notification.domain.TemplateValidationException;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Validation and literal rendering of templates (REQ-SEC-013, SDD V14-A6). */
class PlaceholderRendererTest {

    private static final String INVITATION = "workspace.invitation";

    private final PlaceholderRenderer renderer = new PlaceholderRenderer();

    @Test
    void replacesEveryPlaceholderOfSubjectAndBody() {
        Template template = template("Join {{workspaceName}}", "Role {{ role }}, link {{invitationLink}}, {{role}}");

        RenderedMessage message = renderer.render(
                template, Map.of("workspaceName", "Acme", "role", "MEMBER", "invitationLink", "https://x/#t"));

        assertThat(message.subject()).isEqualTo("Join Acme");
        assertThat(message.body()).isEqualTo("Role MEMBER, link https://x/#t, MEMBER");
    }

    @Test
    @Verifies("REQ-SEC-013")
    void aValueThatLooksLikeAPlaceholderIsInsertedLiterally() {
        Template template = template("Join {{workspaceName}}", "{{workspaceName}}: {{invitationLink}}");

        RenderedMessage message = renderer.render(
                template, Map.of("workspaceName", "{{invitationLink}}", "invitationLink", "https://x/#secret"));

        assertThat(message.subject()).isEqualTo("Join {{invitationLink}}");
        assertThat(message.body()).isEqualTo("{{invitationLink}}: https://x/#secret");
    }

    @Test
    @Verifies("REQ-SEC-013")
    void aWorkspaceNamedTokenIsRenderedLiterally() {
        Template template = template("Join {{workspaceName}}", "Workspace {{workspaceName}}");

        RenderedMessage message = renderer.render(template, Map.of("workspaceName", "{{token}}"));

        assertThat(message.subject()).isEqualTo("Join {{token}}");
        assertThat(message.body()).isEqualTo("Workspace {{token}}");
    }

    @Test
    void replacementCharactersOfRegularExpressionsAreInsertedLiterally() {
        Template template = template("{{workspaceName}}", "{{workspaceName}}");

        RenderedMessage message = renderer.render(template, Map.of("workspaceName", "$1 \\ $0 ${x}"));

        assertThat(message.body()).isEqualTo("$1 \\ $0 ${x}");
    }

    @Test
    void aVariableWithoutValueIsAnError() {
        Template template = template("Join {{workspaceName}}", "Role {{role}}");

        assertThatThrownBy(() -> renderer.render(template, Map.of("workspaceName", "Acme")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No value for template variable role");
    }

    @Test
    void textWithoutPlaceholdersIsLeftUnchanged() {
        Template template = template("Plain subject", "Plain body with { single } braces");

        RenderedMessage message = renderer.render(template, Map.of());

        assertThat(message.subject()).isEqualTo("Plain subject");
        assertThat(message.body()).isEqualTo("Plain body with { single } braces");
    }

    @Test
    void theRenderedMessageNeverPrintsItsContent() {
        RenderedMessage message = new RenderedMessage("subject", "https://x/#secret");

        assertThat(message.toString()).doesNotContain("secret").doesNotContain("subject");
    }

    @Test
    void aTemplateWithDeclaredVariablesOnlyIsValid() {
        assertThatCode(() -> renderer.validate(
                        INVITATION,
                        "Join {{workspaceName}}",
                        "{{ role }} until {{expiresAt}}: {{invitationLink}} { not a placeholder }"))
                .doesNotThrowAnyException();
    }

    @Test
    @Verifies("REQ-SEC-013")
    void anUndeclaredVariableIsRejectedInBodyAndSubject() {
        assertThatThrownBy(() -> renderer.validate(INVITATION, "Hi {{password}}", "Token {{token}} {{role}}"))
                .isInstanceOfSatisfying(
                        TemplateValidationException.class,
                        failure -> assertThat(failure.violations())
                                .containsExactly(
                                        "subject: undeclared variables [password]",
                                        "body: undeclared variables [token]"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{{#if role}}admin{{/if}}",
                "{{> partial}}",
                "{{workspace.name}}",
                "{{role | upper}}",
                "{{ role role }}",
                "{{}}",
                "{{1role}}",
                "unclosed {{role",
                "stray }} braces",
                "${role} {{role}} }}"
            })
    @Verifies("REQ-SEC-013")
    void anythingButAPlaceholderBetweenDoubleBracesIsRejected(String body) {
        assertThatThrownBy(() -> renderer.validate(INVITATION, "Subject", body))
                .isInstanceOfSatisfying(
                        TemplateValidationException.class,
                        failure -> assertThat(failure.violations())
                                .anySatisfy(violation -> assertThat(violation).startsWith("body: ")));
    }

    @Test
    void anUnknownKeyIsRejected() {
        assertThatThrownBy(() -> renderer.validate("security.new-device", "Subject", "Body"))
                .isInstanceOfSatisfying(
                        TemplateValidationException.class,
                        failure -> assertThat(failure.violations()).containsExactly("key: unknown template key"));
    }

    @Test
    void violationsNeverQuoteTheTemplateText() {
        assertThatThrownBy(() -> renderer.validate(INVITATION, "Subject", "secret-value {{#each}}"))
                .isInstanceOf(TemplateValidationException.class)
                .hasMessageNotContaining("secret-value");
    }

    private static Template template(String subject, String body) {
        return new Template(INVITATION, "es", 1, subject, body);
    }
}
