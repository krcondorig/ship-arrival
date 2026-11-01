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
