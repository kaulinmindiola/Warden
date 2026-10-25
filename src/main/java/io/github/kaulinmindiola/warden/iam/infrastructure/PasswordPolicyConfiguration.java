package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.PasswordPolicy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Builds the password policy from the bundled common-password list (REQ-FUNC-003, TBD-01). The
 * list and its provenance are described in {@code docs/security/common-passwords.md}. Startup
 * fails if the list is missing or empty.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordPolicyConfiguration {

    /** Classpath location of the list: one lower-case password per line, UTF-8. */
    static final String COMMON_PASSWORDS = "security/common-passwords.txt";

    @Bean
    public PasswordPolicy passwordPolicy() {
        return new PasswordPolicy(loadCommonPasswords(new ClassPathResource(COMMON_PASSWORDS)));
    }

    static Set<String> loadCommonPasswords(Resource resource) {
        Set<String> passwords;
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            passwords = reader.lines()
                    .filter(line -> !line.isEmpty())
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read the common-password list " + resource.getDescription(), exception);
        }
        if (passwords.isEmpty()) {
            throw new IllegalStateException("The common-password list " + resource.getDescription() + " is empty");
        }
        return passwords;
    }
}
