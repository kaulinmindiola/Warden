package io.github.kaulinmindiola.warden.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The template catalog of release 1.0 against SRS appendix B. */
class TemplateKeyTest {

    @Test
    void theCatalogHasTheSevenKeysOfRelease10() {
        assertThat(Arrays.stream(TemplateKey.values()).map(TemplateKey::key))
                .containsExactlyInAnyOrder(
                        "security.refresh-reuse",
                        "account.password-reset",
                        "account.email-verification",
                        "account.registration-attempt",
                        "account.password-changed",
                        "workspace.invitation",
                        "workspace.role-changed");
    }

    @Test
    void categoriesScopesAndVariablesFollowAppendixB() {
        assertThat(TemplateKey.WORKSPACE_INVITATION.category()).isEqualTo(NotificationCategory.TRANSACTIONAL);
        assertThat(TemplateKey.WORKSPACE_INVITATION.scope()).isEqualTo(NotificationScope.WORKSPACE);
        assertThat(TemplateKey.WORKSPACE_INVITATION.variables())
                .containsExactlyInAnyOrder("workspaceName", "role", "invitationLink", "expiresAt");
        assertThat(TemplateKey.WORKSPACE_ROLE_CHANGED.category()).isEqualTo(NotificationCategory.INFORMATIONAL);
        assertThat(TemplateKey.WORKSPACE_ROLE_CHANGED.variables())
                .containsExactlyInAnyOrder("workspaceName", "affectedMember", "previousRole", "newRole");
        assertThat(TemplateKey.ACCOUNT_PASSWORD_RESET.variables()).containsExactlyInAnyOrder("resetLink", "expiresAt");
        assertThat(TemplateKey.ACCOUNT_EMAIL_VERIFICATION.variables())
                .containsExactlyInAnyOrder("verificationLink", "expiresAt");
        assertThat(Arrays.stream(TemplateKey.values())
                        .filter(key -> key.scope() == NotificationScope.SYSTEM)
                        .map(TemplateKey::category))
                .containsOnly(NotificationCategory.SECURITY);
    }

    @Test
    void keysAreFoundByTheirStoredName() {
        assertThat(TemplateKey.fromKey("workspace.invitation")).contains(TemplateKey.WORKSPACE_INVITATION);
        assertThat(TemplateKey.fromKey("security.new-device")).isEmpty();
    }
}
