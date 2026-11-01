package io.github.kaulinmindiola.warden.notification.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.kaulinmindiola.warden.notification.domain.NotificationRecipient;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The payloads of SDD V10 as the notification module reads them. The module never imports the
 * event classes of {@code iam} or {@code workspace} (REQ-MAINT-002), so it declares only the fields
 * it consumes and ignores the rest.
 */
final class EventPayloads {

    private EventPayloads() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Recipient(UUID userId, String email, String fallbackLocale) {

        NotificationRecipient toRecipient() {
            return new NotificationRecipient(userId, email, fallbackLocale);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Secret(String ciphertext, String keyId, Instant expiresAt) {

        SealedSecret toSealedSecret() {
            return new SealedSecret(ciphertext, keyId, expiresAt);
        }
    }

    /** {@code iam.EmailVerificationRequested} and {@code iam.PasswordResetRequested}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountLink(Recipient recipient, Secret secret, Instant expiresAt) {}

    /**
     * {@code iam.RegistrationAttemptedOnExistingAccount}, {@code iam.PasswordChanged} and {@code
     * iam.RefreshTokenReuseDetected}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountNotice(Recipient recipient, Instant occurredAt) {}

    /** {@code workspace.MemberInvited}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Invitation(
            UUID invitationId,
            String workspaceName,
            String role,
            Recipient recipient,
            Secret secret,
            Instant expiresAt) {}

    /** {@code workspace.MemberRoleChanged}: the owners are those at the time of the change. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoleChanged(
            String workspaceName, Recipient affected, List<Recipient> owners, String previousRole, String newRole) {}
}
