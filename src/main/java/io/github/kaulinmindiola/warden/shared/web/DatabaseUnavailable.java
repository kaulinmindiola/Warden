package io.github.kaulinmindiola.warden.shared.web;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;

/**
 * Recognizes a failure caused by PostgreSQL being unreachable (REQ-REL-009, SDD V17, ADR-007).
 *
 * <p>The decision is made on the JDBC cause, whatever Spring or Hibernate wrapped around it: a
 * connection exception, a connection that could not be obtained within the Hikari timeout (2 s),
 * or an SQLSTATE of class {@code 08} (connection exception) or {@code 57P0x} (the server is shutting
 * down, crashed or not accepting connections yet). Redis failures are never classified as database
 * failures, although Spring Data Redis reports them as {@code DataAccessResourceFailureException}.
 */
final class DatabaseUnavailable {

    /** Bound on the cause chain walked, against pathological or cyclic chains. */
    private static final int MAX_CAUSES = 32;

    private DatabaseUnavailable() {}

    /** Whether {@code failure} or one of its causes says that PostgreSQL cannot be reached. */
    static boolean causedBy(Throwable failure) {
        return connectionFailure(failure).isPresent();
    }

    /** Exception class and SQLSTATE of the JDBC cause, for the log; never the message. */
    static String describe(Throwable failure) {
        return connectionFailure(failure)
                .map(cause -> cause.getClass().getSimpleName() + ", SQLSTATE " + cause.getSQLState())
                .orElse(failure.getClass().getSimpleName());
    }

    private static Optional<SQLException> connectionFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
            if (current instanceof SQLException sql && isConnectionFailure(sql)) {
                return Optional.of(sql);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static boolean isConnectionFailure(SQLException sql) {
        if (sql instanceof SQLTransientConnectionException || sql instanceof SQLNonTransientConnectionException) {
            return true;
        }
        String state = sql.getSQLState();
        return state != null && (state.startsWith("08") || state.startsWith("57P0"));
    }
}
