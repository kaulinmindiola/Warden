package io.github.kaulinmindiola.warden.notification.application.worker;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Lets the claiming workers run only while the application is up (REQ-AVAIL-002, SDD V17). When the
 * application starts shutting down, for instance on SIGTERM, the gate closes: no new batch of
 * events or deliveries is claimed, and the shutdown waits for the batches in progress to be
 * recorded, within the shutdown timeout ({@code spring.lifecycle.timeout-per-shutdown-phase}).
 *
 * <p>The gate stops in the same lifecycle phase as the web server's graceful shutdown, so the
 * requests and the batches in progress drain together, and both before the connection pool closes.
 * A batch cut short by the timeout loses nothing: its leases lapse and another instance claims it
 * (ADR-015).
 */
@Component
public class WorkerGate implements SmartLifecycle {

    /** The phase of the web server's graceful shutdown. */
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 1024;

    private final Object lock = new Object();
    private boolean open;
    private int active;
    private Runnable whenDrained;

    /**
     * Admits one run if the gate is open; every admitted run must call {@link #exit()}.
     *
     * @return whether the run may claim work
     */
    public boolean tryEnter() {
        synchronized (lock) {
            if (!open) {
                return false;
            }
            active++;
            return true;
        }
    }

    /** Ends a run admitted by {@link #tryEnter()}. */
    public void exit() {
        Runnable drained = null;
        synchronized (lock) {
            active--;
            if (active == 0 && whenDrained != null) {
                drained = whenDrained;
                whenDrained = null;
            }
        }
        if (drained != null) {
            drained.run();
        }
    }

    @Override
    public void start() {
        synchronized (lock) {
            open = true;
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            open = false;
        }
    }

    /** Closes the gate and reports the stop once the runs in progress have ended. */
    @Override
    public void stop(Runnable callback) {
        boolean drained;
        synchronized (lock) {
            open = false;
            drained = active == 0;
            if (!drained) {
                whenDrained = callback;
            }
        }
        if (drained) {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (lock) {
            return open;
        }
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** How many runs are in progress; for tests. */
    int active() {
        synchronized (lock) {
            return active;
        }
    }
}
