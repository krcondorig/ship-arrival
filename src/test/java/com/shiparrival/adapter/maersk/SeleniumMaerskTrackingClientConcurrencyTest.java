package com.shiparrival.adapter.maersk;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SeleniumMaerskTrackingClientConcurrencyTest {
    @Test
    void preservesSerializationWithoutProvableDriverIsolation() throws Exception {
        var method = SeleniumMaerskTrackingClient.class.getMethod("fetchTrackingJson", String.class);
        assertTrue(Modifier.isSynchronized(method.getModifiers()));
    }
}
