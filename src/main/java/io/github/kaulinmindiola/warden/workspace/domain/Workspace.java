package io.github.kaulinmindiola.warden.workspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * A workspace (SDD V06). Deleting it is logical: a deleted workspace is kept but behaves as
 * non-existent for everyone (BR-012).
 */
@Entity
@Table(name = "workspace")
public class Workspace {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** For JPA only. */
    protected Workspace() {}

    private Workspace(UUID id, WorkspaceName name, UUID createdBy, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name").value();
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** A new workspace; its creation time is truncated to the database precision. */
    public static Workspace create(UUID id, WorkspaceName name, UUID createdBy, Instant now) {
        Objects.requireNonNull(now, "now");
        return new Workspace(id, name, createdBy, now.truncatedTo(ChronoUnit.MICROS));
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant deletedAt() {
        return deletedAt;
    }
}
