package io.github.kaulinmindiola.warden.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Proves that the integration test infrastructure has the guarantees later tests rely on. */
class IntegrationTestSupportIT extends IntegrationTest {

    /** PostgreSQL SQLSTATE for insufficient_privilege ("permission denied"). */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private Clock applicationClock;

    @Test
    void connectsAsTheApplicationRoleWhichCannotBypassRowLevelSecurity() {
        String currentUser =
                jdbc().sql("SELECT current_user").query(String.class).single();
        Boolean superuser = jdbc().sql("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class)
                .single();
        Boolean bypassesRls = jdbc().sql("SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class)
                .single();

        assertThat(currentUser).isEqualTo("warden_app");
        assertThat(superuser).isFalse();
        assertThat(bypassesRls).isFalse();
    }

    @Test
    void theApplicationRoleCannotCreateSchemaObjects() {
        assertThatThrownBy(() ->
                        jdbc().sql("CREATE TABLE must_not_exist (id integer)").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .asInstanceOf(InstanceOfAssertFactories.type(SQLException.class))
                .extracting(SQLException::getSQLState)
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    @Test
    @Verifies("REQ-INST-003")
    void flywayMigratedAtStartupAsTheSchemaOwner() {
        String installedBy = jdbc().sql(
                        "SELECT installed_by FROM flyway_schema_history WHERE version = '1' AND success")
                .query(String.class)
                .single();
        String historyOwner = jdbc().sql(
                        "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'flyway_schema_history'")
                .query(String.class)
                .single();

        assertThat(installedBy).isEqualTo("warden_owner");
        assertThat(historyOwner).isEqualTo("warden_owner");
    }

    @Test
    void redisIsReachable() {
        String key = "warden:test:" + UUID.randomUUID();

        redisTemplate.opsForValue().set(key, "value");

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("value");
    }

    @Test
    void theApplicationClockIsTheControllableTestClock() {
        Instant before = applicationClock.instant();

        clock.advance(Duration.ofMinutes(30));

        assertThat(applicationClock).isSameAs(clock);
        assertThat(applicationClock.instant()).isEqualTo(before.plus(Duration.ofMinutes(30)));
    }

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }
}
