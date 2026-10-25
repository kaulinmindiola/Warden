package io.github.kaulinmindiola.warden.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEntryTest {

    @Test
    void anEntryWithoutDetailsHasAnEmptyMap() {
        AuditEntry entry = new AuditEntry(AuditAction.LOGIN, UUID.randomUUID(), null, null);

        assertThat(entry.details()).isEmpty();
        assertThat(AuditEntry.of(AuditAction.LOGOUT, null, null).details()).isEmpty();
    }

    @Test
    void detailsAreAnImmutableCopy() {
        Map<String, String> details = new HashMap<>();
        details.put("reason", "wrong_password");

        AuditEntry entry = new AuditEntry(AuditAction.LOGIN, null, null, details);
        details.put("reason", "changed");

        assertThat(entry.details()).containsExactly(Map.entry("reason", "wrong_password"));
        assertThatThrownBy(() -> entry.details().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDetailsThatLookLikeTokens() {
        for (String secret : new String[] {"wrt_abc", "wot_abc", "Bearer eyJhbGciOiJFUzI1NiJ9"}) {
            assertThatThrownBy(() -> new AuditEntry(AuditAction.LOGIN, null, null, Map.of("value", secret)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Audit detail 'value' looks like a token; audit records must not contain secrets")
                    .message()
                    .doesNotContain(secret);
        }
    }

    @Test
    void requiresAnAction() {
        assertThatThrownBy(() -> AuditEntry.of(null, null, null)).isInstanceOf(NullPointerException.class);
    }
}
