package com.shiparrival.service;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Runs pilot carrier lookups on a dedicated, bounded executor. */
@Component
public class CarrierTrackingCoordinator {
    private static final Logger log = LoggerFactory.getLogger(CarrierTrackingCoordinator.class);
    private final boolean enabled;
    private final ThreadPoolExecutor executor;
    private final Map<Carrier, Worker> carrierTails = new EnumMap<>(Carrier.class);
    private final List<Worker> cycleTasks = new ArrayList<>();
    private final Object lifecycle = new Object();
    private final ReentrantLock drainLock = new ReentrantLock(true);
    private final ReentrantLock barrierLock = new ReentrantLock(true);
    private int activeBarriers;
    private volatile Runnable barrierEnteredHook = () -> {};
    private volatile Runnable drainWaitingHook = () -> {};
    private int activeCalls;
    private int peakInFlight;
    private final Map<Carrier, Integer> carrierSuccesses = new EnumMap<>(Carrier.class);
    private final Map<Carrier, Integer> carrierFailures = new EnumMap<>(Carrier.class);

    void setBarrierEnteredHook(Runnable hook) { barrierEnteredHook = hook; }
    void setDrainWaitingHook(Runnable hook) { drainWaitingHook = hook; }
    private boolean cycleOpen;
    private boolean cycleCancelled;

