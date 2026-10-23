package com.shiparrival.adapter.maersk;

/**
 * Cliente encargado de obtener el JSON de rastreo de Maersk.
 *
 * <p>Se abstrae detrás de una interfaz para poder probar el mapeo de datos
 * sin depender de un navegador real.</p>
 */
public interface MaerskTrackingClient extends AutoCloseable {

    String fetchTrackingJson(String trackingNumber);

    @Override
    void close();
}
