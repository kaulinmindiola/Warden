package io.github.kaulinmindiola.warden.iam.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private static final String EMOJI = "\uD83D\uDE00";

    private final PasswordPolicy policy = new PasswordPolicy(Set.of("password1234", "qwerty123456"));

    @Test
    @Verifies("REQ-FUNC-003")
    void elevenCharactersAreTooShort() {
        assertRejected(() -> policy.check("abcdefghijk"), InvalidPasswordException.Reason.TOO_SHORT);
    }

    @Test
    @Verifies("REQ-FUNC-003")
    void twelveLowerCaseLettersWithoutDigitsOrSymbolsAreAccepted() {
        assertThatCode(() -> policy.check("abcdefghijkl")).doesNotThrowAnyException();
    }

    @Test
    void oneHundredTwentyEightCharactersAreAcceptedAndOneMoreIsTooLong() {
        assertThatCode(() -> policy.check("a".repeat(128))).doesNotThrowAnyException();
        assertRejected(() -> policy.check("a".repeat(129)), InvalidPasswordException.Reason.TOO_LONG);
    }

    @Test
    @Verifies("REQ-FUNC-003")
    void aCommonPasswordIsRejectedIgnoringCase() {
        assertRejected(() -> policy.check("password1234"), InvalidPasswordException.Reason.TOO_COMMON);
        assertRejected(() -> policy.check("QWERTY123456"), InvalidPasswordException.Reason.TOO_COMMON);
    }

    @Test
    void lengthIsCountedInCodePoints() {
        assertThatCode(() -> policy.check(EMOJI.repeat(12))).doesNotThrowAnyException();
        assertRejected(() -> policy.check(EMOJI.repeat(11)), InvalidPasswordException.Reason.TOO_SHORT);
    }

    @Test
    void theMessageNeverContainsThePassword() {
        assertThatThrownBy(() -> policy.check("password1234")).message().doesNotContain("password1234");
        assertThatThrownBy(() -> policy.check("short-pass")).message().doesNotContain("short-pass");
    }

    private static void assertRejected(ThrowingCallable check, InvalidPasswordException.Reason reason) {
        assertThatThrownBy(check)
                .isInstanceOfSatisfying(
                        InvalidPasswordException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.reason())
                                .isEqualTo(reason));
    }
}
