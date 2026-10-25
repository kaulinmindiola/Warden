package io.github.kaulinmindiola.warden.iam.domain;

/**
 * Hashes and verifies passwords (REQ-SEC-003). Implementations bound how many hashes run at
 * once, because each one uses tens of megabytes.
 */
public interface PasswordHasher {

    /** Hashes a password that already satisfies the {@link PasswordPolicy}. */
    PasswordHash hash(String rawPassword);

    /** Whether the password matches the stored hash. */
    boolean matches(String rawPassword, PasswordHash hash);

    /** Whether the hash was made with weaker parameters than the current ones and should be redone. */
    boolean needsRehash(PasswordHash hash);

    /**
     * Verifies the password against a fixed hash that matches no account, and returns {@code
     * false}. Used when the e-mail has no account, so the response costs the same work as a real
     * verification and does not reveal whether the account exists (BR-007, SDD V14-A2).
     */
    boolean matchesDummy(String rawPassword);
}
