package io.github.kaulinmindiola.warden.iam.domain;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** SHA-256 of a 256-bit token (REQ-SEC-004). Only this hash is stored, never the token. */
public final class TokenHash {

    /** Length of a SHA-256 hash in bytes. */
    public static final int LENGTH = 32;

    private final byte[] value;

    private TokenHash(byte[] value) {
        this.value = value;
    }

    /**
     * @param value the 32-byte hash; it is copied
     * @throws IllegalArgumentException if it is not 32 bytes long
     */
    public static TokenHash of(byte[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("A token hash must be " + LENGTH + " bytes, but is " + value.length);
        }
        return new TokenHash(value.clone());
    }

    /** A copy of the hash bytes. */
    public byte[] bytes() {
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TokenHash that && Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    /** The first four bytes in hexadecimal, enough to correlate in a debugger. */
    @Override
    public String toString() {
        return "TokenHash[" + HexFormat.of().formatHex(value, 0, 4) + "...]";
    }
}
