package io.github.kaulinmindiola.warden.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Which failures mean that PostgreSQL cannot be reached (REQ-REL-009). */
class DatabaseUnavailableTest {

    @Test
    void aConnectionThatCannotBeObtainedIsAnOutageWhateverWrapsIt() {
        SQLTransientConnectionException hikariTimeout =
                new SQLTransientConnectionException("Connection is not available, request timed out after 2000ms");

        assertThat(DatabaseUnavailable.causedBy(hikariTimeout)).isTrue();
        assertThat(DatabaseUnavailable.causedBy(new CannotCreateTransactionException(
                        "Could not open JPA EntityManager for transaction",
                        new IllegalStateException("Unable to acquire JDBC Connection", hikariTimeout))))
                .isTrue();
    }

    @Test
    void connectionAndShutdownSqlStatesAreOutages() {
        assertThat(DatabaseUnavailable.causedBy(new SQLException("Connection refused", "08001")))
                .isTrue();
        assertThat(DatabaseUnavailable.causedBy(new SQLException("I/O error", "08006")))
                .isTrue();
        assertThat(DatabaseUnavailable.causedBy(new SQLException("terminating connection", "57P01")))
                .isTrue();
        assertThat(DatabaseUnavailable.causedBy(new SQLException("the database system is starting up", "57P03")))
                .isTrue();
        assertThat(DatabaseUnavailable.causedBy(new SQLNonTransientConnectionException("closed")))
                .isTrue();
    }

    @Test
    void otherDatabaseErrorsAreNotOutages() {
        SQLException duplicate = new SQLException("duplicate key", "23505");

        assertThat(DatabaseUnavailable.causedBy(duplicate)).isFalse();
        assertThat(DatabaseUnavailable.causedBy(new DataIntegrityViolationException("duplicate", duplicate)))
                .isFalse();
        assertThat(DatabaseUnavailable.causedBy(new SQLException("no state"))).isFalse();
        assertThat(DatabaseUnavailable.causedBy(new IllegalStateException("bug")))
                .isFalse();
        assertThat(DatabaseUnavailable.causedBy(null)).isFalse();
    }

    @Test
    void aRedisFailureIsNotADatabaseOutage() {
        assertThat(DatabaseUnavailable.causedBy(new RedisConnectionFailureException("Redis down")))
                .isFalse();
    }

    @Test
    void theDescriptionNamesTheJdbcCauseAndItsStateButNoMessage() {
        CannotCreateTransactionException failure = new CannotCreateTransactionException(
                "Could not open JPA EntityManager", new SQLException("secret host db.internal:5432", "08001"));

        assertThat(DatabaseUnavailable.describe(failure))
                .isEqualTo("SQLException, SQLSTATE 08001")
                .doesNotContain("db.internal");
        assertThat(DatabaseUnavailable.describe(new IllegalStateException("x"))).isEqualTo("IllegalStateException");
    }
}
