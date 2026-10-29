package io.github.kaulinmindiola.warden.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kaulinmindiola.warden.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class RevocationReconcilerTest {

    private static final Duration TTL = Duration.ofSeconds(630);
    private static final Duration INTERVAL = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock();
    private RedisRevocationStore store;
    private AuthSessionRepository sessions;
    private RevocationReconciler reconciler;

    @BeforeEach
    void setUp() {
        store = mock(RedisRevocationStore.class);
        sessions = mock(AuthSessionRepository.class);
        reconciler = new RevocationReconciler(store, sessions, new RevocationProperties(TTL, INTERVAL), clock);
        when(store.rewrite(anyList(), any(Instant.class), anyLong())).thenReturn(true);
    }

    @Test
    void recoveringAHealthyStoreDoesNothing() {
        assertThat(reconciler.recover()).isTrue();

        verify(store, never()).ping();
        verify(sessions, never()).findRevokedSince(any());
    }

    @Test
    void aDegradedStoreStaysDegradedWhileRedisDoesNotAnswer() {
        when(store.isDegraded()).thenReturn(true);
        when(store.ping()).thenReturn(false);

        assertThat(reconciler.recover()).isFalse();
        verify(sessions, never()).findRevokedSince(any());
    }

    @Test
    void recoveryRewritesTheRevocationsOfTheLastTtl() {
        Instant now = clock.instant();
        List<RevokedSession> recent = List.of(new RevokedSession(UUID.randomUUID(), now.minusSeconds(10)));
        when(store.isDegraded()).thenReturn(true);
        when(store.ping()).thenReturn(true);
        when(store.failureCount()).thenReturn(3L);
        when(sessions.findRevokedSince(now.minus(TTL))).thenReturn(recent);

        assertThat(reconciler.recover()).isTrue();
        verify(store).rewrite(recent, now, 3L);
    }

    @Test
    void aPostgresFailureIsReportedWithoutThrowing() {
        when(sessions.findRevokedSince(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(reconciler.reconcile()).isFalse();
        verify(store, never()).rewrite(anyList(), any(Instant.class), anyLong());
    }

    @Test
    void aHealthyStoreIsReconciledOncePerInterval() {
        when(sessions.findRevokedSince(any())).thenReturn(List.of());

        reconciler.tick();
        reconciler.tick();
        verify(sessions, times(1)).findRevokedSince(any());

        clock.advance(INTERVAL);
        reconciler.tick();
        verify(sessions, times(2)).findRevokedSince(any());
    }

    @Test
    void aDegradedStoreIsRecoveredOnEveryTick() {
        when(store.isDegraded()).thenReturn(true);
        when(store.ping()).thenReturn(false);

        reconciler.tick();
        reconciler.tick();

        verify(store, times(2)).ping();
    }
}