    @Autowired
    public CarrierTrackingCoordinator(@Value("${tracking.parallel.enabled:false}") boolean enabled,
                                      @Value("${tracking.delay-ms:0}") long delayMs) {
        this(enabled, new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy()), delayMs,
                System::nanoTime, millis -> TimeUnit.MILLISECONDS.sleep(millis));
    }

    CarrierTrackingCoordinator(boolean enabled) {
        this(enabled, new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy()));
    }

    CarrierTrackingCoordinator(boolean enabled, ThreadPoolExecutor executor) {
        this(enabled, executor, 0, System::nanoTime, millis -> TimeUnit.MILLISECONDS.sleep(millis));
    }

    CarrierTrackingCoordinator(boolean enabled, ThreadPoolExecutor executor, long delayMs,
                               java.util.function.LongSupplier monotonicClock, Sleeper sleeper) {
        this.enabled = enabled;
        this.executor = executor;
        this.delayNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, delayMs));
        this.monotonicClock = monotonicClock;
        this.sleeper = sleeper;
    }

    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private final long delayNanos;
    private final java.util.function.LongSupplier monotonicClock;
    private final Sleeper sleeper;
    private long lastCallStart = Long.MIN_VALUE;
    private final Map<Carrier, Long> carrierCompletion = new EnumMap<>(Carrier.class);
    private volatile StartReservationHook afterStartReservationHook = carrier -> {};
    private volatile AuthorizationHook authorizationHook = (carrier, timestamp) -> {};
    private volatile StartReservationHook beforeAuthorizationHook = carrier -> {};

    @FunctionalInterface
    interface StartReservationHook { void afterReservation(Carrier carrier) throws InterruptedException; }

    void setAfterStartReservationHook(StartReservationHook hook) { afterStartReservationHook = hook; }
    void setAuthorizationHook(AuthorizationHook hook) { authorizationHook = hook; }
    void setBeforeAuthorizationHook(StartReservationHook hook) { beforeAuthorizationHook = hook; }

    @FunctionalInterface
    interface AuthorizationHook { void authorized(Carrier carrier, long timestampNanos); }

    public void beginCycle() {
        drainLock.lock();
        try {
            synchronized (lifecycle) {
                if (cycleOpen || !cycleTasks.isEmpty()) throw new IllegalStateException("Previous tracking cycle is not drained");
                cycleOpen = true;
                cycleCancelled = false;
                activeCalls = 0;
                peakInFlight = 0;
                carrierSuccesses.clear();
                carrierFailures.clear();
            }
        } finally {
            drainLock.unlock();
        }
    }

    public CompletableFuture<TrackingResult> submit(Carrier carrier,
            CarrierTrackingAdapter adapter, String trackingNumber) {
        barrierLock.lock();
        try {
        synchronized (lifecycle) {
            if (!enabled || (carrier != Carrier.MSC && carrier != Carrier.MAERSK)) {
                throw new IllegalStateException("Carrier is not enabled for parallel tracking: " + carrier);
            }
            if (!cycleOpen || activeBarriers > 0) throw new IllegalStateException("Tracking cycle submissions are closed");

            Worker predecessor = carrierTails.get(carrier);
            CompletableFuture<TrackingResult> result = new CompletableFuture<>();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            Worker worker = new Worker(carrier, result, completion);
            result.whenComplete((ignored, error) -> {
                if (result.isCancelled()) {
                    Thread thread = worker.thread;
                    if (thread != null) thread.interrupt();
                }
            });
            cycleTasks.add(worker);
            carrierTails.put(carrier, worker);
            Runnable dispatch = () -> {
                synchronized (lifecycle) {
                    if (cycleCancelled || result.isCancelled()) {
                        completion.complete(null);
                        return;
                    }
                    try {
                        executor.execute(() -> {
                            worker.thread = Thread.currentThread();
                            boolean success = false;
                            long invocationStarted;
                            try {
                                awaitStart(carrier);
                                afterStartReservationHook.afterReservation(carrier);
                                beforeAuthorizationHook.afterReservation(carrier);
                                invocationStarted = reserveInvocationStart(carrier);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                result.cancel(false);
                                worker.thread = null;
                                completion.complete(null);
                                return;
                            }
                            synchronized (lifecycle) {
                                if (cycleCancelled || result.isCancelled()) {
                                    worker.thread = null;
                                    completion.complete(null);
                                    return;
                                }
                                if (invocationStarted == Long.MIN_VALUE) {
                                    result.cancel(false);
                                    worker.thread = null;
                                    completion.complete(null);
                                    return;
                                }
                                activeCalls++;
                                peakInFlight = Math.max(peakInFlight, activeCalls);
                            }
                            authorizationHook.authorized(carrier, invocationStarted);
                            try {
                                TrackingResult tracked = adapter.track(trackingNumber);
                                success = tracked != null && tracked.isSuccess();
                                result.complete(tracked);
                            } catch (Throwable error) {
                                result.completeExceptionally(error);
                            } finally {
                                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(monotonicClock.getAsLong() - invocationStarted);
                                int successes;
                                int failures;
                                int observedPeak;
                                synchronized (lifecycle) {
                                    carrierCompletion.put(carrier, monotonicClock.getAsLong());
                                    lifecycle.notifyAll();
                                    activeCalls--;
                                    Map<Carrier, Integer> outcomes = success ? carrierSuccesses : carrierFailures;
                                    outcomes.merge(carrier, 1, Integer::sum);
                                    successes = carrierSuccesses.getOrDefault(carrier, 0);
                                    failures = carrierFailures.getOrDefault(carrier, 0);
                                    observedPeak = peakInFlight;
                                }
                                log.info("Pilot adapter call completed carrier={} durationMs={} success={} carrierSuccesses={} carrierFailures={} inFlightPeak={}",
                                        carrier, elapsedMs, success, successes, failures, observedPeak);
                                worker.thread = null;
                                completion.complete(null);
                            }
                        });
                    } catch (RuntimeException error) {
                        result.completeExceptionally(new DispatchRejectedException(error));
                        completion.complete(null);
                    }
                }
            };
            if (predecessor == null) dispatch.run();
            else predecessor.completion.whenComplete((ignored, error) -> {
                if (cycleCancelled) {
                    result.cancel(false);
                    completion.complete(null);
                } else dispatch.run();
            });
            return result;
        }
        } finally { barrierLock.unlock(); }
    }

    /** Temporarily closes submissions while taking and waiting for a stable worker snapshot. */
    public void awaitWorkers() throws InterruptedException {
        barrierLock.lockInterruptibly();
        List<Worker> tasks;
        try {
            synchronized (lifecycle) {
                activeBarriers++;
                tasks = new ArrayList<>(cycleTasks);
            }
        } catch (RuntimeException | Error error) {
            barrierLock.unlock();
            throw error;
        }
        try { barrierEnteredHook.run(); waitForWorkers(tasks); }
        finally {
            synchronized (lifecycle) { activeBarriers--; }
            barrierLock.unlock();
        }
    }

    /** Temporarily closes submissions while waiting for a carrier's stable worker snapshot. */
    public void awaitCarrier(Carrier carrier) throws InterruptedException {
        barrierLock.lockInterruptibly();
        List<Worker> tasks;
        try {
            synchronized (lifecycle) {
                activeBarriers++;
                tasks = cycleTasks.stream().filter(worker -> worker.carrier == carrier).toList();
            }
        } catch (RuntimeException | Error error) {
            barrierLock.unlock();
            throw error;
        }
        try { barrierEnteredHook.run(); waitForWorkers(tasks); }
        finally {
            synchronized (lifecycle) { activeBarriers--; }
            barrierLock.unlock();
        }
    }

    /** Ends this cycle's submission boundary and waits for all accepted work to terminate. */
    public void drain() throws InterruptedException {
        drainLock.lock();
        try {
            List<Worker> tasks;
            synchronized (lifecycle) {
                cycleOpen = false;
                tasks = new ArrayList<>(cycleTasks);
            }
            drainWaitingHook.run();
            waitForWorkers(tasks);
            synchronized (lifecycle) { cycleTasks.removeAll(tasks); carrierTails.clear(); }
        } finally {
            drainLock.unlock();
        }
    }

    /** Cancels accepted work and waits without falsely reporting completion on timeout. */
    public void cancelAndDrain() throws InterruptedException {
        drainLock.lock();
        try {
            List<Worker> tasks;
            synchronized (lifecycle) {
                cycleOpen = false;
                cycleCancelled = true;
                lifecycle.notifyAll();
                tasks = new ArrayList<>(cycleTasks);
            }
            tasks.forEach(worker -> {
                worker.result.cancel(true);
                Thread thread = worker.thread;
                if (thread != null) thread.interrupt();
            });
            drainWaitingHook.run();
            waitForWorkers(tasks);
            synchronized (lifecycle) { cycleTasks.removeAll(tasks); carrierTails.clear(); }
        } finally {
            drainLock.unlock();
        }
    }

    int observedPeakInFlight() {
        synchronized (lifecycle) { return peakInFlight; }
    }

    private void awaitStart(Carrier carrier) throws InterruptedException {
        while (true) {
            long remaining = remainingUntilStart(carrier);
            if (remaining <= 0) return;
            sleeper.sleep(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }
    }

    private long reserveInvocationStart(Carrier carrier) throws InterruptedException {
        while (true) {
            long waitNanos;
            synchronized (lifecycle) {
                long now = monotonicClock.getAsLong();
                waitNanos = earliestStartLocked(carrier, now) - now;
                if (waitNanos <= 0) {
                    lastCallStart = now;
                    return now;
                }
            }
            sleeper.sleep(Math.max(1, TimeUnit.NANOSECONDS.toMillis(waitNanos)));
        }
    }

    private long remainingUntilStart(Carrier carrier) {
        synchronized (lifecycle) {
            long now = monotonicClock.getAsLong();
            return earliestStartLocked(carrier, now) - now;
        }
    }

    private long earliestStartLocked(Carrier carrier, long now) {
        long earliest = lastCallStart == Long.MIN_VALUE ? now : lastCallStart + delayNanos;
        Long completed = carrierCompletion.get(carrier);
        if (completed != null) earliest = Math.max(earliest, completed + delayNanos);
        return earliest;
    }

    private static void waitForWorkers(List<Worker> tasks) throws InterruptedException {
        for (Worker worker : tasks) {
            try { worker.completion.get(); }
            catch (java.util.concurrent.ExecutionException ignored) { }
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            cancelAndDrain();
            executor.shutdown();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS))
                    throw new IllegalStateException("Tracking workers remain active during shutdown");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining tracking workers", error);
        }
    }

    static final class DispatchRejectedException extends RuntimeException {
        DispatchRejectedException(RuntimeException cause) {
            super(cause);
        }
    }

    private static final class Worker {
        private final Carrier carrier;
        private final CompletableFuture<TrackingResult> result;
        private final CompletableFuture<Void> completion;
        private volatile Thread thread;

        private Worker(Carrier carrier, CompletableFuture<TrackingResult> result, CompletableFuture<Void> completion) {
            this.carrier = carrier;
            this.result = result;
            this.completion = completion;
        }
    }
}
