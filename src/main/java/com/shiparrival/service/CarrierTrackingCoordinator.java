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
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Runs pilot carrier lookups on a dedicated, bounded executor. */
@Component
public class CarrierTrackingCoordinator {
    private final boolean enabled;
    private final ThreadPoolExecutor executor;
    private final Map<Carrier, CompletableFuture<?>> carrierTails = new EnumMap<>(Carrier.class);
    private final List<CompletableFuture<?>> cycleTasks = new ArrayList<>();

    public CarrierTrackingCoordinator(@Value("${tracking.parallel.enabled:false}") boolean enabled) {
        this(enabled, new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy()));
    }

    CarrierTrackingCoordinator(boolean enabled, ThreadPoolExecutor executor) {
        this.enabled = enabled;
        this.executor = executor;
    }

    public synchronized CompletableFuture<TrackingResult> submit(Carrier carrier,
            CarrierTrackingAdapter adapter, String trackingNumber) {
        if (!enabled || (carrier != Carrier.MSC && carrier != Carrier.MAERSK)) {
            throw new IllegalStateException("Carrier is not enabled for parallel tracking: " + carrier);
        }
        CompletableFuture<?> previous = carrierTails.get(carrier);
        CompletableFuture<TrackingResult> result = new CompletableFuture<>();
        Future<?> task = executor.submit(() -> {
            try {
                if (previous != null) previous.join();
                result.complete(adapter.track(trackingNumber));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) task.cancel(true);
        });
        carrierTails.put(carrier, result);
        cycleTasks.add(result);
        return result;
    }

    /** Waits for every submitted cycle task, propagating interruption to callers. */
    public void drain() throws InterruptedException {
        List<CompletableFuture<?>> tasks;
        synchronized (this) {
            tasks = new ArrayList<>(cycleTasks);
        }
        for (CompletableFuture<?> task : tasks) {
            try { task.get(); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException ignored) { }
        }
        synchronized (this) {
            cycleTasks.removeAll(tasks);
            carrierTails.clear();
        }
    }

    /** Cancels outstanding work and waits for worker termination before resources are released. */
    public void cancelAndDrain() throws InterruptedException {
        List<CompletableFuture<?>> tasks;
        synchronized (this) { tasks = new ArrayList<>(cycleTasks); }
        tasks.forEach(task -> task.cancel(true));
        drainWorkers();
        synchronized (this) { cycleTasks.removeAll(tasks); carrierTails.clear(); }
    }

    private void drainWorkers() throws InterruptedException {
        executor.getQueue().clear();
        executor.shutdownNow();
        executor.awaitTermination(60, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
