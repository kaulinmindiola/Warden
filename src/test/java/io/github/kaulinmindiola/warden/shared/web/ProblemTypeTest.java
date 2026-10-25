package io.github.kaulinmindiola.warden.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProblemTypeTest {

    @Test
    void slugsAreUniqueAndKebabCase() {
        assertThat(Arrays.stream(ProblemType.values()).map(ProblemType::slug))
                .doesNotHaveDuplicates()
                .allMatch(slug -> slug.matches("[a-z]+(-[a-z]+)*"));
    }

    @ParameterizedTest
    @EnumSource(ProblemType.class)
    void everyTypeHasAStableUrnAndAnErrorStatus(ProblemType type) {
        assertThat(type.uri()).hasToString(ProblemType.URI_PREFIX + type.slug());
        assertThat(type.status().isError()).isTrue();
        assertThat(type.title()).isNotBlank();
        assertThat(type.defaultDetail()).isNotBlank();
    }

    @Test
    void resolvesATypeFromItsSlug() {
        assertThat(ProblemType.fromSlug("last-owner")).contains(ProblemType.LAST_OWNER);
        assertThat(ProblemType.fromSlug("does-not-exist")).isEmpty();
    }
}
