package io.github.kaulinmindiola.warden.notes.domain;

import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A note of a workspace (SDD V06). Tenant-scoped: the Hibernate filter {@code tenant} and Row Level
 * Security restrict it to the workspace of the current transaction (V07). The version increases
 * on every edit and rejects edits based on a stale copy (REQ-FUNC-045).
 */
@Entity
@Table(name = "note")
@Filter(name = TenantTransactions.TENANT_FILTER)
public class Note {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For JPA only. */
    protected Note() {}

    private Note(UUID id, UUID workspaceId, NoteContent content, UUID authorId, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.title = Objects.requireNonNull(content, "content").title();
        this.body = content.body();
        this.authorId = Objects.requireNonNull(authorId, "authorId");
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A new note; its times are truncated to the database precision. */
    public static Note create(UUID id, UUID workspaceId, NoteContent content, UUID authorId, Instant now) {
        Objects.requireNonNull(now, "now");
        return new Note(id, workspaceId, content, authorId, now.truncatedTo(ChronoUnit.MICROS));
    }

    /** Replaces the title and body; the version increases when the change is flushed. */
    public void edit(NoteContent content, Instant now) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(now, "now");
        this.title = content.title();
        this.body = content.body();
        this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public UUID id() {
        return id;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public UUID authorId() {
        return authorId;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
