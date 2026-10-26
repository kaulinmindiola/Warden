package io.github.kaulinmindiola.warden.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.Verifies;
import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    @Verifies("REQ-SEC-011")
    void rolesAreOrderedFromViewerToOwner() {
        assertThat(Role.values()).containsExactly(Role.VIEWER, Role.MEMBER, Role.ADMIN, Role.OWNER);
        assertThat(Role.VIEWER.isAtLeast(Role.VIEWER)).isTrue();
        assertThat(Role.VIEWER.isAtLeast(Role.MEMBER)).isFalse();
        assertThat(Role.ADMIN.isAtLeast(Role.MEMBER)).isTrue();
        assertThat(Role.ADMIN.isAtLeast(Role.OWNER)).isFalse();
        assertThat(Role.OWNER.isAtLeast(Role.ADMIN)).isTrue();
    }

    @Test
    @Verifies("REQ-SEC-011")
    void storedNamesMapToRolesAndUnknownOnesToNoRole() {
        assertThat(Role.fromStored("ADMIN")).contains(Role.ADMIN);
        assertThat(Role.fromStored("admin")).isEmpty();
        assertThat(Role.fromStored("SUPERUSER")).isEmpty();
        assertThat(Role.fromStored("")).isEmpty();
        assertThat(Role.fromStored(null)).isEmpty();
    }
}
