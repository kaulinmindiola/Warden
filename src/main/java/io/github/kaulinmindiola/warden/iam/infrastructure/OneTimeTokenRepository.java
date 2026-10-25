package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.OneTimeToken;
import io.github.kaulinmindiola.warden.shared.persistence.PersistOperations;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** One-time tokens. Consumption uses a conditional update, added with the verification use case. */
public interface OneTimeTokenRepository extends Repository<OneTimeToken, UUID>, PersistOperations<OneTimeToken> {

    Optional<OneTimeToken> findById(UUID id);

    /** Tokens of a user and purpose that are neither consumed nor invalidated, expired or not. */
    List<OneTimeToken> findByUserIdAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNull(
            UUID userId, OneTimeToken.Purpose purpose);
}
