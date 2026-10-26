package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.RefreshToken;
import io.github.kaulinmindiola.warden.iam.domain.TokenHash;
import io.github.kaulinmindiola.warden.shared.persistence.PersistOperations;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Refresh tokens. Rotation itself uses the conditional update of {@link RefreshTokenConsumer}. */
public interface RefreshTokenRepository extends Repository<RefreshToken, UUID>, PersistOperations<RefreshToken> {

    Optional<RefreshToken> findById(UUID id);

    /** The token with this hash, used or not; distinguishes reuse from unknown or expired tokens. */
    Optional<RefreshToken> findByHash(TokenHash hash);
}
