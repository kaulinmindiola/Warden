package io.github.kaulinmindiola.warden.shared.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Implementation of {@link PersistOperations}, found by Spring Data through the {@code Impl}
 * suffix and shared by every repository that extends the fragment.
 *
 * @param <T> entity type
 */
class PersistOperationsImpl<T> implements PersistOperations<T> {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void persist(T entity) {
        entityManager.persist(entity);
    }

    @Override
    public void flush() {
        entityManager.flush();
    }
}
