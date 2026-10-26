package io.github.kaulinmindiola.warden.notes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kaulinmindiola.warden.support.Verifies;
import org.junit.jupiter.api.Test;

class NoteContentTest {

    /** U+1D538: two UTF-16 units, one code point. */
    private static final String DOUBLE_STRUCK_A = "\uD835\uDD38";

    @Test
    @Verifies("REQ-FUNC-044")
    void titlesOf1To200AndBodiesUpTo10000CharactersAreAccepted() {
        NoteContent content = NoteContent.of("  Plan  ", "");

        assertThat(content.title()).isEqualTo("Plan");
        assertThat(content.body()).isEmpty();
        assertThat(NoteContent.of("a".repeat(200), "b".repeat(10_000)).body()).hasSize(10_000);
        assertThat(NoteContent.of(DOUBLE_STRUCK_A.repeat(200), DOUBLE_STRUCK_A.repeat(10_000))
                        .title())
                .hasSize(400);
    }

    @Test
    @Verifies("REQ-FUNC-044")
    void everyInvalidFieldIsReportedTogether() {
        assertThat(NoteContent.violations("a".repeat(201), "b".repeat(10_001)))
                .containsExactly(
                        java.util.Map.entry("title", NoteContent.TITLE_LENGTH),
                        java.util.Map.entry("body", NoteContent.BODY_LENGTH));
        assertThat(NoteContent.violations("   ", null))
                .containsExactly(
                        java.util.Map.entry("title", NoteContent.TITLE_LENGTH),
                        java.util.Map.entry("body", NoteContent.REQUIRED));
        assertThat(NoteContent.violations(null, "body"))
                .containsExactly(java.util.Map.entry("title", NoteContent.REQUIRED));
        assertThat(NoteContent.violations("ok", "ok")).isEmpty();
    }

    @Test
    void invalidContentCannotBeCreated() {
        assertThatThrownBy(() -> NoteContent.of("", "body"))
                .isInstanceOfSatisfying(
                        InvalidNoteException.class,
                        invalid -> assertThat(invalid.violations()).containsOnlyKeys("title"));
        assertThatThrownBy(() -> NoteContent.of(DOUBLE_STRUCK_A.repeat(201), "body"))
                .isInstanceOf(InvalidNoteException.class);
    }
}
