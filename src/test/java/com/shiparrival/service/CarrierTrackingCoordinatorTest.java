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

    @Test
    void overlappingBarriersAndSubmittersRemainSerializedUntilBothBarriersFinish() throws Exception {
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true,
                new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(2), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()));
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch barrierEntered = new CountDownLatch(1);
        CountDownLatch releaseBarrier = new CountDownLatch(1);
        coordinator.setBarrierEnteredHook(() -> { barrierEntered.countDown(); try { releaseBarrier.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        CarrierTrackingAdapter adapter = mock(CarrierTrackingAdapter.class);
        when(adapter.track(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
            if ("first".equals(inv.getArgument(0))) { firstEntered.countDown(); release.await(); }
            return TrackingResult.builder().success(true).build();
        });
        try {
            coordinator.beginCycle();
            coordinator.submit(Carrier.MSC, adapter, "first");
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            Thread a = new Thread(() -> { try { coordinator.awaitWorkers(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            Thread b = new Thread(() -> { try { coordinator.awaitCarrier(Carrier.MSC); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            a.start(); b.start();
            assertTrue(barrierEntered.await(2, TimeUnit.SECONDS));
            assertTrue(waitUntilWaiting(b), "second barrier must queue behind the first barrier gate");
            CountDownLatch submitStarted = new CountDownLatch(1);
            Thread submitter = new Thread(() -> { submitStarted.countDown(); coordinator.submit(Carrier.MAERSK, adapter, "queued"); });
            submitter.start();
            assertTrue(submitStarted.await(2, TimeUnit.SECONDS));
            assertTrue(waitUntilWaiting(submitter), "submitter must queue behind the barrier gate");
            releaseBarrier.countDown();
            release.countDown();
            a.join(2000); b.join(2000); submitter.join(2000);
            assertFalse(a.isAlive()); assertFalse(b.isAlive()); assertFalse(submitter.isAlive());
            coordinator.drain();
            assertEquals(1, coordinator.observedPeakInFlight());
        } finally { releaseBarrier.countDown(); release.countDown(); coordinator.shutdown(); }
    }

    @Test
    void awaitWorkersIsBarrierAgainstConcurrentAcceptedSubmissions() throws Exception {
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true,
                new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(2), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()));
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch awaiting = new CountDownLatch(1);
        CountDownLatch barrierEntered = new CountDownLatch(1);
        coordinator.setBarrierEnteredHook(barrierEntered::countDown);
        CarrierTrackingAdapter msc = mock(CarrierTrackingAdapter.class);
        CarrierTrackingAdapter maersk = mock(CarrierTrackingAdapter.class);
        when(msc.track("first")).thenAnswer(inv -> { firstEntered.countDown(); releaseFirst.await(); return TrackingResult.builder().success(true).build(); });
        when(maersk.track("second")).thenReturn(TrackingResult.builder().success(true).build());
        try {
            coordinator.beginCycle();
            coordinator.submit(Carrier.MSC, msc, "first");
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            Thread barrier = new Thread(() -> { awaiting.countDown(); try { coordinator.awaitWorkers(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            barrier.start();
            assertTrue(awaiting.await(2, TimeUnit.SECONDS));
            assertTrue(barrierEntered.await(2, TimeUnit.SECONDS));
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<TrackingResult>> accepted = new java.util.concurrent.atomic.AtomicReference<>();
            Thread submitter = new Thread(() -> accepted.set(coordinator.submit(Carrier.MAERSK, maersk, "second")));
            submitter.start();
            assertTrue(waitUntilWaiting(submitter));
            assertTrue(submitter.isAlive(), "submitter must remain gated until the barrier's workers finish");
            releaseFirst.countDown();
            barrier.join(2000);
            submitter.join(2000);
            assertFalse(barrier.isAlive());
            assertFalse(submitter.isAlive());
            assertNotNull(accepted.get());
            coordinator.drain();
        } finally { releaseFirst.countDown(); coordinator.shutdown(); }
    }

    @Test
    void startsDifferentCarriersAtLeastDelayApartWhileFirstRemainsInFlight() throws Exception {
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true,
                new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(2), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()),
                120, System::nanoTime, millis -> TimeUnit.MILLISECONDS.sleep(millis));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong firstAt = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong secondAt = new java.util.concurrent.atomic.AtomicLong();
        CarrierTrackingAdapter first = mock(CarrierTrackingAdapter.class);
        CarrierTrackingAdapter second = mock(CarrierTrackingAdapter.class);
        when(first.track("first")).thenAnswer(inv -> { firstAt.set(System.nanoTime()); firstStarted.countDown(); release.await(); return TrackingResult.builder().success(true).build(); });
        when(second.track("second")).thenAnswer(inv -> { secondAt.set(System.nanoTime()); secondStarted.countDown(); return TrackingResult.builder().success(true).build(); });
        try {
            coordinator.beginCycle();
            coordinator.submit(Carrier.MSC, first, "first");
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            coordinator.submit(Carrier.MAERSK, second, "second");
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(secondAt.get() - firstAt.get()) >= 120);
            assertEquals(2, coordinator.observedPeakInFlight());
            release.countDown();
            coordinator.drain();
        } finally { release.countDown(); coordinator.shutdown(); }
    }

    @Test
    void pacingWaitDoesNotCountAsInFlightAndInterruptionIsNotAnAdapterFailure() throws Exception {
        CountDownLatch pacingWaitEntered = new CountDownLatch(1);
        CountDownLatch releasePacing = new CountDownLatch(1);
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true,
                new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(2), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()),
                60_000, System::nanoTime, millis -> {
                    pacingWaitEntered.countDown();
                    releasePacing.await();
                });
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CarrierTrackingAdapter adapter = mock(CarrierTrackingAdapter.class);
        when(adapter.track("first")).thenAnswer(inv -> { firstEntered.countDown(); releaseFirst.await(); return TrackingResult.builder().success(true).build(); });
        try {
            coordinator.beginCycle();
            coordinator.submit(Carrier.MSC, adapter, "first");
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            var waitingCall = coordinator.submit(Carrier.MAERSK, adapter, "waiting");
            assertTrue(pacingWaitEntered.await(2, TimeUnit.SECONDS));
            assertEquals(1, coordinator.observedPeakInFlight(), "only the first adapter call is in flight while the other carrier waits for pacing");
            coordinator.cancelAndDrain();
            releasePacing.countDown();
            assertTrue(waitingCall.isCancelled());
            assertEquals(1, coordinator.observedPeakInFlight());
            org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).track("waiting");
            coordinator.beginCycle();
            coordinator.drain();
        } finally { releaseFirst.countDown(); coordinator.shutdown(); }
    }

    @Test
    void delayedWorkerCannotInvalidateSpacingAtCoordinatorAuthorizationBoundary() throws Exception {
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true,
                new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(2), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()),
                100, System::nanoTime, millis -> TimeUnit.MILLISECONDS.sleep(millis));
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch allowFirstInvocation = new CountDownLatch(1);
        CountDownLatch secondAuthorized = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong firstAuthorization = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong secondAuthorization = new java.util.concurrent.atomic.AtomicLong();
        coordinator.setAuthorizationHook((carrier, timestamp) -> {
            if (carrier == Carrier.MSC) firstAuthorization.set(timestamp);
            else { secondAuthorization.set(timestamp); secondAuthorized.countDown(); }
        });
        coordinator.setAfterStartReservationHook(carrier -> {
            if (carrier == Carrier.MSC) {
                reserved.countDown();
                allowFirstInvocation.await();
            }
        });
        CarrierTrackingAdapter msc = mock(CarrierTrackingAdapter.class);
        CarrierTrackingAdapter maersk = mock(CarrierTrackingAdapter.class);
        when(msc.track("first")).thenReturn(TrackingResult.builder().success(true).build());
        when(maersk.track("second")).thenReturn(TrackingResult.builder().success(true).build());
        try {
            coordinator.beginCycle();
            coordinator.submit(Carrier.MSC, msc, "first");
            assertTrue(reserved.await(2, TimeUnit.SECONDS));
            coordinator.submit(Carrier.MAERSK, maersk, "second");
            assertTrue(secondAuthorized.await(2, TimeUnit.SECONDS));
            allowFirstInvocation.countDown();
            coordinator.drain();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(Math.abs(secondAuthorization.get() - firstAuthorization.get())) >= 100,
                    "coordinator authorization events must be separated; this is not a physical adapter-entry assertion");
        } finally { allowFirstInvocation.countDown(); coordinator.shutdown(); }
    }

    @Test
    void cancellationWinsBeforeAuthorizationAndPreventsAdapterInvocation() throws Exception {
        CountDownLatch authorizationAttempt = new CountDownLatch(1);
        CountDownLatch allowAuthorizationAttempt = new CountDownLatch(1);
        CountDownLatch adapterCalled = new CountDownLatch(1);
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true,
                new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(1), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()),
                0, System::nanoTime, millis -> {});
        coordinator.setBeforeAuthorizationHook(carrier -> {
            authorizationAttempt.countDown();
            allowAuthorizationAttempt.await();
        });
        CarrierTrackingAdapter adapter = mock(CarrierTrackingAdapter.class);
        when(adapter.track("cancelled-before-start")).thenAnswer(inv -> {
            adapterCalled.countDown();
            return TrackingResult.builder().success(true).build();
        });
        try {
            coordinator.beginCycle();
            var result = coordinator.submit(Carrier.MSC, adapter, "cancelled-before-start");
            assertTrue(authorizationAttempt.await(2, TimeUnit.SECONDS));
            coordinator.cancelAndDrain();
            allowAuthorizationAttempt.countDown();
            assertTrue(result.isCancelled());
            assertFalse(adapterCalled.await(100, TimeUnit.MILLISECONDS), "a cancellation that wins before authorization must prevent adapter invocation");
            org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).track("cancelled-before-start");
        } finally { allowAuthorizationAttempt.countDown(); coordinator.shutdown(); }
    }

    @Test
    void sameCarrierSuccessorWaitsForPredecessorCompletionAndDelay() throws Exception {
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true,
                new java.util.concurrent.ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(2), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()),
                120, System::nanoTime, millis -> TimeUnit.MILLISECONDS.sleep(millis));
        CountDownLatch firstRelease = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong firstDone = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong secondAt = new java.util.concurrent.atomic.AtomicLong();
        CarrierTrackingAdapter adapter = mock(CarrierTrackingAdapter.class);
        when(adapter.track("first")).thenAnswer(inv -> { firstRelease.await(); firstDone.set(System.nanoTime()); return TrackingResult.builder().success(true).build(); });
        when(adapter.track("second")).thenAnswer(inv -> { secondAt.set(System.nanoTime()); return TrackingResult.builder().success(true).build(); });
        try {
            coordinator.beginCycle();
            coordinator.submit(Carrier.MSC, adapter, "first");
            coordinator.submit(Carrier.MSC, adapter, "second");
            firstRelease.countDown();
            coordinator.drain();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(secondAt.get() - firstDone.get()) >= 120);
        } finally { firstRelease.countDown(); coordinator.shutdown(); }
    }

    @Test
    void rejectionHasExplicitDispatchFailureOutcome() throws Exception {
        var executor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new java.util.concurrent.SynchronousQueue<>(), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        CountDownLatch executorOccupied = new CountDownLatch(1);
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        executor.execute(() -> { executorOccupied.countDown(); try { releaseExecutor.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        assertTrue(executorOccupied.await(2, TimeUnit.SECONDS));
        CarrierTrackingCoordinator coordinator = new CarrierTrackingCoordinator(true, executor, 0, System::nanoTime, millis -> {});
        try {
            coordinator.beginCycle();
            var outcome = coordinator.submit(Carrier.MSC, mock(CarrierTrackingAdapter.class), "safe");
            assertInstanceOf(CarrierTrackingCoordinator.DispatchRejectedException.class,
                    assertThrows(java.util.concurrent.CompletionException.class, outcome::join).getCause());
            coordinator.drain();
        } finally { releaseExecutor.countDown(); coordinator.shutdown(); }
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
