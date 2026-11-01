package io.github.kaulinmindiola.warden.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.notification.domain.LinkType;
import io.github.kaulinmindiola.warden.notification.domain.TemplateKey;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Single-use links carry the token only in the fragment (REQ-INT-009). */
class LinkBuilderTest {

    private static final String TOKEN = "wot_Q2hhbmdlTWVQbGVhc2VUaGlzSXNOb3RBUmVhbFRva2Vu";

    private final LinkBuilder builder = new LinkBuilder(new LinkProperties(
            URI.create("https://app.example.test/verify"),
            URI.create("https://app.example.test/auth/reset-password"),
            URI.create("https://app.example.test/invitations/accept")));

    @Test
    @Verifies("REQ-INT-009")
    void eachLinkTypeStartsWithItsConfiguredBaseUrl() {
        assertThat(builder.build(LinkType.EMAIL_VERIFICATION, TOKEN))
                .isEqualTo("https://app.example.test/verify#token=" + TOKEN);
        assertThat(builder.build(LinkType.PASSWORD_RESET, TOKEN))
                .isEqualTo("https://app.example.test/auth/reset-password#token=" + TOKEN);
        assertThat(builder.build(LinkType.INVITATION, TOKEN))
                .isEqualTo("https://app.example.test/invitations/accept#token=" + TOKEN);
    }

    @ParameterizedTest
    @EnumSource(LinkType.class)
    @Verifies("REQ-INT-009")
    void theTokenIsOnlyInTheFragment(LinkType type) {
        String link = builder.build(type, TOKEN);
        URI uri = URI.create(link);

        assertThat(link.substring(0, link.indexOf('#'))).doesNotContain(TOKEN);
        assertThat(uri.getRawPath()).doesNotContain(TOKEN);
        assertThat(uri.getRawQuery()).isNull();
        assertThat(uri.getRawFragment()).isEqualTo("token=" + TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "with space", "a+b", "a/b", "a=b", "a&b", "a#b", "a.b"})
    void aTokenThatIsNotBase64urlIsRejectedWithoutEchoingIt(String token) {
        assertThatThrownBy(() -> builder.build(LinkType.PASSWORD_RESET, token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A link token must be a non-empty Base64url string");
    }

    @Test
    void aMissingTokenIsRejected() {
        assertThatThrownBy(() -> builder.build(LinkType.PASSWORD_RESET, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Verifies("REQ-INT-009")
    void theLinkVariableIsTheOneTheTemplateDeclares() {
        assertThat(builder.linkVariable(TemplateKey.ACCOUNT_PASSWORD_RESET, TOKEN))
                .containsExactlyEntriesOf(
                        Map.of("resetLink", "https://app.example.test/auth/reset-password#token=" + TOKEN));
        assertThat(builder.linkVariable(TemplateKey.WORKSPACE_ROLE_CHANGED, null))
                .isEmpty();
    }

    @Test
    void aTemplateWithALinkNeedsAToken() {
        assertThatThrownBy(() -> builder.linkVariable(TemplateKey.WORKSPACE_INVITATION, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(TemplateKey.class)
    void everyTemplateLinkVariableHasALinkTypeAndNoOther(TemplateKey key) {
        String declared = key.variables().stream()
                .filter(name -> name.endsWith("Link"))
                .findFirst()
                .orElse(null);

        assertThat(LinkType.of(key).map(LinkType::variable).orElse(null)).isEqualTo(declared);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/reset-password",
                "ftp://app.example.test/reset",
                "https://app.example.test/reset?next=home",
                "https://app.example.test/reset#section",
                "mailto:someone@example.test"
            })
    void aBaseUrlMustBeAnAbsoluteHttpUrlWithoutQueryOrFragment(String base) {
        URI valid = URI.create("http://localhost:3001/verify-email");

        assertThatThrownBy(() -> new LinkProperties(valid, URI.create(base), valid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("warden.notification.links.reset-password");
    }
}
