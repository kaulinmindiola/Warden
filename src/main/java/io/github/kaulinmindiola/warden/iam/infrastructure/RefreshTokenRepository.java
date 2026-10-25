package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.RefreshToken;
import io.github.kaulinmindiola.warden.shared.persistence.PersistOperations;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Refresh tokens. Rotation uses a conditional update, added with the refresh use case. */
public interface RefreshTokenRepository extends Repository<RefreshToken, UUID>, PersistOperations<RefreshToken> {

    Optional<RefreshToken> findById(UUID id);
}
