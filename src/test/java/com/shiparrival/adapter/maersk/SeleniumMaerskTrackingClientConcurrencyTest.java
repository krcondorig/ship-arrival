package com.shiparrival.adapter.maersk;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeleniumMaerskTrackingClientConcurrencyTest {
    @Test
    void concurrentCallsUseIndependentSessionsResponsesAndCloseBoth() throws Exception {
        CountDownLatch bothSessionsEntered = new CountDownLatch(2);
        CountDownLatch releaseResponses = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        SeleniumMaerskTrackingClient client = new SeleniumMaerskTrackingClient(() -> {
            int session = created.incrementAndGet();
            return new SeleniumMaerskTrackingClient.BrowserSession() {
                @Override
                public String fetch(String trackingNumber) {
                    bothSessionsEntered.countDown();
                    try {
                        if (!bothSessionsEntered.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("calls did not enter independent sessions concurrently");
                        }
                        if (!releaseResponses.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("response release timed out");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                    return "{\"tracking\":\"" + trackingNumber + "\",\"session\":" + session + "}";
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> client.fetchTrackingJson("BL-FIRST"));
            var second = executor.submit(() -> client.fetchTrackingJson("BL-SECOND"));
            assertTrue(bothSessionsEntered.await(5, TimeUnit.SECONDS), "both calls should use sessions concurrently");
            releaseResponses.countDown();
            assertEquals("{\"tracking\":\"BL-FIRST\",\"session\":1}", first.get(5, TimeUnit.SECONDS));
            assertEquals("{\"tracking\":\"BL-SECOND\",\"session\":2}", second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(2, created.get());
        assertEquals(2, closed.get());
    }
}
