package com.shiparrival.adapter;

import com.shiparrival.domain.Carrier;

public interface CarrierTrackingAdapter {

    boolean supports(Carrier carrier);

    TrackingResult track(String trackingNumber);

    /**
     * Libera recursos externos (por ejemplo, un navegador headless) al finalizar
     * un ciclo de monitoreo. Por defecto no hay nada que liberar.
     */
    default void releaseResources() {
        // Sin recursos que liberar.
    }
}
