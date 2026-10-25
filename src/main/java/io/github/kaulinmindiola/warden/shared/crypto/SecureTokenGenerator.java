package io.github.kaulinmindiola.warden.shared.crypto;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Generates opaque tokens with 256 bits of entropy from {@link SecureRandom} (REQ-SEC-004).
 *
 * <p>A token is a recognisable prefix followed by 32 random bytes in unpadded Base64url, 47
 * characters in total. The prefixes let secret scanners detect leaked tokens (SDD V08). Only the
 * SHA-256 hash of a token is ever stored ({@link TokenHasher}).
 */
public class SecureTokenGenerator {

    /** Prefix of refresh tokens. */
    public static final String REFRESH_TOKEN_PREFIX = "wrt_";

    /** Prefix of one-time tokens (e-mail verification, password reset, invitations). */
    public static final String ONE_TIME_TOKEN_PREFIX = "wot_";

    /** Random bytes per token: 256 bits. */
    public static final int TOKEN_BYTES = 32;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random;

    public SecureTokenGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /** New refresh token: {@code wrt_} followed by 43 Base64url characters. */
    public String newRefreshToken() {
        return REFRESH_TOKEN_PREFIX + randomPart();
    }

    /** New one-time token: {@code wot_} followed by 43 Base64url characters. */
    public String newOneTimeToken() {
        return ONE_TIME_TOKEN_PREFIX + randomPart();
    }

    private String randomPart() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
