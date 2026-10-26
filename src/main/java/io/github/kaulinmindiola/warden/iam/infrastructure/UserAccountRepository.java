package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.EmailAddress;
import io.github.kaulinmindiola.warden.iam.domain.UserAccount;
import io.github.kaulinmindiola.warden.shared.persistence.PersistOperations;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** User accounts. Only the operations the use cases need are exposed. */
public interface UserAccountRepository extends Repository<UserAccount, UUID>, PersistOperations<UserAccount> {

    Optional<UserAccount> findById(UUID id);

    /** The account with this normalized e-mail, if any. */
    Optional<UserAccount> findByEmail(EmailAddress email);

    /** The accounts with these ids; unknown ids are ignored. */
    List<UserAccount> findByIdIn(Collection<UUID> ids);
}
