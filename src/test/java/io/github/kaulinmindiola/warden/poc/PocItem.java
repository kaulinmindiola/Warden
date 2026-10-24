package io.github.kaulinmindiola.warden.poc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Throwaway entity used only by POC-001 to prove persistence with Hibernate on PostgreSQL 17. */
@Entity
@Table(name = "poc_item", schema = "poc")
public class PocItem {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    protected PocItem() {}

    public PocItem(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
