package io.github.kaulinmindiola.warden.workspace.domain;

import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * Membership of a user in a workspace (SDD V06). Tenant-scoped: the Hibernate filter {@code
 * tenant} and Row Level Security restrict it to the current workspace or to the caller's own rows
 * (V07). The role is kept as stored; an unknown value means no role (REQ-SEC-011).
 */
@Entity
@Table(name = "workspace_member")
@IdClass(WorkspaceMember.Key.class)
@Filter(name = TenantTransactions.TENANT_FILTER)
public class WorkspaceMember {

    @Id
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    /** For JPA only. */
    protected WorkspaceMember() {}

    private WorkspaceMember(UUID workspaceId, UUID userId, Role role, Instant joinedAt) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.role = Objects.requireNonNull(role, "role").name();
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
    }

    /** A new membership; the join time is truncated to the database precision. */
    public static WorkspaceMember join(UUID workspaceId, UUID userId, Role role, Instant now) {
        Objects.requireNonNull(now, "now");
        return new WorkspaceMember(workspaceId, userId, role, now.truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Replaces the role. Authority rules and the last-OWNER guard are enforced by the caller, which
     * holds the workspace lock (SDD V14-A4).
     */
    public void changeRole(Role newRole) {
        this.role = Objects.requireNonNull(newRole, "newRole").name();
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public UUID userId() {
        return userId;
    }

    /** The role, or empty if the stored value is unknown. */
    public Optional<Role> role() {
        return Role.fromStored(role);
    }

    public Instant joinedAt() {
        return joinedAt;
    }

    /** Composite identifier: workspace and user. */
    public static class Key implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private UUID workspaceId;
        private UUID userId;

        /** For JPA only. */
        protected Key() {}

        public Key(UUID workspaceId, UUID userId) {
            this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
            this.userId = Objects.requireNonNull(userId, "userId");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(workspaceId, key.workspaceId)
                    && Objects.equals(userId, key.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workspaceId, userId);
        }
    }
}
