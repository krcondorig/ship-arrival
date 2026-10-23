package com.shiparrival.adapter.hapaglloyd;

/**
 * Cliente encargado de obtener el snapshot de rastreo de Hapag-Lloyd.
 */
public interface HapagLloydTrackingClient extends AutoCloseable {

    HapagLloydTrackingSnapshot fetchTrackingSnapshot(String trackingNumber);

    @Override
    void close();
}
