package io.github.kaulinmindiola.warden.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Defensive masking of tokens and e-mail addresses in log text (REQ-OBS-005, SDD V19). */
class LogRedactorTest {

    private static final String JWT = "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLWJ5dGVz";

    @Test
    @Verifies("REQ-OBS-005")
    void refreshAndSingleUseTokensKeepOnlyTheirPrefix() {
        assertThat(LogRedactor.redact("refresh wrt_Ab-1_xYz9 and link token wot_Q2w3E4r5"))
                .isEqualTo("refresh wrt_[REDACTED] and link token wot_[REDACTED]");
    }

    @Test
    @Verifies("REQ-OBS-005")
    void jsonWebTokensAreMaskedWholeAlsoAfterBearer() {
        assertThat(LogRedactor.redact("Authorization: Bearer " + JWT + " rejected"))
                .isEqualTo("Authorization: Bearer [REDACTED-JWT] rejected");
    }

    @Test
    @Verifies("REQ-OBS-005")
    void emailAddressesAreMasked() {
        assertThat(LogRedactor.redact("invitee-42@mail.test, Ana.Perez+work@example.co.uk"))
                .isEqualTo("[REDACTED-EMAIL], [REDACTED-EMAIL]");
    }

    @Test
    void ordinaryTextIdentifiersAndClassNamesAreLeftAsTheyAre() {
        String text = "Delivery " + UUID.randomUUID() + " is SENT after 1 attempts (provider A) "
                + "at io.github.Example.run(Example.java:12) java.lang.Object@1a2b3c";

        assertThat(LogRedactor.redact(text)).isEqualTo(text);
    }

    @Test
    void nullAndEmptyStayAsTheyAre() {
        assertThat(LogRedactor.redact(null)).isNull();
        assertThat(LogRedactor.redact("")).isEmpty();
    }
}
