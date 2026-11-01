package com.shiparrival.service;

import com.shiparrival.adapter.CarrierTrackingAdapter;
import com.shiparrival.adapter.TrackingResult;
import com.shiparrival.domain.Carrier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CarrierTrackingCoordinatorTest {
    @Test
    void overlapsOneMscAndOneMaerskAndDrainsCycle() throws Exception {
        CarrierTrackingAdapter msc = mock(CarrierTrackingAdapter.class);
        CarrierTrackingAdapter maersk = mock(CarrierTrackingAdapter.class);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(msc.track("a")).thenAnswer(invocation -> run(entered, release, active, maximum, "a"));
        when(maersk.track("b")).thenAnswer(invocation -> run(entered, release, active, maximum, "b"));
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true,
                new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(2), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()));
        try {
            coordinator.beginCycle();
            var a = coordinator.submit(Carrier.MSC, msc, "a");
            var b = coordinator.submit(Carrier.MAERSK, maersk, "b");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(2, maximum.get());
            release.countDown();
            coordinator.drain();
            assertTrue(a.isDone());
            assertTrue(b.isDone());
        } finally {
            release.countDown();
            coordinator.shutdown();
        }
    }

    @Test
    void repeatedSameCarrierSubmissionsAreSerializedWithoutBlockingWorkers() throws Exception {
        // Regression for worker starvation when same-carrier jobs wait on each other's futures.
        CarrierTrackingAdapter msc = mock(CarrierTrackingAdapter.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(msc.track(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation ->
                run(entered, release, active, maximum, invocation.getArgument(0)));
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true,
                new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(2), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()));
        try {
            coordinator.beginCycle();
            var first = coordinator.submit(Carrier.MSC, msc, "1");
            var second = coordinator.submit(Carrier.MSC, msc, "2");
            var third = coordinator.submit(Carrier.MSC, msc, "3");
            CarrierTrackingAdapter maerskAdapter = mock(CarrierTrackingAdapter.class);
            CountDownLatch maerskEntered = new CountDownLatch(1);
            when(maerskAdapter.track("4")).thenAnswer(invocation -> {
                maerskEntered.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return TrackingResult.builder().success(true).trackingNumber("4").build();
            });
            var maersk = coordinator.submit(Carrier.MAERSK, maerskAdapter, "4");
            assertTrue(maerskEntered.await(2, TimeUnit.SECONDS));
            assertEquals(0, entered.getCount(), "first MSC call must be running alongside Maersk");
            assertEquals(1, maximum.get());
            release.countDown();
            coordinator.drain();
            assertTrue(first.isDone() && second.isDone() && third.isDone() && maersk.isDone());
        } finally {
            release.countDown();
            coordinator.shutdown();
        }
    }

    @Test
    void cancellationPreventsDeferredSameCarrierSuccessorAndWaitsForActiveWorker() throws Exception {
        CarrierTrackingAdapter msc = mock(CarrierTrackingAdapter.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch activeExited = new CountDownLatch(1);
        AtomicInteger successorCalls = new AtomicInteger();
        AtomicInteger mscMaximum = new AtomicInteger();
        AtomicInteger mscActive = new AtomicInteger();
        AtomicInteger globalActive = new AtomicInteger();
        AtomicInteger globalMaximum = new AtomicInteger();
        CountDownLatch oppositeStarted = new CountDownLatch(1);
        CarrierTrackingAdapter maerskAdapter = mock(CarrierTrackingAdapter.class);
        when(maerskAdapter.track(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            int now = globalActive.incrementAndGet();
            globalMaximum.accumulateAndGet(now, Math::max);
            oppositeStarted.countDown();
            try { return TrackingResult.builder().success(true).trackingNumber(invocation.getArgument(0)).build(); }
            finally { globalActive.decrementAndGet(); }
        });
        when(msc.track("first")).thenAnswer(invocation -> {
            int now = globalActive.incrementAndGet();
            globalMaximum.accumulateAndGet(now, Math::max);
            mscMaximum.accumulateAndGet(mscActive.incrementAndGet(), Math::max);
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return fail("worker should be interrupted");
            } catch (InterruptedException expected) {
                interrupted.countDown();
                return TrackingResult.builder().success(false).trackingNumber("first").build();
            } finally { mscActive.decrementAndGet(); globalActive.decrementAndGet(); activeExited.countDown(); }
        });
        when(msc.track("successor")).thenAnswer(invocation -> {
            successorCalls.incrementAndGet();
            return TrackingResult.builder().success(true).trackingNumber("successor").build();
        });
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true,
                new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(2), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()));
        try {
            coordinator.beginCycle();
            coordinator.submit(Carrier.MSC, msc, "first");
            var successor = coordinator.submit(Carrier.MSC, msc, "successor");
            var opposite = coordinator.submit(Carrier.MAERSK, maerskAdapter, "opposite");
            assertTrue(oppositeStarted.await(2, TimeUnit.SECONDS));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(1, mscMaximum.get());
            assertTrue(globalMaximum.get() <= 2);
            coordinator.cancelAndDrain();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(activeExited.await(2, TimeUnit.SECONDS));
            assertEquals(0, successorCalls.get());
            assertTrue(successor.isCancelled());
            coordinator.beginCycle();
            coordinator.drain();
        } finally { coordinator.shutdown(); }
    }

    @Test
    void beginCycleWaitsForSerializedDrainsAndPreservesNextCycleSubmissions() throws Exception {
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true,
                new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(2), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CarrierTrackingAdapter adapter = mock(CarrierTrackingAdapter.class);
        when(adapter.track(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return TrackingResult.builder().success(true).trackingNumber(invocation.getArgument(0)).build();
        });
        try {
            coordinator.beginCycle();
            coordinator.submit(Carrier.MSC, adapter, "old");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CountDownLatch firstDrainDone = new CountDownLatch(1);
            CountDownLatch secondDrainDone = new CountDownLatch(1);
            Thread firstDrain = new Thread(() -> { try { coordinator.drain(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } finally { firstDrainDone.countDown(); } });
            Thread secondDrain = new Thread(() -> { try { coordinator.cancelAndDrain(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } finally { secondDrainDone.countDown(); } });
            firstDrain.start();
            assertTrue(waitUntilWaiting(firstDrain));
            secondDrain.start();
            assertTrue(waitUntilWaiting(secondDrain));
            CountDownLatch cycleStarted = new CountDownLatch(1);
            Thread nextCycleStarter = new Thread(() -> { coordinator.beginCycle(); cycleStarted.countDown(); });
            nextCycleStarter.start();
            assertTrue(waitUntilWaiting(nextCycleStarter));
            release.countDown();
            assertTrue(firstDrainDone.await(2, TimeUnit.SECONDS));
            assertTrue(secondDrainDone.await(2, TimeUnit.SECONDS));
            nextCycleStarter.join(2000);
            assertFalse(nextCycleStarter.isAlive());
            assertTrue(cycleStarted.await(2, TimeUnit.SECONDS));
            var nextCycle = coordinator.submit(Carrier.MSC, adapter, "new");
            coordinator.drain();
            assertTrue(nextCycle.isDone());
        } finally {
            release.countDown();
            coordinator.shutdown();
        }
    }

    private boolean waitUntilWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.WAITING) return true;
            Thread.yield();
        }
        return false;
    }


    private TrackingResult run(CountDownLatch entered, CountDownLatch release, AtomicInteger active,
                               AtomicInteger maximum, String number) throws InterruptedException {
        int current = active.incrementAndGet();
        maximum.accumulateAndGet(current, Math::max);
        entered.countDown();
        try {
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return TrackingResult.builder().success(true).trackingNumber(number).build();
        } finally { active.decrementAndGet(); }
    }
}
