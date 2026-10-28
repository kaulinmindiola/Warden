package io.github.kaulinmindiola.warden.workspace.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceTest {

    @Test
    @Verifies({"REQ-FUNC-029", "BR-012"})
    void deletingIsLogicalAndHappensOnlyOnce() {
        Instant now = Instant.parse("2026-10-28T10:00:00.123456789Z");
        Workspace workspace = Workspace.create(UUID.randomUUID(), WorkspaceName.of("Team"), UUID.randomUUID(), now);

        assertThat(workspace.isDeleted()).isFalse();
        workspace.delete(now);

        assertThat(workspace.isDeleted()).isTrue();
        assertThat(workspace.deletedAt()).isEqualTo(Instant.parse("2026-10-28T10:00:00.123456Z"));
        assertThat(workspace.name()).isEqualTo("Team");
        assertThatThrownBy(() -> workspace.delete(now.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThat(workspace.deletedAt()).isEqualTo(Instant.parse("2026-10-28T10:00:00.123456Z"));
    }
}
