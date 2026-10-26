package io.github.kaulinmindiola.warden.iam.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.iam.UserDirectory;
import io.github.kaulinmindiola.warden.iam.UserSummary;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;

/** The {@code iam} public API that other modules use to read user facts (SDD V09). */
class UserDirectoryIT extends IntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserDirectory userDirectory;

    private IamTestClient iam;

    @BeforeEach
    void setUpClient() {
        iam = new IamTestClient(context);
    }

    @Test
    void anAccountIsFoundWithItsVerificationState() throws Exception {
        String unverified = iam.registerNewAccount();
        String verified = iam.registerVerifiedAccount();

        UserSummary unverifiedSummary =
                userDirectory.findById(iam.userIdOf(unverified)).orElseThrow();
        UserSummary verifiedSummary =
                userDirectory.findById(iam.userIdOf(verified)).orElseThrow();

        assertThat(unverifiedSummary.email()).isEqualTo(unverified);
        assertThat(unverifiedSummary.locale()).isEqualTo("es");
        assertThat(unverifiedSummary.emailVerified()).isFalse();
        assertThat(verifiedSummary.emailVerified()).isTrue();
        assertThat(unverifiedSummary.toString()).doesNotContain(unverified);
        assertThat(userDirectory.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void severalAccountsAreFoundAtOnceAndUnknownIdsAreLeftOut() throws Exception {
        UUID first = iam.userIdOf(iam.registerNewAccount());
        UUID second = iam.userIdOf(iam.registerNewAccount());

        Map<UUID, UserSummary> found = userDirectory.findByIds(Set.of(first, second, UUID.randomUUID()));

        assertThat(found).containsOnlyKeys(first, second);
        assertThat(userDirectory.findByIds(Set.of())).isEmpty();
    }
}
