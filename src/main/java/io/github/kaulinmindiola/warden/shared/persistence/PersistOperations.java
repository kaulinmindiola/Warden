package io.github.kaulinmindiola.warden.shared.persistence;

/**
 * Repository fragment for aggregates whose identifiers are assigned by the domain (UUIDv7).
 *
 * <p>Spring Data's {@code save} treats an entity with an identifier as existing and merges it,
 * which costs an extra SELECT and returns a copy. {@link #persist} inserts the given instance
 * instead. Callers must run inside a transaction.
 *
 * @param <T> entity type
 */
public interface PersistOperations<T> {

    /** Makes a new entity managed; it is inserted at the next flush or at commit. */
    void persist(T entity);

    /** Writes pending changes now, so constraint violations surface at this call. */
    void flush();
}
