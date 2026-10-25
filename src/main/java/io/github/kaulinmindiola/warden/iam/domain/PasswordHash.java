package io.github.kaulinmindiola.warden.iam.domain;

import java.util.Objects;

/**
 * An encoded password hash, such as an Argon2id string. Never the password itself.
 *
 * @param value the encoded hash
 */
public record PasswordHash(String value) {

    public PasswordHash {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("A password hash cannot be blank");
        }
    }

    @Override
    public String toString() {
        return "PasswordHash[redacted]";
    }
}
