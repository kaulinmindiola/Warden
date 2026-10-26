package io.github.kaulinmindiola.warden.iam.application;

import io.github.kaulinmindiola.warden.iam.UserDirectory;
import io.github.kaulinmindiola.warden.iam.UserSummary;
import io.github.kaulinmindiola.warden.iam.domain.UserAccount;
import io.github.kaulinmindiola.warden.iam.infrastructure.UserAccountRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** {@link UserDirectory} over the account table. */
@Service
class UserDirectoryService implements UserDirectory {

    private final UserAccountRepository accounts;

    UserDirectoryService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<UserSummary> findById(UUID userId) {
        return accounts.findById(userId).map(UserDirectoryService::summaryOf);
    }

    @Override
    public Map<UUID, UserSummary> findByIds(Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return accounts.findByIdIn(userIds).stream()
                .collect(Collectors.toUnmodifiableMap(UserAccount::id, UserDirectoryService::summaryOf));
    }

    private static UserSummary summaryOf(UserAccount account) {
        return new UserSummary(
                account.id(),
                account.email().value(),
                account.registrationLocale().tag(),
                account.isEmailVerified());
    }
}
