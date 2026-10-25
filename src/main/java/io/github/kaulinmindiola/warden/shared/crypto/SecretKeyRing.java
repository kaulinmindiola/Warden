package io.github.kaulinmindiola.warden.shared.crypto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;

/**
 * AES-256 keys identified by id. The current key seals new secrets; every key in the ring can
 * still open secrets sealed before a rotation.
 */
public final class SecretKeyRing {

    private final String currentKeyId;
    private final Map<String, SecretKey> keys;

    SecretKeyRing(String currentKeyId, Map<String, SecretKey> keys) {
        Objects.requireNonNull(currentKeyId, "currentKeyId");
        if (!keys.containsKey(currentKeyId)) {
            throw new IllegalArgumentException("The current key id is not in the ring");
        }
        this.currentKeyId = currentKeyId;
        this.keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
    }

    /** Id of the key that seals new secrets. */
    public String currentKeyId() {
        return currentKeyId;
    }

    /** Key that seals new secrets. */
    public SecretKey current() {
        return keys.get(currentKeyId);
    }

    /** Key with the given id, if it is still in the ring. */
    public Optional<SecretKey> find(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }

    /** Ids of every key in the ring, the current one first. */
    public Set<String> keyIds() {
        return keys.keySet();
    }

    @Override
    public String toString() {
        return "SecretKeyRing[currentKeyId=" + currentKeyId + ", keyIds=" + keys.keySet() + ", key material redacted]";
    }
}
