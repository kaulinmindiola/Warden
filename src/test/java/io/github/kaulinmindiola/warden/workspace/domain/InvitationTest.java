package io.github.kaulinmindiola.warden.workspace.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The invitation lifecycle of SDD V15, with expiry derived from the clock. */
class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00.123456Z");

    @Test
    @Verifies({"REQ-FUNC-031", "BR-008"})
    void aNewInvitationIsPendingFor7DaysAndNeverGrantsOwner() {
        Invitation invitation = issue(Role.MEMBER);

        assertThat(invitation.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(invitation.isPending(invitation.expiresAt().minusNanos(1000)))
                .isTrue();
        assertThat(invitation.isPending(invitation.expiresAt())).isFalse();
        assertThatThrownBy(() -> issue(Role.OWNER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Verifies("REQ-FUNC-035")
    void aPendingInvitationCanBeRevokedOnce() {
        Invitation invitation = issue(Role.VIEWER);

        invitation.revoke(NOW);

        assertThat(invitation.status()).isEqualTo(InvitationStatus.REVOKED);
        assertThatThrownBy(() -> invitation.revoke(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invitation.accept(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiredInvitationCannotBeRevokedOrAccepted() {
        Invitation invitation = issue(Role.ADMIN);
        Instant afterExpiry = invitation.expiresAt().plusSeconds(1);

        assertThatThrownBy(() -> invitation.revoke(afterExpiry)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invitation.accept(afterExpiry)).isInstanceOf(IllegalStateException.class);
        assertThat(invitation.status()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    @Verifies("REQ-FUNC-032")
    void anInvitationStoredAsPendingIsReplacedEvenAfterExpiry() {
        Invitation expired = issue(Role.MEMBER);

        expired.replace();

        assertThat(expired.status()).isEqualTo(InvitationStatus.REPLACED);
        assertThatThrownBy(expired::replace).isInstanceOf(IllegalStateException.class);
        Invitation accepted = issue(Role.MEMBER);
        accepted.accept(NOW);
        assertThatThrownBy(accepted::replace).isInstanceOf(IllegalStateException.class);
    }

    private static Invitation issue(Role role) {
        return Invitation.issue(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "guest@mail.com",
                role,
                "es",
                new byte[32],
                UUID.randomUUID(),
                NOW);
    }
}
