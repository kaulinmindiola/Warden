package io.github.kaulinmindiola.warden.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.support.Verifies;
import org.junit.jupiter.api.Test;

class EmailAddressTest {

    @Test
    @Verifies("REQ-FUNC-004")
    void valuesThatDifferOnlyInCaseOrSurroundingWhitespaceAreTheSameAddress() {
        EmailAddress typed = EmailAddress.of("  Ana@Mail.com ");

        assertThat(typed.value()).isEqualTo("ana@mail.com");
        assertThat(typed).isEqualTo(EmailAddress.of("ana@mail.com"));
        assertThat(EmailAddress.of("\tANA@MAIL.COM\n")).isEqualTo(typed);
    }

    @Test
    void rejectsMalformedAddresses() {
        String[] malformed = {null, "", "   ", "ana", "ana@", "@mail.com", "ana@mail", "ana@@mail.com", "an a@mail.com"
        };
        for (String value : malformed) {
            assertThatThrownBy(() -> EmailAddress.of(value))
                    .as("value <%s>", value)
                    .isInstanceOf(InvalidEmailException.class)
                    .hasMessage("The e-mail address is not well formed");
        }
    }

    @Test
    void rejectsAddressesThatAreTooLong() {
        String longLocalPart = "a".repeat(65) + "@mail.com";
        String longAddress = "ana@" + "d".repeat(246) + ".com";

        assertThat(EmailAddress.of("a".repeat(64) + "@mail.com").value()).hasSize(73);
        assertThatThrownBy(() -> EmailAddress.of(longLocalPart)).isInstanceOf(InvalidEmailException.class);
        assertThat(longAddress).hasSize(254);
        assertThat(EmailAddress.of(longAddress).value()).hasSize(254);
        assertThatThrownBy(() -> EmailAddress.of("x" + longAddress)).isInstanceOf(InvalidEmailException.class);
    }

    @Test
    void theConstructorAcceptsOnlyNormalizedValues() {
        assertThatThrownBy(() -> new EmailAddress("Ana@mail.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailAddress(" ana@mail.com")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new EmailAddress("ana@mail.com").value()).isEqualTo("ana@mail.com");
    }

    @Test
    void theTextualFormDoesNotRevealTheAddress() {
        assertThat(EmailAddress.of("ana@mail.com").toString()).doesNotContain("ana", "mail.com");
    }
}
