package io.github.kaulinmindiola.warden.workspace.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.support.Verifies;
import org.junit.jupiter.api.Test;

class WorkspaceNameTest {

    /** U+1D538, a character outside the Basic Multilingual Plane: two UTF-16 units, one code point. */
    private static final String DOUBLE_STRUCK_A = "\uD835\uDD38";

    @Test
    @Verifies("REQ-FUNC-027")
    void oneToOneHundredCharactersAreAcceptedAfterStripping() {
        assertThat(WorkspaceName.of("  Acme  ").value()).isEqualTo("Acme");
        assertThat(WorkspaceName.of("a").value()).isEqualTo("a");
        assertThat(WorkspaceName.of("a".repeat(100)).value()).hasSize(100);
    }

    @Test
    @Verifies("REQ-FUNC-027")
    void emptyBlankAndTooLongNamesAreRejected() {
        for (String invalid : new String[] {"", "   ", "a".repeat(101)}) {
            assertThatThrownBy(() -> WorkspaceName.of(invalid))
                    .as("name of %d characters", invalid.length())
                    .isInstanceOf(InvalidWorkspaceNameException.class);
        }
    }

    @Test
    void lengthIsCountedInCodePointsLikePostgres() {
        assertThat(WorkspaceName.of(DOUBLE_STRUCK_A.repeat(100)).value()).hasSize(200);
        assertThatThrownBy(() -> WorkspaceName.of(DOUBLE_STRUCK_A.repeat(101)))
                .isInstanceOf(InvalidWorkspaceNameException.class);
    }
}
