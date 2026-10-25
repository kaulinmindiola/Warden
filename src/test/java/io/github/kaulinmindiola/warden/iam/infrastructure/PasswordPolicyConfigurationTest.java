package io.github.kaulinmindiola.warden.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.iam.domain.InvalidPasswordException;
import io.github.kaulinmindiola.warden.iam.domain.PasswordPolicy;
import io.github.kaulinmindiola.warden.support.Verifies;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PasswordPolicyConfigurationTest {

    @Test
    @Verifies("REQ-FUNC-003")
    void theBundledListRejectsCommonPasswords() {
        PasswordPolicy policy = new PasswordPolicyConfiguration().passwordPolicy();

        assertThat(policy.commonPasswordCount()).isEqualTo(1_195);
        for (String common : new String[] {"password1234", "Qwerty123456", "PASSWORD123456789"}) {
            assertThatThrownBy(() -> policy.check(common))
                    .as(common)
                    .isInstanceOfSatisfying(
                            InvalidPasswordException.class,
                            exception -> assertThat(exception.reason())
                                    .isEqualTo(InvalidPasswordException.Reason.TOO_COMMON));
        }
        assertThatCode(() -> policy.check("correct horse battery staple")).doesNotThrowAnyException();
    }

    @Test
    void aMissingListFailsWithAClearMessage() {
        assertThatThrownBy(() ->
                        PasswordPolicyConfiguration.loadCommonPasswords(new ClassPathResource("security/missing.txt")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Cannot read the common-password list");
    }
}
