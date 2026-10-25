package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.AuthSession;
import io.github.kaulinmindiola.warden.shared.persistence.PersistOperations;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Login sessions. Only the operations the use cases need are exposed. */
public interface AuthSessionRepository extends Repository<AuthSession, UUID>, PersistOperations<AuthSession> {

    Optional<AuthSession> findById(UUID id);
}
