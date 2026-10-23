package com.shiparrival.adapter.msc;

/**
 * Cliente encargado de obtener el JSON de rastreo de MSC.
 *
 * <p>Se abstrae detrás de una interfaz para poder probar el mapeo de datos
 * sin depender de un navegador real.</p>
 */
public interface MscTrackingClient extends AutoCloseable {

    String fetchTrackingJson(String trackingNumber);

    @Override
    void close();
}
