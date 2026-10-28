package io.github.kaulinmindiola.warden.workspace.domain;

import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * An invitation of an e-mail address to a workspace (SDD V06, V15). Only the SHA-256 of its
 * single-use token is kept. It grants ADMIN, MEMBER or VIEWER, never OWNER (BR-008), and expires
 * after 7 days (REQ-FUNC-031); expiry is derived, not stored.
 */
@Entity
@Table(name = "invitation")
@Filter(name = TenantTransactions.TENANT_FILTER)
public class Invitation {

    /** How long a link stays valid (REQ-FUNC-031). */
    public static final Duration LIFETIME = Duration.ofDays(7);

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "email_normalized", nullable = false, updatable = false)
    private String email;

    @Column(name = "role", nullable = false, updatable = false)
    private String role;

    @Column(name = "locale", nullable = false, updatable = false)
    private String locale;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InvitationStatus status;

    @Column(name = "invited_by", nullable = false, updatable = false)
    private UUID invitedBy;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected Invitation() {}

    /**
     * A new pending invitation that expires {@link #LIFETIME} after {@code now}.
     *
     * @throws IllegalArgumentException if the role is OWNER or the hash is not 32 bytes
     */
    public static Invitation issue(
            UUID id,
            UUID workspaceId,
            String normalizedEmail,
            Role role,
            String locale,
            byte[] tokenHash,
            UUID invitedBy,
            Instant now) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(now, "now");
        if (role == Role.OWNER) {
            throw new IllegalArgumentException("OWNER cannot be granted by invitation");
        }
        if (tokenHash.length != 32) {
            throw new IllegalArgumentException("The token hash must be 32 bytes");
        }
        Invitation invitation = new Invitation();
        invitation.id = Objects.requireNonNull(id, "id");
        invitation.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        invitation.email = Objects.requireNonNull(normalizedEmail, "normalizedEmail");
        invitation.role = role.name();
        invitation.locale = Objects.requireNonNull(locale, "locale");
        invitation.tokenHash = tokenHash.clone();
        invitation.status = InvitationStatus.PENDING;
        invitation.invitedBy = Objects.requireNonNull(invitedBy, "invitedBy");
        invitation.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        invitation.expiresAt = invitation.createdAt.plus(LIFETIME);
        return invitation;
    }

    /** Whether the link can still be used at {@code now}: pending and not expired. */
    public boolean isPending(Instant now) {
        Objects.requireNonNull(now, "now");
        return status == InvitationStatus.PENDING && now.isBefore(expiresAt);
    }

    /**
     * Revokes a usable invitation (REQ-FUNC-035).
     *
     * @throws IllegalStateException if it is not pending or has expired
     */
    public void revoke(Instant now) {
        requirePending(now);
        status = InvitationStatus.REVOKED;
    }

    /**
     * Accepts a usable invitation (REQ-FUNC-036).
     *
     * @throws IllegalStateException if it is not pending or has expired
     */
    public void accept(Instant now) {
        requirePending(now);
        status = InvitationStatus.ACCEPTED;
    }

    /**
     * Marks a stored-pending invitation as replaced by a new one for the same address
     * (REQ-FUNC-032). Expired invitations are replaced too, which frees their pending slot.
     *
     * @throws IllegalStateException if it is not stored as pending
     */
    public void replace() {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException("Only a pending invitation can be replaced");
        }
        status = InvitationStatus.REPLACED;
    }

    private void requirePending(Instant now) {
        if (!isPending(now)) {
            throw new IllegalStateException("The invitation is not pending");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public String email() {
        return email;
    }

    public Role role() {
        return Role.fromStored(role).orElseThrow(() -> new IllegalStateException("Unknown invitation role"));
    }

    public String locale() {
        return locale;
    }

    public InvitationStatus status() {
        return status;
    }

    public UUID invitedBy() {
        return invitedBy;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
