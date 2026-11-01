package com.shiparrival.service;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import jakarta.annotation.PreDestroy;
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

/** Runs pilot carrier lookups on a dedicated, bounded executor. */
@Component
public class CarrierTrackingCoordinator {
    private final boolean enabled;
    private final ThreadPoolExecutor executor;
    private final Map<Carrier, Worker> carrierTails = new EnumMap<>(Carrier.class);
    private final List<Worker> cycleTasks = new ArrayList<>();
    private final Object lifecycle = new Object();
    private final Object drainLock = new Object();
    private boolean cycleOpen;
    private boolean cycleCancelled;

    public CarrierTrackingCoordinator(@Value("${tracking.parallel.enabled:false}") boolean enabled) {
        this(enabled, new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy()));
    }

    CarrierTrackingCoordinator(boolean enabled, ThreadPoolExecutor executor) {
        this.enabled = enabled;
        this.executor = executor;
    }

    public void beginCycle() {
        synchronized (lifecycle) {
            if (cycleOpen || !cycleTasks.isEmpty()) throw new IllegalStateException("Previous tracking cycle is not drained");
            cycleOpen = true;
            cycleCancelled = false;
        }
    }

    public CompletableFuture<TrackingResult> submit(Carrier carrier,
            CarrierTrackingAdapter adapter, String trackingNumber) {
        synchronized (lifecycle) {
            if (!enabled || (carrier != Carrier.MSC && carrier != Carrier.MAERSK)) {
                throw new IllegalStateException("Carrier is not enabled for parallel tracking: " + carrier);
            }
            if (!cycleOpen) throw new IllegalStateException("No tracking cycle is open");

            Worker predecessor = carrierTails.get(carrier);
            CompletableFuture<TrackingResult> result = new CompletableFuture<>();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            Worker worker = new Worker(result, completion);
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
                            try {
                                synchronized (lifecycle) {
                                    if (cycleCancelled || result.isCancelled()) return;
                                }
                                result.complete(adapter.track(trackingNumber));
                            } catch (Throwable error) {
                                result.completeExceptionally(error);
                            } finally {
                                worker.thread = null;
                                completion.complete(null);
                            }
                        });
                    } catch (RuntimeException error) {
                        result.completeExceptionally(error);
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
    }

    /** Ends this cycle's submission boundary and waits for all accepted work to terminate. */
    public void drain() throws InterruptedException {
        synchronized (drainLock) {
            List<Worker> tasks;
            synchronized (lifecycle) {
                cycleOpen = false;
                tasks = new ArrayList<>(cycleTasks);
            }
            waitForWorkers(tasks);
            synchronized (lifecycle) { cycleTasks.removeAll(tasks); carrierTails.clear(); }
        }
    }

    /** Cancels accepted work and waits without falsely reporting completion on timeout. */
    public void cancelAndDrain() throws InterruptedException {
        synchronized (drainLock) {
            List<Worker> tasks;
            synchronized (lifecycle) {
                cycleOpen = false;
                cycleCancelled = true;
                tasks = new ArrayList<>(cycleTasks);
            }
            tasks.forEach(worker -> {
                worker.result.cancel(true);
                Thread thread = worker.thread;
                if (thread != null) thread.interrupt();
            });
            waitForWorkers(tasks);
            synchronized (lifecycle) { cycleTasks.removeAll(tasks); carrierTails.clear(); }
        }
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

    private static final class Worker {
        private final CompletableFuture<TrackingResult> result;
        private final CompletableFuture<Void> completion;
        private volatile Thread thread;

        private Worker(CompletableFuture<TrackingResult> result, CompletableFuture<Void> completion) {
            this.result = result;
            this.completion = completion;
        }
    }
}
